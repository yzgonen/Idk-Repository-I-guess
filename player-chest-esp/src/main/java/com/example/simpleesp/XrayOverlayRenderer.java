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
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.MappableRingBuffer;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.BufferAllocator;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.OptionalInt;

public final class XrayOverlayRenderer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    // Scan farther than the old ESP range, but only through chunks the client actually has loaded.
    private static final int SCAN_RANGE_CHUNKS = 16;
    private static final double SCAN_RANGE_BLOCKS = SCAN_RANGE_CHUNKS * 16.0;
    private static final double SCAN_RANGE_SQ = SCAN_RANGE_BLOCKS * SCAN_RANGE_BLOCKS;
    private static final double NEAR_ORE_RANGE_SQ = 96.0 * 96.0;
    private static final double CAVE_RENDER_RANGE_SQ = 176.0 * 176.0;

    private static final int CHUNKS_PER_TICK = 2;
    private static final int CAVE_SCAN_TOP_Y = 112;
    private static final int MAX_CAVE_MARKERS_PER_CHUNK = 28;
    private static final int MAX_LAVA_MARKERS_PER_CHUNK = 180;

    private static final int MAX_NEAR_ORE_BOXES = 4500;
    private static final int MAX_FAR_ORE_BOXES = 5000;
    private static final int MAX_LAVA_BOXES = 2200;
    private static final int MAX_CAVE_BOXES = 1800;

    private static final BufferAllocator ALLOCATOR = new BufferAllocator(16 * 1024 * 1024);
    private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
    private static final Vector3f MODEL_OFFSET = new Vector3f();
    private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();
    private static MappableRingBuffer vertexBuffer;

    private static final Map<Long, ChunkScanResult> RESULTS = new HashMap<>();
    private static final Deque<ChunkPos> SCAN_QUEUE = new ArrayDeque<>();
    private static ClientWorld scanWorld;
    private static int scanCenterChunkX = Integer.MIN_VALUE;
    private static int scanCenterChunkZ = Integer.MIN_VALUE;
    private static int scanTick;

    private static final RenderPipeline XRAY_LINES = RenderPipelines.register(
            RenderPipeline.builder(RenderPipelines.RENDERTYPE_LINES_SNIPPET)
                    .withLocation(Identifier.of("simpleesp", "pipeline/xray_overlay_lines"))
                    .withDepthTestFunction(DepthTestFunction.NO_DEPTH_TEST)
                    .withDepthWrite(false)
                    .build()
    );

    private XrayOverlayRenderer() {
    }

    public static void hardRefresh(ClientWorld world, Vec3d origin) {
        RESULTS.clear();
        SCAN_QUEUE.clear();
        scanWorld = world;
        scanTick = 0;

        if (world == null) {
            scanCenterChunkX = Integer.MIN_VALUE;
            scanCenterChunkZ = Integer.MIN_VALUE;
            return;
        }

        ChunkPos center = new ChunkPos(BlockPos.ofFloored(origin));
        scanCenterChunkX = center.x;
        scanCenterChunkZ = center.z;

        if (world.isChunkLoaded(center.x, center.z)) {
            scanOneChunk(world, center);
        }
        rebuildQueue(world, center, true);
    }

    public static void tickScan(ClientWorld world, Vec3d origin) {
        if (world == null) {
            clear();
            return;
        }

        ChunkPos center = new ChunkPos(BlockPos.ofFloored(origin));
        if (scanWorld != world) {
            hardRefresh(world, origin);
            return;
        }

        if (center.x != scanCenterChunkX || center.z != scanCenterChunkZ) {
            scanCenterChunkX = center.x;
            scanCenterChunkZ = center.z;
            rebuildQueue(world, center, false);
        }

        for (int i = 0; i < CHUNKS_PER_TICK; i++) {
            ChunkPos pos = SCAN_QUEUE.pollFirst();
            if (pos == null) {
                break;
            }
            if (world.isChunkLoaded(pos.x, pos.z)) {
                scanOneChunk(world, pos);
            }
        }

        scanTick++;
        // Keep the chunk the player is actively mining in fresh without rescanning the whole radius.
        if (scanTick % 20 == 0 && world.isChunkLoaded(center.x, center.z)) {
            scanOneChunk(world, center);
        }
    }

    private static void rebuildQueue(ClientWorld world, ChunkPos center, boolean forceAll) {
        SCAN_QUEUE.clear();

        RESULTS.entrySet().removeIf(entry -> {
            int cx = unpackChunkX(entry.getKey());
            int cz = unpackChunkZ(entry.getKey());
            return Math.abs(cx - center.x) > SCAN_RANGE_CHUNKS || Math.abs(cz - center.z) > SCAN_RANGE_CHUNKS;
        });

        List<ChunkPos> pending = new ArrayList<>();
        for (int dx = -SCAN_RANGE_CHUNKS; dx <= SCAN_RANGE_CHUNKS; dx++) {
            for (int dz = -SCAN_RANGE_CHUNKS; dz <= SCAN_RANGE_CHUNKS; dz++) {
                int cx = center.x + dx;
                int cz = center.z + dz;
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }
                long key = chunkKey(cx, cz);
                if (forceAll || !RESULTS.containsKey(key)) {
                    pending.add(new ChunkPos(cx, cz));
                }
            }
        }

        pending.sort(Comparator.comparingInt(pos -> {
            int dx = pos.x - center.x;
            int dz = pos.z - center.z;
            return dx * dx + dz * dz;
        }));
        SCAN_QUEUE.addAll(pending);
    }

    private static void scanOneChunk(ClientWorld world, ChunkPos chunkPos) {
        WorldChunk chunk = world.getChunk(chunkPos.x, chunkPos.z);
        List<OreTarget> ores = new ArrayList<>();
        List<BlockPos> lava = new ArrayList<>();
        List<BlockPos> caves = new ArrayList<>();

        int minX = chunkPos.getStartX();
        int minZ = chunkPos.getStartZ();
        int minY = world.getBottomY();
        int maxY = world.getTopYInclusive();
        int caveMaxY = Math.min(maxY, CAVE_SCAN_TOP_Y);

        BlockPos.Mutable mutable = new BlockPos.Mutable();
        for (int y = minY; y <= maxY; y++) {
            boolean caveSampleY = y <= caveMaxY && (y & 1) == 0;
            for (int z = minZ; z < minZ + 16; z++) {
                boolean caveSampleZ = (z & 1) == 0;
                for (int x = minX; x < minX + 16; x++) {
                    mutable.set(x, y, z);
                    BlockState state = chunk.getBlockState(mutable);

                    if (SimpleEspClient.shouldRenderInXray(state)) {
                        ores.add(new OreTarget(mutable.toImmutable(), oreColor(state)));
                        continue;
                    }

                    if (state.isOf(Blocks.LAVA)) {
                        if (lava.size() < MAX_LAVA_MARKERS_PER_CHUNK && isLavaBoundary(world, mutable)) {
                            lava.add(mutable.toImmutable());
                        }
                        continue;
                    }

                    if (caves.size() < MAX_CAVE_MARKERS_PER_CHUNK
                            && caveSampleY
                            && caveSampleZ
                            && (x & 1) == 0
                            && state.isAir()
                            && isCaveSurfaceCell(world, mutable)) {
                        caves.add(mutable.toImmutable());
                    }
                }
            }
        }

        RESULTS.put(chunkKey(chunkPos.x, chunkPos.z), new ChunkScanResult(ores, lava, caves));
    }

    private static boolean isLavaBoundary(ClientWorld world, BlockPos pos) {
        for (Direction direction : Direction.values()) {
            if (!world.getBlockState(pos.offset(direction)).isOf(Blocks.LAVA)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isCaveSurfaceCell(ClientWorld world, BlockPos pos) {
        int air = 0;
        int solid = 0;
        for (Direction direction : Direction.values()) {
            BlockState neighbor = world.getBlockState(pos.offset(direction));
            if (neighbor.isAir()) {
                air++;
            } else if (!neighbor.isOf(Blocks.LAVA) && neighbor.getFluidState().isEmpty()) {
                solid++;
            }
        }
        return air >= 2 && solid >= 2;
    }

    public static void render(WorldRenderContext context) {
        if (!SimpleEspClient.isXrayEnabled() || MC.world == null || MC.player == null) {
            return;
        }

        MatrixStack matrices = context.matrices();
        Vec3d camera = context.worldState().cameraRenderState.pos;
        Vec3d origin = MC.player.getEntityPos();

        matrices.push();
        matrices.translate(-camera.x, -camera.y, -camera.z);

        BufferBuilder buffer = new BufferBuilder(
                ALLOCATOR,
                XRAY_LINES.getVertexFormatMode(),
                XRAY_LINES.getVertexFormat()
        );

        int boxes = 0;
        boxes += renderMiningTarget(matrices, buffer);
        boxes += renderOres(origin, matrices, buffer, true, MAX_NEAR_ORE_BOXES);
        boxes += renderOres(origin, matrices, buffer, false, MAX_FAR_ORE_BOXES);
        boxes += renderLava(origin, matrices, buffer);
        boxes += renderCaves(origin, matrices, buffer);

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

    private static int renderMiningTarget(MatrixStack matrices, BufferBuilder buffer) {
        if (MC.crosshairTarget == null || MC.crosshairTarget.getType() != HitResult.Type.BLOCK) {
            return 0;
        }
        if (GLFW.glfwGetMouseButton(MC.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) != GLFW.GLFW_PRESS) {
            return 0;
        }

        BlockPos pos = ((BlockHitResult) MC.crosshairTarget).getBlockPos();
        VertexRendering.drawOutline(
                matrices,
                buffer,
                VoxelShapes.cuboid(new Box(pos).expand(0.06)),
                0.0,
                0.0,
                0.0,
                0xFFFFFFFF,
                3.5F
        );
        return 1;
    }

    private static int renderOres(Vec3d origin, MatrixStack matrices, BufferBuilder buffer, boolean near, int maxBoxes) {
        int count = 0;
        for (ChunkScanResult result : RESULTS.values()) {
            for (OreTarget ore : result.ores()) {
                BlockPos pos = ore.pos();
                double distanceSq = origin.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distanceSq > SCAN_RANGE_SQ) {
                    continue;
                }
                if (near ? distanceSq > NEAR_ORE_RANGE_SQ : distanceSq <= NEAR_ORE_RANGE_SQ) {
                    continue;
                }

                VertexRendering.drawOutline(
                        matrices,
                        buffer,
                        VoxelShapes.cuboid(new Box(pos).expand(0.035)),
                        0.0,
                        0.0,
                        0.0,
                        ore.color(),
                        near ? 2.6F : 2.0F
                );
                if (++count >= maxBoxes) {
                    return count;
                }
            }
        }
        return count;
    }

    private static int renderLava(Vec3d origin, MatrixStack matrices, BufferBuilder buffer) {
        int count = 0;
        for (ChunkScanResult result : RESULTS.values()) {
            for (BlockPos pos : result.lava()) {
                double distanceSq = origin.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distanceSq > SCAN_RANGE_SQ) {
                    continue;
                }

                VertexRendering.drawOutline(
                        matrices,
                        buffer,
                        VoxelShapes.cuboid(new Box(pos).expand(0.02)),
                        0.0,
                        0.0,
                        0.0,
                        0xFFFF5A1F,
                        1.8F
                );
                if (++count >= MAX_LAVA_BOXES) {
                    return count;
                }
            }
        }
        return count;
    }

    private static int renderCaves(Vec3d origin, MatrixStack matrices, BufferBuilder buffer) {
        int count = 0;
        for (ChunkScanResult result : RESULTS.values()) {
            for (BlockPos pos : result.caves()) {
                double distanceSq = origin.squaredDistanceTo(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);
                if (distanceSq > CAVE_RENDER_RANGE_SQ) {
                    continue;
                }

                // Slightly oversized sampled air cells form a wireframe shell that reads like cave volume.
                VertexRendering.drawOutline(
                        matrices,
                        buffer,
                        VoxelShapes.cuboid(new Box(pos).expand(0.22)),
                        0.0,
                        0.0,
                        0.0,
                        0xFF8FA4AD,
                        1.15F
                );
                if (++count >= MAX_CAVE_BOXES) {
                    return count;
                }
            }
        }
        return count;
    }

    private static int oreColor(BlockState state) {
        if (state.isIn(BlockTags.DIAMOND_ORES)) return 0xFF42E8F5;
        if (state.isIn(BlockTags.EMERALD_ORES)) return 0xFF38E86E;
        if (state.isIn(BlockTags.REDSTONE_ORES)) return 0xFFFF4242;
        if (state.isIn(BlockTags.LAPIS_ORES)) return 0xFF4D77FF;
        if (state.isIn(BlockTags.GOLD_ORES) || state.isOf(Blocks.NETHER_GOLD_ORE)) return 0xFFFFD43B;
        if (state.isIn(BlockTags.COPPER_ORES)) return 0xFFFF9855;
        if (state.isIn(BlockTags.IRON_ORES)) return 0xFFE8D9C8;
        if (state.isIn(BlockTags.COAL_ORES)) return 0xFF777777;
        if (state.isOf(Blocks.ANCIENT_DEBRIS)) return 0xFFB26F5A;
        if (state.isOf(Blocks.NETHER_QUARTZ_ORE)) return 0xFFF3EEE4;
        if (state.isOf(Blocks.SPAWNER) || state.isOf(Blocks.TRIAL_SPAWNER)) return 0xFFB56CFF;
        if (state.isOf(Blocks.CHEST) || state.isOf(Blocks.TRAPPED_CHEST) || state.isOf(Blocks.BARREL)) return 0xFFFFB923;
        if (state.isOf(Blocks.ENDER_CHEST)) return 0xFFB450FF;
        return 0xFFFFFFFF;
    }

    private static GpuBuffer upload(BuiltBuffer.DrawParameters drawParameters, VertexFormat format, BuiltBuffer builtBuffer) {
        int vertexBufferSize = drawParameters.vertexCount() * format.getVertexSize();
        if (vertexBuffer == null || vertexBuffer.size() < vertexBufferSize) {
            if (vertexBuffer != null) {
                vertexBuffer.close();
            }
            vertexBuffer = new MappableRingBuffer(
                    () -> "simpleesp xray overlay",
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

    private static void draw(BuiltBuffer builtBuffer, BuiltBuffer.DrawParameters drawParameters, GpuBuffer vertices, VertexFormat format) {
        RenderSystem.ShapeIndexBuffer shapeIndexBuffer = RenderSystem.getSequentialBuffer(XRAY_LINES.getVertexFormatMode());
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
                        () -> "simpleesp xray overlay rendering",
                        MC.getFramebuffer().getColorAttachmentView(),
                        OptionalInt.empty(),
                        MC.getFramebuffer().getDepthAttachmentView(),
                        OptionalDouble.empty()
                )) {
            renderPass.setPipeline(XRAY_LINES);
            RenderSystem.bindDefaultUniforms(renderPass);
            renderPass.setUniform("DynamicTransforms", dynamicTransforms);
            renderPass.setVertexBuffer(0, vertices);
            renderPass.setIndexBuffer(indices, indexType);
            renderPass.drawIndexed(0 / format.getVertexSize(), 0, drawParameters.indexCount(), 1);
        }

        builtBuffer.close();
    }

    public static void clear() {
        RESULTS.clear();
        SCAN_QUEUE.clear();
        scanWorld = null;
        scanCenterChunkX = Integer.MIN_VALUE;
        scanCenterChunkZ = Integer.MIN_VALUE;
        scanTick = 0;
    }

    public static void close() {
        clear();
        ALLOCATOR.close();
        if (vertexBuffer != null) {
            vertexBuffer.close();
            vertexBuffer = null;
        }
    }

    private static long chunkKey(int x, int z) {
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int unpackChunkX(long key) {
        return (int) (key >> 32);
    }

    private static int unpackChunkZ(long key) {
        return (int) key;
    }

    private record OreTarget(BlockPos pos, int color) {
    }

    private record ChunkScanResult(List<OreTarget> ores, List<BlockPos> lava, List<BlockPos> caves) {
    }
}
