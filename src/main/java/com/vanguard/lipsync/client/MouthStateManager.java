package com.vanguard.lipsync.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class MouthStateManager {
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private MouthStateManager() {}

    public static void update(UUID id, short[] pcm) {
        if (id == null || pcm == null || pcm.length == 0) return;
        double sum = 0.0;
        for (short sample : pcm) {
            double n = sample / 32768.0;
            sum += n * n;
        }
        float rms = (float)Math.sqrt(sum / pcm.length);
        float target = Math.max(0F, Math.min(1F, (rms - 0.012F) * 8F));
        State s = STATES.computeIfAbsent(id, ignored -> new State());
        s.target = target;
        s.lastFrame = System.currentTimeMillis();
    }

    public static float get(UUID id) {
        State s = STATES.get(id);
        if (s == null) return 0F;
        if (System.currentTimeMillis() - s.lastFrame > 120L) s.target = 0F;
        float speed = s.target > s.value ? 0.65F : 0.28F;
        s.value += (s.target - s.value) * speed;
        if (s.value < 0.005F) s.value = 0F;
        return s.value;
    }

    private static final class State {
        volatile float value;
        volatile float target;
        volatile long lastFrame;
    }
}
