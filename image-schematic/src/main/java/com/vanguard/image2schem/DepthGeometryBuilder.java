package com.vanguard.image2schem;

import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Converts a neural monocular depth map into sparse Minecraft architecture.
 * Unlike the old generator this NEVER creates a full rectangular room shell.
 * Blocks are placed only where the image/depth evidence supports a visible surface.
 */
public final class DepthGeometryBuilder {
    private DepthGeometryBuilder() {}

    public static ImageConverter.Result build(BufferedImage source, float[][] sourceDepth,
                                               int targetWidth, int requestedDepth,
                                               IntConsumer progress) {
        targetWidth = Math.max(48, Math.min(144, targetWidth));
        int targetHeight = Math.max(28, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        if (targetHeight > 112) {
            float f = 112F / targetHeight;
            targetHeight = 112;
            targetWidth = Math.max(48, Math.round(targetWidth * f));
        }
        int worldDepth = Math.max(24, Math.min(64, requestedDepth));
        progress.accept(52);

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        float[][] depth = resizeDepth(sourceDepth, targetWidth, targetHeight);
        depth = bilateralLikeSmooth(depth, 2);
        progress.accept(57);

        // Depth Anything may encode near/far in either direction for our purposes.
        // Pick the orientation that makes the lower-centre foreground closer than the upper centre.
        if (shouldInvert(depth)) invert(depth);
        robustNormalize(depth);
        progress.accept(60);

        // Image features used to reject sky/background and preserve actual architectural edges.
        float[][] lum = luminance(scaled);
        float[][] edge = edgeStrength(scaled, depth);
        boolean[][] keep = buildEvidenceMask(scaled, depth, edge);
        keep = cleanMask(keep, 2);
        progress.accept(65);

        Opening opening = detectMainOpening(lum, depth, edge);
        FloorBand floorBand = detectFloorBand(lum, depth, edge, opening);
        progress.accept(69);

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

        // Primary visible-surface reconstruction from depth. This creates depth-separated walls,
        // beams and pillars instead of extruding an entire photograph or inventing a big box.
        for (int y=0; y<targetHeight; y++) {
            int outY = targetHeight - 1 - y;
            for (int x=0; x<targetWidth; x++) {
                if (!keep[y][x]) continue;
                if (opening.contains(x,y) && y > opening.y0 + 1) continue;

                float d = depth[y][x];
                int z = Math.round((1f - d) * (worldDepth - 8));
                z = clamp(z, 1, worldDepth - 4);
                int rgb = scaled.getRGB(x,y);
                int r=(rgb>>>16)&255, gg=(rgb>>>8)&255, b=rgb&255;
                int material;
                if (b > r + 25 && b > gg + 18) material = glass;
                else if (lum[y][x] < .22f) material = dark;
                else if (edge[y][x] > .52f) material = trim;
                else material = wall;

                // Thicker geometry where the reference contains strong depth discontinuities.
                int thickness = edge[y][x] > .62f ? 3 : 2;
                for (int dz=0; dz<thickness && z+dz<worldDepth; dz++) set(blocks,targetWidth,targetHeight,worldDepth,x,outY,z+dz,material);
            }
            progress.accept(69 + Math.round(10f*(y+1)/targetHeight));
        }

        // Reconstruct a perspective floor/ramp from only the lower visible region. It is deliberately
        // finite and follows depth; there is no infinite rectangular floor.
        buildFloorRamp(blocks, targetWidth, targetHeight, worldDepth, depth, floorBand, floor);
        progress.accept(82);

        // Turn the detected entrance into actual depth: portal frame + corridor, but only around the opening.
        buildEntrance(blocks,targetWidth,targetHeight,worldDepth,opening,depth,dark,trim,light,floor);
        progress.accept(87);

        // Strong vertical depth edges become structural columns. Horizontal ones become beams.
        addArchitecturalPlanes(blocks,targetWidth,targetHeight,worldDepth,depth,edge,keep,opening,stone,trim);
        progress.accept(91);

        // Clean unsupported voxel noise while preserving connected architectural surfaces.
        removeTinyComponents(blocks,targetWidth,targetHeight,worldDepth,12);
        progress.accept(95);
        bridgeSurfaceGaps(blocks,targetWidth,targetHeight,worldDepth);
        progress.accept(97);
        carveOpening(blocks,targetWidth,targetHeight,worldDepth,opening);
        progress.accept(99);

        return new ImageConverter.Result(targetWidth,targetHeight,worldDepth,blocks,palette);
    }

    private static void buildFloorRamp(int[] a,int w,int h,int d,float[][] depth,FloorBand f,int material){
        int cx=w/2;
        for(int sy=f.y0; sy<h; sy++){
            float t=(sy-f.y0)/(float)Math.max(1,h-1-f.y0);
            int half=Math.round((w*.18f)*(1-t)+(w*.43f)*t);
            int y=Math.max(0,h-1-sy);
            for(int x=Math.max(0,cx-half);x<=Math.min(w-1,cx+half);x++){
                float dv=depth[sy][x]; int z=clamp(Math.round((1f-dv)*(d-8)),1,d-4);
                set(a,w,h,d,x,y,z,material);
                if(y>0 && ((x+sy)&3)==0) set(a,w,h,d,x,y-1,z,material);
            }
        }
    }

    private static void buildEntrance(int[] a,int w,int h,int d,Opening o,float[][] depth,int wall,int trim,int light,int floor){
        int x0=clamp(o.x0,2,w-3),x1=clamp(o.x1,2,w-3);
        int yBottom=Math.max(1,h-1-o.y1),yTop=Math.min(h-2,h-1-o.y0);
        if(x1-x0<4 || yTop-yBottom<5) return;
        float avg=0;int n=0;for(int y=o.y0;y<=o.y1;y++)for(int x=o.x0;x<=o.x1;x++){avg+=depth[y][x];n++;}
        int start=clamp(Math.round((1f-(avg/Math.max(1,n)))*(d-8)),2,d-10);
        int end=Math.min(d-3,start+Math.max(10,d/3));
        int frame=2;
        for(int z=Math.max(0,start-frame);z<=Math.min(d-1,start+frame);z++){
            for(int y=yBottom;y<=yTop;y++){for(int k=0;k<2;k++){set(a,w,h,d,x0-k,y,z,trim);set(a,w,h,d,x1+k,y,z,trim);}}
            for(int x=x0-1;x<=x1+1;x++){set(a,w,h,d,x,yTop,z,trim);set(a,w,h,d,x,yTop-1,z,wall);}
        }
        for(int z=start;z<=end;z++){
            for(int x=x0;x<=x1;x++){set(a,w,h,d,x,yBottom,z,floor);set(a,w,h,d,x,yTop,z,wall);}
            setWallStrip(a,w,h,d,x0,yBottom,yTop,z,wall); setWallStrip(a,w,h,d,x1,yBottom,yTop,z,wall);
            if((z-start)%6==3){int m=(x0+x1)/2;set(a,w,h,d,m,yTop,z,light);if(m+1<x1)set(a,w,h,d,m+1,yTop,z,light);}
        }
    }

    private static void addArchitecturalPlanes(int[] a,int w,int h,int d,float[][] depth,float[][] edge,boolean[][] keep,Opening o,int wall,int trim){
        // Vertical columns from long runs of strong edges outside the opening.
        for(int x=2;x<w-2;x++){
            int run=0,best=0,end=0;for(int y=2;y<h-2;y++){if(keep[y][x]&&edge[y][x]>.48f&&!o.contains(x,y)){run++;if(run>best){best=run;end=y;}}else run=0;}
            if(best>h*.23f){int y0=end-best+1;float av=0;for(int y=y0;y<=end;y++)av+=depth[y][x];int z=clamp(Math.round((1f-av/best)*(d-8)),1,d-5);for(int xx=x-1;xx<=x+1;xx++)for(int y=y0;y<=end;y++)for(int zz=z;zz<Math.min(d,z+3);zz++)set(a,w,h,d,xx,h-1-y,zz,trim);x+=2;}
        }
        // Horizontal beams from long strong-edge runs in upper 70%.
        for(int y=2;y<(int)(h*.72f);y++){
            int run=0,best=0,end=0;for(int x=2;x<w-2;x++){if(keep[y][x]&&edge[y][x]>.50f&&!o.contains(x,y)){run++;if(run>best){best=run;end=x;}}else run=0;}
            if(best>w*.22f){int x0=end-best+1;float av=0;for(int x=x0;x<=end;x++)av+=depth[y][x];int z=clamp(Math.round((1f-av/best)*(d-8)),1,d-5);for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int x=x0;x<=end;x++)for(int zz=z;zz<Math.min(d,z+2);zz++)set(a,w,h,d,x,h-1-yy,zz,wall);y+=2;}
        }
    }

