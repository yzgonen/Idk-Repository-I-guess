package com.vanguard.image2schem;

import ai.onnxruntime.*;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.function.IntConsumer;

/** Real neural inference used by Image2Schem. The model is downloaded once and cached locally. */
public final class NeuralDepthAI {
    private static final String MODEL_URL = "https://huggingface.co/onnx-community/depth-anything-v2-small/resolve/main/onnx/model.onnx?download=true";
    private static final long EXPECTED_MIN_BYTES = 90_000_000L;
    private static final int INPUT = 518;
    private NeuralDepthAI() {}

    public static float[][] estimate(BufferedImage source, IntConsumer progress) throws Exception {
        Path dir = Path.of(System.getProperty("user.home"), ".image2schem", "models");
        Files.createDirectories(dir);
        Path model = dir.resolve("depth-anything-v2-small.onnx");
        if (!Files.exists(model) || Files.size(model) < EXPECTED_MIN_BYTES) download(model, progress);
        progress.accept(22);

        BufferedImage resized = new BufferedImage(INPUT, INPUT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = resized.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.drawImage(source, 0, 0, INPUT, INPUT, null);
        g.dispose();

        float[] tensor = new float[3 * INPUT * INPUT];
        float[] mean = {0.485f, 0.456f, 0.406f};
        float[] std = {0.229f, 0.224f, 0.225f};
        for (int y=0;y<INPUT;y++) for(int x=0;x<INPUT;x++) {
            int rgb=resized.getRGB(x,y), i=y*INPUT+x;
            float r=((rgb>>>16)&255)/255f, gg=((rgb>>>8)&255)/255f, b=(rgb&255)/255f;
            tensor[i]=(r-mean[0])/std[0]; tensor[INPUT*INPUT+i]=(gg-mean[1])/std[1]; tensor[2*INPUT*INPUT+i]=(b-mean[2])/std[2];
        }
        progress.accept(28);

        OrtEnvironment env = OrtEnvironment.getEnvironment();
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions(); OrtSession session = env.createSession(model.toString(), options)) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            String inputName = session.getInputNames().iterator().next();
            progress.accept(31);
            try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(tensor), new long[]{1,3,INPUT,INPUT});
                 OrtSession.Result result = session.run(Map.of(inputName, input))) {
                progress.accept(48);
                Object value = result.get(0).getValue();
                float[][] raw;
                if (value instanceof float[][][] a) raw = a[0];
                else if (value instanceof float[][] a) raw = a;
                else throw new IllegalStateException("Unexpected depth model output: " + value.getClass().getName());
                return normalizeAndResize(raw, source.getWidth(), source.getHeight());
            }
        }
    }

    private static void download(Path target, IntConsumer progress) throws Exception {
        progress.accept(3);
        Path temp=target.resolveSibling(target.getFileName()+".part");
        HttpURLConnection c=(HttpURLConnection) URI.create(MODEL_URL).toURL().openConnection();
        c.setConnectTimeout(15000); c.setReadTimeout(120000); c.setInstanceFollowRedirects(true); c.setRequestProperty("User-Agent","Image2Schem/0.4");
        long total=c.getContentLengthLong(), read=0;
        try(InputStream in=c.getInputStream(); var out=Files.newOutputStream(temp)) {
            byte[] buf=new byte[1024*256]; int n;
            while((n=in.read(buf))>=0){out.write(buf,0,n);read+=n;if(total>0)progress.accept(3+(int)Math.min(16,16.0*read/total));}
        } finally { c.disconnect(); }
        if(Files.size(temp)<EXPECTED_MIN_BYTES) throw new IllegalStateException("Neural model download was incomplete.");
        Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        progress.accept(20);
    }

    private static float[][] normalizeAndResize(float[][] raw,int w,int h){
        int rh=raw.length,rw=raw[0].length; float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        for(float[] row:raw)for(float v:row){if(Float.isFinite(v)){min=Math.min(min,v);max=Math.max(max,v);}}
        float range=Math.max(1e-6f,max-min); float[][] out=new float[h][w];
        for(int y=0;y<h;y++){float sy=(h==1?0:y*(rh-1f)/(h-1));int y0=(int)sy,y1=Math.min(rh-1,y0+1);float fy=sy-y0;
            for(int x=0;x<w;x++){float sx=(w==1?0:x*(rw-1f)/(w-1));int x0=(int)sx,x1=Math.min(rw-1,x0+1);float fx=sx-x0;
                float a=raw[y0][x0]*(1-fx)+raw[y0][x1]*fx,b=raw[y1][x0]*(1-fx)+raw[y1][x1]*fx;out[y][x]=((a*(1-fy)+b*fy)-min)/range;}}
        return out;
    }
}
