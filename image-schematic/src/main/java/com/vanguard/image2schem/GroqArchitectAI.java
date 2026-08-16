package com.vanguard.image2schem;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

/** Groq is the scene-understanding brain; Minecraft geometry is still built locally. */
public final class GroqArchitectAI {
    private static final String ENDPOINT = "https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL = "qwen/qwen3.6-27b";

    private GroqArchitectAI() {}

    public static Plan analyze(BufferedImage source, IntConsumer progress) throws IOException {
        String key = GroqKeyStore.load();
        if (key == null || key.isBlank()) {
            throw new IOException("Groq API key missing. Paste it in the K menu and click Save Key.");
        }
        if (!key.trim().startsWith("gsk_")) {
            throw new IOException("Saved Groq API key is invalid. It should start with gsk_.");
        }

        progress.accept(3);
        String dataUrl = encodeForVision(source);
        progress.accept(7);

        JsonObject body = new JsonObject();
        body.addProperty("model", MODEL);
        body.addProperty("temperature", 0.4);
        body.addProperty("top_p", 0.8);
        body.addProperty("max_completion_tokens", 1400);
        body.addProperty("reasoning_effort", "none");
        body.addProperty("reasoning_format", "hidden");

        JsonObject responseFormat = new JsonObject();
        responseFormat.addProperty("type", "json_object");
        body.add("response_format", responseFormat);

        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", PROMPT);
        content.add(text);

        JsonObject image = new JsonObject();
        image.addProperty("type", "image_url");
        JsonObject imageUrl = new JsonObject();
        imageUrl.addProperty("url", dataUrl);
        image.add("image_url", imageUrl);
        content.add(image);

        JsonObject user = new JsonObject();
        user.addProperty("role", "user");
        user.add("content", content);
        JsonArray messages = new JsonArray();
        messages.add(user);
        body.add("messages", messages);

        progress.accept(10);
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT))
                .timeout(Duration.ofSeconds(45))
                .header("Authorization", "Bearer " + key.trim())
                .header("Content-Type", "application/json")
                .header("User-Agent", "Image2Schem/1.1.1")
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        final HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Groq request interrupted.", e);
        } catch (Exception e) {
            throw new IOException("Could not reach Groq: " + safe(e), e);
        }
        progress.accept(21);

        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("Groq API error " + response.statusCode() + ": " + safeGroqError(response.body()));
        }

        try {
            JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
            JsonArray choices = root.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) throw new IOException("Groq returned no choices.");
            JsonObject message = choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if (message == null || !message.has("content")) throw new IOException("Groq returned no architecture plan.");
            String planJson = message.get("content").getAsString();
            Plan plan = parsePlan(JsonParser.parseString(planJson).getAsJsonObject());
            progress.accept(24);
            return plan;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Groq returned invalid architecture JSON: " + safe(e), e);
        }
    }

    private static String encodeForVision(BufferedImage source) throws IOException {
        // 640px is enough for structural planning and keeps upload/API latency much lower.
        int maxSide = 640;
        int sw = source.getWidth(), sh = source.getHeight();
        float scale = Math.min(1f, maxSide / (float)Math.max(sw, sh));
        int w = Math.max(1, Math.round(sw * scale));
        int h = Math.max(1, Math.round(sh * scale));
        BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(source, 0, 0, w, h, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(rgb, "jpg", out)) throw new IOException("Could not encode image for Groq vision.");
        return "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Plan parsePlan(JsonObject o) {
        Rect entrance = rect(obj(o,"main_entrance"), new Rect(.30f,.28f,.70f,.72f,.35f));
        Point vp = point(obj(o,"vanishing_point"), new Point(.5f,.5f));

        JsonObject fo = obj(o,"floor");
        Floor floor = new Floor(
                f(fo,"top_y", entrance.y1),
                f(fo,"center_x", (entrance.x0+entrance.x1)/2f),
                f(fo,"top_width", Math.max(.18f, entrance.x1-entrance.x0)),
                f(fo,"bottom_width", .78f),
                str(fo,"slope","up_toward_entrance"),
                f(fo,"confidence",.5f)).clamped();

        JsonObject io = obj(o,"interior");
        Interior interior = new Interior(bool(io,"exists",true), f(io,"depth_scale",.55f), f(io,"confidence",.5f)).clamped();

        List<Structure> structures = new ArrayList<>();
        for (JsonElement e : array(o,"structures")) {
            if (!e.isJsonObject()) continue;
            JsonObject s=e.getAsJsonObject();
            Rect r=rect(s,new Rect(0,0,0,0,0));
            float conf=f(s,"confidence",.5f);
            if (r.x1-r.x0<.01f || r.y1-r.y0<.01f || conf<.20f) continue;
            structures.add(new Structure(str(s,"type","wall").toLowerCase(Locale.ROOT), r,
                    str(s,"depth_layer","mid").toLowerCase(Locale.ROOT),
                    str(s,"material","concrete").toLowerCase(Locale.ROOT),
                    bool(s,"mirror",false), conf));
            if (structures.size()>=20) break;
        }

        List<MaterialRegion> materials = new ArrayList<>();
        for (JsonElement e : array(o,"materials")) {
            if (!e.isJsonObject()) continue;
            JsonObject m=e.getAsJsonObject();
            Rect r=rect(m,new Rect(0,0,1,1,.3f));
            String block=str(m,"minecraft_block","");
            float conf=f(m,"confidence",.5f);
            if (isAllowedBlock(block) && conf>=.25f) materials.add(new MaterialRegion(r,block,conf));
            if (materials.size()>=10) break;
        }

        return new Plan(str(o,"scene_type","architecture"), f(o,"confidence",.5f), entrance.clamped(), vp.clamped(),
                floor, interior, clamp01(f(o,"symmetry",.5f)), List.copyOf(structures), List.copyOf(materials));
    }

    private static boolean isAllowedBlock(String b){
        return b.equals("minecraft:gray_concrete") || b.equals("minecraft:light_gray_concrete") ||
                b.equals("minecraft:deepslate_tiles") || b.equals("minecraft:polished_deepslate") ||
                b.equals("minecraft:stone_bricks") || b.equals("minecraft:smooth_stone") ||
                b.equals("minecraft:black_concrete") || b.equals("minecraft:white_concrete") ||
                b.equals("minecraft:brown_concrete") || b.equals("minecraft:orange_concrete") ||
                b.equals("minecraft:yellow_concrete") || b.equals("minecraft:red_concrete") ||
                b.equals("minecraft:blue_concrete") || b.equals("minecraft:tinted_glass") ||
                b.equals("minecraft:iron_block");
    }

    private static String safeGroqError(String body){
        try {
            JsonObject o=JsonParser.parseString(body).getAsJsonObject();
            if(o.has("error")&&o.get("error").isJsonObject()){
                JsonObject e=o.getAsJsonObject("error");
                if(e.has("message"))return e.get("message").getAsString();
            }
        } catch(Exception ignored) {}
        if(body==null||body.isBlank())return "empty response";
        String s=body.replaceAll("\\s+"," ").trim();
        return s.length()>220?s.substring(0,220)+"...":s;
    }

    private static Rect rect(JsonObject o, Rect d){ return o==null?d:new Rect(f(o,"x0",d.x0),f(o,"y0",d.y0),f(o,"x1",d.x1),f(o,"y1",d.y1),f(o,"confidence",d.confidence)).clamped(); }
    private static Point point(JsonObject o, Point d){ return o==null?d:new Point(f(o,"x",d.x),f(o,"y",d.y)).clamped(); }
    private static JsonObject obj(JsonObject o,String k){ return o!=null&&o.has(k)&&o.get(k).isJsonObject()?o.getAsJsonObject(k):new JsonObject(); }
    private static JsonArray array(JsonObject o,String k){ return o!=null&&o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray(); }
    private static float f(JsonObject o,String k,float d){ try{return o!=null&&o.has(k)?o.get(k).getAsFloat():d;}catch(Exception e){return d;} }
    private static boolean bool(JsonObject o,String k,boolean d){ try{return o!=null&&o.has(k)?o.get(k).getAsBoolean():d;}catch(Exception e){return d;} }
    private static String str(JsonObject o,String k,String d){ try{return o!=null&&o.has(k)?o.get(k).getAsString():d;}catch(Exception e){return d;} }
    private static float clamp01(float v){ return Math.max(0f,Math.min(1f,v)); }
    private static String safe(Throwable e){ return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage(); }

    public record Plan(String sceneType,float confidence,Rect entrance,Point vanishingPoint,Floor floor,
                       Interior interior,float symmetry,List<Structure> structures,List<MaterialRegion> materials){}
    public record Rect(float x0,float y0,float x1,float y1,float confidence){
        Rect clamped(){ float ax=clamp01(Math.min(x0,x1)),bx=clamp01(Math.max(x0,x1)),ay=clamp01(Math.min(y0,y1)),by=clamp01(Math.max(y0,y1)); return new Rect(ax,ay,bx,by,clamp01(confidence)); }
    }
    public record Point(float x,float y){ Point clamped(){ return new Point(clamp01(x),clamp01(y)); } }
    public record Floor(float topY,float centerX,float topWidth,float bottomWidth,String slope,float confidence){
        Floor clamped(){ return new Floor(clamp01(topY),clamp01(centerX),Math.max(.05f,Math.min(1f,topWidth)),Math.max(.08f,Math.min(1f,bottomWidth)),slope,clamp01(confidence)); }
    }
    public record Interior(boolean exists,float depthScale,float confidence){
        Interior clamped(){ return new Interior(exists,Math.max(.15f,Math.min(1f,depthScale)),clamp01(confidence)); }
    }
    public record Structure(String type,Rect box,String depthLayer,String material,boolean mirror,float confidence){}
    public record MaterialRegion(Rect box,String minecraftBlock,float confidence){}

    private static final String PROMPT = """
Analyze this image as architecture for a Minecraft reconstruction system. Return ONLY valid JSON. Do not trace pixels. Identify large real structural objects and broad materials. Coordinates are normalized 0..1 from the image top-left. Ignore text, logos, shadows, reflections, rain and tiny texture noise as geometry.

JSON shape:
{
 "scene_type":"short description",
 "confidence":0.0,
 "main_entrance":{"x0":0.0,"y0":0.0,"x1":1.0,"y1":1.0,"confidence":0.0},
 "vanishing_point":{"x":0.5,"y":0.5},
 "floor":{"top_y":0.60,"center_x":0.5,"top_width":0.35,"bottom_width":0.80,"slope":"up_toward_entrance","confidence":0.0},
 "interior":{"exists":true,"depth_scale":0.6,"confidence":0.0},
 "symmetry":0.0,
 "structures":[{"type":"column","x0":0.0,"y0":0.0,"x1":0.1,"y1":0.8,"depth_layer":"front","material":"concrete","mirror":false,"confidence":0.0}],
 "materials":[{"x0":0.0,"y0":0.0,"x1":1.0,"y1":1.0,"minecraft_block":"minecraft:gray_concrete","confidence":0.0}]
}

Rules: main_entrance is the actual major portal/opening. structures should be 6-16 major items when visible. type must be column, beam, wall, platform, window, door, opening, railing, roof, or trim. depth_layer must be front, mid, or back. floor widths are FULL image-width ratios. symmetry is 0..1. Materials are broad regions only. minecraft_block must be one of minecraft:gray_concrete, minecraft:light_gray_concrete, minecraft:deepslate_tiles, minecraft:polished_deepslate, minecraft:stone_bricks, minecraft:smooth_stone, minecraft:black_concrete, minecraft:white_concrete, minecraft:brown_concrete, minecraft:orange_concrete, minecraft:yellow_concrete, minecraft:red_concrete, minecraft:blue_concrete, minecraft:tinted_glass, minecraft:iron_block.
""";
}
