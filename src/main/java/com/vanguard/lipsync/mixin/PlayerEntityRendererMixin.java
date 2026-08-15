package com.vanguard.lipsync.mixin;

import com.vanguard.lipsync.client.RenderStateTracker;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.entity.EntityPose;
import net.minecraft.entity.PlayerLikeEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public abstract class PlayerEntityRendererMixin {
    @Inject(method = "updateRenderState(Lnet/minecraft/entity/PlayerLikeEntity;Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;F)V", at = @At("TAIL"))
    private void vanguard$bindPlayer(PlayerLikeEntity player, PlayerEntityRenderState state, float tickProgress, CallbackInfo ci) {
        boolean crawl = player.isInSwimmingPose() && !player.isSwimming();
        boolean sit = state.isInPose(EntityPose.SITTING) || player.hasVehicle();
        RenderStateTracker.bind(
                state,
                player.getUuid(),
                player.isSprinting() && !player.isSwimming(),
                player.isInSneakingPose(),
                crawl,
                sit
        );
    }
}
