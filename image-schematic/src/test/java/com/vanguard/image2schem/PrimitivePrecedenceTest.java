package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class PrimitivePrecedenceTest {
    private static final GroqArchitectAI.Rect FULL=new GroqArchitectAI.Rect(0,0,1,1);
    private static final GroqArchitectAI.Box WALL=new GroqArchitectAI.Box(.2f,.1f,.45f,.8f,.8f,.50f);

    @Test
    void higherConfidenceSolidWinsWhenTwoSolidPlansOverlap() {
        var high=new GroqArchitectAI.Primitive("wall",FULL,WALL,"minecraft:red_concrete","z",false,1,.98f);
        var low=new GroqArchitectAI.Primitive("wall",FULL,WALL,"minecraft:blue_concrete","z",false,1,.40f);
        ImageConverter.Result r=build(List.of(high,low));
        int center=at(r,.5f,.5f,.48f);
        assertEquals(r.palette().get("minecraft:red_concrete"),center,
                "lower-confidence solid geometry must not overwrite a high-confidence object");
    }

    @Test
    void semanticWindowOverlayWinsWallEvenAtLowerConfidence() {
        var wall=new GroqArchitectAI.Primitive("wall",FULL,WALL,"minecraft:stone_bricks","z",false,1,.99f);
        var windowBox=new GroqArchitectAI.Box(.4f,.35f,.44f,.6f,.65f,.51f);
        var window=new GroqArchitectAI.Primitive("window",FULL,windowBox,"minecraft:glass","z",false,1,.55f);
        ImageConverter.Result r=build(List.of(wall,window));
        int center=at(r,.5f,.5f,.48f);
        assertEquals(r.palette().get("minecraft:glass"),center,
                "a detected window is an overlay/cut-in and should not be buried by its host wall");
    }

    @Test
    void explicitOpeningAlwaysCarvesAfterAllSolids() {
        var wall=new GroqArchitectAI.Primitive("wall",FULL,WALL,"minecraft:stone_bricks","z",false,1,.99f);
        var openingBox=new GroqArchitectAI.Box(.44f,.1f,.43f,.56f,.5f,.52f);
        var opening=new GroqArchitectAI.Primitive("opening",FULL,openingBox,"","z",false,1,.35f);
        ImageConverter.Result r=build(List.of(wall,opening));
        assertEquals(0,at(r,.5f,.3f,.48f),"explicit openings must carve regardless of confidence ordering");
    }

    private static ImageConverter.Result build(List<GroqArchitectAI.Primitive> objects){
        BufferedImage img=new BufferedImage(64,48,BufferedImage.TYPE_INT_RGB);
        float[][] depth=new float[48][64];
        for(float[] row:depth)java.util.Arrays.fill(row,.5f);
        var plan=new GroqArchitectAI.Plan("precedence",.9f,new GroqArchitectAI.Proportions(1f,.8f,.7f),objects);
        return GroqArchitectureBuilder.build(img,depth,plan,96,44,p->{});
    }

    private static int at(ImageConverter.Result r,float fx,float fy,float fz){
        int x=Math.round(fx*(r.width()-1)),y=Math.round(fy*(r.height()-1)),z=Math.round(fz*(r.length()-1));
        return r.paletteIds()[x+z*r.width()+y*r.width()*r.length()];
    }
}
