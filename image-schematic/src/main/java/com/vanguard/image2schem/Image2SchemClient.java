package com.vanguard.image2schem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.IntConsumer;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Image2SchemClient implements ClientModInitializer {
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("image2schem");
    private static final Path INPUT = ROOT.resolve("input");
    private static final Path OUTPUT = ROOT.resolve("output");
    private static final int DEFAULT_DEPTH = 44;

    private static final KeyBinding OPEN_MENU = KeyBindingHelper.registerKeyBinding(new KeyBinding(
            "key.image2schem.open_menu",
            InputUtil.Type.KEYSYM,
            GLFW.GLFW_KEY_K,
            KeyBinding.Category.MISC
    ));

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(INPUT);
            Files.createDirectories(OUTPUT);
        } catch (Exception ignored) {}

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            while (OPEN_MENU.wasPressed()) {
                // Open only when gameplay has no other GUI active; do not steal K from screens or text fields.
                if (client.currentScreen == null) client.setScreen(new Image2SchemScreen());
            }
        });

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> {
            var root = literal("image2schem");
            root.then(literal("where").executes(ctx -> {
                ctx.getSource().sendFeedback(Text.literal("Image2Schem input folder: " + INPUT.toAbsolutePath()));
                return 1;
            }));

            var generate = literal("generate");
            var file = argument("filename", StringArgumentType.word());
            // Keep command limits aligned with the generic builder instead of accepting values it silently clamps.
            var width = argument("width", IntegerArgumentType.integer(64, 176));
            width.executes(ctx -> generate(ctx.getSource(), StringArgumentType.getString(ctx, "filename"), IntegerArgumentType.getInteger(ctx, "width"), DEFAULT_DEPTH));

            var depth = argument("depth", IntegerArgumentType.integer(24, 120));
            depth.executes(ctx -> generate(ctx.getSource(), StringArgumentType.getString(ctx, "filename"), IntegerArgumentType.getInteger(ctx, "width"), IntegerArgumentType.getInteger(ctx, "depth")));

            width.then(depth);
            file.then(width);
            generate.then(file);
            root.then(generate);
            dispatcher.register(root);
        });
    }

    static Path inputFolder() { return INPUT; }
    static Path outputFolder() { return OUTPUT; }

    static GenerationResult generatePath(Path in, int width, int depth, IntConsumer progress) throws Exception {
        if (in == null || !Files.isRegularFile(in)) throw new IllegalArgumentException("Choose a PNG or JPG first.");
        String lower = in.getFileName().toString().toLowerCase();
        if (!(lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg"))) {
            throw new IllegalArgumentException("Only PNG, JPG and JPEG files are supported.");
        }

        ImageConverter.Result result = ImageConverter.convert(in, width, depth, progress);
        String filename = in.getFileName().toString();
        String clean = filename.replaceAll("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_-]", "_");
        Path out = OUTPUT.resolve(clean + "-" + result.width() + "x" + result.height() + "x" + result.length() + ".schem");
        progress.accept(99);
        SchemWriter.write(out, result, clean);
        progress.accept(100);
        return new GenerationResult(out, result);
    }

    static GenerationResult generateFile(String filename, int width, int depth) throws Exception {
        Path in = INPUT.resolve(filename).normalize();
        if (!in.startsWith(INPUT)) throw new IllegalArgumentException("Invalid filename");
        return generatePath(in, width, depth, ignored -> {});
    }

    private static int generate(FabricClientCommandSource source, String filename, int width, int depth) {
        try {
            source.sendFeedback(Text.literal("Converting " + filename + "..."));
            GenerationResult result = generateFile(filename, width, depth);
            source.sendFeedback(Text.literal("Done: " + result.width() + "x" + result.height() + "x" + result.depth()));
            source.sendFeedback(Text.literal("Saved .schem: " + result.output().toAbsolutePath()));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("Image2Schem failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }

    record GenerationResult(Path output, ImageConverter.Result model) {
        int width() { return model.width(); }
        int height() { return model.height(); }
        int depth() { return model.length(); }
    }
}
