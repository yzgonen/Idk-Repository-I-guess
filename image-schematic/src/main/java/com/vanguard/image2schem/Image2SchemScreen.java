package com.vanguard.image2schem;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWDropCallback;

import javax.imageio.ImageIO;
import javax.swing.JFileChooser;
import javax.swing.filechooser.FileNameExtensionFilter;
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

    private volatile int progress = 0;
    private volatile boolean generating = false;
    private String status = "Choose or drop a PNG/JPG to begin.";

    private ButtonWidget generateButton;
    private ButtonWidget saveButton;
    private GLFWDropCallback previousDrop;
    private GLFWDropCallback dropCallback;

    public Image2SchemScreen() {
        super(Text.literal("Image2Schem Builder"));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int panelWidth = Math.min(620, this.width - 32);
        int left = center - panelWidth / 2;
        int bottom = this.height - 34;

        addDrawableChild(ButtonWidget.builder(Text.literal("Choose PNG / JPG"), button -> chooseImage())
                .dimensions(left, 42, 150, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Open Input Folder"), button -> {
            net.minecraft.util.Util.getOperatingSystem().open(Image2SchemClient.inputFolder());
        }).dimensions(left + 160, 42, 150, 20).build());

        generateButton = addDrawableChild(ButtonWidget.builder(Text.literal("Generate 3D Build"), button -> startGenerate())
                .dimensions(left + 320, 42, 150, 20).build());
        generateButton.active = selectedImage != null && !generating;

        saveButton = addDrawableChild(ButtonWidget.builder(Text.literal("Save Schematic As..."), button -> saveSchematic())
                .dimensions(left + 480, 42, Math.max(120, panelWidth - 480), 20).build());
        saveButton.active = generated != null && !generating;

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
                .dimensions(center - 100, bottom, 200, 20).build());

        installDropCallback();
    }

    private void installDropCallback() {
        MinecraftClient mc = MinecraftClient.getInstance();
        long window = mc.getWindow().getHandle();
        dropCallback = GLFWDropCallback.create((win, count, names) -> {
            if (count <= 0) return;
            String name = GLFWDropCallback.getName(names, 0);
            mc.execute(() -> selectImage(Path.of(name)));
        });
        previousDrop = GLFW.glfwSetDropCallback(window, dropCallback);
    }

    private void chooseImage() {
        status = "Opening file picker...";
        Thread picker = new Thread(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Choose an image for Image2Schem");
                chooser.setFileFilter(new FileNameExtensionFilter("PNG / JPG images", "png", "jpg", "jpeg"));
                int result = chooser.showOpenDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    Path path = chooser.getSelectedFile().toPath();
                    MinecraftClient.getInstance().execute(() -> selectImage(path));
                } else {
                    MinecraftClient.getInstance().execute(() -> status = "No image selected.");
                }
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> status = "File picker error: " + e.getMessage());
            }
        }, "Image2Schem-FilePicker");
        picker.setDaemon(true);
        picker.start();
    }

    private void selectImage(Path path) {
        try {
            String lower = path.getFileName().toString().toLowerCase();
            if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
                throw new IllegalArgumentException("Drop a PNG, JPG or JPEG file.");
            }
            if (!Files.isRegularFile(path)) throw new IllegalArgumentException("That file cannot be read.");

            BufferedImage image = ImageIO.read(path.toFile());
            if (image == null) throw new IllegalArgumentException("Unsupported image file.");

            selectedImage = path;
            selectedPreview = image;
            suggestion = ImageConverter.suggest(path);
            generated = null;
            progress = 0;
            status = "Ready - auto size: " + suggestion.width() + " x " + suggestion.height() + " x " + suggestion.depth() + " blocks";
            if (generateButton != null) generateButton.active = true;
            if (saveButton != null) saveButton.active = false;
        } catch (Exception e) {
            status = "Image error: " + e.getMessage();
        }
    }

    private void startGenerate() {
        if (selectedImage == null || suggestion == null || generating) return;
        generating = true;
        generated = null;
        progress = 1;
        status = "Building... 1%";
        generateButton.active = false;
        saveButton.active = false;

        Path image = selectedImage;
        ImageConverter.Suggestion size = suggestion;
        Thread worker = new Thread(() -> {
            try {
                Image2SchemClient.GenerationResult result = Image2SchemClient.generatePath(image, size.width(), size.depth(), p -> {
                    progress = Math.max(1, Math.min(100, p));
                });
                MinecraftClient.getInstance().execute(() -> {
                    generated = result;
                    generating = false;
                    progress = 100;
                    status = "Finished - " + result.width() + " x " + result.height() + " x " + result.depth() + " blocks";
                    generateButton.active = true;
                    saveButton.active = true;
                });
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> {
                    generating = false;
                    status = "Generation failed: " + e.getMessage();
                    generateButton.active = true;
                });
            }
        }, "Image2Schem-Generator");
        worker.setDaemon(true);
        worker.start();
    }

    private void saveSchematic() {
        if (generated == null) return;
        Path source = generated.output();
        Thread saver = new Thread(() -> {
            try {
                JFileChooser chooser = new JFileChooser();
                chooser.setDialogTitle("Save generated schematic");
                chooser.setSelectedFile(source.getFileName().toFile());
                int result = chooser.showSaveDialog(null);
                if (result == JFileChooser.APPROVE_OPTION) {
                    Path target = chooser.getSelectedFile().toPath();
                    if (!target.getFileName().toString().toLowerCase().endsWith(".schem")) {
                        target = target.resolveSibling(target.getFileName() + ".schem");
                    }
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    Path finalTarget = target;
                    MinecraftClient.getInstance().execute(() -> status = "Saved: " + finalTarget.toAbsolutePath());
                }
            } catch (Exception e) {
                MinecraftClient.getInstance().execute(() -> status = "Save failed: " + e.getMessage());
            }
        }, "Image2Schem-Saver");
        saver.setDaemon(true);
        saver.start();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        context.fill(0, 0, this.width, this.height, 0xCC101010);
        super.render(context, mouseX, mouseY, deltaTicks);

        int center = this.width / 2;
        int panelWidth = Math.min(620, this.width - 32);
        int left = center - panelWidth / 2;

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, center, 16, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal("Drag & drop an image anywhere on this screen, or use Choose PNG / JPG"),
                center, 28, 0xB0B0B0);

        int previewTop = 76;
        int previewHeight = Math.max(120, this.height - 180);
        int gap = 12;
        int boxWidth = (panelWidth - gap) / 2;
        int leftBox = left;
        int rightBox = left + boxWidth + gap;

        drawPanel(context, leftBox, previewTop, boxWidth, previewHeight, "SOURCE IMAGE");
        drawPanel(context, rightBox, previewTop, boxWidth, previewHeight, "3D BLOCK PREVIEW");

        if (selectedPreview != null) {
            drawFlatPreview(context, selectedPreview, leftBox + 8, previewTop + 24, boxWidth - 16, previewHeight - 56);
            String info = selectedImage.getFileName() + "  |  " + selectedPreview.getWidth() + "x" + selectedPreview.getHeight() + " px";
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(info), leftBox + boxWidth / 2, previewTop + previewHeight - 22, 0xD0D0D0);
        } else {
            context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Drop image here"), leftBox + boxWidth / 2, previewTop + previewHeight / 2, 0x909090);
        }

        if (generated != null) {
            draw3dPreview(context, generated.model(), rightBox + 8, previewTop + 24, boxWidth - 16, previewHeight - 56);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal(generated.width() + " x " + generated.height() + " x " + generated.depth() + " blocks"),
                    rightBox + boxWidth / 2, previewTop + previewHeight - 22, 0xD0D0D0);
        } else if (suggestion != null) {
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Auto calculated: " + suggestion.width() + " x " + suggestion.height() + " x " + suggestion.depth()),
                    rightBox + boxWidth / 2, previewTop + previewHeight / 2 - 6, 0xD0D0D0);
            context.drawCenteredTextWithShadow(this.textRenderer,
                    Text.literal("Press Generate 3D Build"), rightBox + boxWidth / 2, previewTop + previewHeight / 2 + 10, 0x909090);
        }

        int barY = this.height - 70;
        int barX = left;
        int barW = panelWidth;
        context.fill(barX, barY, barX + barW, barY + 12, 0xFF202020);
        int fill = Math.round(barW * progress / 100F);
        if (fill > 0) context.fill(barX + 1, barY + 1, barX + Math.max(2, fill - 1), barY + 11, 0xFF7FB238);
        context.drawCenteredTextWithShadow(this.textRenderer,
                Text.literal((generating ? "BUILDING " : "PROGRESS ") + progress + "%"), center, barY + 2, 0xFFFFFF);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), center, barY + 18, 0xD0D0D0);
    }

    private void drawPanel(DrawContext context, int x, int y, int w, int h, String title) {
        context.fill(x, y, x + w, y + h, 0xFF1B1B1B);
        context.fill(x, y, x + w, y + 1, 0xFF8A8A8A);
        context.fill(x, y + h - 1, x + w, y + h, 0xFF404040);
        context.fill(x, y, x + 1, y + h, 0xFF8A8A8A);
        context.fill(x + w - 1, y, x + w, y + h, 0xFF404040);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(title), x + w / 2, y + 7, 0xFFFFFF);
    }

    private void drawFlatPreview(DrawContext context, BufferedImage image, int x, int y, int w, int h) {
        int sampleW = Math.min(96, image.getWidth());
        int sampleH = Math.min(96, image.getHeight());
        float scale = Math.min(w / (float) sampleW, h / (float) sampleH);
        int cell = Math.max(1, (int) scale);
        int drawW = sampleW * cell;
        int drawH = sampleH * cell;
        int ox = x + (w - drawW) / 2;
        int oy = y + (h - drawH) / 2;
        for (int sy = 0; sy < sampleH; sy++) {
            int srcY = sy * image.getHeight() / sampleH;
            for (int sx = 0; sx < sampleW; sx++) {
                int srcX = sx * image.getWidth() / sampleW;
                int rgb = image.getRGB(srcX, srcY) & 0xFFFFFF;
                context.fill(ox + sx * cell, oy + sy * cell, ox + (sx + 1) * cell, oy + (sy + 1) * cell, 0xFF000000 | rgb);
            }
        }
    }

    private void draw3dPreview(DrawContext context, ImageConverter.Result model, int x, int y, int w, int h) {
        Map<Integer, String> inverse = new HashMap<>();
        model.palette().forEach((name, id) -> inverse.put(id, name));

        int stepX = Math.max(1, model.width() / 70);
        int stepY = Math.max(1, model.height() / 55);
        int cols = (model.width() + stepX - 1) / stepX;
        int rows = (model.height() + stepY - 1) / stepY;
        int cell = Math.max(1, Math.min(w / Math.max(1, cols + model.length()), h / Math.max(1, rows + model.length())));
        int drawW = (cols + model.length()) * cell;
        int drawH = (rows + model.length()) * cell;
        int ox = x + (w - drawW) / 2 + model.length() * cell / 2;
        int oy = y + (h - drawH) / 2;

        for (int py = rows - 1; py >= 0; py--) {
            int srcY = Math.min(model.height() - 1, py * stepY);
            for (int px = 0; px < cols; px++) {
                int srcX = Math.min(model.width() - 1, px * stepX);
                int topId = 0;
                int depth = 0;
                for (int z = 0; z < model.length(); z++) {
                    int idx = srcX + z * model.width() + srcY * model.width() * model.length();
                    int id = model.paletteIds()[idx];
                    if (id != 0) {
                        depth = z + 1;
                        topId = id;
                    }
                }
                if (topId == 0) continue;
                int color = BlockPalette.colorFor(inverse.getOrDefault(topId, ""));
                int shade = 0xFF000000 | (((color >> 16) & 255) * 3 / 4 << 16) | (((color >> 8) & 255) * 3 / 4 << 8) | ((color & 255) * 3 / 4);
                int bx = ox + px * cell + depth * cell / 2;
                int by = oy + (rows - 1 - py) * cell + (model.length() - depth) * cell / 2;
                context.fill(bx, by, bx + cell, by + cell, color);
                if (depth > 1 && cell > 1) context.fill(bx + cell, by + cell / 2, bx + cell + Math.max(1, cell / 2), by + cell + cell / 2, shade);
            }
        }
    }

    @Override
    public void removed() {
        if (client != null && dropCallback != null) {
            long window = client.getWindow().getHandle();
            GLFW.glfwSetDropCallback(window, previousDrop);
            dropCallback.free();
            dropCallback = null;
        }
        super.removed();
    }

    @Override
    public boolean shouldPause() { return false; }
}
