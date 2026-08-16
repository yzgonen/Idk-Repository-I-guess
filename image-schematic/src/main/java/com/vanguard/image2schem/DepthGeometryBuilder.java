package com.vanguard.image2schem;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Neural depth -> architectural planes -> Minecraft geometry.
 * Depth is treated as evidence; noisy pixel depth is regularized into clean planes.
 */
public final class DepthGeometryBuilder {
    private DepthGeometryBuilder() {}

    public static ImageConverter.Result build(BufferedImage source, float[][] sourceDepth,
                                               int targetWidth, int requestedDepth,
                                               IntConsumer progress) {
        targetWidth = Math.max(64, Math.min(160, targetWidth));
        int targetHeight = Math.max(32, Math.round(source.getHeight() * (targetWidth / (float) source.getWidth())));
        if (targetHeight > 120) {
            float f = 120f / targetHeight;
            targetHeight = 120;
            targetWidth = Math.max(64, Math.round(targetWidth * f));
        }
        int worldDepth = Math.max(32, Math.min(72, requestedDepth));
        progress.accept(52);

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, targetWidth, targetHeight, null);
        g.dispose();

        float[][] depth = resizeDepth(sourceDepth, targetWidth, targetHeight);
        depth = smoothDepth(depth, 3);
        if (shouldInvert(depth)) invert(depth);
        robustNormalize(depth);
        progress.accept(58);

        float[][] lum = luminance(scaled);
        float[][] edge = edgeStrength(scaled, depth);
        Opening opening = detectMainOpening(lum, depth, edge).bounded(targetWidth, targetHeight);
        int floorStart = detectFloorStart(depth, edge, opening);
        progress.accept(63);

        // Architectural regularization: flatten texture noise into a few stable depth planes.
        float[][] regularDepth = regularizeDepthPlanes(depth, edge, opening, floorStart);
        boolean[][] keep = cleanMask(buildEvidenceMask(scaled, regularDepth, edge), 3);
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
        int accent = id(palette, "minecraft:iron_block");
        int[] blocks = new int[targetWidth * targetHeight * worldDepth];

        // 1) Clean facade surfaces from regularized depth; low-confidence texture does not become geometry.
        for (int sy=0; sy<targetHeight; sy++) {
            int wy = targetHeight - 1 - sy;
            for (int x=0; x<targetWidth; x++) {
                if (!keep[sy][x]) continue;
                if (opening.contains(x, sy) && sy > opening.y0 + 1) continue;
                if (sy >= floorStart) continue; // floor is built separately as one fitted plane

                int z = toWorldZ(regularDepth[sy][x], worldDepth);
                int rgb = scaled.getRGB(x,sy);
                int r=(rgb>>>16)&255, gg=(rgb>>>8)&255, b=rgb&255;
                int material = b > r + 28 && b > gg + 20 ? glass
                        : lum[sy][x] < .20f ? dark
                        : edge[sy][x] > .58f ? trim : wall;
                int thickness = edge[sy][x] > .64f ? 3 : 2;
                for (int dz=0; dz<thickness; dz++) set(blocks,targetWidth,targetHeight,worldDepth,x,wy,z+dz,material);
            }
            if ((sy & 3) == 0) progress.accept(69 + Math.round(7f * (sy + 1) / targetHeight));
        }

        // 2) Fit a single perspective ramp instead of copying every noisy depth row.
        buildFittedRamp(blocks,targetWidth,targetHeight,worldDepth,regularDepth,floorStart,opening,floor,trim);
        progress.accept(79);

        // 3) Build a deliberate entrance portal and reconstruct the visible interior independently.
        buildStructuredEntrance(blocks,targetWidth,targetHeight,worldDepth,opening,regularDepth,wall,trim,dark,light,floor,accent);
        progress.accept(85);

        // 4) Detect and snap long features into real columns and beams.
        addRegularizedColumnsAndBeams(blocks,targetWidth,targetHeight,worldDepth,regularDepth,edge,keep,opening,stone,trim);
        progress.accept(90);

