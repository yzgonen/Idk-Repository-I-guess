package com.vanguard.lipsync.client;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Associates Minecraft's reusable player render-state objects with players and motion flags. */
public final class RenderStateTracker {
    private static final Map<PlayerEntityRenderState, PlayerRenderInfo> INFO =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RenderStateTracker() {}

    public static void bind(PlayerEntityRenderState state, UUID playerId,
                            boolean sprinting, boolean crouching, boolean crawling, boolean sitting) {
        if (state != null && playerId != null) {
            INFO.put(state, new PlayerRenderInfo(playerId, sprinting, crouching, crawling, sitting));
        }
    }

    public static UUID get(PlayerEntityRenderState state) {
        PlayerRenderInfo info = INFO.get(state);
        return info == null ? null : info.playerId();
    }

    public static PlayerRenderInfo info(PlayerEntityRenderState state) {
        PlayerRenderInfo info = INFO.get(state);
        return info == null ? PlayerRenderInfo.NONE : info;
    }

    public record PlayerRenderInfo(UUID playerId, boolean sprinting, boolean crouching,
                                   boolean crawling, boolean sitting) {
        public static final PlayerRenderInfo NONE =
                new PlayerRenderInfo(new UUID(0L, 0L), false, false, false, false);
    }
}
