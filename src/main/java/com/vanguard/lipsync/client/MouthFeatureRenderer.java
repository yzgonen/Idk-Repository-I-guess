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
 * Subtle speech deformation using only the player's own skin pixels.
 * The mouth never scales like rubber. Instead, fixed-size skin strips shift
 * slightly to suggest jaw opening, lip compression, wide vowels and rounded
 * vowels while keeping the original Minecraft face shape intact.
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

        // Keep the mouth's actual width fixed. Only tiny position offsets change.
        // Quantizing the jaw movement prevents the old smooth "expanding rubber"
        // look and makes it read like Minecraft pixel animation.
        float jawPx = snap(open * (0.58F - press * 0.18F), 0.125F);
        float lowerJawPx = snap(open * 0.28F, 0.125F);

        // Wide vowels pull the two mouth halves apart by at most 1/4 pixel.
        // Rounded vowels push them inward by at most 1/4 pixel. No stretching.
        float cornerShiftPx = snap((wide - round) * 0.24F, 0.125F);
        float leftShift = -cornerShiftPx;
        float rightShift = cornerShiftPx;

        matrices.push();
        getContextModel().getHead().applyTransform(matrices);

        // Upper mouth row: nearly anchored to preserve moustaches, masks and the
        // original mouth line from the skin.
        texturedRect(queue, matrices, layer, light,
                -2.0F * PX, -1.98F * PX,
                 2.0F * PX, -1.10F * PX,
                FACE_Z,
                10F / 64F, 13F / 64F,
                14F / 64F, 14F / 64F);

        // Lower-left and lower-right halves shift as rigid pixel blocks rather
        // than scaling. This is what gives a speech shape without ballooning.
        float top = (-1.08F + jawPx * 0.18F) * PX;
        float bottom = (0.02F + jawPx) * PX;

        texturedRect(queue, matrices, layer, light,
                (-2.0F + leftShift) * PX, top,
                (0.0F + leftShift) * PX, bottom,
                FACE_Z - 0.00012F,
                10F / 64F, 14F / 64F,
                12F / 64F, 16F / 64F);

        texturedRect(queue, matrices, layer, light,
                (0.0F + rightShift) * PX, top,
                (2.0F + rightShift) * PX, bottom,
                FACE_Z - 0.00012F,
                12F / 64F, 14F / 64F,
                14F / 64F, 16F / 64F);

        // Tiny chin/jaw continuation. It only translates down; it never grows.
        if (lowerJawPx > 0F) {
            texturedRect(queue, matrices, layer, light,
                    -1.5F * PX, (0.02F + jawPx) * PX,
                     1.5F * PX, (0.52F + jawPx + lowerJawPx) * PX,
                    FACE_Z - 0.00018F,
                    10.5F / 64F, 15.5F / 64F,
                    13.5F / 64F, 16F / 64F);
        }

        matrices.pop();
    }

    private static float snap(float value, float step) {
        return Math.round(value / step) * step;
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
}
