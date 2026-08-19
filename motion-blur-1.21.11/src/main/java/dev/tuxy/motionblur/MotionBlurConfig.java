package dev.tuxy.motionblur;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

public final class MotionBlurConfig {
    public static final int DEFAULT_STRENGTH_PERCENT = 24;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir()
            .resolve("tuxymotionblur.json");

    private static Data data = load();

    private MotionBlurConfig() {
    }

    public static synchronized int getStrengthPercent() {
        return data.strengthPercent;
    }

    public static synchronized void setStrengthPercent(int strengthPercent) {
        data.strengthPercent = clamp(strengthPercent);
        save();
    }

    private static Data load() {
        if (!Files.exists(CONFIG_PATH)) {
            return new Data();
        }

        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            Data loaded = GSON.fromJson(reader, Data.class);
            if (loaded == null) {
                return new Data();
            }
            loaded.strengthPercent = clamp(loaded.strengthPercent);
            return loaded;
        } catch (Exception exception) {
            System.err.println("[Tuxy Motion Blur] Could not read config; using defaults: " + exception.getMessage());
            return new Data();
        }
    }

    private static void save() {
        try {
            Files.createDirectories(CONFIG_PATH.getParent());
            try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
                GSON.toJson(data, writer);
            }
        } catch (Exception exception) {
            System.err.println("[Tuxy Motion Blur] Could not save config: " + exception.getMessage());
        }
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(100, value));
    }

    private static final class Data {
        int strengthPercent = DEFAULT_STRENGTH_PERCENT;
    }
}
