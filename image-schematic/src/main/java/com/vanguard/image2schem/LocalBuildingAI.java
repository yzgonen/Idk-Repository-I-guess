package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntConsumer;

/**
 * Experimental fully-local architecture reconstructor.
 *
 * This is deliberately specialized for Minecraft buildings rather than a generic
 * image-to-pixel extruder. It estimates background, structural silhouette,
 * openings, a front facade, a walkable interior shell and a corridor behind the
 * dominant entrance. No network/API calls are used.
 */
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
        progress.accept(5);

        BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, targetWidth, targetHeight, null);
        g.dispose();
        progress.accept(10);

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
            progress.accept(10 + Math.round(15F * (y + 1) / targetHeight));
        }
        structure = smoothMask(structure, 2);
        progress.accept(28);

        Bounds bounds = boundsOf(structure, targetWidth, targetHeight);
        if (bounds == null) bounds = new Bounds(targetWidth / 8, targetHeight / 8, targetWidth * 7 / 8, targetHeight * 7 / 8);

        Opening opening = detectOpening(lum, structure, bounds, targetWidth, targetHeight);
        progress.accept(36);

        Map<String,Integer> palette = new LinkedHashMap<>();
        palette.put("minecraft:air", 0);
        int stone = id(palette, "minecraft:deepslate_tiles");
        int wall = id(palette, "minecraft:gray_concrete");
        int trim = id(palette, "minecraft:polished_deepslate");
        int floor = id(palette, "minecraft:smooth_stone");
        int light = id(palette, "minecraft:sea_lantern");
        int glass = id(palette, "minecraft:tinted_glass");

        int[] blocks = new int[targetWidth * targetHeight * depth];

        // Front facade: only detected architectural structure survives. Background is air.
        int facadeThickness = 2;
        for (int y = 0; y < targetHeight; y++) {
            int outY = targetHeight - 1 - y;
            for (int x = 0; x < targetWidth; x++) {
                if (!structure[y][x]) continue;
                if (opening.contains(x, y)) continue;
                int rgb = scaled.getRGB(x, y);
                int r=(rgb>>>16)&255, gg=(rgb>>>8)&255, b=rgb&255;
                int material = lum[y][x] < .24F ? stone : lum[y][x] > .72F ? trim : wall;
                if (b > r + 28 && b > gg + 18) material = glass;
                for (int z=0; z<facadeThickness; z++) set(blocks,targetWidth,targetHeight,depth,x,outY,z,material);
            }
            progress.accept(36 + Math.round(22F * (y + 1) / targetHeight));
        }

        // Architecture shell behind the facade. This turns the result into a usable building.
        int left = Math.max(1, bounds.minX());
        int right = Math.min(targetWidth - 2, bounds.maxX());
        int bottom = Math.max(1, targetHeight - 1 - bounds.maxY());
        int top = Math.min(targetHeight - 2, targetHeight - 1 - bounds.minY());
        int shellStart = facadeThickness;
        int shellEnd = depth - 1;

        for (int z=shellStart; z<=shellEnd; z++) {
            for (int x=left; x<=right; x++) {
                set(blocks,targetWidth,targetHeight,depth,x,bottom,z,floor);
                set(blocks,targetWidth,targetHeight,depth,x,top,z,trim);
            }
            for (int y=bottom; y<=top; y++) {
                set(blocks,targetWidth,targetHeight,depth,left,y,z,wall);
                set(blocks,targetWidth,targetHeight,depth,right,y,z,wall);
            }
            progress.accept(58 + Math.round(18F * (z - shellStart + 1) / Math.max(1, shellEnd-shellStart+1)));
        }

        // Dominant entrance becomes a real corridor instead of a dark rectangle.
        int ox0 = Math.max(left + 2, opening.minX());
        int ox1 = Math.min(right - 2, opening.maxX());
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
        progress.accept(84);

        // Add structural columns where the facade has strong vertical occupancy.
        int span = Math.max(1, bounds.maxY()-bounds.minY()+1);
        for (int x=left+2; x<=right-2; x++) {
            int count=0;
            for(int y=bounds.minY(); y<=bounds.maxY(); y++) if(structure[y][x] && !opening.contains(x,y)) count++;
            if(count > span*0.78 && ((x-left)%Math.max(5,(right-left)/8)==0)) {
                for(int z=0; z<Math.min(5,depth); z++) for(int y=bottom; y<=top; y++) set(blocks,targetWidth,targetHeight,depth,x,y,z,trim);
            }
        }
        progress.accept(92);

        // Back wall, but leave a centered service doorway for a believable connected interior.
        int doorW=Math.max(3,(right-left)/10), mid=(left+right)/2;
        for(int y=bottom; y<=top; y++) for(int x=left; x<=right; x++) {
            boolean serviceDoor = x>=mid-doorW/2 && x<=mid+doorW/2 && y<=bottom+Math.max(4,(top-bottom)/3);
            if(!serviceDoor) set(blocks,targetWidth,targetHeight,depth,x,y,depth-1,wall);
        }
        progress.accept(98);
        return new ImageConverter.Result(targetWidth,targetHeight,depth,blocks,palette);
    }

    private static int[] estimateBackground(BufferedImage img) {
        long r=0,g=0,b=0,n=0; int w=img.getWidth(),h=img.getHeight();
        int band=Math.max(2,Math.min(w,h)/16);
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) if(x<band||x>=w-band||y<band||y>=h-band){int rgb=img.getRGB(x,y);r+=(rgb>>>16)&255;g+=(rgb>>>8)&255;b+=rgb&255;n++;}
        return new int[]{(int)(r/Math.max(1,n)),(int)(g/Math.max(1,n)),(int)(b/Math.max(1,n))};
    }
    private static int colorDistance(int r,int g,int b,int r2,int g2,int b2){int dr=r-r2,dg=g-g2,db=b-b2;return (int)Math.sqrt(dr*dr+dg*dg+db*db);}
    private static int localEdge(BufferedImage i,int x,int y){int x2=Math.min(i.getWidth()-1,x+1),y2=Math.min(i.getHeight()-1,y+1);int a=i.getRGB(x,y),b=i.getRGB(x2,y),c=i.getRGB(x,y2);return rgbDistance(a,b)+rgbDistance(a,c);}
    private static int rgbDistance(int a,int b){int dr=((a>>>16)&255)-((b>>>16)&255),dg=((a>>>8)&255)-((b>>>8)&255),db=(a&255)-(b&255);return (Math.abs(dr)+Math.abs(dg)+Math.abs(db))/3;}
    private static boolean[][] smoothMask(boolean[][] m,int passes){int h=m.length,w=m[0].length;for(int p=0;p<passes;p++){boolean[][] n=new boolean[h][w];for(int y=0;y<h;y++)for(int x=0;x<w;x++){int c=0,t=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){t++;if(m[yy][xx])c++;}n[y][x]=c>=Math.max(3,t/2);}m=n;}return m;}
    private static Bounds boundsOf(boolean[][] m,int w,int h){int minX=w,minY=h,maxX=-1,maxY=-1;for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(m[y][x]){minX=Math.min(minX,x);minY=Math.min(minY,y);maxX=Math.max(maxX,x);maxY=Math.max(maxY,y);}return maxX<0?null:new Bounds(minX,minY,maxX,maxY);}
    private static Opening detectOpening(float[][] lum,boolean[][] structure,Bounds b,int w,int h){int cx=(b.minX()+b.maxX())/2;int searchW=Math.max(6,(b.maxX()-b.minX())*2/3);int minX=Math.max(b.minX(),cx-searchW/2),maxX=Math.min(b.maxX(),cx+searchW/2);int minY=b.minY()+Math.max(1,(b.maxY()-b.minY())/5),maxY=b.maxY();int bestX0=cx-2,bestX1=cx+2,bestY0=Math.max(minY,maxY-10),bestY1=maxY;int bestArea=0;for(int x0=minX;x0<maxX;x0++){int dark=0;for(int y=minY;y<=maxY;y++)if(lum[y][x0]<.27F)dark++;if(dark<Math.max(3,(maxY-minY)/4))continue;int x1=x0;while(x1<=maxX){int d=0;for(int y=minY;y<=maxY;y++)if(lum[y][x1]<.30F)d++;if(d<Math.max(3,(maxY-minY)/5))break;x1++;}int width=x1-x0;if(width>=3){int y0=maxY;for(int y=minY;y<=maxY;y++){int c=0;for(int x=x0;x<x1;x++)if(lum[y][x]<.32F)c++;if(c>=width*2/3){y0=y;break;}}int area=width*(maxY-y0+1);if(area>bestArea){bestArea=area;bestX0=x0;bestX1=x1-1;bestY0=y0;bestY1=maxY;}}x0=Math.max(x0,x1);}return new Opening(Math.max(b.minX()+1,bestX0),Math.max(b.minY()+1,bestY0),Math.min(b.maxX()-1,bestX1),Math.min(b.maxY(),bestY1));}
    private static int id(Map<String,Integer> p,String block){return p.computeIfAbsent(block,k->p.size());}
    private static void set(int[] a,int w,int h,int d,int x,int y,int z,int id){if(x<0||x>=w||y<0||y>=h||z<0||z>=d)return;a[x+z*w+y*w*d]=id;}
    private record Bounds(int minX,int minY,int maxX,int maxY){}
    private record Opening(int minX,int minY,int maxX,int maxY){boolean contains(int x,int y){return x>=minX&&x<=maxX&&y>=minY&&y<=maxY;}}
}
