package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

final class SemanticInsetDepthTest {
    @Test
    void lowConfidenceOpeningStaysOnItsHostWallDespiteDifferentImageDepthSample() {
        var wall = new GroqArchitectAI.Primitive(
                "wall",
                new GroqArchitectAI.Rect(0, 0, 1, 1),
                new GroqArchitectAI.Box(.20f, .10f, .45f, .80f, .80f, .50f),
                "minecraft:stone_bricks", "z", false, 1, .99f);
        var opening = new GroqArchitectAI.Primitive(
                "opening",
                new GroqArchitectAI.Rect(0, .25f, .18f, .75f),
                new GroqArchitectAI.Box(.44f, .10f, .43f, .56f, .50f, .52f),
                "", "z", false, 1, .35f);

        ImageConverter.Result r = build(List.of(wall, opening));
        assertEquals(0, at(r, .50f, .30f, .48f),
                "an opening is a semantic cutout in its planned host plane; neural depth must not shift it away from that wall");
    }

    @Test
    void lowConfidenceWindowStaysOnItsHostWallDespiteDifferentImageDepthSample() {
        var wall = new GroqArchitectAI.Primitive(
                "wall",
                new GroqArchitectAI.Rect(0, 0, 1, 1),
                new GroqArchitectAI.Box(.20f, .10f, .45f, .80f, .80f, .50f),
                "minecraft:stone_bricks", "z", false, 1, .99f);
        var window = new GroqArchitectAI.Primitive(
                "window",
                new GroqArchitectAI.Rect(0, .25f, .18f, .75f),
                new GroqArchitectAI.Box(.40f, .35f, .44f, .60f, .65f, .51f),
                "minecraft:glass", "z", false, 1, .35f);

        ImageConverter.Result r = build(List.of(wall, window));
        assertEquals(r.palette().get("minecraft:glass"), at(r, .50f, .50f, .48f),
                "a window is a semantic wall inset; neural depth must not float it away from the planned wall plane");
    }

    private static ImageConverter.Result build(List<GroqArchitectAI.Primitive> objects) {
        BufferedImage image = new BufferedImage(96, 72, BufferedImage.TYPE_INT_RGB);
        float[][] depth = new float[72][96];
        for (int y = 0; y < depth.length; y++) {
            for (int x = 0; x < depth[0].length; x++) depth[y][x] = x / 95f;
        }
        var plan = new GroqArchitectAI.Plan("wall insets", .9f,
                new GroqArchitectAI.Proportions(1f, .8f, .7f), objects);
        return GroqArchitectureBuilder.build(image, depth, plan, 96, 44, ignored -> {});
    }

    private static int at(ImageConverter.Result r, float fx, float fy, float fz) {
        int x = Math.round(fx * (r.width() - 1));
        int y = Math.round(fy * (r.height() - 1));
        int z = Math.round(fz * (r.length() - 1));
        return r.paletteIds()[x + z * r.width() + y * r.width() * r.length()];
    }
}
