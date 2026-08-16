package com.vanguard.lipsync.mixin;

import com.vanguard.lipsync.client.RenderStateTracker;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Adds restrained, grounded movement after vanilla has posed the player. */
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
            applySit(model);
            copyWearLayers(model);
            return;
        }

        if (info.crouching()) applyCrouch(model);
        if (info.sprinting()) applySprint(model);
        copyWearLayers(model);
    }

    private static void applySprint(PlayerEntityModel m) {
        // Small forward drive only. No side roll, bounce, or cartoon arm flare.
        m.body.pitch += 0.085F;
        m.head.pitch -= 0.035F;
        m.rightArm.pitch *= 1.06F;
        m.leftArm.pitch *= 1.06F;
        m.rightLeg.pitch *= 1.04F;
        m.leftLeg.pitch *= 1.04F;
        m.rightArm.roll += 0.012F;
        m.leftArm.roll -= 0.012F;
    }

    private static void applyCrouch(PlayerEntityModel m) {
        // Tight, controlled crouch. Keep limbs close to the body.
        m.body.pitch += 0.075F;
        m.head.pitch -= 0.025F;
        m.rightLeg.pitch += 0.075F;
        m.leftLeg.pitch += 0.075F;
        m.rightArm.pitch -= 0.025F;
        m.leftArm.pitch -= 0.025F;
    }

    private static void applyCrawl(PlayerEntityModel m, float t) {
        // Low military-style crawl: elbows and knees stay tucked in with a
        // restrained alternating pull instead of swimming-like flailing.
        float stroke = (float) Math.sin(t * 0.26F) * 0.13F;
        float opposite = (float) Math.sin(t * 0.26F + Math.PI) * 0.13F;

        m.rightArm.pitch = -2.30F + stroke;
        m.leftArm.pitch = -2.30F + opposite;
        m.rightArm.roll = 0.045F;
        m.leftArm.roll = -0.045F;

        m.rightLeg.pitch = 0.12F + opposite;
        m.leftLeg.pitch = 0.12F + stroke;
        m.rightLeg.roll = 0.025F;
        m.leftLeg.roll = -0.025F;
        m.head.pitch += 0.045F;
    }

    private static void applySit(PlayerEntityModel m) {
        // Stable relaxed seat: no swaying/breathing loop.
        m.body.pitch += 0.015F;
        m.rightLeg.pitch = -1.12F;
        m.leftLeg.pitch = -1.12F;
        m.rightLeg.yaw = 0.045F;
        m.leftLeg.yaw = -0.045F;
        m.rightArm.pitch = -0.12F;
        m.leftArm.pitch = -0.12F;
        m.rightArm.roll = 0.025F;
        m.leftArm.roll = -0.025F;
    }

    private static void copyWearLayers(PlayerEntityModel m) {
        m.leftSleeve.setTransform(m.leftArm.getTransform());
        m.rightSleeve.setTransform(m.rightArm.getTransform());
        m.leftPants.setTransform(m.leftLeg.getTransform());
        m.rightPants.setTransform(m.rightLeg.getTransform());
        m.jacket.setTransform(m.body.getTransform());
    }
}
