package tripwire;

import arc.Core;
import arc.math.geom.Vec2;
import mindustry.gen.Player;
import mindustry.gen.Unit;

import java.lang.reflect.Method;

import static mindustry.Vars.player;
import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;

public final class TripwireAlert {
    private static Method mindustryXNewMarkFromChat;
    private static boolean mindustryXMarkerResolved;
    private static Method vanillaPingMethod;
    private static boolean vanillaPingResolved;

    private TripwireAlert() {
    }

    public static void crossed(float x, float y, Unit unit) {
        if (unit == null || unit.type == null || ui == null) return;
        int tileX = (int)(x / tilesize);
        int tileY = (int)(y / tilesize);
        String unitName = unit.type.localizedName;
        String message = Core.bundle.format("tripwire.alert.crossed", tileX, tileY, unitName);
        String markerMessage = Core.bundle.format("tripwire.alert.marker", tileX, tileY);
        if (TripwireSettings.toastAlert()) {
            ui.announce(message, 4f);
        }
        if (!markWithMindustryX(markerMessage, tileX, tileY)) {
            markWithVanillaPing(x, y, Core.bundle.format("tripwire.alert.ping", unitName));
        }
        if (TripwireSettings.chatAlert() && ui.chatfrag != null) {
            ui.chatfrag.addMessage(message);
        }
    }

    private static boolean markWithMindustryX(String markerMessage, int tileX, int tileY) {
        try {
            if (!mindustryXMarkerResolved) {
                mindustryXMarkerResolved = true;
                Class<?> markerType = Class.forName("mindustryX.features.MarkerType");
                mindustryXNewMarkFromChat = markerType.getMethod("newMarkFromChat", String.class, Vec2.class);
            }
            if (mindustryXNewMarkFromChat == null) return false;
            mindustryXNewMarkFromChat.invoke(null, markerMessage, new Vec2(tileX, tileY));
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void markWithVanillaPing(float x, float y, String text) {
        if (player == null) return;
        try {
            if (!vanillaPingResolved) {
                vanillaPingResolved = true;
                vanillaPingMethod = Class.forName("mindustry.gen.Call").getMethod("pingLocation", Player.class, float.class, float.class, String.class);
            }
            if (vanillaPingMethod != null) {
                vanillaPingMethod.invoke(null, player, x, y, text);
                return;
            }
        } catch (Throwable ignored) {
        }

        try {
            Class.forName("mindustry.input.InputHandler")
                .getMethod("pingLocation", Player.class, float.class, float.class, String.class)
                .invoke(null, player, x, y, text);
        } catch (Throwable ignored) {
        }
    }
}
