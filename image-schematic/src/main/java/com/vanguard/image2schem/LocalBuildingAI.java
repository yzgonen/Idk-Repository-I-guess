package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/** Experimental fully-local Minecraft architecture reconstructor. */
public final class LocalBuildingAI {
    private LocalBuildingAI() {}

    public static ImageConverter.Result reconstruct(Path imagePath, int targetWidth, int requestedDepth, IntConsumer progress) throws IOException {
        progress.accept(1);
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) throw new IOException("Unsupported or unreadable image: " + imagePath.getFileName());

        targetWidth = Math.max(32, Math.min(128, targetWidth));
        int targetHeight = Math.max(24, Math.round(src.getHeight() * (targetWidth / (float) src.getWidth())));
        if (targetHeight > 112) {
            float f = 112F / targetHeight;
            targetHeight = 112;
            targetWidth = Math.max(32, Math.round(targetWidth * f));
        }
        int depth = Math.max(16, Math.min(48, requestedDepth));
        progress.accept(4);

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        progress.accept(8);

        int[] bg = estimateBackground(scaled);
        boolean[][] structure = new boolean[targetHeight][targetWidth];
        float[][] lum = new float[targetHeight][targetWidth];
        for (int y = 0; y < targetHeight; y++) {
            for (int x = 0; x < targetWidth; x++) {
                int rgb = scaled.getRGB(x, y);
                int r = (rgb >>> 16) & 255, gg = (rgb >>> 8) & 255, b = rgb & 255;
                lum[y][x] = (0.2126F * r + 0.7152F * gg + 0.0722F * b) / 255F;
                int d = colorDistance(r, gg, b, bg[0], bg[1], bg[2]);
                boolean edge = localEdge(scaled, x, y) > 30;
                structure[y][x] = d > 42 || edge;
            }
            progress.accept(8 + Math.round(10F * (y + 1) / targetHeight));
        }

        // Multi-scale cleanup: this is real work, not a fake delay.
        structure = smoothMask(structure, 1); progress.accept(20);
        structure = bridgeGaps(structure); progress.accept(23);
        structure = smoothMask(structure, 1); progress.accept(26);

        Bounds bounds = boundsOf(structure, targetWidth, targetHeight);
        if (bounds == null) bounds = new Bounds(targetWidth / 8, targetHeight / 8, targetWidth * 7 / 8, targetHeight * 7 / 8);
        Opening opening = detectOpening(lum, structure, bounds, targetWidth, targetHeight);
        progress.accept(31);

        Map<String,Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        int stone = id(palette, "minecraft:deepslate_tiles");
        int wall = id(palette, "minecraft:gray_concrete");
        int trim = id(palette, "minecraft:polished_deepslate");
        int floor = id(palette, "minecraft:smooth_stone");
        int light = id(palette, "minecraft:sea_lantern");
        int glass = id(palette, "minecraft:tinted_glass");
        int[] blocks = new int[targetWidth * targetHeight * depth];

        int facadeThickness = 2;
        for (int y = 0; y < targetHeight; y++) {
            int outY = targetHeight - 1 - y;
            for (int x = 0; x < targetWidth; x++) {
                if (!structure[y][x] || opening.contains(x, y)) continue;
                int rgb = scaled.getRGB(x, y);
                int r=(rgb>>>16)&255, gg=(rgb>>>8)&255, b=rgb&255;
                int material = lum[y][x] < .24F ? stone : lum[y][x] > .72F ? trim : wall;
                if (b > r + 28 && b > gg + 18) material = glass;
                for (int z=0; z<facadeThickness; z++) set(blocks,targetWidth,targetHeight,depth,x,outY,z,material);
            }
            progress.accept(31 + Math.round(15F * (y + 1) / targetHeight));
        }

        int left = Math.max(1, bounds.minX()), right = Math.min(targetWidth - 2, bounds.maxX());
        int bottom = Math.max(1, targetHeight - 1 - bounds.maxY());
        int top = Math.min(targetHeight - 2, targetHeight - 1 - bounds.minY());
        int shellStart = facadeThickness, shellEnd = depth - 1;

        for (int z=shellStart; z<=shellEnd; z++) {
            for (int x=left; x<=right; x++) {
                set(blocks,targetWidth,targetHeight,depth,x,bottom,z,floor);
                set(blocks,targetWidth,targetHeight,depth,x,top,z,trim);
            }
            for (int y=bottom; y<=top; y++) {
                set(blocks,targetWidth,targetHeight,depth,left,y,z,wall);
                set(blocks,targetWidth,targetHeight,depth,right,y,z,wall);
            }
            progress.accept(46 + Math.round(12F * (z-shellStart+1) / Math.max(1,shellEnd-shellStart+1)));
        }

