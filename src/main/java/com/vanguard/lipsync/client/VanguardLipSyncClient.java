package com.vanguard.lipsync.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.LivingEntityFeatureRendererRegistrationCallback;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.feature.FeatureRendererContext;
import net.minecraft.client.render.entity.model.PlayerEntityModel;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;

public final class VanguardLipSyncClient implements ClientModInitializer {
    @Override
    @SuppressWarnings({"rawtypes", "unchecked"})
    public void onInitializeClient() {
        LivingEntityFeatureRendererRegistrationCallback.EVENT.register((entityType, entityRenderer, helper, context) -> {
            if (entityRenderer instanceof PlayerEntityRenderer<?> playerRenderer) {
                helper.register(new MouthFeatureRenderer(
                        (FeatureRendererContext<PlayerEntityRenderState, PlayerEntityModel>) (FeatureRendererContext) playerRenderer
                ));
            }
        });
    }
}
