package com.vanguard.image2schem;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Clean primitive-based builder driven by Groq's scene graph.
 * The important rule is that image rows are NOT directly turned into floor height.
 * Floors/ramps are built in the XZ plane, while facade objects are explicit cuboids/planes.
 */
public final class GroqArchitectureBuilder {
    private GroqArchitectureBuilder() {}

    public static ImageConverter.Result build(BufferedImage source, float[][] sourceDepth,
                                               GroqArchitectAI.Plan plan,
                                               int requestedWidth, int requestedDepth,
                                               IntConsumer progress) {
        int w=Math.max(72,Math.min(176,requestedWidth));
        int h=Math.max(36,Math.round(source.getHeight()*(w/(float)source.getWidth())));
        if(h>124){float f=124f/h;h=124;w=Math.max(72,Math.round(w*f));}
        int d=Math.max(32,Math.min(80,requestedDepth));
        progress.accept(60);

        BufferedImage img=scale(source,w,h);
        float[][] depth=normalizeDepth(resizeDepth(sourceDepth,w,h));

        Map<String,Integer> palette=new LinkedHashMap<>();
        palette.put("minecraft:air",0);
        int gray=id(palette,"minecraft:gray_concrete");
        int lightGray=id(palette,"minecraft:light_gray_concrete");
        int dark=id(palette,"minecraft:deepslate_tiles");
        int trim=id(palette,"minecraft:polished_deepslate");
        int stone=id(palette,"minecraft:stone_bricks");
        int floor=id(palette,"minecraft:smooth_stone");
        int black=id(palette,"minecraft:black_concrete");
        int white=id(palette,"minecraft:white_concrete");
        int brown=id(palette,"minecraft:brown_concrete");
        int orange=id(palette,"minecraft:orange_concrete");
        int yellow=id(palette,"minecraft:yellow_concrete");
        int red=id(palette,"minecraft:red_concrete");
        int blue=id(palette,"minecraft:blue_concrete");
        int glass=id(palette,"minecraft:tinted_glass");
        int iron=id(palette,"minecraft:iron_block");
        int light=id(palette,"minecraft:sea_lantern");
        int[] blocks=new int[w*h*d];

        GroqArchitectAI.Rect er=plan.entrance();
        int x0=clamp(Math.round(er.x0()*w),3,w-8);
        int x1=clamp(Math.round(er.x1()*w),x0+7,w-4);
        int sy0=clamp(Math.round(er.y0()*h),2,h-12);
        int sy1=clamp(Math.round(er.y1()*h),sy0+8,h-3);
        int yBottom=h-1-sy1;
        int yTop=h-1-sy0;
        int portalZ=choosePortalZ(depth,er,d);
        progress.accept(64);

        // Broad facade is made from a few clean planes around the portal, never a pixel/depth sculpture.
        int facadeMat=materialAt(plan,(er.x0()+er.x1())*.5f,Math.max(.05f,er.y0()-.08f),palette,gray);
        int sideMat=materialAt(plan,Math.max(.05f,er.x0()-.08f),(er.y0()+er.y1())*.5f,palette,dark);
        buildFacade(blocks,w,h,d,x0,x1,yBottom,yTop,portalZ,facadeMat,sideMat,trim);
        progress.accept(69);

        // Major AI-detected architecture becomes explicit primitives.
        for(GroqArchitectAI.Structure s:plan.structures()){
            if(s.confidence()<.35f)continue;
            addStructure(blocks,w,h,d,img,depth,plan,s,portalZ,palette,gray,trim,stone,glass,iron);
        }
        progress.accept(76);

        // If the scene is strongly symmetrical, ensure the two portal supports are coherent.
        if(plan.symmetry()>.58f) reinforcePortalSupports(blocks,w,h,d,x0,x1,yBottom,yTop,portalZ,trim,stone);

        // Correct 3D ramp: image perspective controls width, but WORLD Y changes only with actual ramp rise.
        buildRealRamp(blocks,w,h,d,plan.floor(),x0,x1,yBottom,portalZ,floor,trim,yellow);
        progress.accept(82);

        // Interior is a separate room/corridor extending behind the entrance.
        int interiorEnd=buildInterior(blocks,w,h,d,plan.interior(),x0,x1,yBottom,yTop,portalZ,
                gray,dark,floor,trim,light);
        progress.accept(88);

        // Add coherent detail bands rather than noisy individual blocks.
        addPortalDetail(blocks,w,h,d,x0,x1,yBottom,yTop,portalZ,interiorEnd,trim,iron,light);
        progress.accept(92);

        // Material regions can deliberately override broad structural surfaces.
        applyMaterialRegions(blocks,w,h,d,plan,palette,portalZ,yBottom,yTop);
        progress.accept(95);

        removeTinyComponents(blocks,w,h,d,10);
        bridgeGaps(blocks,w,h,d);
        carveDoorway(blocks,w,h,d,x0,x1,yBottom,yTop,portalZ);
        progress.accept(99);
        return new ImageConverter.Result(w,h,d,blocks,palette);
    }

