package com.vanguard.image2schem;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** End-to-end smoke test: boot a real Minecraft client, enter a world, and open Image2Schem with K. */
@SuppressWarnings("UnstableApiUsage")
public final class Image2SchemClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1100, 700);

        // Match the production condition exactly: a loaded game world with no GUI open.
        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientWorld().waitForChunksRender();
            context.waitForScreen(null);

            // Fabric's TestInput routes this through Minecraft's real keyboard handler, then waits a tick.
            context.getInput().pressKey(GLFW.GLFW_KEY_K);
            context.waitForScreen(Image2SchemScreen.class);

            context.runOnClient(client -> {
                if (!(client.currentScreen instanceof Image2SchemScreen)) {
                    throw new AssertionError("K did not open Image2SchemScreen during gameplay");
                }
            });

            // Rendering/init is part of the smoke test; GUI crashes or broken sizing fail before this returns.
            context.waitTicks(3);
            Path screenshot = context.takeScreenshot("image2schem-k-menu");
            if (!Files.isRegularFile(screenshot) || size(screenshot) < 1_000) {
                throw new AssertionError("Image2Schem screen screenshot was not produced correctly: " + screenshot);
            }
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return -1;
        }
    }
}
