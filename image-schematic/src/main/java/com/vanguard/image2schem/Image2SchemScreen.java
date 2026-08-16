package com.vanguard.image2schem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;

import javax.imageio.ImageIO;
import java.awt.FileDialog;
import java.awt.Frame;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

public final class Image2SchemScreen extends Screen {
    private Path selectedImage;
    private BufferedImage selectedPreview;
    private ImageConverter.Suggestion suggestion;
    private Image2SchemClient.GenerationResult generated;
    private volatile int progress;
    private volatile boolean generating;
    private String status = "Choose or drop a PNG/JPG to begin.";
    private ButtonWidget generateButton;
    private ButtonWidget downloadButton;
    private GLFWDropCallback previousDrop;
    private GLFWDropCallback dropCallback;

    public Image2SchemScreen() { super(Text.literal("Image2Schem Builder")); }

    @Override protected void init() {
        int center = width / 2;
        int panelWidth = Math.min(760, width - 32);
        int left = center - panelWidth / 2;
        int buttonGap = 8;
        int buttonW = (panelWidth - buttonGap * 2) / 3;
        addDrawableChild(ButtonWidget.builder(Text.literal("Choose PNG / JPG"), b -> chooseImage()).dimensions(left, 42, buttonW, 20).build());
        generateButton = addDrawableChild(ButtonWidget.builder(Text.literal("Generate 3D Build"), b -> startGenerate()).dimensions(left + buttonW + buttonGap, 42, buttonW, 20).build());
        downloadButton = addDrawableChild(ButtonWidget.builder(Text.literal("Download Schematic"), b -> downloadSchematic()).dimensions(left + (buttonW + buttonGap) * 2, 42, buttonW, 20).build());
        generateButton.active = selectedImage != null && !generating;
        downloadButton.active = generated != null && !generating;
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close()).dimensions(center - 100, height - 30, 200, 20).build());
        installDropCallback();
    }

    private void installDropCallback() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long window = mc.getWindow().getHandle();
        dropCallback = GLFWDropCallback.create((win, count, names) -> {
            if (count > 0) {
                String name = GLFWDropCallback.getName(names, 0);
                mc.execute(() -> selectImage(Path.of(name)));
            }
        });
        previousDrop = GLFW.glfwSetDropCallback(window, dropCallback);
    }

    private void chooseImage() {
        status = "Opening Windows file picker...";
        Thread t = new Thread(() -> {
            Frame owner = null;
            try {
                owner = new Frame();
                owner.setUndecorated(true);
                FileDialog dialog = new FileDialog(owner, "Choose PNG / JPG", FileDialog.LOAD);
                dialog.setFilenameFilter((dir, name) -> {
                    String n = name.toLowerCase();
                    return n.endsWith(".png") || n.endsWith(".jpg") || n.endsWith(".jpeg");
                });
                dialog.setVisible(true);
                String file = dialog.getFile();
                String dir = dialog.getDirectory();
                if (file != null && dir != null) {
                    Path picked = Path.of(dir, file);
                    MinecraftClient.getInstance().execute(() -> selectImage(picked));
                } else MinecraftClient.getInstance().execute(() -> status = "No image selected.");
                dialog.dispose();
            } catch (Throwable e) {
                MinecraftClient.getInstance().execute(() -> status = "Picker failed - drag/drop still works: " + safeMessage(e));
            } finally { if (owner != null) owner.dispose(); }
        }, "Image2Schem-NativePicker");
        t.setDaemon(true);
        t.start();
    }

    private void selectImage(Path path) {
        try {
            String lower = path.getFileName().toString().toLowerCase();
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) throw new IllegalArgumentException("Choose a PNG, JPG or JPEG file.");
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("That file cannot be read.");
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IllegalArgumentException("Unsupported image file.");
            selectedImage = path;
            selectedPreview = image;
            suggestion = ImageConverter.suggest(path);
            generated = null;
            progress = 0;
            status = "Ready - calculated build: " + suggestion.width() + " x " + suggestion.height() + " x " + suggestion.depth() + " blocks";
            generateButton.active = true;
            downloadButton.active = false;
        } catch (Exception e) { status = "Image error: " + safeMessage(e); }
    }

    private void startGenerate() {
        if (selectedImage == null || suggestion == null || generating) return;
        generating = true;
        generated = null;
        progress = 0;
        status = "Analyzing image";
        generateButton.active = false;
        downloadButton.active = false;
        Path image = selectedImage;
        ImageConverter.Suggestion size = suggestion;
        Thread worker = new Thread(() -> {
            try {
                Image2SchemClient.GenerationResult result = Image2SchemClient.generatePath(image, size.width(), size.depth(), p -> {
                    int next = Math.max(0, Math.min(100, p));
                    progress = next;
                    status = stageFor(next);
                });
                MinecraftClient.getInstance().execute(() -> {
                    generated = result;
                    generating = false;
                    progress = 100;
                    status = "FINISHED - refined and validated. Click Download Schematic.";
                    generateButton.active = true;
                    downloadButton.active = true;
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    generating = false;
                    progress = 0;
                    status = "Generation failed: " + safeMessage(e);
                    generateButton.active = true;
                });
            }
        }, "Image2Schem-Generator");
        worker.setDaemon(true);
        worker.start();
    }

    private static String stageFor(int p) {
        if (p < 8) return "Loading and scaling reference";
        if (p < 20) return "Analyzing edges and background";
        if (p < 31) return "Cleaning structure and detecting entrance";
        if (p < 46) return "Reconstructing facade";
        if (p < 64) return "Building interior shell and depth";
        if (p < 73) return "Creating corridor and structural columns";
        if (p < 80) return "Checking rear structure";
        if (p < 86) return "Refinement pass 1 - removing noise";
        if (p < 91) return "Refinement pass 2 - reinforcing architecture";
        if (p < 96) return "Refinement pass 3 - carving walkable spaces";
        if (p < 99) return "Refinement pass 4 - removing spikes";
        if (p < 100) return "Final structural validation";
        return "FINISHED";
    }

    private void downloadSchematic() {
        if (generated == null) return;
        try {
            Path downloads = Path.of(System.getProperty("user.home"), "Downloads");
            Files.createDirectories(downloads);
            Path source = generated.output();
            Path target = uniquePath(downloads, source.getFileName().toString());
            Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
            status = "Downloaded: " + target.getFileName() + " -> Downloads folder";
            net.minecraft.util.Util.getOperatingSystem().open(downloads);
        } catch (Exception e) { status = "Download failed: " + safeMessage(e); }
    }

    private static Path uniquePath(Path folder, String filename) {
        Path candidate = folder.resolve(filename);
        if (!Files.exists(candidate)) return candidate;
        int dot = filename.lastIndexOf('.');
        String stem = dot > 0 ? filename.substring(0, dot) : filename;
        String ext = dot > 0 ? filename.substring(dot) : "";
        for (int i = 2; ; i++) {
            candidate = folder.resolve(stem + "-" + i + ext);
            if (!Files.exists(candidate)) return candidate;
        }
    }

    @Override public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, width, height, 0xCC101010);
        super.render(context, mouseX, mouseY, deltaTicks);
        int center = width / 2;
        int panelWidth = Math.min(760, width - 32);
        int left = center - panelWidth / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, center, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Choose an image or drag it onto Minecraft"), center, 27, 0xB0B0B0);
        int previewTop = 76;
        int previewHeight = Math.max(100, height - 190);
        int gap = 12;
        int boxWidth = (panelWidth - gap) / 2;
        int rightBox = left + boxWidth + gap;
        drawPanel(context, left, previewTop, boxWidth, previewHeight, "SOURCE IMAGE");
        drawPanel(context, rightBox, previewTop, boxWidth, previewHeight, "3D BLOCK PREVIEW");
        if (selectedPreview != null) {
            drawFlatPreview(context, selectedPreview, left + 8, previewTop + 24, boxWidth - 16, previewHeight - 50);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(selectedImage.getFileName() + " | " + selectedPreview.getWidth() + "x" + selectedPreview.getHeight() + " px"), left + boxWidth / 2, previewTop + previewHeight - 18, 0xD0D0D0);
        } else context.drawCenteredTextWithShadow(textRenderer, Text.literal("No image selected"), left + boxWidth / 2, previewTop + previewHeight / 2, 0x909090);
        if (generated != null) {
            draw3dPreview(context, generated.model(), rightBox + 8, previewTop + 24, boxWidth - 16, previewHeight - 50);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(generated.width() + " x " + generated.height() + " x " + generated.depth() + " blocks"), rightBox + boxWidth / 2, previewTop + previewHeight - 18, 0xD0D0D0);
        } else if (suggestion != null) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Calculated: " + suggestion.width() + " x " + suggestion.height() + " x " + suggestion.depth()), rightBox + boxWidth / 2, previewTop + previewHeight / 2 - 6, 0xD0D0D0);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Press Generate 3D Build"), rightBox + boxWidth / 2, previewTop + previewHeight / 2 + 10, 0x909090);
        }

        int lineY = height - 79;
        String percentLine = generating ? String.format("%3d%%  -  %s", progress, status) : (progress == 100 ? "100%  -  FINISHED" : String.format("%3d%%  -  %s", progress, status));
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(percentLine), center, lineY, progress == 100 ? 0x55FF55 : 0xFFFFFF);
        context.fill(left, lineY + 15, left + panelWidth, lineY + 16, 0xFF606060);
        if (!generating && progress == 100) {
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Refinement complete - schematic is ready to download"), center, lineY + 21, 0xB0B0B0);
        }
    }

    private void drawPanel(DrawContext c, int x, int y, int w, int h, String label) {
        c.fill(x, y, x + w, y + h, 0xFF1B1B1B); c.fill(x, y, x + w, y + 1, 0xFF8A8A8A); c.fill(x, y + h - 1, x + w, y + h, 0xFF404040); c.fill(x, y, x + 1, y + h, 0xFF8A8A8A); c.fill(x + w - 1, y, x + w, y + h, 0xFF404040); c.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + w / 2, y + 7, 0xFFFFFF);
    }

    private void drawFlatPreview(DrawContext c, BufferedImage image, int x, int y, int w, int h) {
        int sw = Math.min(80, image.getWidth()), sh = Math.min(80, image.getHeight());
        float scale = Math.min(w / (float)sw, h / (float)sh); int cell = Math.max(1, (int)scale); int dw = sw * cell, dh = sh * cell, ox = x + (w-dw)/2, oy = y + (h-dh)/2;
        for (int sy=0; sy<sh; sy++) for (int sx=0; sx<sw; sx++) { int rgb=image.getRGB(sx*image.getWidth()/sw, sy*image.getHeight()/sh)&0xFFFFFF; c.fill(ox+sx*cell,oy+sy*cell,ox+(sx+1)*cell,oy+(sy+1)*cell,0xFF000000|rgb); }
    }

    private void draw3dPreview(DrawContext c, ImageConverter.Result m, int x, int y, int w, int h) {
        Map<Integer,String> inverse=new HashMap<>(); m.palette().forEach((n,id)->inverse.put(id,n)); int sxStep=Math.max(1,m.width()/60), syStep=Math.max(1,m.height()/45); int cols=(m.width()+sxStep-1)/sxStep, rows=(m.height()+syStep-1)/syStep; int cell=Math.max(1,Math.min(w/Math.max(1,cols+m.length()),h/Math.max(1,rows+m.length()))); int ox=x+(w-(cols+m.length())*cell)/2+m.length()*cell/2, oy=y+(h-(rows+m.length())*cell)/2;
        for(int py=rows-1;py>=0;py--){int srcY=Math.min(m.height()-1,py*syStep);for(int px=0;px<cols;px++){int srcX=Math.min(m.width()-1,px*sxStep),id=0,d=0;for(int z=0;z<m.length();z++){int idx=srcX+z*m.width()+srcY*m.width()*m.length();if(m.paletteIds()[idx]!=0){d=z+1;id=m.paletteIds()[idx];}}if(id==0)continue;int color=BlockPalette.colorFor(inverse.getOrDefault(id,""));int bx=ox+px*cell+d*cell/2,by=oy+(rows-1-py)*cell+(m.length()-d)*cell/2;c.fill(bx,by,bx+cell,by+cell,color);}}
    }

    private static String safeMessage(Throwable e) { return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(); }

    @Override public void removed() {
        if (client != null && dropCallback != null) {
            long window = client.getWindow().getHandle();
            GLFW.glfwSetDropCallback(window, previousDrop);
            dropCallback.free(); dropCallback = null; previousDrop = null;
        }
        super.removed();
    }

    @Override public boolean shouldPause() { return false; }
}
