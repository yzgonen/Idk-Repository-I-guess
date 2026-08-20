package com.example.simpleesp;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.world.WorldRenderEvents;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

public final class SimpleEspClient implements ClientModInitializer {
    private static final MinecraftClient MC = MinecraftClient.getInstance();

    private static KeyBinding togglePlayers;
    private static KeyBinding toggleChests;
    private static KeyBinding toggleXray;

    private static boolean playerEsp = true;
    private static boolean chestEsp = true;
    private static volatile boolean xrayEnabled = false;

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

        WorldRenderEvents.END_MAIN.register(ThroughWallEspRenderer::render);
    }

    public static boolean isPlayerEspEnabled() {
        return playerEsp;
    }

    public static boolean isChestEspEnabled() {
        return chestEsp;
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

    private static void hud(String message) {
        if (MC.player != null) {
            MC.player.sendMessage(Text.literal(message), true);
        }
    }
}