    private static void buildFacade(int[]a,int w,int h,int d,int x0,int x1,int yb,int yt,int z,int facade,int side,int trim){
        int outerL=Math.max(1,x0-Math.max(7,w/9));
        int outerR=Math.min(w-2,x1+Math.max(7,w/9));
        int top=Math.min(h-2,yt+Math.max(6,h/7));
        int bottom=Math.max(0,yb-2);
        // left and right structural fields
        fillBox(a,w,h,d,outerL,bottom,z,x0-1,top,z+2,side);
        fillBox(a,w,h,d,x1+1,bottom,z,outerR,top,z+2,side);
        // clean lintel field over the portal
        fillBox(a,w,h,d,x0,yt+1,z,x1,top,z+2,facade);
        // strong portal frame
        int fw=Math.max(2,Math.min(4,(x1-x0)/8));
        fillBox(a,w,h,d,x0-fw,yb,z-2,x0-1,yt+2,z+2,trim);
        fillBox(a,w,h,d,x1+1,yb,z-2,x1+fw,yt+2,z+2,trim);
        fillBox(a,w,h,d,x0-fw,yt+1,z-2,x1+fw,yt+fw,z+2,trim);
    }

    private static void addStructure(int[]a,int w,int h,int d,BufferedImage img,float[][]depth,
                                     GroqArchitectAI.Plan plan,GroqArchitectAI.Structure s,int portalZ,
                                     Map<String,Integer>palette,int gray,int trim,int stone,int glass,int iron){
        GroqArchitectAI.Rect r=s.box();
        int ax0=clamp(Math.round(r.x0()*w),0,w-1),ax1=clamp(Math.round(r.x1()*w),ax0,w-1);
        int sy0=clamp(Math.round(r.y0()*h),0,h-1),sy1=clamp(Math.round(r.y1()*h),sy0,h-1);
        int ay0=h-1-sy1,ay1=h-1-sy0;
        int z=structureZ(depth,r,d,portalZ,s.depthLayer());
        int mat=materialForStructure(plan,s,palette,sampleMaterial(img,r,palette,gray));
        String t=s.type();
        if(t.equals("column")){
            int thick=Math.max(3,Math.min(6,Math.max(1,ax1-ax0+1)/2));
            fillBox(a,w,h,d,ax0,ay0,z-thick/2,ax1,ay1,z+thick,mat);
            // architectural ribs
            for(int y=ay0+2;y<=ay1;y+=5)fillBox(a,w,h,d,ax0,y,z-1,ax1,Math.min(ay1,y+1),z+thick+1,trim);
        }else if(t.equals("beam")||t.equals("roof")||t.equals("trim")){
            int thick=t.equals("roof")?4:3;
            fillBox(a,w,h,d,ax0,ay0,z,ax1,ay1,z+thick,mat==gray?stone:mat);
        }else if(t.equals("window")){
            fillBox(a,w,h,d,ax0,ay0,z,ax1,ay1,z+1,glass);
            outlineRect(a,w,h,d,ax0,ay0,ax1,ay1,z,trim);
        }else if(t.equals("platform")||t.equals("railing")){
            int y=ay0;
            fillBox(a,w,h,d,ax0,y,z,ax1,Math.min(h-1,y+(t.equals("railing")?2:1)),Math.min(d-1,z+5),t.equals("railing")?iron:mat);
        }else if(t.equals("door")||t.equals("opening")){
            carveBox(a,w,h,d,ax0,ay0,Math.max(0,z-3),ax1,ay1,Math.min(d-1,z+6));
            outlineRect(a,w,h,d,ax0,ay0,ax1,ay1,z,trim);
        }else{
            fillBox(a,w,h,d,ax0,ay0,z,ax1,ay1,z+2,mat);
        }
        if(s.mirror()) mirrorBox(a,w,h,d,ax0,ay0,ax1,ay1,z,Math.min(d-1,z+4));
    }

