package dev.wtuxy.auction;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.NamespacedKey;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/** Citizens-backed player NPCs, accessed reflectively so Citizens is not bundled into this jar. */
public final class OfflineNpcService {
    private final AuctionPlugin plugin;
    private final Map<String, Object> npcs = new HashMap<>();

    public OfflineNpcService(AuctionPlugin plugin) { this.plugin = plugin; }

    public boolean available() {
        Plugin citizens = Bukkit.getPluginManager().getPlugin("Citizens");
        return citizens != null && citizens.isEnabled();
    }

    public boolean spawn(String playerName, Location location) {
        if (!available() || location == null) return false;
        String key = playerName.toLowerCase();
        Object existing = npcs.get(key);
        if (existing == null && plugin.store().npcId(playerName) >= 0) {
            existing = getById(plugin.store().npcId(playerName));
            if (existing != null) npcs.put(key, existing);
        }
        if (existing != null) {
            setFormattedName(existing, playerName);
            invoke(existing, "teleport", location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            protect(existing);
            return true;
        }
        try {
            Class<?> citizensApi = Class.forName("net.citizensnpcs.api.CitizensAPI");
            Object registry = citizensApi.getMethod("getNPCRegistry").invoke(null);
            Method create = registry.getClass().getMethod("createNPC", EntityType.class, String.class);
            Object npc = create.invoke(registry, EntityType.PLAYER, playerName);
            setFormattedName(npc, playerName);

            Class<?> skinTrait = Class.forName("net.citizensnpcs.trait.SkinTrait");
            Object skin = npc.getClass().getMethod("getOrAddTrait", Class.class).invoke(npc, skinTrait);
            Method skinSetter = null;
            for (Method method : skinTrait.getMethods()) {
                if (!method.getName().equals("setSkinName") || method.getParameterCount() < 1
                        || method.getParameterTypes()[0] != String.class) continue;
                if (method.getParameterCount() == 2 && method.getParameterTypes()[1] == boolean.class) {
                    skinSetter = method;
                    break;
                }
                if (skinSetter == null && method.getParameterCount() == 1) skinSetter = method;
            }
            if (skinSetter != null) {
                if (skinSetter.getParameterCount() == 2) skinSetter.invoke(skin, playerName, true);
                else skinSetter.invoke(skin, playerName);
            }

            protect(npc);
            Method spawn = npc.getClass().getMethod("spawn", Location.class);
            Object success = spawn.invoke(npc, location);
            if (success instanceof Boolean bool && !bool) return false;
            npcs.put(key, npc);
            Object id = invoke(npc, "getId");
            if (id instanceof Number number) plugin.store().saveNpcId(playerName, number.intValue());
            protect(npc);
            return true;
        } catch (ReflectiveOperationException e) {
            plugin.getLogger().warning("Could not create Citizens player stand-in for " + playerName + ": " + e.getMessage());
            return false;
        }
    }

    public void move(String playerName, Location location) {
        Object npc = npcs.get(playerName.toLowerCase());
        if (npc == null && plugin.store().npcId(playerName) >= 0) {
            npc = getById(plugin.store().npcId(playerName));
            if (npc != null) npcs.put(playerName.toLowerCase(), npc);
        }
        if (npc != null && location != null) {
            setFormattedName(npc, playerName);
            invoke(npc, "teleport", location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            protect(npc);
        }
    }

    public void remove(String playerName) {
        Object npc = npcs.remove(playerName.toLowerCase());
        if (npc == null && plugin.store().npcId(playerName) >= 0) npc = getById(plugin.store().npcId(playerName));
        if (npc == null) return;
        invoke(npc, "despawn");
        try {
            Object registry = Class.forName("net.citizensnpcs.api.CitizensAPI")
                    .getMethod("getNPCRegistry").invoke(null);
            for (Method method : registry.getClass().getMethods()) {
                if (method.getName().equals("deregister") && method.getParameterCount() == 1) {
                    method.invoke(registry, npc);
                    break;
                }
            }
        } catch (ReflectiveOperationException ignored) { }
        plugin.store().clearNpcId(playerName);
    }

    public boolean isStandIn(Entity entity) {
        if (entity == null) return false;
        NamespacedKey key = new NamespacedKey(plugin, "offline-player");
        if (entity.getPersistentDataContainer().has(key, PersistentDataType.STRING)) return true;
        for (Object npc : npcs.values()) {
            Object npcEntity = invoke(npc, "getEntity");
            if (npcEntity instanceof Entity candidate && candidate.getUniqueId().equals(entity.getUniqueId())) return true;
        }
        return false;
    }

    private void protect(Object npc) {
        invoke(npc, "setProtected", true);
        Object entity = invoke(npc, "getEntity");
        if (entity instanceof Entity bukkitEntity) {
            bukkitEntity.setInvulnerable(true);
            bukkitEntity.setSilent(true);
            bukkitEntity.setGlowing(false);
            bukkitEntity.setCustomNameVisible(false);
            NamespacedKey key = new NamespacedKey(plugin, "offline-player");
            bukkitEntity.getPersistentDataContainer().set(key, PersistentDataType.STRING, "true");
        }
    }

    private Object getById(int id) {
        try {
            Object registry = Class.forName("net.citizensnpcs.api.CitizensAPI")
                    .getMethod("getNPCRegistry").invoke(null);
            return registry.getClass().getMethod("getById", int.class).invoke(registry, id);
        } catch (ReflectiveOperationException ignored) { return null; }
    }

    private void setFormattedName(Object npc, String playerName) {
        Tier tier = plugin.store().tierOf(playerName);
        if (tier == null) return;
        TeamColor color = plugin.store().colorOf(playerName);
        ChatColor nameColor = color == null ? ChatColor.WHITE : color.chatColor();
        invoke(npc, "setName", ChatColor.GRAY + tier.name() + " - " + nameColor + playerName);
    }

    private Object invoke(Object target, String name, Object... args) {
        if (target == null) return null;
        for (Method method : target.getClass().getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != args.length) continue;
            try { return method.invoke(target, args); } catch (ReflectiveOperationException ignored) { }
        }
        return null;
    }
}
