package com.vanguard.image2schem;

import com.google.gson.*;
import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.function.IntConsumer;

/** Generic scene-understanding brain. It makes no bunker/entrance/ramp assumptions. */
public final class GroqArchitectAI {
    private static final String ENDPOINT="https://api.groq.com/openai/v1/chat/completions";
    private static final String MODEL="qwen/qwen3.6-27b";
    private GroqArchitectAI(){}

    public static Plan analyze(BufferedImage source, IntConsumer progress) throws IOException {
        String key=GroqKeyStore.load();
        if(key==null||key.isBlank()) throw new IOException("Groq API key missing. Paste it in the K menu and save it.");
        if(!key.trim().startsWith("gsk_")) throw new IOException("Saved Groq API key looks invalid.");
        progress.accept(3);
        String dataUrl=encodeForVision(source); progress.accept(7);

        JsonObject body=new JsonObject();
        body.addProperty("model",MODEL);
        body.addProperty("temperature",0.20);
        body.addProperty("top_p",0.8);
        body.addProperty("max_completion_tokens",4200);
        body.addProperty("reasoning_effort","none");
        body.addProperty("reasoning_format","hidden");
        JsonObject rf=new JsonObject(); rf.addProperty("type","json_object"); body.add("response_format",rf);

        JsonArray content=new JsonArray();
        JsonObject t=new JsonObject(); t.addProperty("type","text"); t.addProperty("text",PROMPT); content.add(t);
        JsonObject im=new JsonObject(); im.addProperty("type","image_url"); JsonObject iu=new JsonObject(); iu.addProperty("url",dataUrl); im.add("image_url",iu); content.add(im);
        JsonObject user=new JsonObject(); user.addProperty("role","user"); user.add("content",content);
        JsonArray messages=new JsonArray(); messages.add(user); body.add("messages",messages);

        progress.accept(10);
        HttpRequest req=HttpRequest.newBuilder(URI.create(ENDPOINT)).timeout(Duration.ofSeconds(55))
                .header("Authorization","Bearer "+key.trim()).header("Content-Type","application/json")
                .header("User-Agent","Image2Schem/2.0").POST(HttpRequest.BodyPublishers.ofString(body.toString(),StandardCharsets.UTF_8)).build();
        HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).followRedirects(HttpClient.Redirect.NORMAL).build();
        HttpResponse<String> resp;
        try { resp=client.send(req,HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException("Groq request interrupted.",e);}
        catch(Exception e){throw new IOException("Could not reach Groq: "+safe(e),e);} progress.accept(21);
        if(resp.statusCode()<200||resp.statusCode()>=300) throw new IOException("Groq API error "+resp.statusCode()+": "+safeGroqError(resp.body()));
        try{
            JsonObject root=JsonParser.parseString(resp.body()).getAsJsonObject();
            JsonArray choices=root.getAsJsonArray("choices");
            if(choices==null||choices.isEmpty()) throw new IOException("Groq returned no choices.");
            JsonObject msg=choices.get(0).getAsJsonObject().getAsJsonObject("message");
            if(msg==null||!msg.has("content")) throw new IOException("Groq returned no scene plan.");
            Plan plan=parsePlan(JsonParser.parseString(msg.get("content").getAsString()).getAsJsonObject());
            if(plan.objects().isEmpty()) throw new IOException("Groq found no buildable objects in the image.");
            progress.accept(24); return plan;
        }catch(IOException e){throw e;}catch(Exception e){throw new IOException("Groq returned invalid scene JSON: "+safe(e),e);}
    }

