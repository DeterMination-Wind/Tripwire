# Tripwire Mod — 完整需求文档

## 一、项目概述

做一个 Mindustry "地理围栏" Mod，命名为 **Tripwire**。项目目录为 `codex/Tripwire`，是独立 Java Mod（非 MindustryX 内置功能），需要是 git 仓库。

## 二、核心功能

### 2.1 围栏（Tripwire Fence）数据模型

玩家在对局中画一段折线并赋予这段折线一个方向属性。一个围栏包含：
- 折线端点列表（世界坐标，`Seq<Vec2>`）
- 方向属性：`DirectionMode` 枚举——`inner`（内侧）/`outer`（外侧）/`all`（双向），默认 `all`（旧版 `boolean isRightSide` 模型已废弃：`true=outer`、`false=inner`）
- 监控的 Unit 类型集合（`ObjectSet<UnitType>`，默认全选非核心单位，空集合 = 不监控任何单位）
- 创建者队伍（`Team`）
- 唯一 ID

### 2.2 围栏配置界面（Config UI）

点击折线附近（≤10 世界距离单位）可以弹出一个 config 选择界面。基于 `ItemSelection.buildTable()` 的风格，但实现为**多选版本**（不修改游戏源码，写独立的 `buildMultiSelectTable` 方法）。

界面包含：
- 顶部：搜索框 + [全选] [全不选] [反选] 按钮
- 主体：单位类型网格，每个按钮显示单位图标 + 名称，选中状态用黄边加强显示
- 底部：方向切换按钮，循环切换 `inner` → `outer` → `all` → `inner`（按钮文字来自 bundle key，`all` 时按钮不显示选中态）
- 点击折线外的地方自动保存并关闭 config 配置面板

### 2.3 围栏显示

#### 2.3.1 地图显示

在地图上，折线常驻显示为类似科学光路图中镜子的简要画法：
- 一条线段后面是类似阴影的短斜线
- 斜线方向由 `DirectionMode` 决定：`inner` 斜线在折线左侧，`outer` 在右侧，`all` 两侧都画
- 斜线从折线指向选中方向

每段折线的中点处都沿着折线方向排列玩家选择的 Units：
- 以黑底黄边加强显示（参考 config 选择界面的选中 Unit icon 显示模式）
- 大小约为 3/4 个 block 大
- 随着视角缩放而缩放

config 为空的围栏以紫色标出（参考建筑被禁用时的状态灯颜色），其他围栏以队伍颜色显示。玩家也可在设置中覆盖显示颜色。

渲染层级为 Layer 156（在 fogOfWar 155 之上）。

#### 2.3.2 小地图显示

在小地图上也要显示围栏。参考 betterMiniMap 的实现方式：
- 通过 `ui.hudGroup.find("minimap")` 找到小地图 widget
- 创建自定义 `Element` 子类作为 overlay 添加到小地图 table 的子元素
- 在 overlay 的 `draw()` 中计算变换矩阵，将世界坐标映射到小地图像素坐标
- 在小地图上绘制简化版围栏（线段 + 斜线，不显示 Unit 图标）

### 2.4 创建围栏

"创建/完成围栏"键默认 `KeyCode.unset`（需在游戏设置中绑定）；在 MindustryX 上也可点击浮动"绘制"overlay 按钮进入/退出创建模式。创建流程：
1. 鼠标变为十字
2. 鼠标点击地图上的任意位置创建起点
3. 点击地图上的任何位置创建下一个端点
4. 再点击创建下一个端点……
5. 创建过程中实时显示：已有线段 + 从最后一个端点到鼠标位置的预览线段
6. 再次按下快捷键（或按钮），将最后一个端点视为这段围栏的中止
7. 如果端点少于 2 个则取消创建
8. 创建完成后默认 selectedUnits 为全部非核心单位

### 2.5 删除围栏

"按住框选删除"键默认 `KeyCode.unset`（需在游戏设置中绑定）；在 MindustryX 上也可先点击浮动"删除"overlay 按钮进入删除模式。按住键（或处于删除模式）并拖动鼠标框选一个框，只要被框覆盖到一点点的折线就整个删掉。视觉上显示半透明选择框。

### 2.6 围栏配置修改

- 点击围栏附近（≤10px）弹出 config 面板
- config 面板内可修改方向（`inner`/`outer`/`all` 循环切换按钮）
- 不支持编辑端点，只能删除整段围栏重新画

### 2.7 穿越检测

当有非玩家 team 的围栏选中单位从选中方向穿过围栏时触发报警。

