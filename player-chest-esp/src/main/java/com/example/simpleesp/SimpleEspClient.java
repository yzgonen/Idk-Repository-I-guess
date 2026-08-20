package com.example.simpleesp;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.ChestBlockEntity;
import net.minecraft.block.entity.EnderChestBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.InputUtil;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.WorldChunk;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static KeyBinding togglePlayers;
    private static KeyBinding toggleChests;

    private static boolean playerEsp = true;
    private static boolean chestEsp = true;

    private static final int CHEST_SCAN_CHUNK_RADIUS = 8;
    private static final double MAX_PLAYER_DISTANCE_SQ = 256.0 * 256.0;

    @Override
    public void onInitializeClient() {
        togglePlayers = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.simpleesp.toggle_players",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                "category.simpleesp"
        ));

        toggleChests = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.simpleesp.toggle_chests",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.simpleesp"
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
        });

        WorldRenderEvents.LAST.register(context -> {
            if (MC.world == null || MC.player == null || (!playerEsp && !chestEsp)) return;
            MatrixStack matrices = context.matrixStack();
            if (matrices == null) return;

            Vec3d camera = context.camera().getPos();
            matrices.push();
            matrices.translate(-camera.x, -camera.y, -camera.z);

            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.disableDepthTest();
            RenderSystem.disableCull();
            RenderSystem.lineWidth(2.0F);
            RenderSystem.setShader(GameRenderer::getPositionColorProgram);

            BufferBuilder buffer = Tessellator.getInstance().begin(VertexFormat.DrawMode.DEBUG_LINES, VertexFormats.POSITION_COLOR);
            Matrix4f matrix = matrices.peek().getPositionMatrix();

            if (playerEsp) {
                renderPlayers(MC.world, buffer, matrix);
            }
            if (chestEsp) {
                renderChests(MC.world, buffer, matrix);
            }

            BufferRenderer.drawWithGlobalProgram(buffer.end());

            RenderSystem.lineWidth(1.0F);
            RenderSystem.enableCull();
            RenderSystem.enableDepthTest();
            RenderSystem.disableBlend();
            matrices.pop();
        });
    }

    private static void renderPlayers(ClientWorld world, BufferBuilder buffer, Matrix4f matrix) {
        Vec3d selfPos = MC.player.getPos();
        for (PlayerEntity player : world.getPlayers()) {
            if (player == MC.player || player.isRemoved() || player.isSpectator()) continue;
            if (player.squaredDistanceTo(selfPos) > MAX_PLAYER_DISTANCE_SQ) continue;

            Box box = player.getBoundingBox().expand(0.04);
            drawBox(buffer, matrix, box, 255, 65, 65, 255);
        }
    }

    private static void renderChests(ClientWorld world, BufferBuilder buffer, Matrix4f matrix) {
        ChunkPos center = new ChunkPos(MC.player.getBlockPos());

        for (int dx = -CHEST_SCAN_CHUNK_RADIUS; dx <= CHEST_SCAN_CHUNK_RADIUS; dx++) {
            for (int dz = -CHEST_SCAN_CHUNK_RADIUS; dz <= CHEST_SCAN_CHUNK_RADIUS; dz++) {
                int chunkX = center.x + dx;
                int chunkZ = center.z + dz;
                if (!world.isChunkLoaded(chunkX, chunkZ)) continue;

                WorldChunk chunk = world.getChunk(chunkX, chunkZ);
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    if (!(blockEntity instanceof ChestBlockEntity) && !(blockEntity instanceof EnderChestBlockEntity)) {
                        continue;
                    }

                    BlockPos pos = blockEntity.getPos();
                    Box box = new Box(pos).expand(0.02);

                    if (blockEntity instanceof EnderChestBlockEntity) {
                        drawBox(buffer, matrix, box, 180, 80, 255, 255);
                    } else {
                        drawBox(buffer, matrix, box, 255, 185, 35, 255);
                    }
                }
            }
        }
    }

    private static void drawBox(BufferBuilder buffer, Matrix4f matrix, Box b, int r, int g, int blue, int a) {
        float x1 = (float) b.minX;
        float y1 = (float) b.minY;
        float z1 = (float) b.minZ;
        float x2 = (float) b.maxX;
        float y2 = (float) b.maxY;
        float z2 = (float) b.maxZ;

        line(buffer, matrix, x1,y1,z1, x2,y1,z1, r,g,blue,a);
        line(buffer, matrix, x2,y1,z1, x2,y1,z2, r,g,blue,a);
        line(buffer, matrix, x2,y1,z2, x1,y1,z2, r,g,blue,a);
        line(buffer, matrix, x1,y1,z2, x1,y1,z1, r,g,blue,a);

        line(buffer, matrix, x1,y2,z1, x2,y2,z1, r,g,blue,a);
        line(buffer, matrix, x2,y2,z1, x2,y2,z2, r,g,blue,a);
        line(buffer, matrix, x2,y2,z2, x1,y2,z2, r,g,blue,a);
        line(buffer, matrix, x1,y2,z2, x1,y2,z1, r,g,blue,a);

        line(buffer, matrix, x1,y1,z1, x1,y2,z1, r,g,blue,a);
        line(buffer, matrix, x2,y1,z1, x2,y2,z1, r,g,blue,a);
        line(buffer, matrix, x2,y1,z2, x2,y2,z2, r,g,blue,a);
        line(buffer, matrix, x1,y1,z2, x1,y2,z2, r,g,blue,a);
    }

    private static void line(BufferBuilder buffer, Matrix4f matrix,
                             float x1, float y1, float z1,
                             float x2, float y2, float z2,
                             int r, int g, int b, int a) {
        buffer.vertex(matrix, x1, y1, z1).color(r, g, b, a);
        buffer.vertex(matrix, x2, y2, z2).color(r, g, b, a);
    }

    private static void hud(String message) {
        if (MC.player != null) {
            MC.player.sendMessage(Text.literal(message), true);
        }
    }
}
