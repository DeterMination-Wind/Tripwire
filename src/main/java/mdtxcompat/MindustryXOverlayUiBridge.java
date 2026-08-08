package mdtxcompat;

import arc.Core;
import arc.func.Prov;
import arc.scene.Element;
import arc.scene.ui.layout.Table;
import arc.util.Log;
import mindustry.Vars;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

public class MindustryXOverlayUiBridge implements OverlayUiBridge {
    private static final String OVERLAY_UI_CLASS = "mindustryX.features.ui.OverlayUI";
    private static final long RETRY_DELAY_MS = 250L;

    private enum State {
        UNRESOLVED,
        DEFERRED,
        BINDING,
        READY,
        RETRYABLE_FAILURE,
        UNAVAILABLE_CLASS
    }

    private final Map<String, PendingWindow> pendingWindows = new LinkedHashMap<>();
    private final Map<String, WindowHandle> realWindowsByName = new LinkedHashMap<>();

    private State state = State.UNRESOLVED;
    private Class<?> overlayClass;
    private Field instanceField;
    private Method registerWindow;
    private Method getOpen;
    private Method toggle;
    private Method init;
    private Method windowSetAvailability;
    private Method windowSetAutoHeight;
    private Method windowSetResizable;
    private Method windowGetData;
    private Method dataSetEnabled;
    private Method dataSetPinned;
    private Method dataGetEnabled;
    private Object overlayUi;
    private Throwable lastFailure;
    private long nextRetryAt;
    private boolean shadowOpen;
    private State lastLoggedState;

    public MindustryXOverlayUiBridge() {
        logStateTransition(State.UNRESOLVED, "bridge constructed");
    }

    @Override
    public boolean isSupported() {
        tryBindOrInit("isSupported");
        return state == State.READY;
    }

    @Override
    public OverlayWindowHandle registerWindow(String name, Table table, Prov<Boolean> availability) {
        PendingWindow pending = pendingWindows.get(name);
        if (pending == null) {
            pending = new PendingWindow(name, table, availability);
            pendingWindows.put(name, pending);
        } else {
            pending.table = table;
            pending.availability = availability;
        }
        tryBindOrInit("registerWindow:" + name);
        return pending.handle;
    }

    @Override
    public void closeEditorIfOpen() {
        tryBindOrInit("closeEditorIfOpen");
        if (state == State.READY) {
            Object open = invoke(getOpen, overlayUi);
            if (Boolean.TRUE.equals(open)) {
                invoke(toggle, overlayUi);
            }
            syncShadowOpen();
            return;
        }
        shadowOpen = false;
    }

    private void tryBindOrInit(String reason) {
        if (state == State.READY || state == State.BINDING) return;
        if (!shouldRetryNow()) return;

        if (!resolveMetadata()) return;
        if (!isSafeToBindNow()) {
            setState(State.DEFERRED, reason + " waiting for safe runtime: " + runtimeState());
            return;
        }

        setState(State.BINDING, reason + " starting lazy bind");
        try {
            Object instance = instanceField.get(null);
            if (instance == null) {
                throw new IllegalStateException("MindustryX OverlayUI.INSTANCE is null");
            }
            invoke(init, instance);

            overlayUi = instance;
            replayPendingWindows();
            reconcileOpenStateAfterBind();
            syncShadowOpen();
            lastFailure = null;
            nextRetryAt = 0L;
            setState(State.READY, reason + " bound real OverlayUI; windows=" + realWindowsByName.size());
        } catch (Throwable t) {
            markFailure(unwrapReflectionFailure(t), reason);
        }
    }

