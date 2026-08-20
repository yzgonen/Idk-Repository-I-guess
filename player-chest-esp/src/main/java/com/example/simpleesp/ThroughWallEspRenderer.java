package com.example.simpleesp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.BlendFunction;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.UniformType;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class ThroughWallEspRenderer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int RANGE_CHUNKS = 10;
    private static final double RANGE_BLOCKS = RANGE_CHUNKS * 16.0;
    private static final double RANGE_SQ = RANGE_BLOCKS * RANGE_BLOCKS;

    private static final BufferAllocator ALLOCATOR = new BufferAllocator(256 * 1024);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

    private static final RenderPipeline THROUGH_WALL_LINES = RenderPipeline.builder()
            .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
            .withUniform("Projection", UniformType.UNIFORM_BUFFER)
            .withUniform("Fog", UniformType.UNIFORM_BUFFER)
            .withUniform("Globals", UniformType.UNIFORM_BUFFER)
            .withVertexShader("core/rendertype_lines")
            .withFragmentShader("core/rendertype_lines")
            .withBlend(BlendFunction.TRANSLUCENT)
            .withCull(false)
            .withDepthWrite(false)
            .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
            .withVertexFormat(VertexFormats.POSITION_COLOR_NORMAL_LINE_WIDTH, VertexFormat.DrawMode.LINES)
            .withLocation("simpleesp:pipeline/lines_through_walls")
            .build();

    private ThroughWallEspRenderer() {
    }

    public static void render(WorldRenderContext context) {
        if (MC.world == null || MC.player == null) {
            return;
        }
        if (!SimpleEspClient.isPlayerEspEnabled() && !SimpleEspClient.isChestEspEnabled()) {
            return;
        }

        MatrixStack matrices = context.matrices();
        Vec3d camera = MC.gameRenderer.getCamera().getCameraPos();
        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        BufferBuilder buffer = new BufferBuilder(
                ALLOCATOR,
                THROUGH_WALL_LINES.getVertexFormatMode(),
                THROUGH_WALL_LINES.getVertexFormat()
        );

        int boxes = 0;
        if (SimpleEspClient.isPlayerEspEnabled()) {
            boxes += renderPlayers(MC.world, matrices, buffer);
        }
        if (SimpleEspClient.isChestEspEnabled()) {
            boxes += renderChests(MC.world, matrices, buffer);
        }

        matrices.pop();
        if (boxes == 0) {
            return;
        }

        BuiltBuffer built = buffer.end();
        draw(built);
    }

    private static int renderPlayers(ClientWorld world, MatrixStack matrices, BufferBuilder buffer) {
        int count = 0;
        Vec3d selfPos = MC.player.getEntityPos();
        for (PlayerEntity player : world.getPlayers()) {
            if (player == MC.player || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            if (player.squaredDistanceTo(selfPos) > RANGE_SQ) {
                continue;
            }

            VertexRendering.drawOutline(
                    matrices,
                    buffer,
                    VoxelShapes.cuboid(player.getBoundingBox().expand(0.045)),
                    0.0,
                    0.0,
                    0.0,
                    0xFFFF4141,
                    2.5F
            );
            count++;
        }
        return count;
    }

    private static int renderChests(ClientWorld world, MatrixStack matrices, BufferBuilder buffer) {
        int count = 0;
        ChunkPos center = new ChunkPos(MC.player.getBlockPos());
        Vec3d selfPos = MC.player.getEntityPos();

        for (int dx = -RANGE_CHUNKS; dx <= RANGE_CHUNKS; dx++) {
            for (int dz = -RANGE_CHUNKS; dz <= RANGE_CHUNKS; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ChestBlockEntity) && !(blockEntity instanceof EnderChestBlockEntity)) {
                        continue;
                    }

                    BlockPos pos = blockEntity.getPos();
                    double cx = pos.getX() + 0.5;
                    double cy = pos.getY() + 0.5;
                    double cz = pos.getZ() + 0.5;
                    if (selfPos.squaredDistanceTo(cx, cy, cz) > RANGE_SQ) {
                        continue;
                    }

                    int color = blockEntity instanceof EnderChestBlockEntity ? 0xFFB450FF : 0xFFFFB923;
                    Box box = new Box(pos).expand(0.025);
                    VertexRendering.drawOutline(
                            matrices,
                            buffer,
                            VoxelShapes.cuboid(box),
                            0.0,
                            0.0,
                            0.0,
                            color,
                            2.5F
                    );
                    count++;
                }
            }
        }
        return count;
    }

    private static void draw(BuiltBuffer built) {
        try (built) {
            BuiltBuffer.DrawParameters params = built.getDrawParameters();
            VertexFormat format = params.format();
            GpuBuffer vertices = format.uploadImmediateVertexBuffer(built.getBuffer());

            RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(params.mode());
            GpuBuffer indices = shapeIndexBuffer.getIndexBuffer(params.indexCount());
            VertexFormat.IndexType indexType = shapeIndexBuffer.getIndexType();

            GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().write(
                    RenderSystem.getModelViewMatrix(),
                    COLOR_MODULATOR,
                    MODEL_OFFSET,
                    TEXTURE_MATRIX
            );

            Framebuffer framebuffer = MC.getFramebuffer();
            if (framebuffer.getColorAttachmentView() == null || framebuffer.getDepthAttachmentView() == null) {
                return;
            }

            try (RenderPass pass = RenderSystem.getDevice()
                    .createCommandEncoder()
                    .createRenderPass(
                            () -> "simpleesp through-wall ESP",
                            framebuffer.getColorAttachmentView(),
                            OptionalInt.empty(),
                            framebuffer.getDepthAttachmentView(),
                            OptionalDouble.empty()
                    )) {
                pass.setPipeline(THROUGH_WALL_LINES);
                RenderSystem.bindDefaultUniforms(pass);
                pass.setUniform("DynamicTransforms", dynamicTransforms);
                pass.setVertexBuffer(0, vertices);
                pass.setIndexBuffer(indices, indexType);
                pass.drawIndexed(0, 0, params.indexCount(), 1);
            }
        }
    }

    public static void close() {
        ALLOCATOR.close();
    }
}
