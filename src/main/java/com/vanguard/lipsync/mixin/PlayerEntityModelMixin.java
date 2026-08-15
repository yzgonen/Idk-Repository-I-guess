package com.vanguard.lipsync.mixin;

import com.vanguard.lipsync.client.RenderStateTracker;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds cinematic motion after vanilla has already posed the player. */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void vanguard$cinematicMovement(PlayerEntityRenderState state, CallbackInfo ci) {
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        RenderStateTracker.PlayerRenderInfo info = RenderStateTracker.info(state);
        float t = state.age;

        if (info.crawling()) {
            applyCrawl(model, t);
            copyWearLayers(model);
            return;
        }

        if (info.sitting()) {
            applySit(model, t);
            copyWearLayers(model);
            return;
        }

        if (info.crouching()) applyCrouch(model, t);
        if (info.sprinting()) applySprint(model, t);
        copyWearLayers(model);
    }

    private static void applySprint(PlayerEntityModel m, float t) {
        float bounce = (float) Math.sin(t * 0.72F);
        float counter = (float) Math.sin(t * 1.44F);
        m.body.pitch += 0.20F;
        m.head.pitch -= 0.09F;
        m.body.roll += bounce * 0.025F;
        m.rightArm.pitch *= 1.20F;
        m.leftArm.pitch *= 1.20F;
        m.rightLeg.pitch *= 1.16F;
        m.leftLeg.pitch *= 1.16F;
        m.rightArm.roll += 0.05F + bounce * 0.035F;
        m.leftArm.roll -= 0.05F + bounce * 0.035F;
        m.body.originY += Math.abs(counter) * 0.13F;
    }

    private static void applyCrouch(PlayerEntityModel m, float t) {
        float breathe = (float) Math.sin(t * 0.11F) * 0.018F;
        m.body.pitch += 0.16F + breathe;
        m.head.pitch -= 0.07F;
        m.rightLeg.pitch += 0.17F;
        m.leftLeg.pitch += 0.17F;
        m.rightArm.pitch -= 0.08F;
        m.leftArm.pitch -= 0.08F;
        m.rightArm.roll += 0.035F;
        m.leftArm.roll -= 0.035F;
    }

    private static void applyCrawl(PlayerEntityModel m, float t) {
        float stroke = (float) Math.sin(t * 0.34F);
        float opposite = (float) Math.sin(t * 0.34F + Math.PI);
        m.rightArm.pitch = -2.45F + stroke * 0.36F;
        m.leftArm.pitch = -2.45F + opposite * 0.36F;
        m.rightArm.roll = 0.12F;
        m.leftArm.roll = -0.12F;
        m.rightLeg.pitch = 0.22F + opposite * 0.34F;
        m.leftLeg.pitch = 0.22F + stroke * 0.34F;
        m.rightLeg.roll = 0.07F;
        m.leftLeg.roll = -0.07F;
        m.head.pitch += 0.10F;
    }

    private static void applySit(PlayerEntityModel m, float t) {
        float breathe = (float) Math.sin(t * 0.09F) * 0.018F;
        m.body.pitch += 0.035F + breathe;
        m.rightLeg.pitch = -1.18F;
        m.leftLeg.pitch = -1.18F;
        m.rightLeg.yaw = 0.10F;
        m.leftLeg.yaw = -0.10F;
        m.rightArm.pitch = -0.24F;
        m.leftArm.pitch = -0.24F;
        m.rightArm.roll = 0.08F;
        m.leftArm.roll = -0.08F;
        m.head.pitch -= breathe * 0.6F;
    }

    private static void copyWearLayers(PlayerEntityModel m) {
        m.leftSleeve.setTransform(m.leftArm.getTransform());
        m.rightSleeve.setTransform(m.rightArm.getTransform());
        m.leftPants.setTransform(m.leftLeg.getTransform());
        m.rightPants.setTransform(m.rightLeg.getTransform());
        m.jacket.setTransform(m.body.getTransform());
    }
}
