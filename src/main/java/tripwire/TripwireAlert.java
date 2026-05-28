package tripwire;

import mindustry.gen.Unit;

import static mindustry.Vars.tilesize;
import static mindustry.Vars.ui;

public final class TripwireAlert {
    private TripwireAlert() {
    }

    public static void crossed(float x, float y, Unit unit) {
        if (unit == null || unit.type == null || ui == null) return;
        String message = "[scarlet]Warn[] (" + (int)(x / tilesize) + "," + (int)(y / tilesize) + ") [scarlet]" + unit.type.localizedName + "[] crossed the tripwire";
        if (TripwireSettings.toastAlert()) {
            ui.showInfoToast(message, 4f);
        }
        if (TripwireSettings.chatAlert() && ui.chatfrag != null) {
            ui.chatfrag.addMessage(message);
        }
    }
}