    private static void buildRealRamp(int[]a,int w,int h,int d,GroqArchitectAI.Floor f,
                                      int x0,int x1,int entranceY,int portalZ,int floor,int trim,int accent){
        int frontZ=2;
        int run=Math.max(4,portalZ-frontZ);
        int rise=Math.max(1,Math.min(7,Math.round(run*.18f)));
        int frontY=Math.max(0,entranceY-rise);
        int cx=clamp(Math.round(f.centerX()*w),0,w-1);
        int topWidth=Math.max(x1-x0+1,Math.round(f.topWidth()*w));
        int bottomWidth=Math.max(topWidth+4,Math.round(f.bottomWidth()*w));
        topWidth=Math.min(w-4,topWidth);bottomWidth=Math.min(w-2,bottomWidth);
        for(int z=frontZ;z<=portalZ;z++){
            float t=(z-frontZ)/(float)Math.max(1,run);
            int y=Math.round(frontY+(entranceY-frontY)*t);
            int width=Math.round(bottomWidth+(topWidth-bottomWidth)*t);
            int left=clamp(cx-width/2,1,w-2),right=clamp(left+width-1,left,w-2);
            for(int x=left;x<=right;x++)set(a,w,h,d,x,y,z,floor);
            set(a,w,h,d,left,y,z,trim);set(a,w,h,d,right,y,z,trim);
            if((z-frontZ)%7==2){
                for(int x=Math.max(left+2,cx-1);x<=Math.min(right-2,cx+1);x++)set(a,w,h,d,x,y+1,z,accent);
            }
        }
    }

    private static int buildInterior(int[]a,int w,int h,int d,GroqArchitectAI.Interior in,
                                     int x0,int x1,int yb,int yt,int portalZ,
                                     int wall,int ceiling,int floor,int trim,int light){
        float scale=in.exists()?in.depthScale():.30f;
        int available=Math.max(8,d-portalZ-4);
        int len=Math.max(10,Math.min(available,Math.round(available*scale)));
        int end=Math.min(d-3,portalZ+len);
        int innerX0=x0+2,innerX1=x1-2,innerYb=yb,innerYt=Math.max(yb+5,yt-2);
        for(int z=portalZ;z<=end;z++){
            for(int x=innerX0;x<=innerX1;x++){
                set(a,w,h,d,x,innerYb,z,floor);
                set(a,w,h,d,x,innerYt,z,ceiling);
            }
            for(int y=innerYb;y<=innerYt;y++){
                set(a,w,h,d,innerX0,y,z,wall);set(a,w,h,d,innerX1,y,z,wall);
            }
            if((z-portalZ)%6==3){
                int cx=(innerX0+innerX1)/2;
                for(int x=Math.max(innerX0+2,cx-1);x<=Math.min(innerX1-2,cx+1);x++)set(a,w,h,d,x,innerYt-1,z,light);
            }
        }
        // back bulkhead with a smaller secondary doorway
        int cx=(innerX0+innerX1)/2;
        for(int x=innerX0;x<=innerX1;x++)for(int y=innerYb;y<=innerYt;y++){
            boolean door=Math.abs(x-cx)<=2 && y<=innerYb+5;
            if(!door)set(a,w,h,d,x,y,end,wall);
        }
        outlineRect(a,w,h,d,cx-3,innerYb,cx+3,innerYb+6,end,trim);
        return end;
    }

    private static void reinforcePortalSupports(int[]a,int w,int h,int d,int x0,int x1,int yb,int yt,int z,int trim,int stone){
        int pw=Math.max(3,Math.min(6,(x1-x0)/7));
        fillBox(a,w,h,d,x0-pw,yb,z-4,x0-1,yt+5,z+4,trim);
        fillBox(a,w,h,d,x1+1,yb,z-4,x1+pw,yt+5,z+4,trim);
        // outer shoulders
        fillBox(a,w,h,d,x0-pw-3,yb,z-2,x0-pw-1,yt+1,z+3,stone);
        fillBox(a,w,h,d,x1+pw+1,yb,z-2,x1+pw+3,yt+1,z+3,stone);
    }

