package com.vanguard.image2schem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.IntConsumer;

/** Combines a real neural monocular-depth model with the Minecraft architecture engine. */
public final class NeuralBuildingAI {
    private NeuralBuildingAI() {}

    public static ImageConverter.Result reconstruct(Path imagePath, int targetWidth, int requestedDepth, IntConsumer progress) throws IOException {
        try {
            progress.accept(1);
            BufferedImage src=ImageIO.read(imagePath.toFile());
            if(src==null) throw new IOException("Unsupported image");

            // Real neural-network inference. First run also downloads the ~99 MB model once.
            float[][] depth=NeuralDepthAI.estimate(src, p -> progress.accept(Math.max(1, Math.min(50, p))));
            progress.accept(50);

            // Geometry reconstruction remains Minecraft-specific; map its 1..99 range into 51..93.
            ImageConverter.Result base=LocalBuildingAI.reconstruct(imagePath,targetWidth,requestedDepth,
                    p -> progress.accept(51 + Math.round(Math.max(0,Math.min(99,p))*42f/99f)));
            progress.accept(94);

            int w=base.width(),h=base.height(),d=base.length();
            int[] blocks=base.paletteIds().clone();
            int[] original=base.paletteIds();
            int maxRelief=Math.max(2,Math.min(10,d/4));

            // Neural depth reshapes only the visible facade. The procedural shell/interior stays stable.
            for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
                int sy=Math.min(src.getHeight()-1,Math.round((h-1-y)*(src.getHeight()-1f)/Math.max(1,h-1)));
                int sx=Math.min(src.getWidth()-1,Math.round(x*(src.getWidth()-1f)/Math.max(1,w-1)));
                float near=Math.max(0,Math.min(1,depth[sy][sx]));
                int targetZ=Math.round((1f-near)*maxRelief);
                int id=0;
                for(int z=0;z<Math.min(3,d);z++) { int v=original[index(w,d,x,y,z)]; if(v!=0){id=v;break;} }
                if(id==0) continue;
                for(int z=0;z<Math.min(3,d);z++) blocks[index(w,d,x,y,z)]=0;
                blocks[index(w,d,x,y,Math.min(d-1,targetZ))]=id;
                if(targetZ+1<d) blocks[index(w,d,x,y,targetZ+1)]=id;
            }
            progress.accept(97);

            // Connect depth-displaced facade pixels toward the interior where needed to avoid floating sheets.
            for(int y=1;y<h-1;y++) for(int x=1;x<w-1;x++) {
                int first=-1,id=0;
                for(int z=0;z<Math.min(d,maxRelief+3);z++) if(blocks[index(w,d,x,y,z)]!=0){first=z;id=blocks[index(w,d,x,y,z)];break;}
                if(first>1 && id!=0) {
                    boolean supported=blocks[index(w,d,x-1,y,first)]!=0||blocks[index(w,d,x+1,y,first)]!=0||blocks[index(w,d,x,y-1,first)]!=0||blocks[index(w,d,x,y+1,first)]!=0;
                    if(!supported) for(int z=Math.max(0,first-2);z<first;z++) blocks[index(w,d,x,y,z)]=id;
                }
            }
            progress.accept(99);
            return new ImageConverter.Result(w,h,d,blocks,base.palette());
        } catch(IOException e){throw e;} catch(Exception e){throw new IOException("Neural AI failed: "+(e.getMessage()==null?e.getClass().getSimpleName():e.getMessage()),e);}
    }

    private static int index(int w,int d,int x,int y,int z){return x+z*w+y*w*d;}
}