    private boolean resolveMetadata() {
        if (overlayClass != null) return true;
        try {
            ClassLoader loader = MindustryXOverlayUiBridge.class.getClassLoader();
            overlayClass = Class.forName(OVERLAY_UI_CLASS, false, loader);
            instanceField = overlayClass.getField("INSTANCE");
            registerWindow = overlayClass.getMethod("registerWindow", String.class, Table.class);
            getOpen = overlayClass.getMethod("getOpen");
            toggle = overlayClass.getMethod("toggle");
            init = overlayClass.getMethod("init");
            Log.info("[Tripwire OverlayUI bridge] Resolved OverlayUI metadata via loader " + overlayClass.getClassLoader() + ".");
            return true;
        } catch (ClassNotFoundException e) {
            state = State.UNAVAILABLE_CLASS;
            overlayClass = null;
            logStateTransition(State.UNAVAILABLE_CLASS, "OverlayUI class not found");
            return false;
        } catch (Throwable t) {
            markFailure(unwrapReflectionFailure(t), "resolveMetadata");
            return false;
        }
    }

    private boolean isSafeToBindNow() {
        return !Vars.headless
            && Core.graphics != null
            && Core.scene != null
            && Vars.ui != null
            && Vars.ui.hudGroup != null
            && Vars.ui.hudfrag != null
            && Vars.state != null;
    }

    private boolean shouldRetryNow() {
        if (state == State.UNRESOLVED || state == State.DEFERRED) return true;
        if (state == State.UNAVAILABLE_CLASS) return false;
        return System.currentTimeMillis() >= nextRetryAt;
    }

    private void replayPendingWindows() {
        for (PendingWindow pending : pendingWindows.values()) {
            if (pending.handle.window != null) continue;
            Object window = invoke(registerWindow, overlayUi, pending.name, pending.table);
            if (window == null) {
                throw new IllegalStateException("MindustryX OverlayUI returned null window for " + pending.name);
            }
            pending.handle.bind(window);
            realWindowsByName.put(pending.name, pending.handle);
            pending.handle.applyPendingState();
            Log.info("[Tripwire OverlayUI bridge] Replayed window '" + pending.name + "'.");
        }
    }

    private void reconcileOpenStateAfterBind() {
        Object open = invoke(getOpen, overlayUi);
        if (!(open instanceof Boolean)) return;
        if (((Boolean)open) != shadowOpen) {
            invoke(toggle, overlayUi);
        }
    }

    private void syncShadowOpen() {
        Object open = invoke(getOpen, overlayUi);
        if (open instanceof Boolean) {
            shadowOpen = (Boolean)open;
        }
    }

    private void markFailure(Throwable failure, String reason) {
        lastFailure = failure;
        nextRetryAt = System.currentTimeMillis() + RETRY_DELAY_MS;
        setState(State.RETRYABLE_FAILURE, reason + " failed: " + failure.getClass().getSimpleName() + " - " + failure.getMessage());
    }

    private void setState(State next, String reason) {
        state = next;
        logStateTransition(next, reason);
    }

    private void logStateTransition(State next, String reason) {
        if (lastLoggedState == next) return;
        lastLoggedState = next;
        Log.info("[Tripwire OverlayUI bridge] state=" + next
            + ", reason=" + reason
            + ", pendingWindows=" + pendingWindows.size()
            + ", realWindows=" + realWindowsByName.size()
            + ", retryAt=" + nextRetryAt
            + ", runtime={" + runtimeState() + "}");
        if (lastFailure != null && next == State.RETRYABLE_FAILURE) {
            Log.err("[Tripwire OverlayUI bridge] last failure", lastFailure);
        }
    }

    private Method windowMethod(Object target, String name, Class<?>... parameters) {
        try {
            return target.getClass().getMethod(name, parameters);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("MindustryX OverlayUI method missing: " + name, e);
        }
    }

    private Throwable unwrapReflectionFailure(Throwable failure) {
        if (failure instanceof InvocationTargetException && ((InvocationTargetException)failure).getCause() != null) {
            return ((InvocationTargetException)failure).getCause();
        }
        return failure;
    }

