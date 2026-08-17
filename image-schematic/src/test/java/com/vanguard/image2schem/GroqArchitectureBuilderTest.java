package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class GroqArchitectureBuilderTest {
    private static final GroqArchitectAI.Rect FULL = new GroqArchitectAI.Rect(0, 0, 1, 1);

    @Test
    void houseSceneBuildsWithoutInventingBunkerGeometry() {
        var plan = plan("modern house", 1.0f, .72f, .65f,
                p("floor", box(.08f,.05f,.15f,.92f,.08f,.82f), "minecraft:smooth_stone"),
                p("wall", box(.08f,.08f,.68f,.92f,.62f,.72f), "minecraft:white_concrete"),
                p("roof", box(.05f,.62f,.62f,.95f,.67f,.78f), "minecraft:gray_concrete"),
                p("opening", box(.43f,.08f,.66f,.57f,.38f,.75f), "minecraft:air"),
                p("window", box(.16f,.30f,.65f,.32f,.48f,.74f), "minecraft:glass"));

        ImageConverter.Result r = build(plan);
        assertValid(r);
        assertTrue(nonAir(r) > 500, "house should contain meaningful geometry");
        assertTrue(fillRatio(r) < .35, "generic house must not become a giant solid volume");
        assertEquals(0, block(r, .50f, .20f, .70f), "explicit doorway should be carved to air");
    }

    @Test
    void castleSceneSupportsTowersAndArch() {
        var plan = plan("stone castle", 1.0f, .9f, .7f,
                p("wall", box(.20f,.08f,.48f,.80f,.58f,.58f), "minecraft:stone_bricks"),
                p("tower", box(.06f,.06f,.38f,.25f,.86f,.62f), "minecraft:stone_bricks", "z", true, 1),
                p("tower", box(.75f,.06f,.38f,.94f,.86f,.62f), "minecraft:stone_bricks", "z", true, 1),
                p("arch", box(.39f,.08f,.43f,.61f,.48f,.61f), "minecraft:polished_andesite"),
                p("opening", box(.44f,.08f,.42f,.56f,.34f,.64f), "minecraft:air"));

        ImageConverter.Result r = build(plan);
        assertValid(r);
        assertTrue(nonAir(r) > 900);
        assertTrue(blockCountInXRange(r, 0f, .30f) > 150, "left tower expected");
        assertTrue(blockCountInXRange(r, .70f, 1f) > 150, "right tower expected");
    }

    @Test
    void bridgeSceneUsesRepeatedSupportsWithoutSceneSpecificExtras() {
        var plan = plan("bridge", 1.4f, .35f, 1.0f,
                p("platform", box(.05f,.55f,.08f,.95f,.60f,.92f), "minecraft:stone_bricks"),
                p("column", box(.14f,.05f,.24f,.86f,.55f,.31f), "minecraft:stone_bricks", "x", false, 5),
                p("railing", box(.05f,.60f,.10f,.95f,.72f,.13f), "minecraft:iron_block", "x", false, 9));

        ImageConverter.Result r = build(plan);
        assertValid(r);
        assertTrue(nonAir(r) > 500);
        assertTrue(fillRatio(r) < .20, "bridge should stay sparse/open");
    }

    @Test
    void stairsAndRampHaveActualVerticalRise() {
        var plan = plan("interior", 1.0f, .65f, .8f,
                p("floor", box(.05f,.05f,.05f,.95f,.07f,.95f), "minecraft:smooth_stone"),
                p("stairs", box(.15f,.07f,.15f,.35f,.48f,.72f), "minecraft:stone_bricks", "z", false, 1),
                p("ramp", box(.55f,.07f,.18f,.82f,.32f,.78f), "minecraft:gray_concrete", "z", false, 1));

        ImageConverter.Result r = build(plan);
        assertValid(r);
        int[] yBounds = nonAirYBounds(r);
        assertTrue(yBounds[1] - yBounds[0] >= Math.round(r.height() * .20f), "slopes should create real vertical rise");
    }

    @Test
    void singleWallDoesNotTriggerEntranceRampCorridorOrSymmetry() {
        var onlyWall = p("wall", box(.04f,.10f,.35f,.24f,.82f,.39f), "minecraft:bricks");
        ImageConverter.Result r = build(plan("wall study", 1f, .8f, .5f, onlyWall));
        assertValid(r);

        int forbidden = 0;
        for (int y=0; y<r.height(); y++) for (int z=0; z<r.length(); z++) for (int x=Math.round(r.width()*.35f); x<r.width(); x++) {
            if (r.paletteIds()[index(r,x,y,z)] != 0) forbidden++;
        }
        assertEquals(0, forbidden, "a lone left-side wall must not invent center/right-side architecture");
    }

    @Test
    void repeatedColumnsRespectZAxis() {
        var columns = p("column", box(.45f,.05f,.12f,.55f,.65f,.88f), "minecraft:stone_bricks", "z", false, 4);
        ImageConverter.Result r = build(plan("colonnade", .7f, .7f, 1.2f, columns));
        assertValid(r);

        int occupiedZSlices = 0;
        for (int z=0; z<r.length(); z++) {
            boolean any=false;
            for (int y=0; y<r.height() && !any; y++) for (int x=0; x<r.width(); x++) {
                if (r.paletteIds()[index(r,x,y,z)] != 0) { any=true; break; }
            }
            if (any) occupiedZSlices++;
        }
        assertTrue(occupiedZSlices < Math.round(r.length()*.45f), "four Z-axis columns should be separated posts, not one long solid wall");
        assertTrue(occupiedZSlices >= 4, "all repeated Z-axis columns should be represented");
    }

    @Test
    void edgeBoxesNeverEscapeArrayAndPaletteIdsRemainValid() {
        var plan = plan("edge case", 1f, 1f, 1f,
                p("tower", box(0,0,0,.08f,1,.08f), "minecraft:polished_blackstone_bricks", "z", true, 1),
                p("beam", box(.92f,.92f,.92f,1,1,1), "minecraft:iron_block"),
                p("floor", box(0,0,0,1,.01f,1), "minecraft:smooth_stone"));
        ImageConverter.Result r = build(plan);
        assertValid(r);
        assertTrue(nonAir(r) > 0);
    }

    private static ImageConverter.Result build(GroqArchitectAI.Plan plan) {
        BufferedImage image = new BufferedImage(96, 72, BufferedImage.TYPE_INT_RGB);
        float[][] depth = new float[72][96];
        for (int y=0; y<depth.length; y++) for (int x=0; x<depth[0].length; x++) depth[y][x] = .5f;
        return GroqArchitectureBuilder.build(image, depth, plan, 96, 44, ignored -> {});
    }

    private static GroqArchitectAI.Plan plan(String type, float w, float h, float d, GroqArchitectAI.Primitive... ps) {
        return new GroqArchitectAI.Plan(type, .95f, new GroqArchitectAI.Proportions(w,h,d), List.of(ps));
    }

    private static GroqArchitectAI.Box box(float x0,float y0,float z0,float x1,float y1,float z1) {
        return new GroqArchitectAI.Box(x0,y0,z0,x1,y1,z1);
    }

    private static GroqArchitectAI.Primitive p(String type, GroqArchitectAI.Box box, String block) {
        return p(type, box, block, "z", false, 1);
    }

    private static GroqArchitectAI.Primitive p(String type, GroqArchitectAI.Box box, String block, String axis, boolean hollow, int repeats) {
        return new GroqArchitectAI.Primitive(type, FULL, box, block, axis, hollow, repeats, .99f);
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

    private static int block(ImageConverter.Result r, float fx, float fy, float fz) {
        int x=Math.min(r.width()-1, Math.max(0, Math.round(fx*(r.width()-1))));
        int y=Math.min(r.height()-1, Math.max(0, Math.round(fy*(r.height()-1))));
        int z=Math.min(r.length()-1, Math.max(0, Math.round(fz*(r.length()-1))));
        return r.paletteIds()[index(r,x,y,z)];
    }

    private static int blockCountInXRange(ImageConverter.Result r,float lo,float hi) {
        int x0=Math.max(0,Math.round(lo*(r.width()-1))), x1=Math.min(r.width()-1,Math.round(hi*(r.width()-1))), n=0;
        for(int y=0;y<r.height();y++)for(int z=0;z<r.length();z++)for(int x=x0;x<=x1;x++)if(r.paletteIds()[index(r,x,y,z)]!=0)n++;
        return n;
    }

    private static int[] nonAirYBounds(ImageConverter.Result r) {
        int min=r.height(),max=-1;
        for(int y=0;y<r.height();y++)for(int z=0;z<r.length();z++)for(int x=0;x<r.width();x++)if(r.paletteIds()[index(r,x,y,z)]!=0){min=Math.min(min,y);max=Math.max(max,y);} 
        return new int[]{min,max};
    }

    private static int index(ImageConverter.Result r,int x,int y,int z) {
        return x + z*r.width() + y*r.width()*r.length();
    }
}
