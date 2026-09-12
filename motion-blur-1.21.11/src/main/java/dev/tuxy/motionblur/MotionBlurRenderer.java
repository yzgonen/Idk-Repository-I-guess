package dev.tuxy.motionblur;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.Std140Builder;
import com.mojang.blaze3d.buffers.Std140SizeCalculator;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.tuxy.motionblur.mixin.PostEffectPassAccessor;
import dev.tuxy.motionblur.mixin.PostEffectProcessorAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.PostEffectPass;
import net.minecraft.client.gl.PostEffectProcessor;
import net.minecraft.client.gl.UniformValue;
import net.minecraft.client.util.ObjectAllocator;
import net.minecraft.util.Identifier;
import org.lwjgl.system.MemoryStack;

import java.util.Map;
import java.util.Set;

public final class MotionBlurRenderer {
    public static final String MOD_ID = "tuxymotionblur";
    private static final Identifier EFFECT_ID = Identifier.of(MOD_ID, "motion_blur");
    private static final Set<Identifier> EXTERNAL_TARGETS = Set.of(PostEffectProcessor.MAIN);

    private static long lastFrameNanos;
    private static Object lastWorld;

    private MotionBlurRenderer() {
    }

    public static void render(MinecraftClient client, ObjectAllocator allocator) {
        if (client.world == null || client.player == null) {
            lastWorld = null;
            lastFrameNanos = 0L;
            return;
        }

        int strengthPercent = MotionBlurConfig.getStrengthPercent();
        if (strengthPercent <= 0) {
            lastFrameNanos = 0L;
            lastWorld = client.world;
            return;
        }

        boolean worldChanged = client.world != lastWorld;
        if (worldChanged) {
            lastWorld = client.world;
            lastFrameNanos = 0L;
        }

        PostEffectProcessor processor = client.getShaderLoader().loadPostEffect(EFFECT_ID, EXTERNAL_TARGETS);
        if (processor == null) {
            return;
        }

        float blendFactor = worldChanged ? 0.0F : calculateBlendFactor(strengthPercent);
        applyBlendFactor(processor, blendFactor);
        processor.render(client.getFramebuffer(), allocator);
    }

    private static float calculateBlendFactor(int strengthPercent) {
        long now = System.nanoTime();
        if (lastFrameNanos == 0L) {
            lastFrameNanos = now;
            return 0.0F;
        }

        double deltaSeconds = (now - lastFrameNanos) / 1_000_000_000.0;
        lastFrameNanos = now;

        // After a long pause or alt-tab, reset history instead of dragging an old frame back in.
        if (deltaSeconds <= 0.0 || deltaSeconds > 0.25) {
            return 0.0F;
        }

        // 1-99% map directly to the original temporal blend scale.
        // 100% is capped just below 1.0 so the image never freezes on an old frame.
        double referenceBlendAt60Fps = Math.min(strengthPercent / 100.0D, 0.995D);
        double normalizedFrames = deltaSeconds * 60.0D;
        return (float) Math.pow(referenceBlendAt60Fps, normalizedFrames);
    }

    private static void applyBlendFactor(PostEffectProcessor processor, float blendFactor) {
        GpuBuffer replacement = createBlendFactorBuffer(blendFactor);
        if (replacement == null) {
            return;
        }

        for (PostEffectPass pass : ((PostEffectProcessorAccessor) processor).tuxy$getPasses()) {
            Map<String, GpuBuffer> uniformBuffers = ((PostEffectPassAccessor) pass).tuxy$getUniformBuffers();
            GpuBuffer previous = uniformBuffers.put("BlurConfig", replacement);
            if (previous != null) {
                previous.close();
                return;
            }
        }

        replacement.close();
    }

    private static GpuBuffer createBlendFactorBuffer(float blendFactor) {
        UniformValue.FloatValue value = new UniformValue.FloatValue(blendFactor);
        Std140SizeCalculator calculator = new Std140SizeCalculator();
        value.addSize(calculator);
        int size = calculator.get();

        try (MemoryStack stack = MemoryStack.stackPush()) {
            Std140Builder builder = Std140Builder.onStack(stack, size);
            value.write(builder);
            return RenderSystem.getDevice().createBuffer(
                    () -> "tuxy_motion_blur_blend_factor",
                    GpuBuffer.USAGE_UNIFORM,
                    builder.get()
            );
        }
    }
}
