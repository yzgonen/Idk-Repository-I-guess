package com.example.simpleesp.mixin;

import com.example.simpleesp.SimpleEspClient;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Entity.class)
public abstract class EntityLookMixin {
    @Inject(method = "changeLookDirection", at = @At("HEAD"), cancellable = true)
    private void simpleesp$redirectFreecamLook(double cursorDeltaX, double cursorDeltaY, CallbackInfo ci) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (SimpleEspClient.isFreecamEnabled() && client.player != null && (Object) this == client.player) {
            SimpleEspClient.redirectLookToFreecam(cursorDeltaX, cursorDeltaY);
            ci.cancel();
        }
    }
}
