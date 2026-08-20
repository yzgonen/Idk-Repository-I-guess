package com.example.simpleesp.mixin;

import com.example.simpleesp.SimpleEspClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Mouse.class)
public abstract class MouseFreecamMixin {
    @Shadow @Final private MinecraftClient client;
    @Shadow private double cursorDeltaX;
    @Shadow private double cursorDeltaY;

    @Inject(method = "updateMouse", at = @At("HEAD"), cancellable = true)
    private void simpleesp$freecamMouse(double timeDelta, CallbackInfo ci) {
        if (!SimpleEspClient.isFreecamEnabled()) {
            return;
        }

        double sensitivity = this.client.options.getMouseSensitivity().getValue() * 0.6F + 0.2F;
        double factor = sensitivity * sensitivity * sensitivity * 8.0;
        double dx = this.cursorDeltaX * factor;
        double dy = this.cursorDeltaY * factor;

        if (this.client.options.getInvertMouseX().getValue()) {
            dx = -dx;
        }
        if (this.client.options.getInvertMouseY().getValue()) {
            dy = -dy;
        }

        SimpleEspClient.rotateFreecam(dx, dy);
        ci.cancel();
    }
}