    private static void addPortalDetail(int[]a,int w,int h,int d,int x0,int x1,int yb,int yt,int z,int end,int trim,int iron,int light){
        int cx=(x0+x1)/2;
        // overhead layered beams
        for(int layer=0;layer<3;layer++)fillBox(a,w,h,d,x0-4-layer,yt+2+layer,z-4-layer,x1+4+layer,yt+3+layer,z+2,layer==1?iron:trim);
        // paired wall lights
        for(int side:new int[]{x0-3,x1+3})for(int zz=z+3;zz<Math.min(end,z+16);zz+=7)set(a,w,h,d,side,Math.min(h-2,yb+5),zz,light);
        // center ceiling spine
        for(int zz=z+2;zz<end;zz++)if((zz-z)%3==0)set(a,w,h,d,cx,yt-1,zz,iron);
    }

    private static void applyMaterialRegions(int[]a,int w,int h,int d,GroqArchitectAI.Plan plan,Map<String,Integer>palette,int portalZ,int yb,int yt){
        if(plan.materials().isEmpty())return;
        // Only recolor existing facade/front structure; never create geometry from material regions.
        for(GroqArchitectAI.MaterialRegion mr:plan.materials()){
            Integer id=palette.get(mr.minecraftBlock());if(id==null||mr.confidence()<.45f)continue;
            GroqArchitectAI.Rect r=mr.box();
            int x0=clamp(Math.round(r.x0()*w),0,w-1),x1=clamp(Math.round(r.x1()*w),x0,w-1);
            int sy0=clamp(Math.round(r.y0()*h),0,h-1),sy1=clamp(Math.round(r.y1()*h),sy0,h-1);
            int wy0=h-1-sy1,wy1=h-1-sy0;
            for(int y=wy0;y<=wy1;y++)for(int x=x0;x<=x1;x++)for(int z=Math.max(0,portalZ-6);z<=Math.min(d-1,portalZ+6);z++){
                int idx=index(w,d,x,y,z);if(a[idx]!=0)a[idx]=id;
            }
        }
    }

    private static int materialForStructure(GroqArchitectAI.Plan plan,GroqArchitectAI.Structure s,Map<String,Integer>palette,int fallback){
        String m=s.material();
        if(m.contains("glass"))return palette.getOrDefault("minecraft:tinted_glass",fallback);
        if(m.contains("metal")||m.contains("steel")||m.contains("iron"))return palette.getOrDefault("minecraft:iron_block",fallback);
        if(m.contains("black"))return palette.getOrDefault("minecraft:black_concrete",fallback);
        if(m.contains("dark"))return palette.getOrDefault("minecraft:deepslate_tiles",fallback);
        if(m.contains("stone"))return palette.getOrDefault("minecraft:stone_bricks",fallback);
        float cx=(s.box().x0()+s.box().x1())*.5f,cy=(s.box().y0()+s.box().y1())*.5f;
        return materialAt(plan,cx,cy,palette,fallback);
    }

    private static int materialAt(GroqArchitectAI.Plan p,float x,float y,Map<String,Integer>palette,int fallback){
        GroqArchitectAI.MaterialRegion best=null;
        for(GroqArchitectAI.MaterialRegion m:p.materials()){
            GroqArchitectAI.Rect r=m.box();
            if(x>=r.x0()&&x<=r.x1()&&y>=r.y0()&&y<=r.y1()&&(best==null||m.confidence()>best.confidence()))best=m;
        }
        return best==null?fallback:palette.getOrDefault(best.minecraftBlock(),fallback);
    }

