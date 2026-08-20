package com.example.simpleesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static boolean playerEsp = true;
    private static volatile boolean xrayEnabled = false;

    private static boolean pWasDown = false;
    private static boolean oWasDown = false;
    private static boolean backslashWasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) {
                return;
            }

            long window = client.getWindow().getHandle();
            boolean pDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
            boolean oDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_O) == GLFW.GLFW_PRESS;
            boolean backslashDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSLASH) == GLFW.GLFW_PRESS;
            boolean allowHotkeys = client.currentScreen == null;

            if (allowHotkeys && pDown && !pWasDown) {
                playerEsp = !playerEsp;
                hud("Player ESP: " + (playerEsp ? "ON" : "OFF"));
            }

            if (allowHotkeys && oDown && !oWasDown) {
                hardRefreshChestEsp(client);
            }

            if (allowHotkeys && backslashDown && !backslashWasDown) {
                xrayEnabled = !xrayEnabled;
                if (client.world != null && client.player != null) {
                    if (xrayEnabled) {
                        XrayOverlayRenderer.hardRefresh(client.world, client.player.getEntityPos());
                    } else {
                        XrayOverlayRenderer.clear();
                    }
                }
                if (client.worldRenderer != null) {
                    client.worldRenderer.reload();
                }
                hud("X-Ray: " + (xrayEnabled ? "ON" : "OFF"));
            }

            if (xrayEnabled && client.world != null && client.player != null) {
                XrayOverlayRenderer.tickScan(client.world, client.player.getEntityPos());
            }

            pWasDown = pDown;
            oWasDown = oDown;
            backslashWasDown = backslashDown;
        });

        WorldRenderEvents.END_MAIN.register(ThroughWallEspRenderer::render);
        WorldRenderEvents.END_MAIN.register(XrayOverlayRenderer::render);
    }

    private static void hardRefreshChestEsp(MinecraftClient client) {
        if (client.world != null && client.player != null) {
            ThroughWallEspRenderer.hardRefreshChestScan(client.world, client.player.getEntityPos());
        }
        if (client.worldRenderer != null) {
            client.worldRenderer.reload();
        }
        hud("Chest ESP: HARD REFRESH");
    }

    public static boolean isPlayerEspEnabled() {
        return playerEsp;
    }

    public static boolean isChestEspEnabled() {
        return true;
    }

    public static boolean isXrayEnabled() {
        return xrayEnabled;
    }

    public static Vec3d getEspOrigin() {
        if (MC.player != null) {
            return MC.player.getEntityPos();
        }
        return Vec3d.ZERO;
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

    private static void hud(String message) {
        if (MC.player != null) {
            MC.player.sendMessage(Text.literal(message), true);
        }
    }
}
