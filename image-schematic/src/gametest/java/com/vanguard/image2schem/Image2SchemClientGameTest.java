package com.vanguard.image2schem;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * End-to-end smoke test that boots a real Minecraft client and opens the mod through its actual K keybind.
 *
 * Wait for Minecraft to finish startup and reach TitleScreen before clearing the screen. Clearing it earlier races
 * Minecraft startup, which legitimately installs TitleScreen a few ticks later and makes the test flaky.
 */
@SuppressWarnings("UnstableApiUsage")
public final class Image2SchemClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1100, 700);

        // Establish a stable startup point first; only then create the exact production condition currentScreen == null.
        context.waitForScreen(TitleScreen.class);
        context.setScreen(() -> null);
        context.waitTicks(1);
        assertNoScreen(context, "before testing K");

        // Exercise the same physical key path the user uses, rather than directly constructing the screen.
        context.getInput().pressKey(GLFW.GLFW_KEY_K);
        context.waitForScreen(Image2SchemScreen.class);

        context.runOnClient(client -> {
            if (!(client.currentScreen instanceof Image2SchemScreen)) {
                throw new AssertionError("K did not open Image2SchemScreen");
            }
        });

        // Rendering/init is part of the smoke test; a GUI crash will fail before this returns.
        context.waitTicks(3);
        Path screenshot = context.takeScreenshot("image2schem-k-menu");
        if (!Files.isRegularFile(screenshot) || size(screenshot) < 1_000) {
            throw new AssertionError("Image2Schem screen screenshot was not produced correctly: " + screenshot);
        }

        // Cleanup only; no need to assert a blank screen again because the tested behavior has already succeeded.
        context.setScreen(() -> null);
    }

    private static void assertNoScreen(ClientGameTestContext context, String phase) {
        context.runOnClient(client -> {
            if (client.currentScreen != null) {
                throw new AssertionError("Expected no screen " + phase + ", got " + client.currentScreen.getClass().getName());
            }
        });
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (Exception e) {
            return -1;
        }
    }
}