    private Object invoke(Method method, Object target, Object... args) {
        try {
            return method.invoke(target, args);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException("Cannot access MindustryX OverlayUI method: " + method.getName(), e);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException)cause;
            if (cause instanceof Error) throw (Error)cause;
            throw new IllegalStateException("MindustryX OverlayUI method failed: " + method.getName(), cause);
        }
    }

    private static String runtimeState() {
        boolean scene = Core.scene != null;
        boolean settings = Core.settings != null;
        boolean ui = Vars.ui != null;
        boolean hudGroup = ui && Vars.ui.hudGroup != null;
        boolean hudfrag = ui && Vars.ui.hudfrag != null;
        boolean hudShown = hudfrag && Vars.ui.hudfrag.shown;
        boolean menu = Vars.state != null && Vars.state.isMenu();
        String size = Core.graphics == null ? "unknown" : Core.graphics.getWidth() + "x" + Core.graphics.getHeight();
        return "scene=" + scene
            + ", settings=" + settings
            + ", ui=" + ui
            + ", hudGroup=" + hudGroup
            + ", hudfrag=" + hudfrag
            + ", hudShown=" + hudShown
            + ", menu=" + menu
            + ", graphics=" + size;
    }

    private final class PendingWindow {
        private final String name;
        private Table table;
        private Prov<Boolean> availability;
        private final WindowHandle handle;

        private PendingWindow(String name, Table table, Prov<Boolean> availability) {
            this.name = name;
            this.table = table;
            this.availability = availability;
            this.handle = new WindowHandle(this);
        }
    }

    private final class WindowHandle implements OverlayWindowHandle {
        private final PendingWindow pending;
        private Object window;
        private Boolean autoHeight;
        private Boolean resizable;
        private Boolean enabled;
        private Boolean pinned;

        private WindowHandle(PendingWindow pending) {
            this.pending = pending;
        }

        private void bind(Object window) {
            this.window = window;
        }

        private void applyPendingState() {
            if (window == null) return;
            if (pending.availability != null) {
                ensureWindowMethods();
                invoke(windowSetAvailability, window, pending.availability);
            }
            if (autoHeight != null) {
                ensureWindowMethods();
                invoke(windowSetAutoHeight, window, autoHeight);
            }
            if (resizable != null) {
                ensureWindowMethods();
                invoke(windowSetResizable, window, resizable);
            }
            if (enabled != null || pinned != null) {
                applyEnabledAndPinned();
            }
        }

        private void ensureWindowMethods() {
            if (window == null) return;
            if (windowSetAvailability == null) {
                windowSetAvailability = windowMethod(window, "setAvailability", Prov.class);
                windowSetAutoHeight = windowMethod(window, "setAutoHeight", boolean.class);
                windowSetResizable = windowMethod(window, "setResizable", boolean.class);
                windowGetData = windowMethod(window, "getData");
            }
        }

        private void ensureDataMethods(Object data) {
            if (dataSetEnabled == null) {
                dataSetEnabled = windowMethod(data, "setEnabled", boolean.class);
                dataSetPinned = windowMethod(data, "setPinned", boolean.class);
                dataGetEnabled = windowMethod(data, "getEnabled");
            }
        }

        private void applyEnabledAndPinned() {
            if (window == null) return;
            ensureWindowMethods();
            Object data = invoke(windowGetData, window);
            ensureDataMethods(data);
            if (enabled != null) invoke(dataSetEnabled, data, enabled);
            if (pinned != null) invoke(dataSetPinned, data, pinned);
        }

        @Override
        public void configure(boolean autoHeight, boolean resizable) {
            this.autoHeight = autoHeight;
            this.resizable = resizable;
            tryBindOrInit("configure:" + pending.name);
            if (window != null) {
                ensureWindowMethods();
                invoke(windowSetAutoHeight, window, autoHeight);
                invoke(windowSetResizable, window, resizable);
            }
        }

        @Override
        public void setEnabledAndPinned(boolean enabled, boolean pinned) {
            this.enabled = enabled;
            this.pinned = pinned;
            tryBindOrInit("setEnabledAndPinned:" + pending.name);
            applyEnabledAndPinned();
        }

        @Override
        public Boolean getEnabled() {
            tryBindOrInit("getEnabled:" + pending.name);
            if (window == null) return enabled;
            ensureWindowMethods();
            Object data = invoke(windowGetData, window);
            ensureDataMethods(data);
            return (Boolean)invoke(dataGetEnabled, data);
        }

        @Override
        public Element asElement() {
            return window instanceof Element ? (Element)window : null;
        }
    }
}
