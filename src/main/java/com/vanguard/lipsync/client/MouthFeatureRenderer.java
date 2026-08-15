package com.vanguard.lipsync.client;

import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.command.OrderedRenderCommandQueue;
import net.minecraft.client.render.entity.feature.FeatureRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;

import java.util.UUID;

/**
 * Draws a tiny blocky mouth directly on the front face of the player's head.
 * The layer follows the normal head model transform, so looking around,
 * crouching, swimming and other player animation continue to work normally.
 */
public final class MouthFeatureRenderer extends FeatureRenderer<PlayerEntityRenderState, PlayerEntityModel> {
    private static final float PX = 1.0F / 16.0F;
    private static final float FACE_Z = -4.035F * PX;

    private static final int CAVITY = 0xFF25080A;
    private static final int LIP = 0xFF6A2529;
    private static final int TEETH = 0xFFE8E5DC;
    private static final int TONGUE = 0xFFB94A55;

    public MouthFeatureRenderer(FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel> context) {
        super(context);
    }

    @Override
    public void render(MatrixStack matrices, OrderedRenderCommandQueue queue, int light,
                       PlayerEntityRenderState state, float limbAngle, float limbDistance) {
        UUID playerId = RenderStateTracker.get(state);
        if (playerId == null) return;

        MouthStateManager.MouthFrame frame = MouthStateManager.get(playerId);
        if (!frame.talking() && frame.open() < 0.01F) return;

        float round = frame.round();
        float wide = frame.wide();
        float open = frame.open();

        // Pixel-space dimensions on an 8x8 Minecraft face.
        float widthPx = 2.15F + wide * 2.15F - round * 0.72F;
        float heightPx = 0.30F + open * 2.20F + round * 0.35F;
        float centerYpx = -2.35F + open * 0.14F;

        // Round sounds become narrower/taller; wide sounds become flatter/wider.
        if (round > wide) {
            heightPx += round * 0.35F;
        } else {
            heightPx -= wide * 0.18F;
        }
        heightPx = Math.max(0.22F, heightPx);

        float halfW = widthPx * 0.5F * PX;
        float halfH = heightPx * 0.5F * PX;
        float cy = centerYpx * PX;

        matrices.push();
        getContextModel().getHead().applyTransform(matrices);

        // Mouth cavity.
        submitRect(queue, matrices, -halfW, cy - halfH, halfW, cy + halfH, FACE_Z, CAVITY);

        // Blocky lip edges. Keeping them thin preserves the player's own skin.
        float lip = (0.10F + open * 0.045F) * PX;
        submitRect(queue, matrices, -halfW, cy - halfH - lip, halfW, cy - halfH, FACE_Z - 0.00015F, LIP);
        submitRect(queue, matrices, -halfW, cy + halfH, halfW, cy + halfH + lip, FACE_Z - 0.00015F, LIP);

        // A subtle top-teeth flash on open/wide syllables.
        if (open > 0.46F && round < 0.68F) {
            float teethH = Math.min(0.34F, (open - 0.40F) * 0.60F) * PX;
            float inset = (0.22F + round * 0.18F) * PX;
            submitRect(queue, matrices, -halfW + inset, cy - halfH, halfW - inset,
                    cy - halfH + teethH, FACE_Z - 0.0003F, TEETH);
        }

        // Tongue appears only on strongly open sounds.
        if (open > 0.68F && round < 0.55F) {
            float tongueH = (open - 0.62F) * 0.42F * PX;
            float inset = 0.42F * PX;
            submitRect(queue, matrices, -halfW + inset, cy + halfH - tongueH,
                    halfW - inset, cy + halfH, FACE_Z - 0.00035F, TONGUE);
        }

        matrices.pop();
    }

    private static void submitRect(OrderedRenderCommandQueue queue, MatrixStack matrices,
                                   float x0, float y0, float x1, float y1, float z, int color) {
        queue.submitCustom(matrices, RenderLayers.debugQuads(), (entry, vertices) -> {
            vertex(vertices, entry, x0, y0, z, color);
            vertex(vertices, entry, x0, y1, z, color);
            vertex(vertices, entry, x1, y1, z, color);
            vertex(vertices, entry, x1, y0, z, color);
        });
    }

    private static void vertex(VertexConsumer vertices, MatrixStack.Entry entry,
                               float x, float y, float z, int color) {
        vertices.vertex(entry, x, y, z).color(color);
    }
}
