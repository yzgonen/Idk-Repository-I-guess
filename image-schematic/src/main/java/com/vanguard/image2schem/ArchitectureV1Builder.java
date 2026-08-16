package com.vanguard.image2schem;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * v1 architecture-first reconstructor.
 * Neural depth is treated as evidence, then regularized into Minecraft-friendly planes.
 * Material selection is region based rather than one gray block for everything.
 */
public final class ArchitectureV1Builder {
    private ArchitectureV1Builder() {}

    public static ImageConverter.Result build(BufferedImage source, float[][] sourceDepth,
                                               int requestedWidth, int requestedDepth,
                                               IntConsumer progress) {
        int w = Math.max(64, Math.min(160, requestedWidth));
        int h = Math.max(32, Math.round(source.getHeight() * (w / (float) source.getWidth())));
        if (h > 120) { float f=120f/h; h=120; w=Math.max(64,Math.round(w*f)); }
        int d = Math.max(28, Math.min(72, requestedDepth));
        progress.accept(51);

        BufferedImage img = new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);
        Graphics2D g=img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source,0,0,w,h,null); g.dispose();

        float[][] depth = resizeDepth(sourceDepth,w,h);
        depth = edgeAwareSmooth(depth,3);
        if (shouldInvert(depth)) invert(depth);
        robustNormalize(depth);
        progress.accept(56);

        float[][] lum=luminance(img);
        float[][] edge=edgeStrength(img,depth);
        boolean[][] evidence=cleanupMask(buildEvidenceMask(img,depth,edge),2);
        Opening opening=detectOpening(lum,depth,edge).bounded(w,h);
        progress.accept(61);

        // Architectural plane regularization: large regions share stable Z levels.
        int[][] planeZ=fitPlaneField(depth,edge,evidence,d);
        smoothPlaneField(planeZ,evidence,3);
        progress.accept(66);

        // Region-based material classification and smoothing.
        int[][] materialClass=classifyMaterials(img,lum,edge);
        materialClass=majoritySmooth(materialClass,2);
        progress.accept(71);

        Map<String,Integer> palette=new LinkedHashMap<>();
        palette.put("minecraft:air",0);
        int gray=id(palette,"minecraft:gray_concrete");
        int lightGray=id(palette,"minecraft:light_gray_concrete");
        int dark=id(palette,"minecraft:deepslate_tiles");
        int trim=id(palette,"minecraft:polished_deepslate");
        int stone=id(palette,"minecraft:stone_bricks");
        int smoothStone=id(palette,"minecraft:smooth_stone");
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
        int[] classToBlock={gray,lightGray,dark,trim,stone,smoothStone,black,white,brown,orange,yellow,red,blue,glass,iron};
        int[] blocks=new int[w*h*d];

        // Visible facade surfaces, snapped to fitted planes instead of raw depth terraces.
        for(int sy=0;sy<h;sy++){
            int wy=h-1-sy;
            for(int x=0;x<w;x++){
                if(!evidence[sy][x]) continue;
                if(opening.contains(x,sy) && sy>opening.y0+1) continue;
                int z=planeZ[sy][x];
                if(z<0) continue;
                int mat=classToBlock[clamp(materialClass[sy][x],0,classToBlock.length-1)];
                int thick=edge[sy][x]>.58f?3:2;
                for(int dz=0;dz<thick;dz++) set(blocks,w,h,d,x,wy,z+dz,mat);
            }
            progress.accept(71+Math.round(7f*(sy+1)/h));
        }

        // Replace depth-terraced floor with one deliberate perspective ramp.
        buildCleanRamp(blocks,w,h,d,depth,opening,smoothStone,trim);
        progress.accept(81);

        // Build a proper portal + interior corridor independently of the noisy facade.
        buildPortalAndInterior(blocks,w,h,d,opening,depth,dark,trim,gray,smoothStone,light);
        progress.accept(86);

        // Extract long structural lines and snap them into clean beams / columns.
        addStructuralLines(blocks,w,h,d,planeZ,edge,evidence,opening,stone,trim);
        progress.accept(90);

        // Symmetry reinforcement only around the main portal zone.
        reinforcePortalSymmetry(blocks,w,h,d,opening);
        progress.accept(93);

        // Material accents from source: bright lights and warm hazard/industrial accents.
        addAccents(blocks,w,h,d,img,planeZ,evidence,light,yellow,orange,red,iron);
        progress.accept(96);

        removeTinyComponents(blocks,w,h,d,14);
        bridgeOneBlockGaps(blocks,w,h,d);
        carvePortal(blocks,w,h,d,opening);
        progress.accept(99);
        return new ImageConverter.Result(w,h,d,blocks,palette);
    }

    private static int[][] fitPlaneField(float[][] depth,float[][] edge,boolean[][] keep,int d){
        int h=depth.length,w=depth[0].length; int[][] z=new int[h][w]; for(int[]r:z)Arrays.fill(r,-1);
        int tile=8;
        for(int ty=0;ty<h;ty+=tile)for(int tx=0;tx<w;tx+=tile){
            int[] hist=new int[d]; int count=0;
            for(int y=ty;y<Math.min(h,ty+tile);y++)for(int x=tx;x<Math.min(w,tx+tile);x++)if(keep[y][x]){
                int q=toZ(depth[y][x],d); hist[q]++;count++;
            }
            if(count==0)continue;
            int best=1;for(int i=2;i<d-2;i++)if(hist[i]>hist[best])best=i;
            for(int y=ty;y<Math.min(h,ty+tile);y++)for(int x=tx;x<Math.min(w,tx+tile);x++)if(keep[y][x]){
                int raw=toZ(depth[y][x],d);
                z[y][x]=edge[y][x]>.42f?raw:(Math.abs(raw-best)<=5?best:raw);
            }
        }
        return z;
    }

    private static void smoothPlaneField(int[][]z,boolean[][]keep,int passes){int h=z.length,w=z[0].length;for(int p=0;p<passes;p++){int[][]n=new int[h][w];for(int[]r:n)Arrays.fill(r,-1);for(int y=0;y<h;y++)for(int x=0;x<w;x++){if(!keep[y][x]||z[y][x]<0)continue;int[]v=new int[9];int k=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)if(z[yy][xx]>=0)v[k++]=z[yy][xx];if(k==0)n[y][x]=z[y][x];else{Arrays.sort(v,0,k);n[y][x]=v[k/2];}}z=n;}}

    private static int[][] classifyMaterials(BufferedImage img,float[][]lum,float[][]edge){int h=img.getHeight(),w=img.getWidth();int[][]m=new int[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=img.getRGB(x,y),r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255;int max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));float sat=(max-min)/255f, l=lum[y][x];int c;if(max>225&&sat<.10)c=7;else if(l<.12)c=6;else if(l<.24)c=2;else if(sat<.08&&l>.72)c=1;else if(sat<.12)c=edge[y][x]>.45?3:0;else if(r>150&&g<95&&b<85)c=11;else if(r>175&&g>115&&b<80)c=9;else if(r>160&&g>145&&b<95)c=10;else if(r>110&&g>75&&b<65)c=8;else if(b>r*1.22&&b>g*1.10)c=12;else if(b>r+18&&b>g+12)c=13;else if(l>.62&&sat<.18)c=14;else c=4;m[y][x]=c;}return m;}

    private static int[][] majoritySmooth(int[][]a,int passes){int h=a.length,w=a[0].length;for(int p=0;p<passes;p++){int[][]n=new int[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int[]c=new int[15];for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)c[a[yy][xx]]++;int best=0;for(int i=1;i<c.length;i++)if(c[i]>c[best])best=i;n[y][x]=best;}a=n;}return a;}

    private static void buildCleanRamp(int[]a,int w,int h,int d,float[][]depth,Opening o,int floor,int trim){int yStart=Math.max(o.y1,h*58/100),cx=(o.x0+o.x1)/2;int baseZ=medianZ(depth,Math.max(0,o.x0),Math.min(h-1,o.y1),Math.min(w-1,o.x1),h-1,d);int rows=Math.max(4,h-yStart);for(int sy=yStart;sy<h;sy++){float t=(sy-yStart)/(float)Math.max(1,rows-1);int half=Math.round((o.x1-o.x0)*.55f+(w*.30f)*t);int wy=h-1-sy;int z=clamp(baseZ-Math.round(t*Math.min(9,d/5)),1,d-4);for(int x=Math.max(0,cx-half);x<=Math.min(w-1,cx+half);x++){set(a,w,h,d,x,wy,z,floor);if(x==cx-half||x==cx+half)set(a,w,h,d,x,wy,z,trim);}}}

    private static void buildPortalAndInterior(int[]a,int w,int h,int d,Opening o,float[][]depth,int dark,int trim,int wall,int floor,int light){int x0=clamp(o.x0,3,w-4),x1=clamp(o.x1,3,w-4),yb=Math.max(1,h-1-o.y1),yt=Math.min(h-2,h-1-o.y0);if(x1-x0<7||yt-yb<7)return;int start=clamp(medianZ(depth,o.x0,o.y0,o.x1,o.y1,d),3,d-16),end=Math.min(d-4,start+Math.max(14,d/2));int fw=3;for(int z=start-2;z<=start+2;z++){for(int y=yb;y<=yt;y++)for(int k=0;k<fw;k++){set(a,w,h,d,x0-k,y,z,trim);set(a,w,h,d,x1+k,y,z,trim);}for(int x=x0-fw+1;x<=x1+fw-1;x++)for(int k=0;k<fw;k++)set(a,w,h,d,x,yt+k,z,trim);}for(int z=start;z<=end;z++){for(int x=x0;x<=x1;x++){set(a,w,h,d,x,yb,z,floor);set(a,w,h,d,x,yt,z,dark);}for(int y=yb;y<=yt;y++){set(a,w,h,d,x0,y,z,wall);set(a,w,h,d,x1,y,z,wall);}if((z-start)%6==3){int m=(x0+x1)/2;set(a,w,h,d,m,yt-1,z,light);set(a,w,h,d,m+1,yt-1,z,light);}}// back bulkhead
        for(int y=yb;y<=yt;y++)for(int x=x0;x<=x1;x++){boolean door=x>(x0+x1)/2-2&&x<(x0+x1)/2+2&&y<yb+5;if(!door)set(a,w,h,d,x,y,end,wall);} }

    private static void addStructuralLines(int[]a,int w,int h,int d,int[][]planeZ,float[][]edge,boolean[][]keep,Opening o,int stone,int trim){for(int x=2;x<w-2;x++){int run=0,best=0,end=0;for(int y=2;y<h-2;y++){if(keep[y][x]&&edge[y][x]>.38f&&!o.contains(x,y)){run++;if(run>best){best=run;end=y;}}else run=0;}if(best>h*.28f){int y0=end-best+1,z=medianPlane(planeZ,x,y0,x,end);if(z>=0){for(int xx=x-1;xx<=x+1;xx++)for(int sy=y0;sy<=end;sy++)for(int dz=0;dz<3;dz++)set(a,w,h,d,xx,h-1-sy,z+dz,trim);}x+=2;}}for(int y=2;y<h*2/3;y++){int run=0,best=0,end=0;for(int x=2;x<w-2;x++){if(keep[y][x]&&edge[y][x]>.40f&&!o.contains(x,y)){run++;if(run>best){best=run;end=x;}}else run=0;}if(best>w*.30f){int x0=end-best+1,z=medianPlane(planeZ,x0,y,end,y);if(z>=0)for(int yy=y-1;yy<=y+1;yy++)for(int x=x0;x<=end;x++)for(int dz=0;dz<2;dz++)set(a,w,h,d,x,h-1-yy,z+dz,stone);y+=2;}}}

    private static void reinforcePortalSymmetry(int[]a,int w,int h,int d,Opening o){int cx=(o.x0+o.x1)/2,rad=Math.min(cx, w-1-cx);int y0=Math.max(0,h-1-o.y1-5),y1=Math.min(h-1,h-1-o.y0+6);for(int y=y0;y<=y1;y++)for(int z=0;z<Math.min(d,18);z++)for(int dx=1;dx<rad;dx++){int l=index(w,d,cx-dx,y,z),r=index(w,d,cx+dx,y,z);if(a[l]!=0&&a[r]==0)a[r]=a[l];else if(a[r]!=0&&a[l]==0)a[l]=a[r];}}

    private static void addAccents(int[]a,int w,int h,int d,BufferedImage img,int[][]z,boolean[][]keep,int light,int yellow,int orange,int red,int iron){for(int sy=1;sy<h-1;sy++)for(int x=1;x<w-1;x++){if(!keep[sy][x]||z[sy][x]<0)continue;int rgb=img.getRGB(x,sy),r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));float sat=(max-min)/255f;int mat=0;if(max>235&&sat<.14)mat=light;else if(r>195&&g>155&&b<95)mat=yellow;else if(r>190&&g>95&&g<165&&b<80)mat=orange;else if(r>180&&g<80&&b<70)mat=red;else if(max>175&&sat<.10)mat=iron;if(mat!=0){int wy=h-1-sy;set(a,w,h,d,x,wy,z[sy][x],mat);}}}

    private static boolean[][] buildEvidenceMask(BufferedImage img,float[][]depth,float[][]edge){int h=img.getHeight(),w=img.getWidth();boolean[][]m=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=img.getRGB(x,y),r=(rgb>>>16)&255,g=(rgb>>>8)&255,b=rgb&255,max=Math.max(r,Math.max(g,b)),min=Math.min(r,Math.min(g,b));float sat=(max-min)/255f;boolean sky=b>r*1.28f&&b>g*1.15f&&y<h*.65f;boolean emptyDark=max<22&&edge[y][x]<.035f&&depth[y][x]>.80f;boolean flatFar=depth[y][x]>.91f&&edge[y][x]<.05f;m[y][x]=!sky&&!emptyDark&&!flatFar&&(edge[y][x]>.045f||depth[y][x]<.85f||sat>.10f);}return m;}

    private static Opening detectOpening(float[][]lum,float[][]depth,float[][]edge){int h=lum.length,w=lum[0].length,cx=w/2;Opening best=new Opening(cx-w/7,h/3,cx+w/7,h*4/5);float bs=-1e9f;for(int ww=Math.max(10,w/6);ww<=w/2;ww+=Math.max(4,w/20))for(int hh=Math.max(12,h/4);hh<=h*3/5;hh+=Math.max(4,h/18)){int x0=cx-ww/2,x1=cx+ww/2;for(int y0=h/5;y0<h*3/5;y0+=Math.max(2,h/30)){int y1=Math.min(h-2,y0+hh);float dark=0,deep=0,border=0;int n=0;for(int y=y0;y<=y1;y+=2)for(int x=x0;x<=x1;x+=2){dark+=1-lum[y][x];deep+=depth[y][x];n++;}for(int x=x0;x<=x1;x++){border+=edge[y0][x]+edge[y1][x];}for(int y=y0;y<=y1;y++){border+=edge[y][x0]+edge[y][x1];}float s=.48f*dark/n+.18f*deep/n+.34f*border/Math.max(1,2*ww+2*hh);if(s>bs){bs=s;best=new Opening(x0,y0,x1,y1);}}}return best;}

    private static float[][] resizeDepth(float[][]src,int w,int h){int sh=src.length,sw=src[0].length;float[][]o=new float[h][w];for(int y=0;y<h;y++){float sy=y*(sh-1f)/Math.max(1,h-1);int y0=(int)sy,y1=Math.min(sh-1,y0+1);float fy=sy-y0;for(int x=0;x<w;x++){float sx=x*(sw-1f)/Math.max(1,w-1);int x0=(int)sx,x1=Math.min(sw-1,x0+1);float fx=sx-x0;o[y][x]=(src[y0][x0]*(1-fx)+src[y0][x1]*fx)*(1-fy)+(src[y1][x0]*(1-fx)+src[y1][x1]*fx)*fy;}}return o;}
    private static float[][] edgeAwareSmooth(float[][]s,int passes){int h=s.length,w=s[0].length;for(int p=0;p<passes;p++){float[][]n=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){float c=s[y][x],sum=0,wt=0;for(int yy=Math.max(0,y-2);yy<=Math.min(h-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(w-1,x+2);xx++){float q=Math.abs(s[yy][xx]-c)<.075f?1f:.08f;sum+=s[yy][xx]*q;wt+=q;}n[y][x]=sum/wt;}s=n;}return s;}
    private static boolean shouldInvert(float[][]d){int h=d.length,w=d[0].length;return avg(d,w/3,h*2/3,w*2/3,h-1)>avg(d,w/3,0,w*2/3,h/3);}
    private static void invert(float[][]d){for(int y=0;y<d.length;y++)for(int x=0;x<d[0].length;x++)d[y][x]=1-d[y][x];}
    private static void robustNormalize(float[][]d){int h=d.length,w=d[0].length,n=h*w;float[]v=new float[n];int k=0;for(float[]r:d)for(float q:r)v[k++]=q;Arrays.sort(v);float lo=v[(int)(n*.025)],hi=v[Math.min(n-1,(int)(n*.975))],range=Math.max(1e-5f,hi-lo);for(int y=0;y<h;y++)for(int x=0;x<w;x++)d[y][x]=clampf((d[y][x]-lo)/range,0,1);}
    private static float[][] luminance(BufferedImage i){int h=i.getHeight(),w=i.getWidth();float[][]l=new float[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int rgb=i.getRGB(x,y);l[y][x]=(.2126f*((rgb>>>16)&255)+.7152f*((rgb>>>8)&255)+.0722f*(rgb&255))/255f;}return l;}
    private static float[][] edgeStrength(BufferedImage i,float[][]d){int h=i.getHeight(),w=i.getWidth();float[][]e=new float[h][w];for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){float im=Math.abs(luma(i,x+1,y)-luma(i,x-1,y))+Math.abs(luma(i,x,y+1)-luma(i,x,y-1));float dep=Math.abs(d[y][x+1]-d[y][x-1])+Math.abs(d[y+1][x]-d[y-1][x]);e[y][x]=clampf(im*.72f+dep*1.65f,0,1);}return e;}
    private static float luma(BufferedImage i,int x,int y){int r=i.getRGB(x,y);return(.2126f*((r>>>16)&255)+.7152f*((r>>>8)&255)+.0722f*(r&255))/255f;}
    private static boolean[][] cleanupMask(boolean[][]m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][]n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)if(m[yy][xx])c++;n[y][x]=m[y][x]?c>=3:c>=6;}m=n;}return m;}

    private static void removeTinyComponents(int[]a,int w,int h,int d,int min){boolean[]vis=new boolean[a.length];int[]q=new int[a.length];for(int i=0;i<a.length;i++){if(a[i]==0||vis[i])continue;int s=0,e=0;q[e++]=i;vis[i]=true;while(s<e){int p=q[s++],y=p/(w*d),rem=p-y*w*d,z=rem/w,x=rem-z*w;int[][]ds={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int[]v:ds){int nx=x+v[0],ny=y+v[1],nz=z+v[2];if(nx<0||nx>=w||ny<0||ny>=h||nz<0||nz>=d)continue;int ni=index(w,d,nx,ny,nz);if(!vis[ni]&&a[ni]!=0){vis[ni]=true;q[e++]=ni;}}}if(e<min)for(int j=0;j<e;j++)a[q[j]]=0;}}
    private static void bridgeOneBlockGaps(int[]a,int w,int h,int d){int[]c=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int i=index(w,d,x,y,z);if(c[i]!=0)continue;int l=c[index(w,d,x-1,y,z)],r=c[index(w,d,x+1,y,z)],u=c[index(w,d,x,y+1,z)],dn=c[index(w,d,x,y-1,z)];if(l!=0&&r!=0)a[i]=l;else if(u!=0&&dn!=0)a[i]=u;}}
    private static void carvePortal(int[]a,int w,int h,int d,Opening o){int y0=Math.max(1,h-1-o.y1),y1=Math.min(h-2,h-1-o.y0),x0=Math.max(1,o.x0+3),x1=Math.min(w-2,o.x1-3);for(int y=y0+1;y<y1-2;y++)for(int x=x0;x<=x1;x++)for(int z=0;z<Math.min(d,10);z++)a[index(w,d,x,y,z)]=0;}

    private static int medianZ(float[][]dep,int x0,int y0,int x1,int y1,int d){int cap=Math.max(1,(x1-x0+1)*(y1-y0+1));int[]v=new int[cap];int k=0;for(int y=Math.max(0,y0);y<=Math.min(dep.length-1,y1);y++)for(int x=Math.max(0,x0);x<=Math.min(dep[0].length-1,x1);x++)v[k++]=toZ(dep[y][x],d);Arrays.sort(v,0,k);return v[Math.max(0,k/2)];}
    private static int medianPlane(int[][]z,int x0,int y0,int x1,int y1){int[]v=new int[Math.max(1,(x1-x0+1)*(y1-y0+1))];int k=0;for(int y=Math.max(0,y0);y<=Math.min(z.length-1,y1);y++)for(int x=Math.max(0,x0);x<=Math.min(z[0].length-1,x1);x++)if(z[y][x]>=0)v[k++]=z[y][x];if(k==0)return-1;Arrays.sort(v,0,k);return v[k/2];}
    private static int toZ(float depth,int d){return clamp(Math.round((1f-clampf(depth,0,1))*(d-8)),1,d-4);}
    private static float avg(float[][]d,int x0,int y0,int x1,int y1){float s=0;int n=0;for(int y=y0;y<=y1&&y<d.length;y++)for(int x=x0;x<=x1&&x<d[0].length;x++){s+=d[y][x];n++;}return s/Math.max(1,n);}
    private static int id(Map<String,Integer>p,String b){return p.computeIfAbsent(b,k->p.size());}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static void set(int[]a,int w,int h,int d,int x,int y,int z,int v){if(x>=0&&x<w&&y>=0&&y<h&&z>=0&&z<d)a[index(w,d,x,y,z)]=v;}
    private static int clamp(int v,int lo,int hi){return Math.max(lo,Math.min(hi,v));}
    private static float clampf(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}

    private record Opening(int x0,int y0,int x1,int y1){boolean contains(int x,int y){return x>=x0&&x<=x1&&y>=y0&&y<=y1;}Opening bounded(int w,int h){return new Opening(clamp(x0,1,w-2),clamp(y0,1,h-2),clamp(x1,1,w-2),clamp(y1,1,h-2));}}
}
