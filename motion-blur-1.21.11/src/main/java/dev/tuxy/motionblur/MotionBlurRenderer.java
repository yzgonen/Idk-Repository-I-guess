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

    // Light classic-client temporal blur at 60 FPS.
    // Frame-rate compensated so the visual strength stays similar at high FPS.
    private static final float REFERENCE_BLEND_AT_60_FPS = 0.24F;

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

        boolean worldChanged = client.world != lastWorld;
        if (worldChanged) {
            lastWorld = client.world;
            lastFrameNanos = 0L;
        }

        PostEffectProcessor processor = client.getShaderLoader().loadPostEffect(EFFECT_ID, EXTERNAL_TARGETS);
        if (processor == null) {
            return;
        }

        float blendFactor = worldChanged ? 0.0F : calculateBlendFactor();
        applyBlendFactor(processor, blendFactor);
        processor.render(client.getFramebuffer(), allocator);
    }

    private static float calculateBlendFactor() {
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

        double normalizedFrames = deltaSeconds * 60.0;
        return (float) Math.pow(REFERENCE_BLEND_AT_60_FPS, normalizedFrames);
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