    private static int sampleMaterial(BufferedImage img,GroqArchitectAI.Rect r,Map<String,Integer>palette,int fallback){
        int w=img.getWidth(),h=img.getHeight();
        int x0=clamp(Math.round(r.x0()*w),0,w-1),x1=clamp(Math.round(r.x1()*w),x0,w-1);
        int y0=clamp(Math.round(r.y0()*h),0,h-1),y1=clamp(Math.round(r.y1()*h),y0,h-1);
        long rs=0,gs=0,bs=0,n=0;int sx=Math.max(1,(x1-x0+1)/8),sy=Math.max(1,(y1-y0+1)/8);
        for(int y=y0;y<=y1;y+=sy)for(int x=x0;x<=x1;x+=sx){int c=img.getRGB(x,y);rs+=(c>>>16)&255;gs+=(c>>>8)&255;bs+=c&255;n++;}
        if(n==0)return fallback;int rr=(int)(rs/n),gg=(int)(gs/n),bb=(int)(bs/n);int max=Math.max(rr,Math.max(gg,bb)),min=Math.min(rr,Math.min(gg,bb));
        float sat=(max-min)/255f,lum=(rr+gg+bb)/(3f*255f);
        if(lum<.12)return palette.getOrDefault("minecraft:black_concrete",fallback);
        if(lum<.25)return palette.getOrDefault("minecraft:deepslate_tiles",fallback);
        if(sat<.08&&lum>.78)return palette.getOrDefault("minecraft:white_concrete",fallback);
        if(sat<.12&&lum>.60)return palette.getOrDefault("minecraft:light_gray_concrete",fallback);
        if(rr>170&&gg<95&&bb<90)return palette.getOrDefault("minecraft:red_concrete",fallback);
        if(rr>180&&gg>120&&bb<90)return palette.getOrDefault("minecraft:orange_concrete",fallback);
        if(rr>170&&gg>155&&bb<110)return palette.getOrDefault("minecraft:yellow_concrete",fallback);
        if(bb>rr*1.2&&bb>gg*1.1)return palette.getOrDefault("minecraft:blue_concrete",fallback);
        return fallback;
    }

    private static int structureZ(float[][]depth,GroqArchitectAI.Rect r,int d,int portalZ,String layer){
        float med=median(depth,r);int raw=toZ(med,d);
        // Keep architecture coherent around the portal while retaining depth ordering from the neural model.
        int mixed=Math.round(portalZ*.65f+raw*.35f);
        if("front".equals(layer))mixed-=3;else if("back".equals(layer))mixed+=4;
        return clamp(mixed,2,d-6);
    }

    private static int choosePortalZ(float[][]depth,GroqArchitectAI.Rect r,int d){
        int raw=toZ(median(depth,r),d);
        int lo=Math.max(8,d/5),hi=Math.max(lo+2,d*2/5);
        return clamp(raw,lo,hi);
    }

    private static float median(float[][]dep,GroqArchitectAI.Rect r){
        int h=dep.length,w=dep[0].length,x0=clamp(Math.round(r.x0()*w),0,w-1),x1=clamp(Math.round(r.x1()*w),x0,w-1),y0=clamp(Math.round(r.y0()*h),0,h-1),y1=clamp(Math.round(r.y1()*h),y0,h-1);
        float[]v=new float[Math.max(1,(x1-x0+1)*(y1-y0+1))];int k=0;for(int y=y0;y<=y1;y++)for(int x=x0;x<=x1;x++)v[k++]=dep[y][x];Arrays.sort(v,0,k);return v[Math.max(0,k/2)];
    }