        int ox0 = Math.max(left + 2, opening.minX()), ox1 = Math.min(right - 2, opening.maxX());
        int oyBottom = Math.max(bottom + 1, targetHeight - 1 - opening.maxY());
        int oyTop = Math.min(top - 1, targetHeight - 1 - opening.minY());
        int corridorEnd = Math.max(shellStart + 6, depth - 4);
        if (ox1 - ox0 >= 3 && oyTop - oyBottom >= 4) {
            for (int z=shellStart; z<=corridorEnd; z++) {
                for (int x=ox0; x<=ox1; x++) {
                    set(blocks,targetWidth,targetHeight,depth,x,oyBottom,z,floor);
                    set(blocks,targetWidth,targetHeight,depth,x,oyTop,z,trim);
                }
                for (int y=oyBottom; y<=oyTop; y++) {
                    set(blocks,targetWidth,targetHeight,depth,ox0,y,z,stone);
                    set(blocks,targetWidth,targetHeight,depth,ox1,y,z,stone);
                }
                if ((z-shellStart)%6==3) {
                    int mid=(ox0+ox1)/2;
                    set(blocks,targetWidth,targetHeight,depth,mid,oyTop,z,light);
                    if (mid+1<ox1) set(blocks,targetWidth,targetHeight,depth,mid+1,oyTop,z,light);
                }
            }
        }
        progress.accept(64);

        // Build strong columns from long vertical lines in the reference.
        int span = Math.max(1,bounds.maxY()-bounds.minY()+1);
        for (int x=left+2; x<=right-2; x++) {
            int count=0;
            for(int y=bounds.minY(); y<=bounds.maxY(); y++) if(structure[y][x] && !opening.contains(x,y)) count++;
            if(count > span*0.78 && ((x-left)%Math.max(5,(right-left)/8)==0)) {
                for(int z=0; z<Math.min(5,depth); z++) for(int y=bottom; y<=top; y++) set(blocks,targetWidth,targetHeight,depth,x,y,z,trim);
            }
        }
        progress.accept(69);

        // Back wall with service doorway.
        int doorW=Math.max(3,(right-left)/10), mid=(left+right)/2;
        for(int y=bottom; y<=top; y++) for(int x=left; x<=right; x++) {
            boolean serviceDoor = x>=mid-doorW/2 && x<=mid+doorW/2 && y<=bottom+Math.max(4,(top-bottom)/3);
            if(!serviceDoor) set(blocks,targetWidth,targetHeight,depth,x,y,depth-1,wall);
        }
        progress.accept(73);

        // Refinement pass 1: remove isolated facade noise.
        removeIsolated(blocks,targetWidth,targetHeight,depth,0,Math.min(5,depth-1));
        progress.accept(80);
        // Refinement pass 2: reinforce major floor/ceiling/wall planes after cleanup.
        reinforceShell(blocks,targetWidth,targetHeight,depth,left,right,bottom,top,shellStart,shellEnd,floor,wall,trim);
        progress.accept(86);
        // Refinement pass 3: carve the entrance and corridor so it is actually walkable.
        carveCorridor(blocks,targetWidth,targetHeight,depth,ox0,ox1,oyBottom,oyTop,shellStart,corridorEnd);
        progress.accept(91);
        // Refinement pass 4: smooth ugly single-block spikes in the whole volume.
        removeSpikes(blocks,targetWidth,targetHeight,depth);
        progress.accept(96);
        // Final structural validation/reinforcement.
        reinforceShell(blocks,targetWidth,targetHeight,depth,left,right,bottom,top,shellStart,shellEnd,floor,wall,trim);
        progress.accept(99);

