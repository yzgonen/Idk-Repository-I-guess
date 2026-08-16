package com.vanguard.image2schem;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Converts neural monocular depth into sparse Minecraft architecture.
 * It intentionally never creates a full rectangular room shell.
 */
public final class DepthGeometryBuilder {
    private DepthGeometryBuilder() {}

    public static ImageConverter.Result build(BufferedImage source, float[][] sourceDepth,
                                               int targetWidth, int requestedDepth,
                                               IntConsumer progress) {
        targetWidth = Math.max(48, Math.min(144, targetWidth));
        int targetHeight = Math.max(28, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        if (targetHeight > 112) {
            float f = 112f / targetHeight;
            targetHeight = 112;
            targetWidth = Math.max(48, Math.round(targetWidth * f));
        }
        int worldDepth = Math.max(24, Math.min(64, requestedDepth));
        progress.accept(52);

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        float[][] depth = resizeDepth(sourceDepth, targetWidth, targetHeight);
        depth = smoothDepth(depth, 2);
        if (shouldInvert(depth)) invert(depth);
        robustNormalize(depth);
        progress.accept(59);

        float[][] lum = luminance(scaled);
        float[][] edge = edgeStrength(scaled, depth);
        boolean[][] keep = cleanMask(buildEvidenceMask(scaled, depth, edge), 2);
        Opening opening = detectMainOpening(lum, depth, edge).bounded(targetWidth, targetHeight);
        int floorStart = detectFloorStart(depth, edge, opening);
        progress.accept(66);

        Map<String,Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        int dark = id(palette, "minecraft:deepslate_tiles");
        int wall = id(palette, "minecraft:gray_concrete");
        int trim = id(palette, "minecraft:polished_deepslate");
        int stone = id(palette, "minecraft:stone_bricks");
        int floor = id(palette, "minecraft:smooth_stone");
        int glass = id(palette, "minecraft:tinted_glass");
        int light = id(palette, "minecraft:sea_lantern");
        int[] blocks = new int[targetWidth * targetHeight * worldDepth];

        // Neural visible surface: each image location becomes a thin surface at its inferred depth.
        for (int sy=0; sy<targetHeight; sy++) {
            int wy = targetHeight - 1 - sy;
            for (int x=0; x<targetWidth; x++) {
                if (!keep[sy][x]) continue;
                if (opening.contains(x, sy) && sy > opening.y0 + 1) continue;
                int z = toWorldZ(depth[sy][x], worldDepth);
                int rgb = scaled.getRGB(x,sy);
                int r=(rgb>>>16)&255, gg=(rgb>>>8)&255, b=rgb&255;
                int material = b > r + 25 && b > gg + 18 ? glass
                        : lum[sy][x] < .22f ? dark
                        : edge[sy][x] > .50f ? trim : wall;
                int thickness = edge[sy][x] > .60f ? 3 : 2;
                for (int dz=0; dz<thickness; dz++) set(blocks,targetWidth,targetHeight,worldDepth,x,wy,z+dz,material);
            }
            progress.accept(66 + Math.round(11f * (sy + 1) / targetHeight));
        }

        // Perspective floor/ramp. Only the visible lower region is created.
        int cx = targetWidth / 2;
        for (int sy=floorStart; sy<targetHeight; sy++) {
            float t = (sy-floorStart)/(float)Math.max(1,targetHeight-1-floorStart);
            int half = Math.round(targetWidth * (.16f + .30f*t));
            int wy = targetHeight - 1 - sy;
            for (int x=Math.max(0,cx-half); x<=Math.min(targetWidth-1,cx+half); x++) {
                int z = toWorldZ(depth[sy][x], worldDepth);
                set(blocks,targetWidth,targetHeight,worldDepth,x,wy,z,floor);
            }
        }
        progress.accept(81);

        // Entrance frame and finite corridor derived from the detected opening.
        buildEntrance(blocks,targetWidth,targetHeight,worldDepth,opening,depth,wall,trim,light,floor);
        progress.accept(87);

        // Reinforce only strong visible vertical/horizontal architectural edges.
        addColumnsAndBeams(blocks,targetWidth,targetHeight,worldDepth,depth,edge,keep,opening,stone,trim);
        progress.accept(92);

        removeTinyComponents(blocks,targetWidth,targetHeight,worldDepth,10);
        bridgeOneBlockGaps(blocks,targetWidth,targetHeight,worldDepth);
        carveFrontOpening(blocks,targetWidth,targetHeight,worldDepth,opening);
        progress.accept(99);

        return new ImageConverter.Result(targetWidth,targetHeight,worldDepth,blocks,palette);
    }

    private static void buildEntrance(int[] a,int w,int h,int d,Opening o,float[][] depth,int wall,int trim,int light,int floor) {
        int x0=clampInt(o.x0,2,w-3), x1=clampInt(o.x1,2,w-3);
        int yBottom=Math.max(1,h-1-o.y1), yTop=Math.min(h-2,h-1-o.y0);
        if (x1-x0<5 || yTop-yBottom<6) return;
        float avg=0; int n=0;
        for(int sy=o.y0;sy<=o.y1;sy++) for(int x=o.x0;x<=o.x1;x++){avg+=depth[sy][x];n++;}
        int start=clampInt(toWorldZ(avg/Math.max(1,n),d),2,d-12);
        int end=Math.min(d-3,start+Math.max(10,d/3));

        for(int z=Math.max(0,start-2);z<=Math.min(d-1,start+2);z++) {
            for(int y=yBottom;y<=yTop;y++) {
                set(a,w,h,d,x0,y,z,trim); set(a,w,h,d,x0-1,y,z,trim);
                set(a,w,h,d,x1,y,z,trim); set(a,w,h,d,x1+1,y,z,trim);
            }
            for(int x=x0-1;x<=x1+1;x++) { set(a,w,h,d,x,yTop,z,trim); set(a,w,h,d,x,yTop-1,z,wall); }
        }
        for(int z=start;z<=end;z++) {
            for(int x=x0;x<=x1;x++) { set(a,w,h,d,x,yBottom,z,floor); set(a,w,h,d,x,yTop,z,wall); }
            for(int y=yBottom;y<=yTop;y++) { set(a,w,h,d,x0,y,z,wall); set(a,w,h,d,x1,y,z,wall); }
            if((z-start)%6==3){int m=(x0+x1)/2;set(a,w,h,d,m,yTop,z,light);if(m+1<x1)set(a,w,h,d,m+1,yTop,z,light);}
        }
    }

    private static void addColumnsAndBeams(int[] a,int w,int h,int d,float[][] depth,float[][] edge,boolean[][] keep,Opening o,int stone,int trim) {
        for(int x=2;x<w-2;x++) {
            int run=0,best=0,end=0;
            for(int sy=2;sy<h-2;sy++) {
                if(keep[sy][x] && edge[sy][x]>.47f && !o.contains(x,sy)){run++;if(run>best){best=run;end=sy;}} else run=0;
            }
            if(best>h*.25f) {
                int y0=end-best+1; float av=0; for(int sy=y0;sy<=end;sy++)av+=depth[sy][x];
                int z=toWorldZ(av/best,d);
                for(int xx=x-1;xx<=x+1;xx++)for(int sy=y0;sy<=end;sy++)for(int dz=0;dz<3;dz++)set(a,w,h,d,xx,h-1-sy,z+dz,trim);
                x+=2;
            }
        }
        for(int sy=2;sy<h*7/10;sy++) {
            int run=0,best=0,end=0;
            for(int x=2;x<w-2;x++) {
                if(keep[sy][x] && edge[sy][x]>.49f && !o.contains(x,sy)){run++;if(run>best){best=run;end=x;}} else run=0;
            }
            if(best>w*.24f) {
                int x0=end-best+1; float av=0; for(int x=x0;x<=end;x++)av+=depth[sy][x];
                int z=toWorldZ(av/best,d);
                for(int yy=Math.max(0,sy-1);yy<=Math.min(h-1,sy+1);yy++)for(int x=x0;x<=end;x++)for(int dz=0;dz<2;dz++)set(a,w,h,d,x,h-1-yy,z+dz,stone);
                sy+=2;
            }
        }
    }

    private static boolean[][] buildEvidenceMask(BufferedImage img,float[][] depth,float[][] edge) {
        int h=img.getHeight(),w=img.getWidth(); boolean[][] m=new boolean[h][w];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++) {
            int rgb=img.getRGB(x,y),r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
            float sat=(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)))/255f;
            boolean sky=b>r*1.25f&&b>g*1.12f&&y<h*.58f;
            boolean flatFar=depth[y][x]>.88f&&edge[y][x]<.07f;
            m[y][x]=!sky&&!flatFar&&(edge[y][x]>.05f||depth[y][x]<.84f||sat>.13f);
        }
        return m;
    }

    private static Opening detectMainOpening(float[][] lum,float[][] depth,float[][] edge) {
        int h=lum.length,w=lum[0].length,cx=w/2; float best=-999; Opening bestO=new Opening(cx-w/8,h/3,cx+w/8,h*4/5);
        int stepW=Math.max(4,w/18),stepH=Math.max(3,h/16);
        for(int ww=Math.max(8,w/7);ww<=w/2;ww+=stepW) for(int hh=Math.max(10,h/4);hh<=h*3/5;hh+=stepH) {
            int x0=cx-ww/2,x1=cx+ww/2;
            for(int y0=h/5;y0<h*3/5;y0+=Math.max(2,h/28)) {
                int y1=Math.min(h-2,y0+hh); float darkness=0,far=0,border=0;int n=0;
                for(int y=y0;y<=y1;y+=2)for(int x=x0;x<=x1;x+=2){darkness+=1f-lum[y][x];far+=depth[y][x];n++;}
                for(int x=x0;x<=x1;x++){border+=edge[y0][x]+edge[y1][x];}
                for(int y=y0;y<=y1;y++){border+=edge[y][x0]+edge[y][x1];}
                float score=.50f*(darkness/n)+.18f*(far/n)+.32f*border/Math.max(1,2*ww+2*hh);
                if(score>best){best=score;bestO=new Opening(x0,y0,x1,y1);}
            }
        }
        return bestO;
    }

    private static int detectFloorStart(float[][] depth,float[][] edge,Opening o) {
        int h=depth.length,w=depth[0].length,start=Math.max(o.y1,h*55/100),bestY=start;float best=-1;
        for(int y=start;y<h-2;y++) { float score=0;for(int x=w/5;x<w*4/5;x++)score+=(1f-edge[y][x])+.45f*(1f-depth[y][x]); if(score>best){best=score;bestY=y;} }
        return Math.min(h-2,bestY);
    }

    private static float[][] resizeDepth(float[][] src,int w,int h){int sh=src.length,sw=src[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){float sy=y*(sh-1f)/Math.max(1,h-1);int y0=(int)sy,y1=Math.min(sh-1,y0+1);float fy=sy-y0;for(int x=0;x<w;x++){float sx=x*(sw-1f)/Math.max(1,w-1);int x0=(int)sx,x1=Math.min(sw-1,x0+1);float fx=sx-x0;o[y][x]=(src[y0][x0]*(1-fx)+src[y0][x1]*fx)*(1-fy)+(src[y1][x0]*(1-fx)+src[y1][x1]*fx)*fy;}}return o;}
    private static float[][] smoothDepth(float[][]s,int passes){int h=s.length,w=s[0].length;for(int p=0;p<passes;p++){float[][]n=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){float c=s[y][x],sum=0,wt=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){float q=Math.abs(s[yy][xx]-c)<.10f?1f:.18f;sum+=s[yy][xx]*q;wt+=q;}n[y][x]=sum/wt;}s=n;}return s;}
    private static boolean shouldInvert(float[][]d){int h=d.length,w=d[0].length;return avg(d,w/3,h*2/3,w*2/3,h-1)>avg(d,w/3,0,w*2/3,h/3);}
    private static void invert(float[][]d){for(int y=0;y<d.length;y++)for(int x=0;x<d[0].length;x++)d[y][x]=1f-d[y][x];}
    private static void robustNormalize(float[][]d){int h=d.length,w=d[0].length,n=h*w;float[]v=new float[n];int k=0;for(float[]r:d)for(float q:r)v[k++]=q;Arrays.sort(v);float lo=v[(int)(n*.03)],hi=v[Math.min(n-1,(int)(n*.97))],range=Math.max(1e-5f,hi-lo);for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=clampFloat((d[y][x]-lo)/range,0,1);}
    private static float[][] luminance(BufferedImage i){int h=i.getHeight(),w=i.getWidth();float[][]l=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=i.getRGB(x,y);l[y][x]=(.2126f*((rgb>>>16)&255)+.7152f*((rgb>>>8)&255)+.0722f*(rgb&255))/255f;}return l;}
    private static float[][] edgeStrength(BufferedImage i,float[][]d){int h=i.getHeight(),w=i.getWidth();float[][]e=new float[h][w];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){float im=Math.abs(luma(i,x+1,y)-luma(i,x-1,y))+Math.abs(luma(i,x,y+1)-luma(i,x,y-1));float dep=Math.abs(d[y][x+1]-d[y][x-1])+Math.abs(d[y+1][x]-d[y-1][x]);e[y][x]=clampFloat(im*.7f+dep*1.8f,0,1);}return e;}
    private static float luma(BufferedImage i,int x,int y){int r=i.getRGB(x,y);return(.2126f*((r>>>16)&255)+.7152f*((r>>>8)&255)+.0722f*(r&255))/255f;}
    private static boolean[][] cleanMask(boolean[][]m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][]n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)if(m[yy][xx])c++;n[y][x]=m[y][x]?c>=2:c>=5;}m=n;}return m;}

    private static void removeTinyComponents(int[]a,int w,int h,int d,int min){boolean[]vis=new boolean[a.length];int[]q=new int[a.length];for(int i=0;i<a.length;i++){if(a[i]==0||vis[i])continue;int s=0,e=0;q[e++]=i;vis[i]=true;while(s<e){int p=q[s++],y=p/(w*d),rem=p-y*w*d,z=rem/w,x=rem-z*w;int[][]ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int[]v:ds){int nx=x+v[0],ny=y+v[1],nz=z+v[2];if(nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d)continue;int ni=index(w,d,nx,ny,nz);if(!vis[ni]&&a[ni]!=0){vis[ni]=true;q[e++]=ni;}}}if(e<min)for(int j=0;j<e;j++)a[q[j]]=0;}}
    private static void bridgeOneBlockGaps(int[]a,int w,int h,int d){int[]c=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=index(w,d,x,y,z);if(c[i]!=0)continue;int l=c[index(w,d,x-1,y,z)],r=c[index(w,d,x+1,y,z)],u=c[index(w,d,x,y+1,z)],dn=c[index(w,d,x,y-1,z)];if(l!=0&&r!=0)a[i]=l;else if(u!=0&&dn!=0)a[i]=u;}}
    private static void carveFrontOpening(int[]a,int w,int h,int d,Opening o){int y0=Math.max(1,h-1-o.y1),y1=Math.min(h-2,h-1-o.y0),x0=Math.max(1,o.x0+2),x1=Math.min(w-2,o.x1-2);for(int y=y0+1;y<y1-1;y++)for(int x=x0;x<=x1;x++)for(int z=0;z<Math.min(d,8);z++)a[index(w,d,x,y,z)]=0;}

    private static int toWorldZ(float normalizedDepth,int d){return clampInt(Math.round((1f-clampFloat(normalizedDepth,0,1))*(d-8)),1,d-4);}
    private static float avg(float[][]d,int x0,int y0,int x1,int y1){float s=0;int n=0;for(int y=y0;y<=y1&&y<d.length;y++)for(int x=x0;x<=x1&&x<d[0].length;x++){s+=d[y][x];n++;}return s/Math.max(1,n);}
    private static int id(Map<String,Integer>p,String b){return p.computeIfAbsent(b,k->p.size());}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[index(w,d,x,y,z)]=v;}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static float clampFloat(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private record Opening(int x0,int y0,int x1,int y1){
        boolean contains(int x,int y){return x>=x0&&x<=x1&&y>=y0&&y<=y1;}
        Opening bounded(int w,int h){return new Opening(clampInt(x0,1,w-2),clampInt(y0,1,h-2),clampInt(x1,1,w-2),clampInt(y1,1,h-2));}
    }
}
