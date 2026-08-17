package com.vanguard.image2schem;

import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.minecraft.client.gui.screen.TitleScreen;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;

/** End-to-end smoke test: boot a real Minecraft client and open Image2Schem through the physical K keybind. */
@SuppressWarnings("UnstableApiUsage")
public final class Image2SchemClientGameTest implements FabricClientGameTest {
    @Override
    public void runTest(ClientGameTestContext context) {
        context.getInput().resizeWindow(1100, 700);

        // TitleScreen is a deterministic post-startup state and production intentionally supports K from here.
        context.waitForScreen(TitleScreen.class);
        context.getInput().pressKey(GLFW.GLFW_KEY_K);
        context.waitForScreen(Image2SchemScreen.class);

        context.runOnClient(client -> {
            if (!(client.currentScreen instanceof Image2SchemScreen)) {
                throw new AssertionError("K did not open Image2SchemScreen");
            }
        });

        // Rendering/init is part of the smoke test; GUI crashes or broken sizing fail before this returns.
        context.waitTicks(3);
        Path screenshot = context.takeScreenshot("image2schem-k-menu");
        if (!Files.isRegularFile(screenshot) || size(screenshot) < 1_000) {
            throw new AssertionError("Image2Schem screen screenshot was not produced correctly: " + screenshot);
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
