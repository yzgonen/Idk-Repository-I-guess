package dev.tuxy.motionblur.compat;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import dev.tuxy.motionblur.ui.MotionBlurConfigScreen;

public final class TuxyMotionBlurModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return MotionBlurConfigScreen::new;
    }
}
