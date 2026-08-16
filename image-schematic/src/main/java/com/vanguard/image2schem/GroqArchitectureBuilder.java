package com.vanguard.image2schem;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;
import java.util.function.IntConsumer;

/** Generic scene-to-Minecraft primitive engine. No scene-specific geometry is invented. */
public final class GroqArchitectureBuilder {
    private GroqArchitectureBuilder(){}

    public static ImageConverter.Result build(BufferedImage source,float[][] sourceDepth,GroqArchitectAI.Plan plan,
                                               int requestedWidth,int requestedDepth,IntConsumer progress){
        float pw=Math.max(.2f,plan.proportions().width()),ph=Math.max(.15f,plan.proportions().height()),pd=Math.max(.1f,plan.proportions().depth());
        int w=clamp(requestedWidth,64,176);
        int h=clamp(Math.round(w*(ph/pw)),28,140);
        int d=clamp(Math.max(requestedDepth,Math.round(w*(pd/pw))),24,120);
        progress.accept(60);

        BufferedImage img=scale(source,w,h);
        float[][] depth=normalizeDepth(resizeDepth(sourceDepth,w,h));
        Map<String,Integer> palette=createPalette();
        int[] blocks=new int[w*h*d];
        List<Placed> carvers=new ArrayList<>();

        List<GroqArchitectAI.Primitive> objects=new ArrayList<>(plan.objects());
        objects.sort(Comparator.comparingDouble(GroqArchitectAI.Primitive::confidence).reversed());
        int total=Math.max(1,objects.size()),done=0;
        for(GroqArchitectAI.Primitive p:objects){
            if(p.confidence()<.20f)continue;
            Placed b=placeBox(p,depth,w,h,d);
            int mat=palette.getOrDefault(p.minecraftBlock(),palette.get("minecraft:stone_bricks"));
            if((p.minecraftBlock()==null||p.minecraftBlock().isBlank())&&p.imageBox()!=null) mat=sampleMaterial(img,p.imageBox(),palette);
            String type=p.type();
            switch(type){
                case "opening","door" -> carvers.add(b);
                case "window" -> fillBox(blocks,w,h,d,b.x0,b.y0,b.z0,b.x1,b.y1,b.z1,palette.get("minecraft:tinted_glass"));
                case "floor" -> buildFloor(blocks,w,h,d,b,mat);
                case "roof","slab","platform" -> buildSlab(blocks,w,h,d,b,mat,p.hollow());
                case "column" -> buildRepeatedColumns(blocks,w,h,d,b,mat,p.repeats(),p.hollow());
                case "beam","wall","detail","object","terrain" -> buildSolidOrShell(blocks,w,h,d,b,mat,p.hollow());
                case "stairs" -> buildSlope(blocks,w,h,d,b,mat,p.axis(),true);
                case "ramp" -> buildSlope(blocks,w,h,d,b,mat,p.axis(),false);
                case "railing" -> buildRailing(blocks,w,h,d,b,mat,p.axis(),p.repeats());
                case "arch" -> buildArch(blocks,w,h,d,b,mat);
                case "tower" -> buildTower(blocks,w,h,d,b,mat,p.hollow());
                default -> buildSolidOrShell(blocks,w,h,d,b,mat,p.hollow());
            }
            done++;
            progress.accept(61+Math.round(30f*done/total));
        }

        // Carve only openings explicitly identified by the vision planner.
        for(Placed c:carvers) carveBox(blocks,w,h,d,c.x0,c.y0,c.z0,c.x1,c.y1,c.z1);
        progress.accept(94);
        removeIsolatedSingles(blocks,w,h,d);
        progress.accept(99);
        return new ImageConverter.Result(w,h,d,blocks,palette);
    }

