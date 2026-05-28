package tripwire;

import arc.Events;
import mindustry.game.EventType;
import mindustry.gen.Icon;
import mindustry.mod.Mod;

import static mindustry.Vars.ui;

public class TripwireMod extends Mod {
    public TripwireMod() {
        TripwireData.init();
        TripwireInput.init();
        TripwireRenderer.init();
        TripwireDetector.init();

        Events.on(EventType.ClientLoadEvent.class, e -> {
            if (ui != null && ui.settings != null) {
                ui.settings.addCategory("@settings.tripwire", Icon.map, TripwireSettings::buildSettings);
            }
        });
    }
}
