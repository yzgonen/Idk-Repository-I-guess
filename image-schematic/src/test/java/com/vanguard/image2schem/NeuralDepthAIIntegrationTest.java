package com.vanguard.image2schem;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.*;

final class NeuralDepthAIIntegrationTest {
    @Test
    void realOnnxModelLoadsRunsAndReturnsFiniteDepthMap() throws Exception {
        Assumptions.assumeTrue("1".equals(System.getenv("IMAGE2SCHEM_RUN_MODEL_TEST")),
                "real neural model smoke test is enabled by CI");

        int w=80,h=60;
        BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        // A synthetic scene with several strong geometric depth cues, not a bunker fixture.
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int v=Math.min(255,20+x*2+y*2);
            int rgb=(v<<16)|(v<<8)|v;
            if(x>22&&x<58&&y>12&&y<48) rgb=0xD0D0D0;
            if(x>33&&x<47&&y>25) rgb=0x202020;
            image.setRGB(x,y,rgb);
        }

        final int[] maxProgress={0};
        float[][] depth=NeuralDepthAI.estimate(image,p->maxProgress[0]=Math.max(maxProgress[0],p));
        assertEquals(h,depth.length);
        assertEquals(w,depth[0].length);
        assertTrue(maxProgress[0]>=48,"inference should reach its completed inference stage");

        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        for(float[] row:depth)for(float v:row){
            assertTrue(Float.isFinite(v),"depth map must not contain NaN/Infinity");
            assertTrue(v>=-.0001f&&v<=1.0001f,"normalized depth must stay in 0..1");
            min=Math.min(min,v);max=Math.max(max,v);
        }
        assertTrue(max-min>.02f,"real model should produce a non-flat depth map for the synthetic scene");
    }
}