    private static BufferedImage scale(BufferedImage s,int w,int h){BufferedImage o=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=o.createGraphics();g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);g.drawImage(s,0,0,w,h,null);g.dispose();return o;}
    private static float[][] resizeDepth(float[][]src,int w,int h){int sh=src.length,sw=src[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){float yy=y*(sh-1f)/Math.max(1,h-1);int y0=(int)yy,y1=Math.min(sh-1,y0+1);float fy=yy-y0;for(int x=0;x<w;x++){float xx=x*(sw-1f)/Math.max(1,w-1);int x0=(int)xx,x1=Math.min(sw-1,x0+1);float fx=xx-x0;o[y][x]=(src[y0][x0]*(1-fx)+src[y0][x1]*fx)*(1-fy)+(src[y1][x0]*(1-fx)+src[y1][x1]*fx)*fy;}}return o;}
    private static float[][] normalizeDepth(float[][]d){int h=d.length,w=d[0].length;float lower=avg(d,w/3,h*2/3,w*2/3,h-1),upper=avg(d,w/3,0,w*2/3,h/3);if(lower>upper)for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=1-d[y][x];float[]v=new float[h*w];int k=0;for(float[]row:d)for(float q:row)v[k++]=q;Arrays.sort(v);float lo=v[(int)(v.length*.03)],hi=v[Math.min(v.length-1,(int)(v.length*.97))],range=Math.max(1e-6f,hi-lo);for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=clampf((d[y][x]-lo)/range,0,1);return d;}
    private static float avg(float[][]d,int x0,int y0,int x1,int y1){float s=0;int n=0;for(int y=y0;y<=y1&&y<d.length;y++)for(int x=x0;x<=x1&&x<d[0].length;x++){s+=d[y][x];n++;}return s/Math.max(1,n);}
    private static int toZ(float dep,int d){return clamp(Math.round((1-clampf(dep,0,1))*(d-8)),1,d-4);}

    private static void outlineRect(int[]a,int w,int h,int d,int x0,int y0,int x1,int y1,int z,int mat){for(int x=x0;x<=x1;x++){set(a,w,h,d,x,y0,z,mat);set(a,w,h,d,x,y1,z,mat);}for(int y=y0;y<=y1;y++){set(a,w,h,d,x0,y,z,mat);set(a,w,h,d,x1,y,z,mat);}}
    private static void fillBox(int[]a,int w,int h,int d,int x0,int y0,int z0,int x1,int y1,int z1,int mat){int ax0=Math.max(0,Math.min(x0,x1)),ax1=Math.min(w-1,Math.max(x0,x1)),ay0=Math.max(0,Math.min(y0,y1)),ay1=Math.min(h-1,Math.max(y0,y1)),az0=Math.max(0,Math.min(z0,z1)),az1=Math.min(d-1,Math.max(z0,z1));for(int y=ay0;y<=ay1;y++)for(int z=az0;z<=az1;z++)for(int x=ax0;x<=ax1;x++)set(a,w,h,d,x,y,z,mat);}
    private static void carveBox(int[]a,int w,int h,int d,int x0,int y0,int z0,int x1,int y1,int z1){int ax0=Math.max(0,Math.min(x0,x1)),ax1=Math.min(w-1,Math.max(x0,x1)),ay0=Math.max(0,Math.min(y0,y1)),ay1=Math.min(h-1,Math.max(y0,y1)),az0=Math.max(0,Math.min(z0,z1)),az1=Math.min(d-1,Math.max(z0,z1));for(int y=ay0;y<=ay1;y++)for(int z=az0;z<=az1;z++)for(int x=ax0;x<=ax1;x++)a[index(w,d,x,y,z)]=0;}
    private static void mirrorBox(int[]a,int w,int h,int d,int x0,int y0,int x1,int y1,int z0,int z1){int mx0=w-1-x1,mx1=w-1-x0;for(int y=Math.max(0,y0);y<=Math.min(h-1,y1);y++)for(int z=Math.max(0,z0);z<=Math.min(d-1,z1);z++)for(int x=Math.max(0,x0);x<=Math.min(w-1,x1);x++){int v=a[index(w,d,x,y,z)];if(v!=0)set(a,w,h,d,mx0+(x-x0),y,z,v);}}
    private static void carveDoorway(int[]a,int w,int h,int d,int x0,int x1,int yb,int yt,int portalZ){for(int x=x0+2;x<=x1-2;x++)for(int y=yb+1;y<=yt-2;y++)for(int z=Math.max(0,portalZ-4);z<portalZ;z++)a[index(w,d,x,y,z)]=0;}
    private static void removeTinyComponents(int[]a,int w,int h,int d,int min){boolean[]vis=new boolean[a.length];int[]q=new int[a.length];int[][]ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int i=0;i<a.length;i++){if(a[i]==0||vis[i])continue;int s=0,e=0;q[e++]=i;vis[i]=true;while(s<e){int p=q[s++],y=p/(w*d),rem=p-y*w*d,z=rem/w,x=rem-z*w;for(int[]v:ds){int nx=x+v[0],ny=y+v[1],nz=z+v[2];if(nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d)continue;int ni=index(w,d,nx,ny,nz);if(!vis[ni]&&a[ni]!=0){vis[ni]=true;q[e++]=ni;}}}if(e<min)for(int j=0;j<e;j++)a[q[j]]=0;}}
    private static void bridgeGaps(int[]a,int w,int h,int d){int[]c=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=index(w,d,x,y,z);if(c[i]!=0)continue;int l=c[index(w,d,x-1,y,z)],r=c[index(w,d,x+1,y,z)],u=c[index(w,d,x,y+1,z)],dn=c[index(w,d,x,y-1,z)];if(l!=0&&r!=0)a[i]=l;else if(u!=0&&dn!=0)a[i]=u;}}
    private static int id(Map<String,Integer>p,String b){return p.computeIfAbsent(b,k->p.size());}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[index(w,d,x,y,z)]=v;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static float clampf(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
}
