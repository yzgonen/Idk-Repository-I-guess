package com.example.simpleesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.RenderLayers;
import net.minecraft.client.render.VertexConsumer;
import net.minecraft.client.render.VertexRendering;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.chunk.WorldChunk;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static KeyBinding togglePlayers;
    private static KeyBinding toggleChests;
    private static KeyBinding toggleXray;

    private static boolean playerEsp = true;
    private static boolean chestEsp = true;
    private static volatile boolean xrayEnabled = false;

    private static final int CHEST_SCAN_CHUNK_RADIUS = 8;
    private static final double MAX_PLAYER_DISTANCE_SQ = 256.0 * 256.0;

    @Override
    public void onInitializeClient() {
        togglePlayers = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.simpleesp.toggle_players",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                KeyBinding.Category.MISC
        ));

        toggleChests = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.simpleesp.toggle_chests",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                KeyBinding.Category.MISC
        ));

        toggleXray = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.simpleesp.toggle_xray",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_BACKSLASH,
                KeyBinding.Category.MISC
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (togglePlayers.wasPressed()) {
                playerEsp = !playerEsp;
                hud("Player ESP: " + (playerEsp ? "ON" : "OFF"));
            }
            while (toggleChests.wasPressed()) {
                chestEsp = !chestEsp;
                hud("Chest ESP: " + (chestEsp ? "ON" : "OFF"));
            }
            while (toggleXray.wasPressed()) {
                xrayEnabled = !xrayEnabled;
                hud("X-Ray: " + (xrayEnabled ? "ON" : "OFF"));
                if (client.worldRenderer != null) {
                    client.worldRenderer.reload();
                }
            }
        });

        WorldRenderEvents.END_MAIN.register(context -> {
            if (MC.world == null || MC.player == null || (!playerEsp && !chestEsp)) {
                return;
            }

            MatrixStack matrices = context.matrices();
            VertexConsumer lines = context.consumers().getBuffer(RenderLayers.LINES_TRANSLUCENT);
            Vec3d camera = MC.gameRenderer.getCamera().getCameraPos();

            matrices.push();
            matrices.translate(-camera.x, -camera.y, -camera.z);

            if (playerEsp) {
                renderPlayers(MC.world, matrices, lines);
            }
            if (chestEsp) {
                renderChests(MC.world, matrices, lines);
            }

            matrices.pop();
        });
    }

    public static boolean isXrayEnabled() {
        return xrayEnabled;
    }

    public static boolean shouldRenderInXray(BlockState state) {
        if (state.isIn(BlockTags.COAL_ORES)
                || state.isIn(BlockTags.IRON_ORES)
                || state.isIn(BlockTags.COPPER_ORES)
                || state.isIn(BlockTags.GOLD_ORES)
                || state.isIn(BlockTags.REDSTONE_ORES)
                || state.isIn(BlockTags.LAPIS_ORES)
                || state.isIn(BlockTags.DIAMOND_ORES)
                || state.isIn(BlockTags.EMERALD_ORES)) {
            return true;
        }

        if (state.getBlock() instanceof ShulkerBoxBlock) {
            return true;
        }

        return state.isOf(Blocks.NETHER_GOLD_ORE)
                || state.isOf(Blocks.NETHER_QUARTZ_ORE)
                || state.isOf(Blocks.ANCIENT_DEBRIS)
                || state.isOf(Blocks.SPAWNER)
                || state.isOf(Blocks.TRIAL_SPAWNER)
                || state.isOf(Blocks.VAULT)
                || state.isOf(Blocks.CHEST)
                || state.isOf(Blocks.TRAPPED_CHEST)
                || state.isOf(Blocks.ENDER_CHEST)
                || state.isOf(Blocks.BARREL);
    }

    private static void renderPlayers(ClientWorld world, MatrixStack matrices, VertexConsumer lines) {
        Vec3d selfPos = MC.player.getEntityPos();
        for (PlayerEntity player : world.getPlayers()) {
            if (player == MC.player || player.isRemoved() || player.isSpectator()) {
                continue;
            }
            if (player.squaredDistanceTo(selfPos) > MAX_PLAYER_DISTANCE_SQ) {
                continue;
            }

            drawBox(matrices, lines, player.getBoundingBox().expand(0.04), 0xFFFF4141);
        }
    }

    private static void renderChests(ClientWorld world, MatrixStack matrices, VertexConsumer lines) {
        ChunkPos center = new ChunkPos(MC.player.getBlockPos());

        for (int dx = -CHEST_SCAN_CHUNK_RADIUS; dx <= CHEST_SCAN_CHUNK_RADIUS; dx++) {
            for (int dz = -CHEST_SCAN_CHUNK_RADIUS; dz <= CHEST_SCAN_CHUNK_RADIUS; dz++) {
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
                    Box box = new Box(pos).expand(0.02);
                    int color = blockEntity instanceof EnderChestBlockEntity ? 0xFFB450FF : 0xFFFFB923;
                    drawBox(matrices, lines, box, color);
                }
            }
        }
    }

    private static void drawBox(MatrixStack matrices, VertexConsumer lines, Box box, int color) {
        VertexRendering.drawOutline(matrices, lines, VoxelShapes.cuboid(box), 0.0, 0.0, 0.0, color, 2.0F);
    }

    private static void hud(String message) {
        if (MC.player != null) {
            MC.player.sendMessage(Text.literal(message), true);
        }
    }
}
