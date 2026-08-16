package com.vanguard.image2schem;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

public final class Image2SchemScreen extends Screen {
    private TextFieldWidget filenameField;
    private TextFieldWidget widthField;
    private TextFieldWidget depthField;
    private String status = "Put a PNG/JPG in the input folder, then generate it.";

    public Image2SchemScreen() {
        super(Text.literal("Image2Schem Builder"));
    }

    @Override
    protected void init() {
        int center = this.width / 2;
        int left = center - 120;
        int top = Math.max(36, this.height / 2 - 100);

        filenameField = new TextFieldWidget(this.textRenderer, left, top + 34, 240, 20, Text.literal("Image filename"));
        filenameField.setText("build.png");
        addDrawableChild(filenameField);

        widthField = new TextFieldWidget(this.textRenderer, left, top + 70, 114, 20, Text.literal("Width"));
        widthField.setText("64");
        addDrawableChild(widthField);

        depthField = new TextFieldWidget(this.textRenderer, left + 126, top + 70, 114, 20, Text.literal("Depth"));
        depthField.setText("4");
        addDrawableChild(depthField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Open Input Folder"), button -> {
            try {
                Util.getOperatingSystem().open(Image2SchemClient.inputFolder());
                status = "Input folder opened.";
            } catch (Exception e) {
                status = "Could not open input folder: " + e.getMessage();
            }
        }).dimensions(left, top + 102, 114, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Open Output Folder"), button -> {
            try {
                Util.getOperatingSystem().open(Image2SchemClient.outputFolder());
                status = "Output folder opened.";
            } catch (Exception e) {
                status = "Could not open output folder: " + e.getMessage();
            }
        }).dimensions(left + 126, top + 102, 114, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Generate Build"), button -> generate()).dimensions(left, top + 134, 240, 20).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close()).dimensions(left, top + 166, 240, 20).build());
    }

    private void generate() {
        try {
            String filename = filenameField.getText().trim();
            int width = Integer.parseInt(widthField.getText().trim());
            int depth = Integer.parseInt(depthField.getText().trim());

            if (filename.isEmpty()) throw new IllegalArgumentException("Enter an image filename.");
            if (width < 8 || width > 256) throw new IllegalArgumentException("Width must be 8-256.");
            if (depth < 1 || depth > 8) throw new IllegalArgumentException("Depth must be 1-8.");

            status = "Generating...";
            Image2SchemClient.GenerationResult result = Image2SchemClient.generateFile(filename, width, depth);
            status = "Done: " + result.width() + "x" + result.height() + "x" + result.depth() + " -> " + result.output().getFileName();
        } catch (NumberFormatException e) {
            status = "Width and depth must be numbers.";
        } catch (Exception e) {
            status = "Error: " + e.getMessage();
        }
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // Deliberately avoid Screen#renderBackground here. Some heavily-modded clients
        // replace the background/blur renderer and can crash as soon as this screen opens.
        context.fill(0, 0, this.width, this.height, 0xCC101014);
        super.render(context, mouseX, mouseY, deltaTicks);

        int center = this.width / 2;
        int left = center - 120;
        int top = Math.max(36, this.height / 2 - 100);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, center, top, 0xFFFFFF);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Image filename"), left, top + 22, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Width (8-256)"), left, top + 58, 0xA0A0A0);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Depth (1-8)"), left + 126, top + 58, 0xA0A0A0);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(status), center, top + 198, 0xD0D0D0);
        context.drawCenteredTextWithShadow(this.textRenderer, Text.literal("Press K anytime in-game to open this menu"), center, top + 214, 0x808080);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