    private static Placed placeBox(GroqArchitectAI.Primitive p,float[][]depth,int w,int h,int d){
        GroqArchitectAI.Box q=p.worldBox();
        int x0=clamp(Math.round(q.x0()*(w-1)),0,w-1),x1=clamp(Math.round(q.x1()*(w-1)),x0,w-1);
        int y0=clamp(Math.round(q.y0()*(h-1)),0,h-1),y1=clamp(Math.round(q.y1()*(h-1)),y0,h-1);
        int z0=clamp(Math.round(q.z0()*(d-1)),0,d-1),z1=clamp(Math.round(q.z1()*(d-1)),z0,d-1);

        // Depth AI is supporting evidence only: gently shift the object's Z, never reshape it pixel-by-pixel.
        GroqArchitectAI.Rect r=p.imageBox();
        if(r!=null&&p.confidence()<.92f){
            float md=medianDepth(depth,r); int neural=clamp(Math.round((1f-md)*(d-1)),0,d-1);
            int center=(z0+z1)/2,span=Math.max(1,z1-z0); int blended=Math.round(center*.78f+neural*.22f);
            z0=clamp(blended-span/2,0,d-1); z1=clamp(z0+span,z0,d-1);
        }

        String type=p.type();
        if(Set.of("wall","window","door","opening","beam","column","railing","arch").contains(type)&&z1-z0<1) z1=Math.min(d-1,z0+1);
        if(Set.of("floor","roof","slab","platform").contains(type)&&y1-y0<1) y1=Math.min(h-1,y0+1);
        return new Placed(x0,y0,z0,x1,y1,z1);
    }

    private static void buildFloor(int[]a,int w,int h,int d,Placed b,int mat){
        int y=b.y0; fillBox(a,w,h,d,b.x0,y,b.z0,b.x1,Math.min(h-1,y+1),b.z1,mat);
    }
    private static void buildSlab(int[]a,int w,int h,int d,Placed b,int mat,boolean hollow){
        int y0=b.y0,y1=Math.min(b.y1,y0+Math.max(1,Math.min(3,b.y1-b.y0)));
        if(hollow) shellBox(a,w,h,d,b.x0,y0,b.z0,b.x1,y1,b.z1,mat); else fillBox(a,w,h,d,b.x0,y0,b.z0,b.x1,y1,b.z1,mat);
    }
    private static void buildSolidOrShell(int[]a,int w,int h,int d,Placed b,int mat,boolean hollow){
        if(hollow)shellBox(a,w,h,d,b.x0,b.y0,b.z0,b.x1,b.y1,b.z1,mat);else fillBox(a,w,h,d,b.x0,b.y0,b.z0,b.x1,b.y1,b.z1,mat);
    }
    private static void buildRepeatedColumns(int[]a,int w,int h,int d,Placed b,int mat,int repeats,boolean hollow){
        repeats=Math.max(1,repeats);
        if(repeats==1){buildSolidOrShell(a,w,h,d,b,mat,hollow);return;}
        int span=Math.max(1,b.x1-b.x0),step=Math.max(1,span/Math.max(1,repeats-1)),th=Math.max(1,span/Math.max(8,repeats*4));
        for(int i=0;i<repeats;i++){int cx=clamp(b.x0+i*step,b.x0,b.x1);Placed c=new Placed(Math.max(b.x0,cx-th/2),b.y0,b.z0,Math.min(b.x1,cx+th/2),b.y1,b.z1);buildSolidOrShell(a,w,h,d,c,mat,hollow);}
    }
    private static void buildSlope(int[]a,int w,int h,int d,Placed b,int mat,String axis,boolean stairs){
        if("x".equals(axis)){
            int run=Math.max(1,b.x1-b.x0); for(int x=b.x0;x<=b.x1;x++){float t=(x-b.x0)/(float)run;int y=Math.round(b.y0+(b.y1-b.y0)*t);int thickness=stairs?Math.max(1,Math.round(run/(float)Math.max(1,b.y1-b.y0+1))):1;for(int z=b.z0;z<=b.z1;z++)for(int xx=x;xx<=Math.min(b.x1,x+thickness-1);xx++)set(a,w,h,d,xx,y,z,mat);}
        }else{
            int run=Math.max(1,b.z1-b.z0); for(int z=b.z0;z<=b.z1;z++){float t=(z-b.z0)/(float)run;int y=Math.round(b.y0+(b.y1-b.y0)*t);int thickness=stairs?Math.max(1,Math.round(run/(float)Math.max(1,b.y1-b.y0+1))):1;for(int x=b.x0;x<=b.x1;x++)for(int zz=z;zz<=Math.min(b.z1,z+thickness-1);zz++)set(a,w,h,d,x,y,zz,mat);}
        }
    }
    private static void buildRailing(int[]a,int w,int h,int d,Placed b,int mat,String axis,int repeats){
        int posts=Math.max(2,repeats>1?repeats:Math.max(2,("x".equals(axis)?b.x1-b.x0:b.z1-b.z0)/5));
        if("x".equals(axis)){
            for(int i=0;i<posts;i++){int x=b.x0+Math.round((b.x1-b.x0)*i/(float)(posts-1));fillBox(a,w,h,d,x,b.y0,b.z0,x,b.y1,b.z1,mat);}fillBox(a,w,h,d,b.x0,b.y1,b.z0,b.x1,b.y1,b.z1,mat);
        }else{
            for(int i=0;i<posts;i++){int z=b.z0+Math.round((b.z1-b.z0)*i/(float)(posts-1));fillBox(a,w,h,d,b.x0,b.y0,z,b.x1,b.y1,z,mat);}fillBox(a,w,h,d,b.x0,b.y1,b.z0,b.x1,b.y1,b.z1,mat);
        }
    }
    private static void buildArch(int[]a,int w,int h,int d,Placed b,int mat){
        int width=Math.max(3,b.x1-b.x0+1),height=Math.max(3,b.y1-b.y0+1),th=Math.max(1,Math.min(3,width/8));
        fillBox(a,w,h,d,b.x0,b.y0,b.z0,Math.min(b.x1,b.x0+th),b.y1,b.z1,mat);
        fillBox(a,w,h,d,Math.max(b.x0,b.x1-th),b.y0,b.z0,b.x1,b.y1,b.z1,mat);
        int cx=(b.x0+b.x1)/2,rx=Math.max(2,width/2),ry=Math.max(2,Math.min(height/2,rx));
        for(int x=b.x0;x<=b.x1;x++){float nx=(x-cx)/(float)rx;if(Math.abs(nx)>1)continue;int y=b.y1-Math.round((float)(ry*Math.sqrt(Math.max(0,1-nx*nx))));for(int yy=y;yy<=Math.min(b.y1,y+th);yy++)for(int z=b.z0;z<=b.z1;z++)set(a,w,h,d,x,yy,z,mat);}
    }
    private static void buildTower(int[]a,int w,int h,int d,Placed b,int mat,boolean hollow){
        int cx=(b.x0+b.x1)/2,cz=(b.z0+b.z1)/2,rx=Math.max(1,(b.x1-b.x0)/2),rz=Math.max(1,(b.z1-b.z0)/2);
        for(int y=b.y0;y<=b.y1;y++)for(int x=b.x0;x<=b.x1;x++)for(int z=b.z0;z<=b.z1;z++){
            float dx=(x-cx)/(float)rx,dz=(z-cz)/(float)rz,v=dx*dx+dz*dz; if(v<=1f&&(!hollow||v>.68f||y==b.y0||y==b.y1))set(a,w,h,d,x,y,z,mat);
        }
    }