        // 5) Add paired supports around the entrance when the reference strongly supports a central portal.
        reinforcePortalSymmetry(blocks,targetWidth,targetHeight,worldDepth,opening,regularDepth,trim,stone);
        progress.accept(93);

        // 6) Remove depth-map sculpture noise and enforce Minecraft structural sanity.
        removeTinyComponents(blocks,targetWidth,targetHeight,worldDepth,18);
        removeDepthSpikes(blocks,targetWidth,targetHeight,worldDepth);
        bridgeOneBlockGaps(blocks,targetWidth,targetHeight,worldDepth);
        carveFrontOpening(blocks,targetWidth,targetHeight,worldDepth,opening);
        ensureWalkableInterior(blocks,targetWidth,targetHeight,worldDepth,opening);
        progress.accept(99);

        return new ImageConverter.Result(targetWidth,targetHeight,worldDepth,blocks,palette);
    }

    private static float[][] regularizeDepthPlanes(float[][] depth,float[][] edge,Opening opening,int floorStart) {
        int h=depth.length,w=depth[0].length;
        float[][] out=new float[h][w];
        int radius=2;
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            if(y>=floorStart){out[y][x]=depth[y][x];continue;}
            float[] vals=new float[(radius*2+1)*(radius*2+1)];int n=0;
            for(int yy=Math.max(0,y-radius);yy<=Math.min(h-1,y+radius);yy++)for(int xx=Math.max(0,x-radius);xx<=Math.min(w-1,x+radius);xx++){
                if(edge[yy][xx] < .42f || Math.abs(depth[yy][xx]-depth[y][x]) < .10f) vals[n++]=depth[yy][xx];
            }
            Arrays.sort(vals,0,n);
            float med=n==0?depth[y][x]:vals[n/2];
            // Quantize to stable planes except at strong boundaries.
            if(edge[y][x] < .50f) med=Math.round(med*18f)/18f;
            out[y][x]=clampFloat(med,0,1);
        }
        // Preserve opening depth so interior stays recessed.
        for(int y=opening.y0;y<=opening.y1;y++)for(int x=opening.x0;x<=opening.x1;x++)out[y][x]=depth[y][x];
        return out;
    }

    private static void buildFittedRamp(int[] a,int w,int h,int d,float[][] depth,int floorStart,Opening o,int floor,int trim) {
        int cx=(o.x0+o.x1)/2;
        int samples=0; double sumY=0,sumZ=0,sumYY=0,sumYZ=0;
        for(int sy=floorStart;sy<h;sy+=2){
            int half=Math.max(2,(o.x1-o.x0)/4);
            for(int x=Math.max(0,cx-half);x<=Math.min(w-1,cx+half);x+=2){
                int z=toWorldZ(depth[sy][x],d); double yy=sy-floorStart;
                sumY+=yy;sumZ+=z;sumYY+=yy*yy;sumYZ+=yy*z;samples++;
            }
        }
        double denom=samples*sumYY-sumY*sumY;
        double slope=Math.abs(denom)<1e-6?0:(samples*sumYZ-sumY*sumZ)/denom;
        double intercept=samples==0?d/3.0:(sumZ-slope*sumY)/samples;
        // Limit wild monocular-depth slopes; Minecraft ramp should remain deliberate.
        slope=Math.max(-0.45,Math.min(0.45,slope));

        for(int sy=floorStart;sy<h;sy++){
            float t=(sy-floorStart)/(float)Math.max(1,h-1-floorStart);
            int half=Math.round((o.x1-o.x0)*.52f + (w*.42f-(o.x1-o.x0)*.52f)*t);
            int wy=h-1-sy;
            int z=clampInt((int)Math.round(intercept+slope*(sy-floorStart)),1,d-5);
            for(int x=Math.max(0,cx-half);x<=Math.min(w-1,cx+half);x++){
                set(a,w,h,d,x,wy,z,floor);
                if((x==cx-half||x==cx+half) && wy+1<h) set(a,w,h,d,x,wy+1,z,trim);
            }
        }
    }

    private static void buildStructuredEntrance(int[] a,int w,int h,int d,Opening o,float[][] depth,int wall,int trim,int dark,int light,int floor,int accent) {
        int x0=clampInt(o.x0,3,w-4),x1=clampInt(o.x1,3,w-4);
        int yBottom=Math.max(1,h-1-o.y1),yTop=Math.min(h-2,h-1-o.y0);
        if(x1-x0<8||yTop-yBottom<8)return;

        float openingDepth=0;int n=0;
        for(int sy=o.y0;sy<=o.y1;sy+=2)for(int x=o.x0;x<=o.x1;x+=2){openingDepth+=depth[sy][x];n++;}
        int start=clampInt(toWorldZ(openingDepth/Math.max(1,n),d),3,d-18);
        int end=Math.min(d-4,start+Math.max(14,d/2));

        // Thick, clean portal frame.
        for(int z=Math.max(1,start-3);z<=Math.min(d-1,start+2);z++){
            for(int y=yBottom;y<=yTop;y++)for(int k=0;k<3;k++){
                set(a,w,h,d,x0-k,y,z,trim);set(a,w,h,d,x1+k,y,z,trim);
            }
            for(int x=x0-2;x<=x1+2;x++)for(int k=0;k<3;k++)set(a,w,h,d,x,yTop-k,z,trim);
        }

        // Interior corridor with perspective-independent clean planes.
        for(int z=start;z<=end;z++){
            for(int x=x0;x<=x1;x++){
                set(a,w,h,d,x,yBottom,z,floor);
                set(a,w,h,d,x,yTop,z,dark);
            }
            for(int y=yBottom;y<=yTop;y++){
                set(a,w,h,d,x0,y,z,wall);set(a,w,h,d,x1,y,z,wall);
            }
            if((z-start)%7==3){int m=(x0+x1)/2;set(a,w,h,d,m,yTop,z,light);if(m+1<x1)set(a,w,h,d,m+1,yTop,z,light);}
            if((z-start)%9==5){set(a,w,h,d,x0+1,yBottom+2,z,accent);set(a,w,h,d,x1-1,yBottom+2,z,accent);}
        }

        // Back bulkhead gives depth without making a giant closed box.
        int doorHalf=Math.max(2,(x1-x0)/7),mid=(x0+x1)/2;
        for(int y=yBottom;y<=yTop;y++)for(int x=x0;x<=x1;x++){
            boolean door=Math.abs(x-mid)<=doorHalf && y<=yBottom+Math.max(5,(yTop-yBottom)/2);
            if(!door)set(a,w,h,d,x,y,end,dark);
        }
    }

    private static void addRegularizedColumnsAndBeams(int[] a,int w,int h,int d,float[][] depth,float[][] edge,boolean[][] keep,Opening o,int stone,int trim) {
        int minColumn=Math.max(12,h/4);
        for(int x=3;x<w-3;x++){
            int run=0,best=0,end=0;
            for(int sy=3;sy<h-3;sy++){
                boolean strong=keep[sy][x]&&edge[sy][x]>.50f&&!o.contains(x,sy);
                if(strong){run++;if(run>best){best=run;end=sy;}}else run=0;
            }
            if(best>=minColumn){
                int sy0=end-best+1; float av=0;for(int sy=sy0;sy<=end;sy++)av+=depth[sy][x];
                int z=toWorldZ(av/best,d);
                for(int xx=x-1;xx<=x+1;xx++)for(int sy=sy0;sy<=end;sy++)for(int dz=0;dz<3;dz++)set(a,w,h,d,xx,h-1-sy,z+dz,trim);
                x+=3;
            }
        }
        int minBeam=Math.max(16,w/4);
        for(int sy=3;sy<h*3/4;sy++){
            int run=0,best=0,end=0;
            for(int x=3;x<w-3;x++){
                boolean strong=keep[sy][x]&&edge[sy][x]>.50f&&!o.contains(x,sy);
                if(strong){run++;if(run>best){best=run;end=x;}}else run=0;
            }
            if(best>=minBeam){
                int x0=end-best+1;float av=0;for(int x=x0;x<=end;x++)av+=depth[sy][x];
                int z=toWorldZ(av/best,d);
                for(int yy=Math.max(0,sy-1);yy<=Math.min(h-1,sy+1);yy++)for(int x=x0;x<=end;x++)for(int dz=0;dz<2;dz++)set(a,w,h,d,x,h-1-yy,z+dz,stone);
                sy+=3;
            }
        }
    }

    private static void reinforcePortalSymmetry(int[]a,int w,int h,int d,Opening o,float[][]depth,int trim,int stone){
        int portalW=o.x1-o.x0; if(portalW<w/7)return;
        int y0=Math.max(1,h-1-o.y1),y1=Math.min(h-2,h-1-o.y0);
        float av=0;int n=0;for(int sy=o.y0;sy<=o.y1;sy+=3){av+=depth[sy][o.x0];av+=depth[sy][o.x1];n+=2;}
        int z=toWorldZ(av/Math.max(1,n),d);
        for(int side=0;side<2;side++){
            int x=side==0?o.x0-3:o.x1+3;
            for(int xx=x-1;xx<=x+1;xx++)for(int y=y0;y<=y1;y++)for(int dz=0;dz<3;dz++)set(a,w,h,d,xx,y,z+dz,trim);
        }
        for(int x=o.x0-4;x<=o.x1+4;x++)for(int dz=0;dz<3;dz++)set(a,w,h,d,x,y1,z+dz,stone);
    }

    private static boolean[][] buildEvidenceMask(BufferedImage img,float[][] depth,float[][] edge) {
        int h=img.getHeight(),w=img.getWidth(); boolean[][] m=new boolean[h][w];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int rgb=img.getRGB(x,y),r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;
            float sat=(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b)))/255f;
            boolean probableSky=b>r*1.22f&&b>g*1.10f&&y<h*.62f;
            boolean lowInfo=edge[y][x]<.045f && (depth[y][x]>.90f || depth[y][x]<.03f);
            m[y][x]=!probableSky&&!lowInfo&&(edge[y][x]>.055f||sat>.10f||depth[y][x]<.84f);
        }
        return m;
    }

    private static Opening detectMainOpening(float[][] lum,float[][] depth,float[][] edge) {
        int h=lum.length,w=lum[0].length,cx=w/2;float best=-999;Opening out=new Opening(cx-w/7,h/3,cx+w/7,h*4/5);
        for(int ww=Math.max(10,w/6);ww<=w*3/5;ww+=Math.max(4,w/20))for(int hh=Math.max(12,h/4);hh<=h*3/5;hh+=Math.max(3,h/18)){
            int x0=cx-ww/2,x1=cx+ww/2;
            for(int y0=h/5;y0<h*3/5;y0+=Math.max(2,h/30)){
                int y1=Math.min(h-2,y0+hh);float darkness=0,deep=0,border=0;int n=0;
                for(int y=y0;y<=y1;y+=2)for(int x=x0;x<=x1;x+=2){darkness+=1f-lum[y][x];deep+=depth[y][x];n++;}
                for(int x=x0;x<=x1;x++){border+=edge[y0][x]+edge[y1][x];}
                for(int y=y0;y<=y1;y++){border+=edge[y][x0]+edge[y][x1];}
                float rectangularity=border/Math.max(1f,2f*ww+2f*hh);
                float centerBonus=1f-Math.abs(((x0+x1)/2f)-cx)/(w/2f);
                float score=.46f*(darkness/n)+.18f*(deep/n)+.28f*rectangularity+.08f*centerBonus;
                if(score>best){best=score;out=new Opening(x0,y0,x1,y1);}
            }
        }
        return out;
    }

    private static int detectFloorStart(float[][] depth,float[][] edge,Opening o){
        int h=depth.length,w=depth[0].length,start=Math.max(o.y1,h*56/100),bestY=start;float best=-1;
        for(int y=start;y<h-2;y++){float score=0;for(int x=w/5;x<w*4/5;x++)score+=(1f-edge[y][x])+.35f*(1f-depth[y][x]);if(score>best){best=score;bestY=y;}}
        return Math.min(h-2,bestY);
    }

    private static void ensureWalkableInterior(int[]a,int w,int h,int d,Opening o){
        int x0=Math.max(2,o.x0+2),x1=Math.min(w-3,o.x1-2),yb=Math.max(1,h-1-o.y1),yt=Math.min(h-2,h-1-o.y0);
        if(x1<=x0||yt-yb<4)return;
        int clearance=Math.min(yt-1,yb+Math.max(4,(yt-yb)*3/4));
        for(int z=8;z<d-2;z++)for(int y=yb+1;y<clearance;y++)for(int x=x0;x<=x1;x++)a[index(w,d,x,y,z)]=0;
    }

    private static void removeDepthSpikes(int[]a,int w,int h,int d){
        int[]c=a.clone();
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
            for(int z=1;z<d-1;z++){
                int i=index(w,d,x,y,z);if(c[i]==0)continue;
                int lateral=0;if(c[index(w,d,x-1,y,z)]!=0)lateral++;if(c[index(w,d,x+1,y,z)]!=0)lateral++;if(c[index(w,d,x,y-1,z)]!=0)lateral++;if(c[index(w,d,x,y+1,z)]!=0)lateral++;
                if(lateral==0 && c[index(w,d,x,y,z-1)]==0 && c[index(w,d,x,y,z+1)]==0)a[i]=0;
            }
        }
    }

    private static float[][] resizeDepth(float[][]src,int w,int h){int sh=src.length,sw=src[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){float sy=y*(sh-1f)/Math.max(1,h-1);int y0=(int)sy,y1=Math.min(sh-1,y0+1);float fy=sy-y0;for(int x=0;x<w;x++){float sx=x*(sw-1f)/Math.max(1,w-1);int x0=(int)sx,x1=Math.min(sw-1,x0+1);float fx=sx-x0;o[y][x]=(src[y0][x0]*(1-fx)+src[y0][x1]*fx)*(1-fy)+(src[y1][x0]*(1-fx)+src[y1][x1]*fx)*fy;}}return o;}
    private static float[][] smoothDepth(float[][]s,int passes){int h=s.length,w=s[0].length;for(int p=0;p<passes;p++){float[][]n=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){float c=s[y][x],sum=0,wt=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){float diff=Math.abs(s[yy][xx]-c),q=diff<.08f?1f:diff<.18f?.35f:.08f;sum+=s[yy][xx]*q;wt+=q;}n[y][x]=sum/Math.max(.001f,wt);}s=n;}return s;}
    private static boolean shouldInvert(float[][]d){int h=d.length,w=d[0].length;return avg(d,w/3,h*2/3,w*2/3,h-1)>avg(d,w/3,0,w*2/3,h/3);}
    private static void invert(float[][]d){for(int y=0;y<d.length;y++)for(int x=0;x<d[0].length;x++)d[y][x]=1f-d[y][x];}
    private static void robustNormalize(float[][]d){int h=d.length,w=d[0].length,n=h*w;float[]v=new float[n];int k=0;for(float[]r:d)for(float q:r)v[k++]=q;Arrays.sort(v);float lo=v[(int)(n*.03)],hi=v[Math.min(n-1,(int)(n*.97))],range=Math.max(1e-5f,hi-lo);for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=clampFloat((d[y][x]-lo)/range,0,1);}
    private static float[][] luminance(BufferedImage i){int h=i.getHeight(),w=i.getWidth();float[][]l=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=i.getRGB(x,y);l[y][x]=(.2126f*((rgb>>>16)&255)+.7152f*((rgb>>>8)&255)+.0722f*(rgb&255))/255f;}return l;}
    private static float[][] edgeStrength(BufferedImage i,float[][]d){int h=i.getHeight(),w=i.getWidth();float[][]e=new float[h][w];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){float im=Math.abs(luma(i,x+1,y)-luma(i,x-1,y))+Math.abs(luma(i,x,y+1)-luma(i,x,y-1));float dep=Math.abs(d[y][x+1]-d[y][x-1])+Math.abs(d[y+1][x]-d[y-1][x]);e[y][x]=clampFloat(im*.72f+dep*1.55f,0,1);}return e;}
    private static float luma(BufferedImage i,int x,int y){int r=i.getRGB(x,y);return(.2126f*((r>>>16)&255)+.7152f*((r>>>8)&255)+.0722f*(r&255))/255f;}
    private static boolean[][] cleanMask(boolean[][]m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][]n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)if(m[yy][xx])c++;n[y][x]=m[y][x]?c>=3:c>=6;}m=n;}return m;}
    private static void removeTinyComponents(int[]a,int w,int h,int d,int min){boolean[]vis=new boolean[a.length];int[]q=new int[a.length];for(int i=0;i<a.length;i++){if(a[i]==0||vis[i])continue;int s=0,e=0;q[e++]=i;vis[i]=true;while(s<e){int p=q[s++],y=p/(w*d),rem=p-y*w*d,z=rem/w,x=rem-z*w;int[][]ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int[]v:ds){int nx=x+v[0],ny=y+v[1],nz=z+v[2];if(nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d)continue;int ni=index(w,d,nx,ny,nz);if(!vis[ni]&&a[ni]!=0){vis[ni]=true;q[e++]=ni;}}}if(e<min)for(int j=0;j<e;j++)a[q[j]]=0;}}
    private static void bridgeOneBlockGaps(int[]a,int w,int h,int d){int[]c=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=index(w,d,x,y,z);if(c[i]!=0)continue;int l=c[index(w,d,x-1,y,z)],r=c[index(w,d,x+1,y,z)],u=c[index(w,d,x,y+1,z)],dn=c[index(w,d,x,y-1,z)];if(l!=0&&r!=0)a[i]=l;else if(u!=0&&dn!=0)a[i]=u;}}
    private static void carveFrontOpening(int[]a,int w,int h,int d,Opening o){int y0=Math.max(1,h-1-o.y1),y1=Math.min(h-2,h-1-o.y0),x0=Math.max(1,o.x0+2),x1=Math.min(w-2,o.x1-2);for(int y=y0+1;y<y1-1;y++)for(int x=x0;x<=x1;x++)for(int z=0;z<Math.min(d,10);z++)a[index(w,d,x,y,z)]=0;}
    private static int toWorldZ(float normalizedDepth,int d){return clampInt(Math.round((1f-clampFloat(normalizedDepth,0,1))*(d-9)),1,d-5);}
    private static float avg(float[][]d,int x0,int y0,int x1,int y1){float s=0;int n=0;for(int y=Math.max(0,y0);y<=y1&&y<d.length;y++)for(int x=Math.max(0,x0);x<=x1&&x<d[0].length;x++){s+=d[y][x];n++;}return s/Math.max(1,n);}
    private static int id(Map<String,Integer>p,String b){return p.computeIfAbsent(b,k->p.size());}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[index(w,d,x,y,z)]=v;}
    private static int clampInt(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static float clampFloat(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private record Opening(int x0,int y0,int x1,int y1){
        boolean contains(int x,int y){return x>=x0&&x<=x1&&y>=y0&&y<=y1;}
        Opening bounded(int w,int h){return new Opening(clampInt(x0,2,w-3),clampInt(y0,2,h-3),clampInt(x1,2,w-3),clampInt(y1,2,h-3));}
    }
}