检测算法：
- 每 N 毫秒检测一次（默认 150ms，可配置 100-200ms），用下一次检测时间戳控制
- 对每个围栏的每个线段，检查单位上一次检测位置和当前位置的叉积符号变化
- `cross1 = (B-A) × (P1-A)`，`cross2 = (B-A) × (P2-A)`
- 如果 `sign(cross1) != sign(cross2)`，说明单位穿过了线段
- 再判断穿越方向是否与 `DirectionMode` 一致（`TripwireFence.crossedSelectedSide`）：
  - `outer`（外侧）：`cross1 < 0 && cross2 >= 0`
  - `inner`（内侧）：`cross1 > 0 && cross2 <= 0`
  - `all`（双向）：上述两种情况任一即触发
  - 若 `cross1` 与 `cross2` 都接近 0（沿线段滑动）则不触发
- 仅检测非己方队伍单位（`unit.team != player.team()`）
- 每次检测后更新所有单位的位置缓存，并清理失效单位

### 2.8 报警系统

穿越触发后：
1. 在玩家屏幕中央弹出 Toast 报警信息（`Vars.ui.showInfoToast`，约 4 秒）
2. 在聊天栏中发送消息（`Vars.ui.chatfrag.addMessage`）

消息格式：`[scarlet]Warn[] (tileX,tileY) [scarlet]<UnitLocalName>[] crossed the tripwire`

玩家可以在设置中分别控制是否弹出 Toast 和是否在聊天栏发送信息。

## 三、持久化

围栏数据需要随游戏存档保存和加载。使用 `SaveVersion.addCustomChunk("tripwire-data", ...)` 注册自定义数据块（`chunkVersion = 2`）。

序列化格式：
```
头部: int version = 2
每个围栏:
  int id
  int pointCount
  float[] points (x1,y1,x2,y2,...)  -- 世界坐标
  int directionId                    -- DirectionMode.id (0=inner, 1=outer, 2=all)
  int selectedUnitCount
  String[] selectedUnitNames         -- UnitType.name
  int teamId                         -- Team.id
```

- 旧存档兼容：`version == 1` 时该字段为 `boolean isRightSide`，通过 `DirectionMode.fromLegacyRightSide()` 迁移（`true=outer`、`false=inner`）
- `shouldWrite()` 返回 `!fences.isEmpty()`
- `writeNet()` 返回 `true`
- 头部带 `int version` 字段，方便未来格式迁移
- Mod 未加载时打开含有围栏数据的存档，CustomChunk 会被跳过（向前兼容）
- `WorldLoadEvent` 时清空所有围栏数据

## 四、设置项

| 设置项 | 类型 | 默认值 | 说明 |
|--------|------|--------|------|
| 检测间隔 | Slider 100-200 | 150ms | 穿越检测频率（毫秒） |
| 聊天栏报警 | Check | true | 是否在聊天栏发送报警 |
| 聊天合并延迟 | Slider 0.1-10s | 0.5s | 多人报警合并窗口 |
| Toast 报警 | Check | true | 是否弹出屏幕 Toast |
| 围栏显示颜色 | Check + RGB Slider | 队伍颜色 | 可覆盖为自定义颜色 |
| 显示围栏 | Check | true | 是否在地图上绘制围栏 |
| 小地图显示 | Check | true | 是否在小地图上显示围栏 |
| 围栏线宽 | Slider 1-8 | 2 | 折线和斜线的线宽 |
| 图标大小 | Slider 8-48 | 24 | 中点 Unit 图标大小 |

设置通过 `Vars.ui.settings.addCategory("Tripwire", Icon.xxx, Settings::buildSettings)` 注册到游戏设置界面。

## 五、技术约束

### 5.1 项目结构
```
codex/Tripwire/
├── src/main/java/tripwire/
│   ├── TripwireMod.java           # Mod 入口（Mindustry）
│   ├── TripwireModX.java          # Mod 入口（MindustryX，注册 Overlay UI）
│   ├── TripwireFence.java         # 数据模型（含 DirectionMode 枚举）
│   ├── TripwireData.java          # 全局数据管理 + 持久化
│   ├── TripwireInput.java         # 输入处理
│   ├── TripwireConfig.java        # 多选 Unit 配置面板
│   ├── TripwireRenderer.java      # 世界 + 小地图渲染
│   ├── TripwireDetector.java      # 穿越检测
│   ├── TripwireAlert.java         # 报警系统
│   ├── TripwireOverlayUi.java     # MindustryX 浮动"绘制/删除"按钮 overlay
│   └── TripwireSettings.java      # 设置项
├── src/main/java/mdtxcompat/      # OverlayUiBridge 抽象 + MindustryX/Noop 实现
├── src/main/resources/
│   └── bundles/
│       ├── bundle.properties      # i18n（英文）
│       └── bundle_zh_CN.properties # i18n（简体中文）
├── mod.json
├── build.gradle
├── settings.gradle
└── gradle/wrapper/
```

