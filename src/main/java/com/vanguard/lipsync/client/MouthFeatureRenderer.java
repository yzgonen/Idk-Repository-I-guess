package com.vanguard.lipsync.client;

import net.minecraft.client.render.OverlayTexture;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

import java.util.UUID;

/**
 * Speech deformation made from the player's OWN skin pixels.
 *
 * Instead of painting a generic mouth over every skin, this renderer re-samples
 * the lower front face of the active player skin and moves/scales those strips.
 * Beards, masks, custom mouths and skin colors therefore remain part of the
 * animation. When speech ends no extra face layer is rendered at all.
 */
public final class MouthFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private static final float PX = 1.0F / 16.0F;
    private static final float FACE_Z = -4.055F * PX;

    public MouthFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        UUID playerId = RenderStateTracker.get(state);
        if (playerId == null || state.skinTextures == null || state.skinTextures.body() == null) return;

        MouthStateManager.MouthFrame frame = MouthStateManager.get(playerId);
        if (!frame.talking() && frame.open() < 0.012F) return;

        Identifier skin = state.skinTextures.body().texturePath();
        RenderLayer layer = RenderLayers.entityCutoutNoCullZOffset(skin);

        float open = frame.open();
        float wide = frame.wide();
        float round = frame.round();
        float press = frame.press();

        // Minecraft head front is skin pixels x=8..16, y=8..16.
        // We animate only the lower center of that face so eyes/hair stay untouched.
        float width = 4.4F + wide * 1.65F - round * 0.85F;
        width = clamp(width, 3.1F, 6.2F);
        float left = -width * 0.5F;
        float right = width * 0.5F;

        // Vertical jaw travel. Lip-closure transitions intentionally reduce it.
        float jaw = open * (1.34F - press * 0.36F);
        float roundNudge = round * 0.26F;

        matrices.push();
        getContextModel().getHead().applyTransform(matrices);

        // Upper lip / moustache strip: preserve skin pixels but allow width change.
        texturedRect(queue, matrices, layer, light,
                left * PX, -2.05F * PX,
                right * PX, -1.20F * PX + jaw * 0.10F * PX,
                FACE_Z,
                9F / 64F, 13F / 64F,
                15F / 64F, 14F / 64F);

        // Main mouth/chin strip moves down with jaw opening. This is the key part:
        // it is literally the player's own skin texture, not our own mouth art.
        float topY = (-1.20F + jaw * 0.08F) * PX;
        float bottomY = (0.35F + jaw + roundNudge) * PX;
        texturedRect(queue, matrices, layer, light,
                left * PX, topY,
                right * PX, bottomY,
                FACE_Z - 0.00012F,
                9F / 64F, 14F / 64F,
                15F / 64F, 16F / 64F);

        // Lower jaw continuation uses the skin's bottom face rows, giving beards
        // and masks a more physical down-and-back motion while speaking.
        if (jaw > 0.08F) {
            float lowerWidth = width * (0.95F - round * 0.08F);
            texturedRect(queue, matrices, layer, light,
                    -lowerWidth * 0.5F * PX, bottomY - 0.04F * PX,
                    lowerWidth * 0.5F * PX, bottomY + (0.48F + jaw * 0.22F) * PX,
                    FACE_Z - 0.00018F,
                    9F / 64F, 15F / 64F,
                    15F / 64F, 16F / 64F);
        }

        matrices.pop();
    }

    private static void texturedRect(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                     RenderLayer layer, int light,
                                     float x0, float y0, float x1, float y1, float z,
                                     float u0, float v0, float u1, float v1) {
        queue.submitCustom(matrices, layer, (entry, vertices) -> {
            vertex(vertices, entry, x0, y0, z, u0, v0, light);
            vertex(vertices, entry, x0, y1, z, u0, v1, light);
            vertex(vertices, entry, x1, y1, z, u1, v1, light);
            vertex(vertices, entry, x1, y0, z, u1, v0, light);
        });
    }

    private static void vertex(VertexConsumer vertices, MatrixStack.Entry entry,
                               float x, float y, float z, float u, float v, int light) {
        vertices.vertex(entry, x, y, z)
                .color(0xFFFFFFFF)
                .texture(u, v)
                .overlay(OverlayTexture.DEFAULT_UV)
                .light(light)
                .normal(entry, 0F, 0F, -1F);
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
