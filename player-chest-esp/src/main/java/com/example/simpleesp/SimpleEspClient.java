package com.example.simpleesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final int AUTO_CHEST_RESCAN_TICKS = 20;

    private static boolean playerEsp = true;
    private static boolean pWasDown = false;
    private static boolean oWasDown = false;
    private static int chestRescanTicks = 0;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) {
                return;
            }

            long window = client.getWindow().getHandle();
            boolean pDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
            boolean oDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_O) == GLFW.GLFW_PRESS;
            boolean allowHotkeys = client.currentScreen == null;

            if (allowHotkeys && pDown && !pWasDown) {
                playerEsp = !playerEsp;
                hud("Player ESP: " + (playerEsp ? "ON" : "OFF"));
            }

            if (allowHotkeys && oDown && !oWasDown) {
                hardRefreshChestEsp(client, true);
                chestRescanTicks = 0;
            }

            if (client.world != null && client.player != null) {
                chestRescanTicks++;
                if (chestRescanTicks >= AUTO_CHEST_RESCAN_TICKS) {
                    hardRefreshChestEsp(client, false);
                    chestRescanTicks = 0;
                }
            } else {
                chestRescanTicks = 0;
            }

            pWasDown = pDown;
            oWasDown = oDown;
        });

        WorldRenderEvents.END_MAIN.register(ThroughWallEspRenderer::render);
    }

    private static void hardRefreshChestEsp(MinecraftClient client, boolean showHud) {
        if (client.world != null && client.player != null) {
            ThroughWallEspRenderer.hardRefreshChestScan(client.world, client.player.getEntityPos());
        }
        if (showHud) {
            hud("Chest ESP: HARD REFRESH");
        }
    }

    public static boolean isPlayerEspEnabled() {
        return playerEsp;
    }

    public static boolean isChestEspEnabled() {
        return true;
    }

    public static Vec3d getEspOrigin() {
        if (MC.player != null) {
            return MC.player.getEntityPos();
        }
        return Vec3d.ZERO;
    }

    private static void hud(String message) {
        if (MC.player != null) {
            MC.player.sendMessage(Text.literal(message), true);
        }
    }
}
