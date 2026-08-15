package com.vanguard.lipsync.mixin;

import com.vanguard.lipsync.client.RenderStateTracker;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds subtle cinematic motion after vanilla has already posed the player.
 * This intentionally enhances Minecraft's animation instead of replacing it.
 */
@Mixin(PlayerEntityModel.class)
public abstract class PlayerEntityModelMixin {

    @Inject(method = "setAngles(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;)V", at = @At("TAIL"))
    private void vanguard$cinematicMovement(PlayerEntityRenderState state, CallbackInfo ci) {
        PlayerEntityModel model = (PlayerEntityModel) (Object) this;
        RenderStateTracker.PlayerRenderInfo info = RenderStateTracker.info(state);
        float t = state.age;

        if (info.crawling()) {
            applyCrawl(model, t);
            return;
        }

        if (info.sitting()) {
            applySit(model, t);
            return;
        }

        if (info.crouching()) {
            applyCrouch(model, t);
        }

        if (info.sprinting()) {
            applySprint(model, t);
        }

        copyWearLayers(model);
    }

    private static void applySprint(PlayerEntityModel m, float t) {
        float bounce = (float) Math.sin(t * 0.72F);
        float counter = (float) Math.sin(t * 1.44F);

        // Athletic forward lean rather than vanilla's upright run.
        m.body.pitch += 0.20F;
        m.head.pitch -= 0.09F;
        m.body.roll += bounce * 0.025F;

        // Exaggerate opposite arm/leg drive while keeping vanilla phase.
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

        // Lower, more guarded crouch: chest forward and knees clearly bent.
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

        // Vanilla already rotates the whole player into swimming/crawling pose.
        // These offsets make the limbs look like an actual low crawl.
        m.rightArm.pitch = -2.45F + stroke * 0.36F;
        m.leftArm.pitch = -2.45F + opposite * 0.36F;
        m.rightArm.roll = 0.12F;
        m.leftArm.roll = -0.12F;

        m.rightLeg.pitch = 0.22F + opposite * 0.34F;
        m.leftLeg.pitch = 0.22F + stroke * 0.34F;
        m.rightLeg.roll = 0.07F;
        m.leftLeg.roll = -0.07F;
        m.head.pitch += 0.10F;

        copyWearLayers(m);
    }

    private static void applySit(PlayerEntityModel m, float t) {
        float breathe = (float) Math.sin(t * 0.09F) * 0.018F;

        // Relaxed seated silhouette instead of rigid 90-degree mannequin limbs.
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

        copyWearLayers(m);
    }

    private static void copyWearLayers(PlayerEntityModel m) {
        m.leftSleeve.copyTransform(m.leftArm);
        m.rightSleeve.copyTransform(m.rightArm);
        m.leftPants.copyTransform(m.leftLeg);
        m.rightPants.copyTransform(m.rightLeg);
        m.jacket.copyTransform(m.body);
    }
}
