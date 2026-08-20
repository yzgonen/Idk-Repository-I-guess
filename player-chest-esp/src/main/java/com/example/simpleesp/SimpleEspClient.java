package com.example.simpleesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();
    private static final double FREECAM_MAX_DISTANCE = 10.0 * 16.0;
    private static final double FREECAM_SPEED = 0.8;
    private static final double FREECAM_BOOST_SPEED = 2.5;

    private static boolean playerEsp = true;
    private static volatile boolean xrayEnabled = false;
    private static boolean freecamEnabled = false;

    private static ArmorStandEntity freecamCamera;
    private static Vec3d freecamOrigin;
    private static Input savedPlayerInput;
    private static Input frozenPlayerInput;

    private static boolean pWasDown = false;
    private static boolean oWasDown = false;
    private static boolean backslashWasDown = false;
    private static boolean equalWasDown = false;

    @Override
    public void onInitializeClient() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.getWindow() == null) {
                return;
            }

            if ((client.world == null || client.player == null) && freecamEnabled) {
                clearFreecamState();
            }

            long window = client.getWindow().getHandle();
            boolean pDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_P) == GLFW.GLFW_PRESS;
            boolean oDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_O) == GLFW.GLFW_PRESS;
            boolean backslashDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_BACKSLASH) == GLFW.GLFW_PRESS;
            boolean equalDown = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_EQUAL) == GLFW.GLFW_PRESS;
            boolean allowHotkeys = client.currentScreen == null;

            if (allowHotkeys && pDown && !pWasDown) {
                playerEsp = !playerEsp;
                hud("Player ESP: " + (playerEsp ? "ON" : "OFF"));
            }

            if (allowHotkeys && oDown && !oWasDown) {
                refreshChestEsp(client);
            }

            if (allowHotkeys && backslashDown && !backslashWasDown) {
                xrayEnabled = !xrayEnabled;
                hud("X-Ray: " + (xrayEnabled ? "ON" : "OFF"));
                if (client.worldRenderer != null) {
                    client.worldRenderer.reload();
                }
            }

            if (allowHotkeys && equalDown && !equalWasDown) {
                toggleFreecam(client);
            }

            if (freecamEnabled && client.player != null && freecamCamera != null) {
                if (frozenPlayerInput != null && client.player.input != frozenPlayerInput) {
                    client.player.input = frozenPlayerInput;
                }
                if (allowHotkeys) {
                    tickFreecamMovement(window);
                }
            }

            pWasDown = pDown;
            oWasDown = oDown;
            backslashWasDown = backslashDown;
            equalWasDown = equalDown;
        });

        WorldRenderEvents.END_MAIN.register(ThroughWallEspRenderer::render);
    }

    private static void refreshChestEsp(MinecraftClient client) {
        if (client.worldRenderer != null) {
            client.worldRenderer.reload();
        }
        hud("Chest ESP: refreshed");
    }

    private static void toggleFreecam(MinecraftClient client) {
        if (freecamEnabled) {
            disableFreecam(client);
        } else {
            enableFreecam(client);
        }
    }

    private static void enableFreecam(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return;
        }

        freecamCamera = new ArmorStandEntity(
                client.world,
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()
        );
        freecamCamera.setInvisible(true);
        freecamCamera.setNoGravity(true);
        freecamCamera.setYaw(client.player.getYaw());
        freecamCamera.setPitch(client.player.getPitch());
        freecamCamera.lastYaw = client.player.lastYaw;
        freecamCamera.lastPitch = client.player.lastPitch;

        freecamOrigin = freecamCamera.getEntityPos();
        savedPlayerInput = client.player.input;
        frozenPlayerInput = new Input();
        client.player.input = frozenPlayerInput;
        client.setCameraEntity(freecamCamera);
        freecamEnabled = true;
        hud("Freecam: ON (10 chunk limit)");
    }

    private static void disableFreecam(MinecraftClient client) {
        if (client.player != null) {
            if (savedPlayerInput != null) {
                client.player.input = savedPlayerInput;
            }
            client.setCameraEntity(client.player);
        }
        clearFreecamState();
        hud("Freecam: OFF");
    }

    private static void clearFreecamState() {
        freecamEnabled = false;
        freecamCamera = null;
        freecamOrigin = null;
        savedPlayerInput = null;
        frozenPlayerInput = null;
    }

    private static void tickFreecamMovement(long window) {
        if (freecamCamera == null || freecamOrigin == null) {
            return;
        }

        double forwardInput = keyDown(window, GLFW.GLFW_KEY_W) - keyDown(window, GLFW.GLFW_KEY_S);
        double strafeInput = keyDown(window, GLFW.GLFW_KEY_D) - keyDown(window, GLFW.GLFW_KEY_A);
        double verticalInput = keyDown(window, GLFW.GLFW_KEY_SPACE)
                - Math.max(keyDown(window, GLFW.GLFW_KEY_LEFT_SHIFT), keyDown(window, GLFW.GLFW_KEY_RIGHT_SHIFT));

        if (forwardInput == 0.0 && strafeInput == 0.0 && verticalInput == 0.0) {
            return;
        }

        double yaw = Math.toRadians(freecamCamera.getYaw());
        Vec3d forward = new Vec3d(-Math.sin(yaw), 0.0, Math.cos(yaw));
        Vec3d right = new Vec3d(Math.cos(yaw), 0.0, Math.sin(yaw));
        Vec3d movement = forward.multiply(forwardInput)
                .add(right.multiply(strafeInput))
                .add(0.0, verticalInput, 0.0);

        if (movement.lengthSquared() <= 1.0E-8) {
            return;
        }

        double speed = (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
                ? FREECAM_BOOST_SPEED
                : FREECAM_SPEED;
        movement = movement.normalize().multiply(speed);

        Vec3d current = freecamCamera.getEntityPos();
        Vec3d next = current.add(movement);
        Vec3d fromOrigin = next.subtract(freecamOrigin);
        double distance = fromOrigin.length();
        if (distance > FREECAM_MAX_DISTANCE) {
            next = freecamOrigin.add(fromOrigin.multiply(FREECAM_MAX_DISTANCE / distance));
        }

        freecamCamera.lastX = current.x;
        freecamCamera.lastY = current.y;
        freecamCamera.lastZ = current.z;
        freecamCamera.setPosition(next.x, next.y, next.z);
    }

    private static int keyDown(long window, int key) {
        return GLFW.glfwGetKey(window, key) == GLFW.GLFW_PRESS ? 1 : 0;
    }

    public static void redirectLookToFreecam(double cursorDeltaX, double cursorDeltaY) {
        if (freecamEnabled && freecamCamera != null) {
            freecamCamera.changeLookDirection(cursorDeltaX, cursorDeltaY);
        }
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

    public static boolean isFreecamEnabled() {
        return freecamEnabled;
    }

    public static Vec3d getEspOrigin() {
        if (freecamEnabled && freecamCamera != null) {
            return freecamCamera.getEntityPos();
        }
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