        return new ImageConverter.Result(targetWidth,targetHeight,depth,blocks,palette);
    }

    private static boolean[][] bridgeGaps(boolean[][] m){int h=m.length,w=m[0].length;boolean[][] n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){boolean v=m[y][x];if(!v&&x>0&&x<w-1&&m[y][x-1]&&m[y][x+1])v=true;if(!v&&y>0&&y<h-1&&m[y-1][x]&&m[y+1][x])v=true;n[y][x]=v;}return n;}
    private static void removeIsolated(int[] a,int w,int h,int d,int z0,int z1){int[] copy=a.clone();for(int y=1;y<h-1;y++)for(int z=Math.max(0,z0);z<=Math.min(d-1,z1);z++)for(int x=1;x<w-1;x++){int idx=index(w,d,x,y,z);if(copy[idx]==0)continue;int neighbors=0;int[][] q={{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};for(int[]v:q){int zz=z+v[2];if(zz>=0&&zz<d&&copy[index(w,d,x+v[0],y+v[1],zz)]!=0)neighbors++;}if(neighbors<=1)a[idx]=0;}}
    private static void removeSpikes(int[] a,int w,int h,int d){int[] copy=a.clone();for(int y=1;y<h-1;y++)for(int z=1;z<d-1;z++)for(int x=1;x<w-1;x++){int idx=index(w,d,x,y,z);if(copy[idx]==0)continue;int n=0;if(copy[index(w,d,x+1,y,z)]!=0)n++;if(copy[index(w,d,x-1,y,z)]!=0)n++;if(copy[index(w,d,x,y+1,z)]!=0)n++;if(copy[index(w,d,x,y-1,z)]!=0)n++;if(copy[index(w,d,x,y,z+1)]!=0)n++;if(copy[index(w,d,x,y,z-1)]!=0)n++;if(n==0)a[idx]=0;}}
    private static void reinforceShell(int[] a,int w,int h,int d,int left,int right,int bottom,int top,int z0,int z1,int floor,int wall,int trim){for(int z=z0;z<=z1;z++){for(int x=left;x<=right;x++){set(a,w,h,d,x,bottom,z,floor);set(a,w,h,d,x,top,z,trim);}for(int y=bottom;y<=top;y++){set(a,w,h,d,left,y,z,wall);set(a,w,h,d,right,y,z,wall);}}}
    private static void carveCorridor(int[] a,int w,int h,int d,int x0,int x1,int y0,int y1,int z0,int z1){if(x1-x0<3||y1-y0<4)return;for(int z=z0;z<=Math.min(d-1,z1);z++)for(int y=y0+1;y<y1;y++)for(int x=x0+1;x<x1;x++)a[index(w,d,x,y,z)]=0;}
    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
    private static int[] estimateBackground(BufferedImage img){long r=0,g=0,b=0,n=0;int w=img.getWidth(),h=img.getHeight(),band=Math.max(2,Math.min(w,h)/16);for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(x<band||x>=w-band||y<band||y>=h-band){int rgb=img.getRGB(x,y);r+=(rgb>>>16)&255;g+=(rgb>>>8)&255;b+=rgb&255;n++;}return new int[]{(int)(r/Math.max(1,n)),(int)(g/Math.max(1,n)),(int)(b/Math.max(1,n))};}
    private static int colorDistance(int r,int g,int b,int r2,int g2,int b2){int dr=r-r2,dg=g-g2,db=b-b2;return (int)Math.sqrt(dr*dr+dg*dg+db*db);}
    private static int localEdge(BufferedImage i,int x,int y){int x2=Math.min(i.getWidth()-1,x+1),y2=Math.min(i.getHeight()-1,y+1);int a=i.getRGB(x,y),b=i.getRGB(x2,y),c=i.getRGB(x,y2);return rgbDistance(a,b)+rgbDistance(a,c);}
    private static int rgbDistance(int a,int b){int dr=((a>>>16)&255)-((b>>>16)&255),dg=((a>>>8)&255)-((b>>>8)&255),db=(a&255)-(b&255);return (Math.abs(dr)+Math.abs(dg)+Math.abs(db))/3;}
    private static boolean[][] smoothMask(boolean[][] m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][] n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0,t=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){t++;if(m[yy][xx])c++;}n[y][x]=c>=Math.max(3,t/2);}m=n;}return m;}
    private static Bounds boundsOf(boolean[][] m,int w,int h){int minX=w,minY=h,maxX=-1,maxY=-1;for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(m[y][x]){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}return maxX<0?null:new Bounds(minX,minY,maxX,maxY);}
    private static Opening detectOpening(float[][] lum,boolean[][] structure,Bounds b,int w,int h){int cx=(b.minX()+b.maxX())/2;int searchW=Math.max(6,(b.maxX()-b.minX())*2/3);int minX=Math.max(b.minX(),cx-searchW/2),maxX=Math.min(b.maxX(),cx+searchW/2);int minY=b.minY()+Math.max(1,(b.maxY()-b.minY())/5),maxY=b.maxY();int bestX0=cx-2,bestX1=cx+2,bestY0=Math.max(minY,maxY-10),bestY1=maxY,bestArea=0;for(int x0=minX;x0<maxX;x0++){int dark=0;for(int y=minY;y<=maxY;y++)if(lum[y][x0]<.27F)dark++;if(dark<Math.max(3,(maxY-minY)/4))continue;int x1=x0;while(x1<=maxX){int dd=0;for(int y=minY;y<=maxY;y++)if(lum[y][x1]<.30F)dd++;if(dd<Math.max(3,(maxY-minY)/5))break;x1++;}int width=x1-x0;if(width>=3){int y0=maxY;for(int y=minY;y<=maxY;y++){int c=0;for(int x=x0;x<x1;x++)if(lum[y][x]<.32F)c++;if(c>=width*2/3){y0=y;break;}}int area=width*(maxY-y0+1);if(area>bestArea){bestArea=area;bestX0=x0;bestX1=x1-1;bestY0=y0;bestY1=maxY;}}x0=Math.max(x0,x1);}return new Opening(Math.max(b.minX()+1,bestX0),Math.max(b.minY()+1,bestY0),Math.min(b.maxX()-1,bestX1),Math.min(b.maxY(),bestY1));}
    private static int id(Map<String,Integer> p,String block){return p.computeIfAbsent(block,k->p.size());}
    private static void set(int[] a,int w,int h,int d,int x,int y,int z,int id){if(x<0||x>=w||y<0||y>=h||z<0||z>=d)return;a[index(w,d,x,y,z)]=id;}
    private record Bounds(int minX,int minY,int maxX,int maxY){}
    private record Opening(int minX,int minY,int maxX,int maxY){boolean contains(int x,int y){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY;}}
}
