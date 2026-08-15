package com.vanguard.lipsync.client;

import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;

/** Associates Minecraft's reusable player render-state objects with players. */
public final class RenderStateTracker {
    private static final Map<PlayerEntityRenderState, UUID> PLAYER_IDS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RenderStateTracker() {}

    public static void bind(PlayerEntityRenderState state, UUID playerId) {
        if (state != null && playerId != null) PLAYER_IDS.put(state, playerId);
    }

    public static UUID get(PlayerEntityRenderState state) {
        return PLAYER_IDS.get(state);
    }
}
