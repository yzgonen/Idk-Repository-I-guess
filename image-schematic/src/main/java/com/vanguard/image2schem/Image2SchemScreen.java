package com.vanguard.image2schem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
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
    private volatile long generationStartedAt;
    private String status = "Choose or drop a PNG/JPG to begin.";
    private ButtonWidget generateButton;
    private ButtonWidget downloadButton;
    private TextFieldWidget groqKeyField;
    private GLFWDropCallback previousDrop;
    private GLFWDropCallback dropCallback;

    public Image2SchemScreen() { super(Text.literal("Image2Schem - Generic 3D Builder")); }

    @Override protected void init() {
        int center = width / 2;
        int panelWidth = Math.min(900, width - 48);
        int left = center - panelWidth / 2;
        int gap = 8;
        int buttonW = (panelWidth - gap * 2) / 3;

        addDrawableChild(ButtonWidget.builder(Text.literal("Choose PNG / JPG"), b -> chooseImage())
                .dimensions(left, 42, buttonW, 20).build());
        generateButton = addDrawableChild(ButtonWidget.builder(Text.literal("Generate 3D Build"), b -> startGenerate())
                .dimensions(left + buttonW + gap, 42, buttonW, 20).build());
        downloadButton = addDrawableChild(ButtonWidget.builder(Text.literal("Download Schematic"), b -> downloadSchematic())
                .dimensions(left + (buttonW + gap) * 2, 42, buttonW, 20).build());

        int keyButtonW = 100;
        groqKeyField = addDrawableChild(new TextFieldWidget(textRenderer, left, 72,
                panelWidth - keyButtonW - 8, 20, Text.literal("Groq API Key")));
        groqKeyField.setMaxLength(256);
        groqKeyField.setText("");
        groqKeyField.setPlaceholder(Text.literal(GroqKeyStore.hasKey()
                ? "Groq key saved - paste a new key only if you want to replace it"
                : "Paste Groq API key here (gsk_...)"));
        addDrawableChild(ButtonWidget.builder(Text.literal("Save Key"), b -> saveGroqKey())
                .dimensions(left + panelWidth - keyButtonW, 72, keyButtonW, 20).build());

        generateButton.active = selectedImage != null && !generating;
        downloadButton.active = generated != null && !generating;
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), b -> close())
                .dimensions(center - 100, height - 30, 200, 20).build());
        installDropCallback();
    }

    private void saveGroqKey() {
        try {
            String key = groqKeyField == null ? "" : groqKeyField.getText().trim();
            if (key.isEmpty()) {
                status = GroqKeyStore.hasKey() ? "Groq key is already saved locally." : "Paste a Groq key first.";
                return;
            }
            if (!key.startsWith("gsk_")) {
                status = "That does not look like a Groq key. It should start with gsk_.";
                return;
            }
            GroqKeyStore.save(key);
            groqKeyField.setText("");
            groqKeyField.setPlaceholder(Text.literal("Groq key saved - hidden for safety"));
            status = "Groq key saved locally and hidden.";
        } catch (Exception e) {
            status = "Could not save Groq key: " + safeMessage(e);
        }
    }

    private boolean ensureGroqKeySaved() {
        try {
            String typed = groqKeyField == null ? "" : groqKeyField.getText().trim();
            if (!typed.isEmpty()) {
                if (!typed.startsWith("gsk_")) {
                    status = "Invalid Groq key. It should start with gsk_.";
                    return false;
                }
                GroqKeyStore.save(typed);
                groqKeyField.setText("");
                groqKeyField.setPlaceholder(Text.literal("Groq key saved - hidden for safety"));
            }
            if (!GroqKeyStore.hasKey()) {
                status = "Paste your Groq API key first.";
                return false;
            }
            return true;
        } catch (Exception e) {
            status = "Could not save Groq key: " + safeMessage(e);
            return false;
        }
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
        status = "Opening file picker...";
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
            } finally {
                if (owner != null) owner.dispose();
            }
        }, "Image2Schem-NativePicker");
        t.setDaemon(true);
        t.start();
    }

    private void selectImage(Path path) {
        try {
            String lower = path.getFileName().toString().toLowerCase();
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg")))
                throw new IllegalArgumentException("Choose a PNG, JPG or JPEG file.");
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("That file cannot be read.");
            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IllegalArgumentException("Unsupported image file.");
            selectedImage = path;
            selectedPreview = image;
            suggestion = ImageConverter.suggest(path);
            generated = null;
            progress = 0;
            status = "Ready - reference loaded. Final dimensions will be inferred from the scene.";
            generateButton.active = true;
            downloadButton.active = false;
        } catch (Exception e) {
            status = "Image error: " + safeMessage(e);
        }
    }

    private void startGenerate() {
        if (selectedImage == null || suggestion == null || generating) return;
        if (!ensureGroqKeySaved()) return;
        generating = true;
        generated = null;
        progress = 1;
        generationStartedAt = System.currentTimeMillis();
        status = "Starting generic scene analysis + local Depth AI...";
        generateButton.active = false;
        downloadButton.active = false;

        Path image = selectedImage;
        ImageConverter.Suggestion size = suggestion;
        Thread worker = new Thread(() -> {
            try {
                Image2SchemClient.GenerationResult result = Image2SchemClient.generatePath(image, size.width(), size.depth(), p -> {
                    int next = Math.max(progress, Math.max(0, Math.min(100, p)));
                    progress = next;
                    status = stageFor(next);
                });
                MinecraftClient.getInstance().execute(() -> {
                    generated = result;
                    generating = false;
                    progress = 100;
                    status = "FINISHED - schematic ready.";
                    generateButton.active = true;
                    downloadButton.active = true;
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    generating = false;
                    status = "FAILED: " + safeMessage(e);
                    generateButton.active = true;
                    downloadButton.active = false;
                });
            }
        }, "Image2Schem-Generator");
        worker.setDaemon(true);
        worker.start();
    }

    private static String stageFor(int p) {
        if (p < 5) return "Preparing reference image...";
        if (p < 46) return "Groq is decomposing the scene while Depth AI runs locally...";
        if (p < 51) return "Fusing scene graph with depth evidence...";
        if (p < 70) return "Placing generic 3D structural primitives...";
        if (p < 83) return "Constructing detected floors, roofs, stairs, ramps, arches and objects...";
        if (p < 91) return "Resolving depth relationships and repeated structures...";
        if (p < 96) return "Applying detected Minecraft materials...";
        if (p < 100) return "Carving detected openings and cleaning geometry...";
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
            status = "Downloaded: " + target.getFileName() + " -> Downloads";
            net.minecraft.util.Util.getOperatingSystem().open(downloads);
        } catch (Exception e) {
            status = "Download failed: " + safeMessage(e);
        }
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
        int panelWidth = Math.min(900, width - 48);
        int left = center - panelWidth / 2;
        context.drawCenteredTextWithShadow(textRenderer, title, center, 14, 0xFFFFFF);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Any scene: Groq identifies objects; the local engine builds only what was detected."), center, 27, 0xB0B0B0);

        String keyState = GroqKeyStore.hasKey() ? "Groq key: SAVED (hidden)" : "Groq key: NOT SAVED";
        context.drawTextWithShadow(textRenderer, Text.literal(keyState), left, 96, GroqKeyStore.hasKey() ? 0x55FF55 : 0xFF7777);
        String liveStatus;
        if (generating) {
            long elapsed = Math.max(0, (System.currentTimeMillis() - generationStartedAt) / 1000L);
            liveStatus = progress + "%  |  " + status + "  |  " + elapsed + "s";
        } else liveStatus = progress == 100 ? "100%  |  FINISHED" : status;
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(liveStatus), center, 108, progress == 100 ? 0x55FF55 : 0xFFFFFF);

        int previewTop = 126;
        int previewHeight = Math.max(100, height - 220);
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
            context.drawCenteredTextWithShadow(textRenderer, Text.literal("Scene dimensions will be inferred during generation"), rightBox + boxWidth / 2, previewTop + previewHeight / 2 - 6, 0xD0D0D0);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(generating ? "AI is working..." : "Press Generate 3D Build"), rightBox + boxWidth / 2, previewTop + previewHeight / 2 + 10, generating ? 0xFFFF55 : 0x909090);
        }

        int lineY = height - 52;
        context.fill(left, lineY, left + panelWidth, lineY + 2, 0xFF505050);
        if (generating) {
            int fill = Math.round(panelWidth * (progress / 100f));
            context.fill(left, lineY, left + fill, lineY + 2, 0xFFFFFFFF);
        }
    }

    private void drawPanel(DrawContext c, int x, int y, int w, int h, String label) {
        c.fill(x, y, x + w, y + h, 0xFF1B1B1B);
        c.fill(x, y, x + w, y + 1, 0xFF8A8A8A);
        c.fill(x, y + h - 1, x + w, y + h, 0xFF404040);
        c.fill(x, y, x + 1, y + h, 0xFF8A8A8A);
        c.fill(x + w - 1, y, x + w, y + h, 0xFF404040);
        c.drawCenteredTextWithShadow(textRenderer, Text.literal(label), x + w / 2, y + 7, 0xFFFFFF);
    }

    private void drawFlatPreview(DrawContext c, BufferedImage image, int x, int y, int w, int h) {
        int sw = Math.min(80, image.getWidth()), sh = Math.min(80, image.getHeight());
        float scale = Math.min(w / (float)sw, h / (float)sh);
        int cell = Math.max(1, (int)scale);
        int dw = sw * cell, dh = sh * cell, ox = x + (w-dw)/2, oy = y + (h-dh)/2;
        for (int sy=0; sy<sh; sy++) for (int sx=0; sx<sw; sx++) {
            int rgb=image.getRGB(sx*image.getWidth()/sw, sy*image.getHeight()/sh)&0xFFFFFF;
            c.fill(ox+sx*cell,oy+sy*cell,ox+(sx+1)*cell,oy+(sy+1)*cell,0xFF000000|rgb);
        }
    }

    private void draw3dPreview(DrawContext c, ImageConverter.Result m, int x, int y, int w, int h) {
        Map<Integer,String> inverse=new HashMap<>();
        m.palette().forEach((n,id)->inverse.put(id,n));
        int sxStep=Math.max(1,m.width()/60), syStep=Math.max(1,m.height()/45);
        int cols=(m.width()+sxStep-1)/sxStep, rows=(m.height()+syStep-1)/syStep;
        int cell=Math.max(1,Math.min(w/Math.max(1,cols+m.length()),h/Math.max(1,rows+m.length())));
        int ox=x+(w-(cols+m.length())*cell)/2+m.length()*cell/2;
        int oy=y+(h-(rows+m.length())*cell)/2;
        for(int py=rows-1;py>=0;py--){
            int srcY=Math.min(m.height()-1,py*syStep);
            for(int px=0;px<cols;px++){
                int srcX=Math.min(m.width()-1,px*sxStep),id=0,d=0;
                for(int z=0;z<m.length();z++){
                    int idx=srcX+z*m.width()+srcY*m.width()*m.length();
                    if(m.paletteIds()[idx]!=0){d=z+1;id=m.paletteIds()[idx];}
                }
                if(id==0)continue;
                int color=BlockPalette.colorFor(inverse.getOrDefault(id,""));
                int bx=ox+px*cell+d*cell/2,by=oy+(rows-1-py)*cell+(m.length()-d)*cell/2;
                c.fill(bx,by,bx+cell,by+cell,color);
            }
        }
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
