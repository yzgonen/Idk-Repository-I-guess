package com.vanguard.image2schem;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

final class GroqArchitectAIPlanParsingTest {
    @Test
    void missingWorldBoxIsRejectedInsteadOfBecomingHugeDefaultGeometry() throws Exception {
        GroqArchitectAI.Plan plan=parse("""
                {"scene_type":"bad","objects":[
                  {"type":"wall","image_box":{"x0":0.1,"y0":0.1,"x1":0.2,"y1":0.8},"confidence":0.9}
                ]}
                """);
        assertTrue(plan.objects().isEmpty(), "planner output missing world_box must not silently become a giant default object");
    }

    @Test
    void missingImageBoxIsRejectedBecauseDepthEvidenceWouldBeMeaningless() throws Exception {
        GroqArchitectAI.Plan plan=parse("""
                {"scene_type":"bad","objects":[
                  {"type":"wall","world_box":{"x0":0.1,"y0":0.1,"z0":0.4,"x1":0.2,"y1":0.8,"z1":0.45},"confidence":0.9}
                ]}
                """);
        assertTrue(plan.objects().isEmpty());
    }

    @Test
    void zeroVolumeWorldBoxIsRejected() throws Exception {
        GroqArchitectAI.Plan plan=parse("""
                {"scene_type":"bad","objects":[
                  {"type":"column","image_box":{"x0":0.2,"y0":0.1,"x1":0.3,"y1":0.8},
                   "world_box":{"x0":0.5,"y0":0.5,"z0":0.5,"x1":0.5,"y1":0.5,"z1":0.5},"confidence":0.99}
                ]}
                """);
        assertTrue(plan.objects().isEmpty(), "zero-volume objects must not be expanded into fake geometry by minimum-thickness rules");
    }

    @Test
    void validObjectSurvivesAndCoordinatesAreClamped() throws Exception {
        GroqArchitectAI.Plan plan=parse("""
                {"scene_type":"wall","proportions":{"width":99,"height":-4,"depth":4},"objects":[
                  {"type":"wall","image_box":{"x0":-1,"y0":0.1,"x1":2,"y1":0.8},
                   "world_box":{"x0":-1,"y0":0.1,"z0":0.4,"x1":2,"y1":0.8,"z1":0.45},
                   "minecraft_block":"minecraft:bricks","confidence":0.9}
                ]}
                """);
        assertEquals(1,plan.objects().size());
        var p=plan.objects().getFirst();
        assertEquals(0f,p.imageBox().x0());
        assertEquals(1f,p.imageBox().x1());
        assertEquals(0f,p.worldBox().x0());
        assertEquals(1f,p.worldBox().x1());
        assertTrue(plan.proportions().width()<=3f);
        assertTrue(plan.proportions().height()>=.15f);
        assertTrue(plan.proportions().depth()<=2f);
    }

    private static GroqArchitectAI.Plan parse(String json)throws Exception{
        Method m=GroqArchitectAI.class.getDeclaredMethod("parsePlan", JsonObject.class);
        m.setAccessible(true);
        return (GroqArchitectAI.Plan)m.invoke(null, JsonParser.parseString(json).getAsJsonObject());
    }
}