    private static boolean[][] buildEvidenceMask(BufferedImage img,float[][] depth,float[][] edge){int h=img.getHeight(),w=img.getWidth();boolean[][] m=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){float e=edge[y][x],d=depth[y][x];int rgb=img.getRGB(x,y);int r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;float sat=(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)))/255f;boolean probableSky=(b>r*1.25f&&b>g*1.12f&&y<h*.55f);boolean flatFar=d>.86f&&e<.08f; m[y][x]=!probableSky && !flatFar && (e>.055f || d<.82f || sat>.12f);}return m;}
    private static boolean shouldInvert(float[][] d){int h=d.length,w=d[0].length;float lower=avg(d,w/3,h*2/3,w*2/3,h-1),upper=avg(d,w/3,0,w*2/3,h/3);return lower>upper;}
    private static void invert(float[][]d){for(int y=0;y<d.length;y++)for(int x=0;x<d[0].length;x++)d[y][x]=1f-d[y][x];}
    private static void robustNormalize(float[][]d){int h=d.length,w=d[0].length,n=h*w;float[]v=new float[n];int k=0;for(float[]r:d)for(float x:r)v[k++]=x;java.util.Arrays.sort(v);float lo=v[(int)(n*.03)],hi=v[Math.min(n-1,(int)(n*.97))],range=Math.max(1e-5f,hi-lo);for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=clampf((d[y][x]-lo)/range,0,1);}
    private static float[][] resizeDepth(float[][] src,int w,int h){int sh=src.length,sw=src[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){float sy=y*(sh-1f)/Math.max(1,h-1);int y0=(int)sy,y1=Math.min(sh-1,y0+1);float fy=sy-y0;for(int x=0;x<w;x++){float sx=x*(sw-1f)/Math.max(1,w-1);int x0=(int)sx,x1=Math.min(sw-1,x0+1);float fx=sx-x0;o[y][x]=(src[y0][x0]*(1-fx)+src[y0][x1]*fx)*(1-fy)+(src[y1][x0]*(1-fx)+src[y1][x1]*fx)*fy;}}return o;}
    private static float[][] bilateralLikeSmooth(float[][]s,int passes){int h=s.length,w=s[0].length;for(int p=0;p<passes;p++){float[][]n=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){float c=s[y][x],sum=0,wt=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){float diff=Math.abs(s[yy][xx]-c),q=diff<.10f?1f:.18f;sum+=s[yy][xx]*q;wt+=q;}n[y][x]=sum/wt;}s=n;}return s;}
    private static float[][] luminance(BufferedImage i){int h=i.getHeight(),w=i.getWidth();float[][]l=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=i.getRGB(x,y);l[y][x]=(.2126f*((rgb>>>16)&255)+.7152f*((rgb>>>8)&255)+.0722f*(rgb&255))/255f;}return l;}
    private static float[][] edgeStrength(BufferedImage i,float[][]d){int h=i.getHeight(),w=i.getWidth();float[][]e=new float[h][w];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){float img=Math.abs(luma(i,x+1,y)-luma(i,x-1,y))+Math.abs(luma(i,x,y+1)-luma(i,x,y-1));float dep=Math.abs(d[y][x+1]-d[y][x-1])+Math.abs(d[y+1][x]-d[y-1][x]);e[y][x]=clampf(img*.7f+dep*1.8f,0,1);}return e;}
    private static float luma(BufferedImage i,int x,int y){int r=i.getRGB(x,y);return(.2126f*((r>>>16)&255)+.7152f*((r>>>8)&255)+.0722f*(r&255))/255f;}
    private static Opening detectMainOpening(float[][]lum,float[][]depth,float[][]edge){int h=lum.length,w=lum[0].length,cx=w/2;float best=-1;Opening out=new Opening(cx-w/10,h/3,cx+w/10,h*4/5);for(int ww=Math.max(6,w/10);ww<=w/2;ww+=Math.max(3,w/16)){for(int hh=Math.max(8,h/5);hh<=h*3/5;hh+=Math.max(3,h/12)){int x0=cx-ww/2,x1=cx+ww/2;for(int y0=h/5;y0<h*3/5;y0+=Math.max(2,h/24)){int y1=Math.min(h-2,y0+hh);float dark=0,deep=0,border=0;int n=0;for(int y=y0;y<=y1;y+=2)for(int x=x0;x<=x1;x+=2){dark+=1f-lum[y][x];deep+=depth[y][x];n++;}for(int x=x0;x<=x1;x++){border+=edge[y0][x]+edge[Math.min(h-1,y1)][x];}for(int y=y0;y<=y1;y++){border+=edge[y][Math.max(0,x0)]+edge[y][Math.min(w-1,x1)];}float score=(dark/n)*.48f+(deep/n)*.22f+border/Math.max(1,2*ww+2*hh)*.30f;if(score>best){best=score;out=new Opening(x0,y0,x1,y1);}}}}return out.clamp(w,h);}
    private static FloorBand detectFloorBand(float[][]lum,float[][]depth,float[][]edge,Opening o){int h=lum.length,w=lum[0].length;int start=Math.max(o.y1,h*55/100);float best=-1;int bestY=start;for(int y=start;y<h-3;y++){float smooth=0,near=0;for(int x=w/5;x<w*4/5;x++){smooth+=1f-edge[y][x];near+=1f-depth[y][x];}float score=(smooth+near*.5f)/(w*3/5f);if(score>best){best=score;bestY=y;}}return new FloorBand(Math.min(h-2,bestY));}
    private static boolean[][] cleanMask(boolean[][]m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][]n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0,t=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){t++;if(m[yy][xx])c++;}n[y][x]=m[y][x]?c>=2:c>=5;}m=n;}return m;}
    private static void removeTinyComponents(int[]a,int w,int h,int d,int min){boolean[]vis=new boolean[a.length];int[]queue=new int[a.length];for(int i=0;i<a.length;i++){if(a[i]==0||vis[i])continue;int qs=0,qe=0;queue[qe++]=i;vis[i]=true;while(qs<qe){int idx=queue[qs++],y=idx/(w*d),rem=idx-y*w*d,z=rem/w,x=rem-z*w;int[][]ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int[]q:ds){int nx=x+q[0],ny=y+q[1],nz=z+q[2];if(nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d)continue;int ni=idx(w,d,nx,ny,nz);if(!vis[ni]&&a[ni]!=0){vis[ni]=true;queue[qe++]=ni;}}}if(qe<min)for(int q=0;q<qe;q++)a[queue[q]]=0;}}
    private static void bridgeSurfaceGaps(int[]a,int w,int h,int d){int[]c=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=idx(w,d,x,y,z);if(c[i]!=0)continue;int l=c[idx(w,d,x-1,y,z)],r=c[idx(w,d,x+1,y,z)],u=c[idx(w,d,x,y+1,z)],dn=c[idx(w,d,x,y-1,z)];if(l!=0&&r!=0)a[i]=l;else if(u!=0&&dn!=0)a[i]=u;}}
    private static void carveOpening(int[]a,int w,int h,int d,Opening o){int y0=Math.max(1,h-1-o.y1),y1=Math.min(h-2,h-1-o.y0);int x0=Math.max(1,o.x0+2),x1=Math.min(w-2,o.x1-2);for(int y=y0+1;y<y1-1;y++)for(int x=x0;x<=x1;x++){for(int z=0;z<Math.min(d,8);z++)a[idx(w,d,x,y,z)]=0;}}
    private static float avg(float[][]d,int x0,int y0,int x1,int y1){float s=0;int n=0;for(int y=y0;y<=y1&&y<d.length;y++)for(int x=x0;x<=x1&&x<d[0].length;x++){s+=d[y][x];n++;}return s/Math.max(1,n);}
    private static int id(Map<String,Integer>p,String b){return p.computeIfAbsent(b,k->p.size());}
    private static int idx(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[idx(w,d,x,y,z)]=v;}
    private static void setWallStrip(int[]a,int w,int h,int d,int x,int y0,int y1,int z,int v){for(int y=y0;y<=y1;y++)set(a,w,h,d,x,y,z,v);}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static float clampf(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private record Opening(int x0,int y0,int x1,int y1){boolean contains(int x,int y){return x>=x0&&x<=x1&&y>=y0&&y<=y1;}Opening clamp(int w,int h){return new Opening(clamp(x0,1,w-2),clamp(y0,1,h-2),clamp(x1,1,w-2),clamp(y1,1,h-2));}}
    private record FloorBand(int y0){}
}
