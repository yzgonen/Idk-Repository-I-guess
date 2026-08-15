package com.vanguard.lipsync.client;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Converts Simple Voice Chat PCM frames into a small set of continuously
 * smoothed mouth controls. This is deliberately language-independent: it
 * follows the acoustic shape of the voice instead of trying to transcribe it.
 */
public final class MouthStateManager {
    private static final Map<UUID, State> STATES = new ConcurrentHashMap<>();
    private static final long SILENCE_TIMEOUT_MS = 140L;

    private MouthStateManager() {}

    public static void update(UUID id, short[] pcm) {
        if (id == null || pcm == null || pcm.length < 8) return;

        double energy = 0.0;
        double derivativeEnergy = 0.0;
        int zeroCrossings = 0;
        short previous = pcm[0];

        for (int i = 0; i < pcm.length; i++) {
            double n = pcm[i] / 32768.0;
            energy += n * n;

            if (i > 0) {
                double p = previous / 32768.0;
                double d = n - p;
                derivativeEnergy += d * d;
                if ((pcm[i] >= 0) != (previous >= 0)) zeroCrossings++;
            }
            previous = pcm[i];
        }

        float rms = (float) Math.sqrt(energy / pcm.length);
        float derivativeRms = (float) Math.sqrt(derivativeEnergy / Math.max(1, pcm.length - 1));
        float zcr = zeroCrossings / (float) Math.max(1, pcm.length - 1);

        // Gate quiet background noise, then map normal speaking volume to 0..1.
        float voiced = clamp01((rms - 0.0085F) * 11.5F);

        // A cheap high-frequency / articulation estimate. Fricatives and bright
        // vowels create more frame-to-frame movement than rounded vowels.
        float brightness = rms > 0.0001F ? clamp01((derivativeRms / rms - 0.28F) * 0.95F) : 0F;
        float crossing = clamp01((zcr - 0.015F) * 5.2F);
        float articulation = clamp01(brightness * 0.65F + crossing * 0.35F);

        // Continuous controls are more natural than jumping between hard poses.
        // OPEN: jaw drop. WIDE: corners pulled sideways (A/E/I-ish sounds).
        // ROUND: lips pushed toward O/U-ish sounds. PRESS: brief lip closure for
        // low-energy consonant transitions (M/B/P-like impression).
        float open = voiced * (0.42F + 0.58F * (1F - articulation * 0.28F));
        float wide = voiced * clamp01(0.18F + articulation * 1.05F);
        float round = voiced * clamp01(0.88F - articulation * 1.1F);
        float press = clamp01((0.20F - voiced) * 4.0F);

        // Loud open vowels should visibly drop the jaw even if the spectrum is dark.
        if (rms > 0.055F) open = Math.max(open, clamp01((rms - 0.04F) * 9F));

        State s = STATES.computeIfAbsent(id, ignored -> new State());
        s.targetOpen = open;
        s.targetWide = wide;
        s.targetRound = round;
        s.targetPress = press;
        s.lastFrame = System.currentTimeMillis();
    }

    public static MouthFrame get(UUID id) {
        if (id == null) return MouthFrame.CLOSED;
        State s = STATES.get(id);
        if (s == null) return MouthFrame.CLOSED;

        if (System.currentTimeMillis() - s.lastFrame > SILENCE_TIMEOUT_MS) {
            s.targetOpen = 0F;
            s.targetWide = 0F;
            s.targetRound = 0F;
            s.targetPress = 1F;
        }

        s.open = approach(s.open, s.targetOpen, s.targetOpen > s.open ? 0.58F : 0.34F);
        s.wide = approach(s.wide, s.targetWide, 0.34F);
        s.round = approach(s.round, s.targetRound, 0.30F);
        s.press = approach(s.press, s.targetPress, 0.40F);

        if (s.open < 0.008F) s.open = 0F;
        if (s.wide < 0.008F) s.wide = 0F;
        if (s.round < 0.008F) s.round = 0F;

        return new MouthFrame(s.open, s.wide, s.round, s.press);
    }

    // Compatibility helper retained for older code/tests.
    public static float getOpenAmount(UUID id) {
        return get(id).open();
    }

    private static float approach(float value, float target, float speed) {
        return value + (target - value) * speed;
    }

    private static float clamp01(float value) {
        return Math.max(0F, Math.min(1F, value));
    }

    public record MouthFrame(float open, float wide, float round, float press) {
        public static final MouthFrame CLOSED = new MouthFrame(0F, 0F, 0F, 1F);

        public boolean talking() {
            return open > 0.025F || wide > 0.04F || round > 0.04F;
        }
    }

    private static final class State {
        volatile float open;
        volatile float wide;
        volatile float round;
        volatile float press = 1F;
        volatile float targetOpen;
        volatile float targetWide;
        volatile float targetRound;
        volatile float targetPress = 1F;
        volatile long lastFrame;
    }
}