    private static Map<String,Integer> createPalette(){
        LinkedHashMap<String,Integer>p=new LinkedHashMap<>();p.put("minecraft:air",0);
        String[] blocks={"minecraft:stone_bricks","minecraft:stone","minecraft:smooth_stone","minecraft:andesite","minecraft:polished_andesite","minecraft:gray_concrete","minecraft:light_gray_concrete","minecraft:black_concrete","minecraft:white_concrete","minecraft:brown_concrete","minecraft:red_concrete","minecraft:orange_concrete","minecraft:yellow_concrete","minecraft:green_concrete","minecraft:blue_concrete","minecraft:deepslate_tiles","minecraft:polished_deepslate","minecraft:polished_blackstone_bricks","minecraft:bricks","minecraft:quartz_block","minecraft:oak_planks","minecraft:spruce_planks","minecraft:dark_oak_planks","minecraft:tinted_glass","minecraft:glass","minecraft:iron_block","minecraft:sea_lantern"};
        for(String b:blocks)p.put(b,p.size());return p;
    }
    private static int sampleMaterial(BufferedImage img,GroqArchitectAI.Rect r,Map<String,Integer>p){
        int x0=clamp(Math.round(r.x0()*(img.getWidth()-1)),0,img.getWidth()-1),x1=clamp(Math.round(r.x1()*(img.getWidth()-1)),x0,img.getWidth()-1);
        int y0=clamp(Math.round(r.y0()*(img.getHeight()-1)),0,img.getHeight()-1),y1=clamp(Math.round(r.y1()*(img.getHeight()-1)),y0,img.getHeight()-1);
        long rr=0,gg=0,bb=0,n=0;for(int y=y0;y<=y1;y+=Math.max(1,(y1-y0)/12+1))for(int x=x0;x<=x1;x+=Math.max(1,(x1-x0)/12+1)){int c=img.getRGB(x,y);rr+=(c>>>16)&255;gg+=(c>>>8)&255;bb+=c&255;n++;}
        int R=(int)(rr/Math.max(1,n)),G=(int)(gg/Math.max(1,n)),B=(int)(bb/Math.max(1,n));
        String b;if(Math.max(R,Math.max(G,B))<55)b="minecraft:black_concrete";else if(Math.abs(R-G)<18&&Math.abs(G-B)<18)b=R>190?"minecraft:white_concrete":R>125?"minecraft:light_gray_concrete":"minecraft:gray_concrete";else if(R>G*1.35&&R>B*1.35)b="minecraft:red_concrete";else if(R>170&&G>110&&B<90)b="minecraft:orange_concrete";else if(R>170&&G>155&&B<100)b="minecraft:yellow_concrete";else if(B>R*1.25&&B>G*1.15)b="minecraft:blue_concrete";else if(G>R*1.2&&G>B*1.15)b="minecraft:green_concrete";else if(R>110&&G>75&&B<70)b="minecraft:brown_concrete";else b="minecraft:stone_bricks";return p.getOrDefault(b,1);
    }

