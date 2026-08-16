package com.vanguard.image2schem;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.text.Text;

import java.nio.file.Files;
import java.nio.file.Path;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

public final class Image2SchemClient implements ClientModInitializer {
    private static final Path ROOT = FabricLoader.getInstance().getConfigDir().resolve("image2schem");
    private static final Path INPUT = ROOT.resolve("input");
    private static final Path OUTPUT = ROOT.resolve("output");

    @Override
    public void onInitializeClient() {
        try {
            Files.createDirectories(INPUT);
            Files.createDirectories(OUTPUT);
        } catch (Exception ignored) {}

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, access) -> dispatcher.register(
                literal("image2schem")
                        .then(literal("where")
                                .executes(ctx -> {
                                    ctx.getSource().sendFeedback(Text.literal("Image2Schem input folder: " + INPUT.toAbsolutePath()));
                                    return 1;
                                }))
                        .then(literal("generate")
                                .then(argument("filename", StringArgumentType.word())
                                        .then(argument("width", IntegerArgumentType.integer(8, 256))
                                                .executes(ctx -> generate(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "filename"),
                                                        IntegerArgumentType.getInteger(ctx, "width"), 4))
                                                .then(argument("depth", IntegerArgumentType.integer(1, 8))
                                                        .executes(ctx -> generate(ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "filename"),
                                                                IntegerArgumentType.getInteger(ctx, "width"),
                                                                IntegerArgumentType.getInteger(ctx, "depth"))))))))
        ));
    }

    private static int generate(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source,
                                String filename, int width, int depth) {
        try {
            Path in = INPUT.resolve(filename).normalize();
            if (!in.startsWith(INPUT)) throw new IllegalArgumentException("Invalid filename");
            if (!Files.exists(in)) {
                source.sendError(Text.literal("Image not found: " + in.toAbsolutePath()));
                return 0;
            }

            source.sendFeedback(Text.literal("Converting " + filename + "..."));
            ImageConverter.Result result = ImageConverter.convert(in, width, depth);
            String clean = filename.replaceAll("\\.[^.]+$", "").replaceAll("[^A-Za-z0-9_-]", "_");
            Path out = OUTPUT.resolve(clean + "-" + result.width() + "x" + result.height() + ".schem");
            SchemWriter.write(out, result, clean);

            source.sendFeedback(Text.literal("Done: " + result.width() + "x" + result.height() + "x" + result.length()));
            source.sendFeedback(Text.literal("Saved .schem: " + out.toAbsolutePath()));
            return 1;
        } catch (Exception e) {
            source.sendError(Text.literal("Image2Schem failed: " + e.getMessage()));
            e.printStackTrace();
            return 0;
        }
    }
}
