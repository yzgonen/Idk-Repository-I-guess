package com.vanguard.image2schem;

import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

final class SlopeDirectionAndDoorTest {
    private static final GroqArchitectAI.Rect FULL=new GroqArchitectAI.Rect(0,0,1,1);

    @Test
    void zMinusRampRisesTowardNearerEnd() {
        var ramp=new GroqArchitectAI.Primitive("ramp",FULL,
                new GroqArchitectAI.Box(.35f,.10f,.20f,.65f,.55f,.80f),
                "minecraft:smooth_stone","z-",false,1,.99f);
        ImageConverter.Result r=build(ramp);
        int nearMax=maxYAtZ(r,Math.round(r.length()*.20f));
        int farMax=maxYAtZ(r,Math.round(r.length()*.80f));
        assertTrue(nearMax>farMax,"z- means the ramp must be higher toward decreasing Z");
    }

    @Test
    void zPlusRampRisesTowardFartherEnd() {
        var ramp=new GroqArchitectAI.Primitive("ramp",FULL,
                new GroqArchitectAI.Box(.35f,.10f,.20f,.65f,.55f,.80f),
                "minecraft:smooth_stone","z+",false,1,.99f);
        ImageConverter.Result r=build(ramp);
        int nearMax=maxYAtZ(r,Math.round(r.length()*.20f));
        int farMax=maxYAtZ(r,Math.round(r.length()*.80f));
        assertTrue(farMax>nearMax,"z+ means the ramp must be higher toward increasing Z");
    }

    @Test
    void visibleDoorIsMaterialWhileOpeningIsAir() {
        var wall=new GroqArchitectAI.Primitive("wall",FULL,
                new GroqArchitectAI.Box(.15f,.05f,.45f,.85f,.80f,.50f),
                "minecraft:stone_bricks","z",false,1,.95f);
        var door=new GroqArchitectAI.Primitive("door",FULL,
                new GroqArchitectAI.Box(.30f,.05f,.44f,.40f,.42f,.51f),
                "minecraft:dark_oak_planks","z",false,1,.80f);
        var opening=new GroqArchitectAI.Primitive("opening",FULL,
                new GroqArchitectAI.Box(.60f,.05f,.44f,.70f,.42f,.51f),
                "","z",false,1,.80f);
        ImageConverter.Result r=build(wall,door,opening);
        assertEquals(r.palette().get("minecraft:dark_oak_planks"),at(r,.35f,.22f,.48f),"visible door should remain material");
        assertEquals(0,at(r,.65f,.22f,.48f),"empty opening should be carved to air");
    }

    private static ImageConverter.Result build(GroqArchitectAI.Primitive... primitives){
        BufferedImage img=new BufferedImage(80,60,BufferedImage.TYPE_INT_RGB);
        float[][] depth=new float[60][80];for(float[] row:depth)Arrays.fill(row,.5f);
        var plan=new GroqArchitectAI.Plan("qa",.9f,new GroqArchitectAI.Proportions(1f,.8f,.8f),List.of(primitives));
        return GroqArchitectureBuilder.build(img,depth,plan,96,44,p->{});
    }

    private static int maxYAtZ(ImageConverter.Result r,int z){
        z=Math.max(0,Math.min(r.length()-1,z));int max=-1;
        for(int y=0;y<r.height();y++)for(int x=0;x<r.width();x++)if(r.paletteIds()[x+z*r.width()+y*r.width()*r.length()]!=0)max=Math.max(max,y);
        return max;
    }

    private static int at(ImageConverter.Result r,float fx,float fy,float fz){
        int x=Math.round(fx*(r.width()-1)),y=Math.round(fy*(r.height()-1)),z=Math.round(fz*(r.length()-1));
        return r.paletteIds()[x+z*r.width()+y*r.width()*r.length()];
    }
}
