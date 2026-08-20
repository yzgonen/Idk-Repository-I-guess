package com.example.simpleesp.mixin;

import com.example.simpleesp.SimpleEspClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.fabric.render.FluidRendererImpl", remap = false)
public abstract class SodiumFluidRendererMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void simpleesp$hideFluidsInXray(CallbackInfo ci) {
        if (SimpleEspClient.isXrayEnabled()) {
            ci.cancel();
        }
    }
}