    private static String encodeForVision(BufferedImage source)throws IOException{
        int maxSide=768,sw=source.getWidth(),sh=source.getHeight(); float scale=Math.min(1f,maxSide/(float)Math.max(sw,sh));
        int w=Math.max(1,Math.round(sw*scale)),h=Math.max(1,Math.round(sh*scale));
        BufferedImage rgb=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB); Graphics2D g=rgb.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR); g.drawImage(source,0,0,w,h,null); g.dispose();
        ByteArrayOutputStream out=new ByteArrayOutputStream();
        if(!ImageIO.write(rgb,"jpg",out)) throw new IOException("Could not encode image for Groq vision.");
        return "data:image/jpeg;base64,"+Base64.getEncoder().encodeToString(out.toByteArray());
    }

    private static Plan parsePlan(JsonObject o){
        String scene=str(o,"scene_type","scene"); float confidence=clamp01(f(o,"confidence",.5f));
        JsonObject dims=obj(o,"proportions"); Proportions p=new Proportions(clamp(f(dims,"width",1f),.2f,3f),clamp(f(dims,"height",.65f),.15f,2f),clamp(f(dims,"depth",.65f),.1f,2f));
        List<Primitive> objects=new ArrayList<>();
        for(JsonElement e:array(o,"objects")){
            if(!e.isJsonObject())continue; JsonObject q=e.getAsJsonObject();
            if(!hasObject(q,"image_box")||!hasObject(q,"world_box"))continue;
            String type=str(q,"type","object").toLowerCase(Locale.ROOT); if(!ALLOWED_TYPES.contains(type))type="object";
            Rect image=rect(q.getAsJsonObject("image_box"),new Rect(0,0,1,1));
            Box world=box(q.getAsJsonObject("world_box"),new Box(0,0,.4f,1,1,.6f));
            if(image.x1()-image.x0()<.005f||image.y1()-image.y0()<.005f)continue;
            float wx=world.x1()-world.x0(),wy=world.y1()-world.y0(),wz=world.z1()-world.z0();
            if(Math.max(wx,Math.max(wy,wz))<.005f)continue;
            float conf=clamp01(f(q,"confidence",.5f)); if(conf<.18f)continue;
            String material=str(q,"minecraft_block",""); if(!ALLOWED_BLOCKS.contains(material))material="";
            String axis=str(q,"axis","z+").toLowerCase(Locale.ROOT);
            if(!ALLOWED_AXES.contains(axis))axis="z+";
            int repeats=Math.max(1,Math.min(32,i(q,"repeats",1)));
            objects.add(new Primitive(type,image,world,material,axis,bool(q,"hollow",false),repeats,conf));
            if(objects.size()>=64)break;
        }
        return new Plan(scene,confidence,p,List.copyOf(objects));
    }

    private static final Set<String> ALLOWED_TYPES=Set.of("wall","floor","roof","slab","column","beam","platform","window","door","opening","stairs","ramp","railing","arch","tower","terrain","detail","object");
    private static final Set<String> ALLOWED_AXES=Set.of("x","y","z","x+","x-","z+","z-");
    private static final Set<String> ALLOWED_BLOCKS=Set.of(
            "minecraft:stone_bricks","minecraft:stone","minecraft:smooth_stone","minecraft:andesite","minecraft:polished_andesite",
            "minecraft:gray_concrete","minecraft:light_gray_concrete","minecraft:black_concrete","minecraft:white_concrete","minecraft:brown_concrete",
            "minecraft:red_concrete","minecraft:orange_concrete","minecraft:yellow_concrete","minecraft:green_concrete","minecraft:blue_concrete",
            "minecraft:deepslate_tiles","minecraft:polished_deepslate","minecraft:polished_blackstone_bricks","minecraft:bricks","minecraft:quartz_block",
            "minecraft:oak_planks","minecraft:spruce_planks","minecraft:dark_oak_planks","minecraft:tinted_glass","minecraft:glass","minecraft:iron_block","minecraft:sea_lantern");

    private static Rect rect(JsonObject o,Rect d){return o==null?d:new Rect(f(o,"x0",d.x0),f(o,"y0",d.y0),f(o,"x1",d.x1),f(o,"y1",d.y1)).clamped();}
    private static Box box(JsonObject o,Box d){return o==null?d:new Box(f(o,"x0",d.x0),f(o,"y0",d.y0),f(o,"z0",d.z0),f(o,"x1",d.x1),f(o,"y1",d.y1),f(o,"z1",d.z1)).clamped();}
    private static boolean hasObject(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonObject();}
    private static JsonObject obj(JsonObject o,String k){return hasObject(o,k)?o.getAsJsonObject(k):new JsonObject();}
    private static JsonArray array(JsonObject o,String k){return o!=null&&o.has(k)&&o.get(k).isJsonArray()?o.getAsJsonArray(k):new JsonArray();}
    private static float f(JsonObject o,String k,float d){try{return o!=null&&o.has(k)?o.get(k).getAsFloat():d;}catch(Exception e){return d;}}
    private static int i(JsonObject o,String k,int d){try{return o!=null&&o.has(k)?o.get(k).getAsInt():d;}catch(Exception e){return d;}}
    private static boolean bool(JsonObject o,String k,boolean d){try{return o!=null&&o.has(k)?o.get(k).getAsBoolean():d;}catch(Exception e){return d;}}
    private static String str(JsonObject o,String k,String d){try{return o!=null&&o.has(k)?o.get(k).getAsString():d;}catch(Exception e){return d;}}
    private static float clamp01(float v){return Math.max(0,Math.min(1,v));} private static float clamp(float v,float a,float b){return Math.max(a,Math.min(b,v));}
    private static String safe(Throwable e){return e.getMessage()==null?e.getClass().getSimpleName():e.getMessage();}
    private static String safeGroqError(String body){try{JsonObject o=JsonParser.parseString(body).getAsJsonObject();if(o.has("error")){JsonObject e=o.getAsJsonObject("error");if(e!=null&&e.has("message"))return e.get("message").getAsString();}}catch(Exception ignored){}if(body==null||body.isBlank())return "empty response";String s=body.replaceAll("\\s+"," ").trim();return s.length()>220?s.substring(0,220)+"...":s;}

    public record Plan(String sceneType,float confidence,Proportions proportions,List<Primitive> objects){}
    public record Proportions(float width,float height,float depth){}
    public record Rect(float x0,float y0,float x1,float y1){Rect clamped(){return new Rect(clamp01(Math.min(x0,x1)),clamp01(Math.min(y0,y1)),clamp01(Math.max(x0,x1)),clamp01(Math.max(y0,y1)));}}
    public record Box(float x0,float y0,float z0,float x1,float y1,float z1){Box clamped(){return new Box(clamp01(Math.min(x0,x1)),clamp01(Math.min(y0,y1)),clamp01(Math.min(z0,z1)),clamp01(Math.max(x0,x1)),clamp01(Math.max(y0,y1)),clamp01(Math.max(z0,z1)));}}
    public record Primitive(String type,Rect imageBox,Box worldBox,String minecraftBlock,String axis,boolean hollow,int repeats,float confidence){}

    private static final String PROMPT="""
You are the generic scene-planning brain for an image-to-Minecraft-3D system. The image may show ANY architecture or buildable scene: house, castle, skyscraper, bridge, bunker, room, tunnel, temple, tower, spaceship, street, ruins, terrain-integrated structure, etc. Do NOT assume there is an entrance, ramp, corridor, facade, symmetry, or any specific style.

Return ONLY one valid JSON object. Your job is to decompose the visible scene into large independent 3D primitives. Do not trace pixels and do not turn shadows/text/logos into geometry.

Coordinate systems:
- image_box: x/y normalized 0..1 from image top-left.
- world_box: x=left/right, y=bottom/top, z=near/far from camera, all normalized 0..1 inside the final build volume.
- Every object MUST include both image_box and world_box. If you cannot place an object confidently, omit it instead of guessing a huge default box.
- Estimate plausible hidden depth conservatively. If depth is uncertain, keep z thickness small rather than inventing a huge volume.

JSON:
{
 "scene_type":"what the image actually depicts",
 "confidence":0.0,
 "proportions":{"width":1.0,"height":0.7,"depth":0.7},
 "objects":[
   {"type":"wall","image_box":{"x0":0.1,"y0":0.2,"x1":0.4,"y1":0.8},"world_box":{"x0":0.1,"y0":0.0,"z0":0.35,"x1":0.4,"y1":0.7,"z1":0.39},"minecraft_block":"minecraft:stone_bricks","axis":"z+","hollow":false,"repeats":1,"confidence":0.9}
 ]
}

Use 8-40 objects when the scene supports it. Allowed types: wall, floor, roof, slab, column, beam, platform, window, door, opening, stairs, ramp, railing, arch, tower, terrain, detail, object.
- opening means EMPTY passable/open space that must be carved from surrounding geometry.
- door means a VISIBLE material door/panel. Do not use door for an empty doorway.
- Use stairs/ramp ONLY when actually visible. Never create a default entrance, corridor, ramp, floor, or symmetry.
For floors/roofs/platforms, world_box should have small y thickness and meaningful z depth. For walls/windows/openings, use small thickness on the appropriate axis. For columns/towers use real vertical height.
For stairs/ramps use axis x+, x-, z+, or z-. The sign tells which end is HIGH: x+ rises toward increasing x, x- toward decreasing x, z+ toward increasing z, z- toward decreasing z. world_box spans the full run and rise.
For repeated columns/railings, axis x or z is the direction along which repeats are distributed. Use repeats only for genuinely repeated elements.
Allowed minecraft_block values: minecraft:stone_bricks, minecraft:stone, minecraft:smooth_stone, minecraft:andesite, minecraft:polished_andesite, minecraft:gray_concrete, minecraft:light_gray_concrete, minecraft:black_concrete, minecraft:white_concrete, minecraft:brown_concrete, minecraft:red_concrete, minecraft:orange_concrete, minecraft:yellow_concrete, minecraft:green_concrete, minecraft:blue_concrete, minecraft:deepslate_tiles, minecraft:polished_deepslate, minecraft:polished_blackstone_bricks, minecraft:bricks, minecraft:quartz_block, minecraft:oak_planks, minecraft:spruce_planks, minecraft:dark_oak_planks, minecraft:tinted_glass, minecraft:glass, minecraft:iron_block, minecraft:sea_lantern.
Prioritize correct large geometry and proportions over decorative detail.
""";
}
