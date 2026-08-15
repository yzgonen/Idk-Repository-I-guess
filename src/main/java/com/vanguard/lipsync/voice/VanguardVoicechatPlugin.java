package com.vanguard.lipsync.voice;

import com.vanguard.lipsync.client.MouthStateManager;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.ClientReceiveSoundEvent;
import de.maxhenkel.voicechat.api.events.ClientSoundEvent;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import net.minecraft.client.MinecraftClient;

public final class VanguardVoicechatPlugin implements VoicechatPlugin {
    @Override
    public String getPluginId() { return "vanguard_lipsync"; }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(ClientSoundEvent.class, this::localVoice);
        registration.registerEvent(ClientReceiveSoundEvent.EntitySound.class, this::remoteVoice);
    }

    private void localVoice(ClientSoundEvent event) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            MouthStateManager.update(client.player.getUuid(), event.getRawAudio());
        }
    }

    private void remoteVoice(ClientReceiveSoundEvent.EntitySound event) {
        MouthStateManager.update(event.getEntityId(), event.getRawAudio());
    }
}
