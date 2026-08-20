package com.example.simpleesp;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.platform.DepthTestFunction;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderContext;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.MappableRingBuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;
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
    private static MappableRingBuffer vertexBuffer;

    private static final List<ChestTarget> CHEST_TARGETS = new ArrayList<>();
    private static ClientWorld chestScanWorld;
    private static int chestScanChunkX = Integer.MIN_VALUE;
    private static int chestScanChunkZ = Integer.MIN_VALUE;

    private static final RenderPipeline THROUGH_WALL_LINES = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation(Identifier.of("simpleesp", "pipeline/lines_through_walls"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private ThroughWallEspRenderer() {
    }

    public static void render(WorldRenderContext context) {
        if (MC.world == null || MC.player == null) {
            return;
        }

        MatrixStack matrices = context.matrices();
        Vec3d camera = context.worldState().cameraRenderState.pos;
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

        BuiltBuffer builtBuffer = buffer.end();
        BuiltBuffer.DrawParameters drawParameters = builtBuffer.getDrawParameters();
        VertexFormat format = drawParameters.format();
        GpuBuffer vertices = upload(drawParameters, format, builtBuffer);
        draw(builtBuffer, drawParameters, vertices, format);
        vertexBuffer.rotate();
    }

    private static int renderPlayers(ClientWorld world, MatrixStack matrices, BufferBuilder buffer) {
        int count = 0;
        Vec3d scanOrigin = SimpleEspClient.getEspOrigin();
        for (PlayerEntity player : world.getPlayers()) {
            if (player == MC.player || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            if (player.squaredDistanceTo(scanOrigin) > RANGE_SQ) {
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
        Vec3d scanOrigin = SimpleEspClient.getEspOrigin();
        ensureChestScan(world, scanOrigin);

        int count = 0;
        for (ChestTarget target : CHEST_TARGETS) {
            BlockPos pos = target.pos();
            double cx = pos.getX() + 0.5;
            double cy = pos.getY() + 0.5;
            double cz = pos.getZ() + 0.5;
            if (scanOrigin.squaredDistanceTo(cx, cy, cz) > RANGE_SQ) {
                continue;
            }

            int color = target.ender() ? 0xFFB450FF : 0xFFFFB923;
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
        return count;
    }

    private static void ensureChestScan(ClientWorld world, Vec3d origin) {
        ChunkPos center = new ChunkPos(BlockPos.ofFloored(origin.x, origin.y, origin.z));
        if (chestScanWorld != world || center.x != chestScanChunkX || center.z != chestScanChunkZ) {
            hardRefreshChestScan(world, origin);
        }
    }

    public static void hardRefreshChestScan(ClientWorld world, Vec3d origin) {
        CHEST_TARGETS.clear();
        chestScanWorld = world;

        if (world == null) {
            chestScanChunkX = Integer.MIN_VALUE;
            chestScanChunkZ = Integer.MIN_VALUE;
            return;
        }

        ChunkPos center = new ChunkPos(BlockPos.ofFloored(origin.x, origin.y, origin.z));
        chestScanChunkX = center.x;
        chestScanChunkZ = center.z;

        for (int dx = -RANGE_CHUNKS; dx <= RANGE_CHUNKS; dx++) {
            for (int dz = -RANGE_CHUNKS; dz <= RANGE_CHUNKS; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (blockEntity instanceof EnderChestBlockEntity) {
                        CHEST_TARGETS.add(new ChestTarget(blockEntity.getPos().toImmutable(), true));
                    } else if (blockEntity instanceof ChestBlockEntity) {
                        CHEST_TARGETS.add(new ChestTarget(blockEntity.getPos().toImmutable(), false));
                    }
                }
            }
        }
    }

    private static GpuBuffer upload(BuiltBuffer.DrawParameters drawParameters, VertexFormat format, BuiltBuffer builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            vertexBuffer = new MappableRingBuffer(
                    () -> "simpleesp through-wall ESP",
                    GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE,
                    vertexBufferSize
            );
        }

        CommandEncoder commandEncoder = RenderSystem.getDevice().createCommandEncoder();
        try (GpuBuffer.MappedView mappedView = commandEncoder.mapBuffer(
                vertexBuffer.getBlocking().slice(0, builtBuffer.getBuffer().remaining()),
                false,
                true
        )) {
            MemoryUtil.memCopy(builtBuffer.getBuffer(), mappedView.data());
        }
        return vertexBuffer.getBlocking();
    }

    private static void draw(
            BuiltBuffer builtBuffer,
            BuiltBuffer.DrawParameters drawParameters,
            GpuBuffer vertices,
            VertexFormat format
    ) {
        RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(THROUGH_WALL_LINES.getVertexFormatMode());
        GpuBuffer indices = shapeIndexBuffer.getIndexBuffer(drawParameters.indexCount());
        VertexFormat.IndexType indexType = shapeIndexBuffer.getIndexType();

        GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms().write(
                RenderSystem.getModelViewMatrix(),
                COLOR_MODULATOR,
                MODEL_OFFSET,
                TEXTURE_MATRIX
        );

        try (RenderPass renderPass = RenderSystem.getDevice()
                .createCommandEncoder()
                .createRenderPass(
                        () -> "simpleesp through-wall ESP rendering",
                        MC.getFramebuffer().getColorAttachmentView(),
                        OptionalInt.empty(),
                        MC.getFramebuffer().getDepthAttachmentView(),
                        OptionalDouble.empty()
                )) {
            renderPass.setPipeline(THROUGH_WALL_LINES);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    public static void close() {
        CHEST_TARGETS.clear();
        chestScanWorld = null;
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    private record ChestTarget(BlockPos pos, boolean ender) {
    }
}