    private static float medianDepth(float[][]d,GroqArchitectAI.Rect r){int h=d.length,w=d[0].length;int x0=clamp(Math.round(r.x0()*(w-1)),0,w-1),x1=clamp(Math.round(r.x1()*(w-1)),x0,w-1),y0=clamp(Math.round(r.y0()*(h-1)),0,h-1),y1=clamp(Math.round(r.y1()*(h-1)),y0,h-1);float[]v=new float[Math.max(1,(x1-x0+1)*(y1-y0+1))];int k=0;for(int y=y0;y<=y1;y+=2)for(int x=x0;x<=x1;x+=2)v[k++]=d[y][x];if(k==0)return .5f;Arrays.sort(v,0,k);return v[k/2];}
    private static BufferedImage scale(BufferedImage s,int w,int h){BufferedImage o=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=o.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BILINEAR);g.drawImage(s,0,0,w,h,null);g.dispose();return o;}
    private static float[][] resizeDepth(float[][]s,int w,int h){int sh=s.length,sw=s[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){int sy=Math.min(sh-1,Math.round(y*(sh-1f)/Math.max(1,h-1)));for(int x=0;x<w;x++){int sx=Math.min(sw-1,Math.round(x*(sw-1f)/Math.max(1,w-1)));o[y][x]=s[sy][sx];}}return o;}
    private static float[][] normalizeDepth(float[][]d){float lo=Float.POSITIVE_INFINITY,hi=Float.NEGATIVE_INFINITY;for(float[]r:d)for(float v:r)if(Float.isFinite(v)){lo=Math.min(lo,v);hi=Math.max(hi,v);}float range=Math.max(1e-5f,hi-lo);for(int y=0;y<d.length;y++)for(int x=0;x<d[0].length;x++)d[y][x]=(d[y][x]-lo)/range;return d;}

    private static void fillBox(int[]a,int w,int h,int d,int x0,int y0,int z0,int x1,int y1,int z1,int mat){for(int y=Math.max(0,y0);y<=Math.min(h-1,y1);y++)for(int z=Math.max(0,z0);z<=Math.min(d-1,z1);z++)for(int x=Math.max(0,x0);x<=Math.min(w-1,x1);x++)a[index(w,d,x,y,z)]=mat;}
    private static void shellBox(int[]a,int w,int h,int d,int x0,int y0,int z0,int x1,int y1,int z1,int mat){for(int y=y0;y<=y1;y++)for(int z=z0;z<=z1;z++)for(int x=x0;x<=x1;x++)if(x==x0||x==x1||y==y0||y==y1||z==z0||z==z1)set(a,w,h,d,x,y,z,mat);}
    private static void carveBox(int[]a,int w,int h,int d,int x0,int y0,int z0,int x1,int y1,int z1){fillBox(a,w,h,d,x0,y0,z0,x1,y1,z1,0);}
    private static void removeIsolatedSingles(int[]a,int w,int h,int d){int[]src=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=index(w,d,x,y,z);if(src[i]==0)continue;int n=0;if(src[index(w,d,x-1,y,z)]!=0)n++;if(src[index(w,d,x+1,y,z)]!=0)n++;if(src[index(w,d,x,y-1,z)]!=0)n++;if(src[index(w,d,x,y+1,z)]!=0)n++;if(src[index(w,d,x,y,z-1)]!=0)n++;if(src[index(w,d,x,y,z+1)]!=0)n++;if(n==0)a[i]=0;}}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;} private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[index(w,d,x,y,z)]=v;} private static int clamp(int v,int a,int b){return Math.max(a,Math.min(b,v));}
    private record Placed(int x0,int y0,int z0,int x1,int y1,int z1){}
}
