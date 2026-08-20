package dev.tuxy.motionblur.ui;

import dev.tuxy.motionblur.MotionBlurConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

public final class MotionBlurConfigScreen extends Screen {
    private final Screen parent;
    private StrengthSlider strengthSlider;

    public MotionBlurConfigScreen(Screen parent) {
        super(Text.literal("Tuxy Motion Blur Settings"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        int centerX = this.width / 2;
        int centerY = this.height / 2;

        this.strengthSlider = this.addDrawableChild(new StrengthSlider(
                centerX - 100,
                centerY - 30,
                200,
                20,
                MotionBlurConfig.getStrengthPercent()
        ));

        this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("Reset to 24%"),
                        button -> this.strengthSlider.setPercent(MotionBlurConfig.DEFAULT_STRENGTH_PERCENT)
                )
                .dimensions(centerX - 100, centerY + 5, 98, 20)
                .build());

        this.addDrawableChild(ButtonWidget.builder(
                        Text.literal("Done"),
                        button -> this.close()
                )
                .dimensions(centerX + 2, centerY + 5, 98, 20)
                .build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float deltaTicks) {
        // Do not call Screen#renderBackground here. When Mod Menu opens this screen,
        // Minecraft may already have applied its one-per-frame GUI blur to the parent
        // screen. Applying it again in the same frame crashes with
        // "Can only blur once per frame" on 1.21.11.
        context.fill(0, 0, this.width, this.height, 0xB0000000);

        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, 40, 0xFFFFFF);
        context.drawCenteredTextWithShadow(
                this.textRenderer,
                Text.literal("Adjust in 1% steps • 0% = off • 100% = maximum"),
                this.width / 2,
                62,
                0xA0A0A0
        );
        super.render(context, mouseX, mouseY, deltaTicks);
    }

    @Override
    public void close() {
        MinecraftClient.getInstance().setScreen(this.parent);
    }

    private static final class StrengthSlider extends SliderWidget {
        private StrengthSlider(int x, int y, int width, int height, int percent) {
            super(x, y, width, height, Text.literal(""), clamp(percent) / 100.0D);
            this.updateMessage();
        }

        @Override
        protected void updateMessage() {
            this.setMessage(Text.literal("Motion Blur Strength: " + this.getPercent() + "%"));
        }

        @Override
        protected void applyValue() {
            int percent = this.getPercent();
            this.value = percent / 100.0D;
            MotionBlurConfig.setStrengthPercent(percent);
            this.updateMessage();
        }

        private int getPercent() {
            return clamp((int) Math.round(this.value * 100.0D));
        }

        private void setPercent(int percent) {
            this.value = clamp(percent) / 100.0D;
            this.applyValue();
        }

        private static int clamp(int percent) {
            return Math.max(0, Math.min(100, percent));
        }
    }
}
