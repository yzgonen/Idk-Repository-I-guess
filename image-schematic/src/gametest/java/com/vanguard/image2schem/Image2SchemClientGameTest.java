package com.vanguard.image2schem;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** End-to-end smoke test that boots a real Minecraft client and opens the mod through its actual K keybind. */
@SuppressWarnings("UnstableApiUsage")
public final class Image2SchemClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1100, 700);

        try (TestSingleplayerContext singleplayer = context.worldBuilder().create()) {
            singleplayer.getClientLevel().waitForChunksRender();

            context.runOnClient(client -> {
                if (client.currentScreen != null) {
                    throw new AssertionError("Expected gameplay with no screen before testing K, got " + client.currentScreen.getClass().getName());
                }
            });

            // Exercise the same physical key path the user uses, rather than directly constructing the screen.
            context.getInput().pressKey(GLFW.GLFW_KEY_K);
            context.waitForScreen(Image2SchemScreen.class);

            context.runOnClient(client -> {
                if (!(client.currentScreen instanceof Image2SchemScreen)) {
                    throw new AssertionError("K did not open Image2SchemScreen");
                }
            });

            // Rendering the screen is part of the smoke test; a render/init crash will fail before this returns.
            context.waitTicks(3);
            Path screenshot = context.takeScreenshot("image2schem-k-menu");
            if (!Files.isRegularFile(screenshot) || size(screenshot) < 1_000) {
                throw new AssertionError("Image2Schem screen screenshot was not produced correctly: " + screenshot);
            }

            context.setScreen(() -> null);
            context.waitForScreen(null);
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
