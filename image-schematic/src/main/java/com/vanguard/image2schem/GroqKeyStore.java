package com.vanguard.image2schem;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Stores the user's Groq API key only in the local Fabric config directory. */
public final class GroqKeyStore {
    private static final String FILE_NAME = "image2schem-groq-key.txt";

    private GroqKeyStore() {}

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    public static String load() {
        try {
            Path p = path();
            if (!Files.isRegularFile(p)) return "";
            return Files.readString(p, StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static void save(String key) throws IOException {
        Path p = path();
        Files.createDirectories(p.getParent());
        String value = key == null ? "" : key.trim();
        if (value.isEmpty()) {
            Files.deleteIfExists(p);
            return;
        }
        Files.writeString(p, value, StandardCharsets.UTF_8);
    }

    public static boolean hasKey() {
        return !load().isBlank();
    }
}
