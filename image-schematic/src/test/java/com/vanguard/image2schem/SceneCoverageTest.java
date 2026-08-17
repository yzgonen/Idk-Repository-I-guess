package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Coverage for scene families that must be represented only by their supplied primitives. */
final class SceneCoverageTest {
    private static final GroqArchitectAI.Rect FULL = new GroqArchitectAI.Rect(0, 0, 1, 1);

    @Test
    void bunkerIsBuiltBecausePlanContainsBunkerPrimitivesNotBecauseBuilderAssumesThem() {
        var frontWall = p("wall", box(.08f,.08f,.30f,.92f,.72f,.36f), "minecraft:gray_concrete");
        var roof = p("roof", box(.05f,.72f,.26f,.95f,.78f,.62f), "minecraft:deepslate_tiles");
        var doorway = p("opening", box(.42f,.08f,.28f,.58f,.48f,.39f), "");
        var floor = p("floor", box(.18f,.06f,.34f,.82f,.08f,.82f), "minecraft:smooth_stone");
        var backWall = p("wall", box(.20f,.08f,.78f,.80f,.58f,.82f), "minecraft:stone_bricks");

        ImageConverter.Result r = build("bunker", 1f, .78f, .9f,
                List.of(frontWall, roof, doorway, floor, backWall));

        assertValid(r);
        assertTrue(nonAir(r) > 700, "explicit bunker primitives should create meaningful geometry");
        assertEquals(0, at(r, .50f, .28f, .34f), "front doorway must be carved");
        assertNotEquals(0, at(r, .50f, .28f, .80f),
                "a front opening must not accidentally tunnel through an unrelated back wall");
        assertTrue(fillRatio(r) < .30, "bunker plan must not inflate into a giant solid volume");
    }

    @Test
    void roomInteriorKeepsSeparateWallsFloorCeilingAndWindowWithoutInventingExterior() {
        var floor = p("floor", box(.10f,.05f,.12f,.90f,.07f,.88f), "minecraft:oak_planks");
        var ceiling = p("roof", box(.10f,.72f,.12f,.90f,.75f,.88f), "minecraft:white_concrete");
        var left = p("wall", box(.10f,.07f,.12f,.14f,.72f,.88f), "minecraft:white_concrete");
        var right = p("wall", box(.86f,.07f,.12f,.90f,.72f,.88f), "minecraft:white_concrete");
        var back = p("wall", box(.14f,.07f,.84f,.86f,.72f,.88f), "minecraft:white_concrete");
        var window = p("window", box(.34f,.34f,.83f,.66f,.58f,.89f), "minecraft:glass");

        ImageConverter.Result r = build("room interior", 1f, .8f, .9f,
                List.of(floor, ceiling, left, right, back, window));

        assertValid(r);
        int glass = r.palette().get("minecraft:glass");
        assertEquals(glass, at(r, .50f, .48f, .86f), "planned back-wall window must remain visible");
        assertEquals(0, at(r, .50f, .40f, .45f), "room center should remain open air");
        assertTrue(fillRatio(r) < .18, "an interior room shell should remain mostly empty space");
    }

    private static GroqArchitectAI.Primitive p(String type, GroqArchitectAI.Box world, String block) {
        return new GroqArchitectAI.Primitive(type, FULL, world, block, "z", false, 1, .99f);
    }

    private static GroqArchitectAI.Box box(float x0,float y0,float z0,float x1,float y1,float z1) {
        return new GroqArchitectAI.Box(x0,y0,z0,x1,y1,z1);
    }

    private static ImageConverter.Result build(String type,float w,float h,float d,List<GroqArchitectAI.Primitive> objects) {
        BufferedImage image = new BufferedImage(96, 72, BufferedImage.TYPE_INT_RGB);
        float[][] depth = new float[72][96];
        for (int y=0; y<depth.length; y++) for (int x=0; x<depth[0].length; x++) depth[y][x] = .5f;
        var plan = new GroqArchitectAI.Plan(type, .95f, new GroqArchitectAI.Proportions(w,h,d), objects);
        return GroqArchitectureBuilder.build(image, depth, plan, 96, 44, ignored -> {});
    }

    private static void assertValid(ImageConverter.Result r) {
        assertTrue(r.width() > 0 && r.height() > 0 && r.length() > 0);
        assertEquals(r.width()*r.height()*r.length(), r.paletteIds().length);
        int max = r.palette().size()-1;
        for (int id : r.paletteIds()) assertTrue(id >= 0 && id <= max, "invalid palette id " + id);
    }

    private static int nonAir(ImageConverter.Result r) {
        int n=0; for (int id : r.paletteIds()) if (id != 0) n++; return n;
    }

    private static double fillRatio(ImageConverter.Result r) {
        return nonAir(r)/(double)r.paletteIds().length;
    }

    private static int at(ImageConverter.Result r,float fx,float fy,float fz) {
        int x=Math.min(r.width()-1,Math.max(0,Math.round(fx*(r.width()-1))));
        int y=Math.min(r.height()-1,Math.max(0,Math.round(fy*(r.height()-1))));
        int z=Math.min(r.length()-1,Math.max(0,Math.round(fz*(r.length()-1))));
        return r.paletteIds()[x + z*r.width() + y*r.width()*r.length()];
    }
}