### 5.2 构建要求

- Java 17 兼容（`sourceCompatibility/targetCompatibility = JavaVersion.VERSION_17`，`options.release = 17`）
- 依赖 Mindustry 核心库：优先使用本地 MindustryX 构建（`../MindustryX-main` 的 core jar/class 目录），否则 `compileOnly "com.github.Anuken.MindustryJitpack:core:$mindustryVersion"`
- `mod.json` 必须包含在 jar 根目录（Mindustry 加载器必需）
- jar 产物：`jar` 任务输出 `Tripwire.zip`，`jarDesktop` 输出 `Tripwire-<version>.jar`，`jarLocalDev` 输出 `Tripwire-dev.jar`；`deploy` 汇总全部产物

### 5.3 mod.json 格式
```json
{
  "name": "Tripwire-dev",
  "displayName": "Tripwire",
  "author": "DeterMination",
  "version": "1.1.1",
  "minGameVersion": 159,
  "description": "Tripwire geofence alerts for Mindustry. Mindustry 地理围栏报警模组。",
  "main": "tripwire.TripwireMod",
  "mainX": "tripwire.TripwireModX",
  "hidden": true,
  "java": true
}
```

### 5.4 Git 要求

- 仓库应当是 git 仓库
- 每做完一个功能就提交一次

### 5.5 参考实现

- **小地图渲染**：参考 betterMiniMap（`codex/betterMiniMap/src/main/java/betterminimap/features/BetterMiniMapFeature.java`）的 `HudMinimapOverlay` 实现，通过 scene graph 找到 minimap widget 并添加自定义 overlay element
- **Unit 选择 UI**：参考 `ItemSelection.buildTable()` 的布局风格，但实现为独立的多选版本
- **快捷键注册**：使用 `KeyBind.add(name, keycode, category)` 或 `Core.input.keyTap(KeyCode.num1)`
- **事件系统**：使用 `Events.run(Trigger.update, ...)` 和 `Events.run(Trigger.draw, ...)`
- **源码**：参考 `"C:\Users\华硕\Documents\MindustryX"` 和 `\codex` 下的其他Mod/游戏本体

### 5.6 Mindustry API 参考

- 渲染：`Draw.color()`, `Lines.stroke/line()`, `Fill.circle/rect()`, `Draw.rect(texture, x, y, w, h)`, `Draw.z(layer)`
- 输入：`Core.input.keyTap(KeyCode)`, `Core.input.keyDown(KeyCode)`, `Core.input.mouseWorldX/Y()`, `Core.scene.hasKeyboard()`
- UI：`BaseDialog`, `ImageButton`, `Styles.clearNoneTogglei`, `Tex.whiteui`, `Table`, `ScrollPane`
- 数据：`Seq<T>`, `ObjectSet<T>`, `ObjectMap<K,V>`, `Vec2`, `Interval`
- 游戏：`Vars.player`, `Vars.state`, `Vars.ui`, `Vars.world`, `Vars.renderer`, `Vars.content`, `Vars.tilesize`
- 持久化：`SaveVersion.addCustomChunk(name, CustomChunk)`, `ContentType.unit`, `Team.get(id)`
- 事件：`Events.on(EventType.class, ...)`, `Events.run(Trigger.xxx, ...)`, `EventType.WorldLoadEvent`, `EventType.ClientLoadEvent`
- 小地图：`renderer.minimap.getZoom()`, `renderer.minimap.getRegion()`, `ui.hudGroup.find("minimap")`, `ui.hudfrag.shown`, `ui.minimapfrag.shown()`

## 六、交互流程汇总

| 操作 | 按键 | 行为 |
|------|------|------|
| 创建围栏 | "创建/完成围栏"键（默认 unset，需在设置中绑定；MindustryX 可用浮动"绘制"按钮） | 鼠标变十字，点击添加端点，再按一次完成（≥2 点） |
| 配置围栏 | 点击围栏≤10px | 弹出多选 Unit 面板 + 方向循环切换（inner/outer/all） |
| 删除围栏 | "按住框选删除"键（默认 unset；MindustryX 可用浮动"删除"按钮） + 拖拽 | 框选删除（覆盖即删） |
| 报警 | 自动 | 非己方单位从选中方向穿过围栏 → Toast + Chat |
