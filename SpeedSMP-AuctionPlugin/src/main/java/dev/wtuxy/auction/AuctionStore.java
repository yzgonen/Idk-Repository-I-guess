package dev.wtuxy.auction;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class AuctionStore {
    private final File file;
    private final YamlConfiguration data;

    public AuctionStore(AuctionPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "data.yml");
        if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
        this.data = YamlConfiguration.loadConfiguration(file);
    }

    private String playerKey(String name) { return name.toLowerCase(Locale.ROOT); }

    public void setTier(String name, Tier tier) {
        String key = playerKey(name);
        data.set("players." + key + ".name", name);
        data.set("players." + key + ".tier", tier.name());
        save();
    }

    public Tier tierOf(String name) {
        String value = data.getString("players." + playerKey(name) + ".tier");
        if (value == null) return null;
        try { return Tier.parse(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public String storedName(String key) {
        return data.getString("players." + playerKey(key) + ".name", key);
    }

    public List<String> playersIn(Tier tier) {
        ConfigurationSection section = data.getConfigurationSection("players");
        List<String> names = new ArrayList<>();
        if (section == null) return names;
        for (String key : section.getKeys(false)) {
            if (tier.name().equalsIgnoreCase(data.getString("players." + key + ".tier"))) {
                names.add(data.getString("players." + key + ".name", key));
            }
        }
        names.sort(Comparator.comparing(String::toLowerCase));
        return names;
    }

    public List<String> allNames() {
        ConfigurationSection section = data.getConfigurationSection("players");
        List<String> names = new ArrayList<>();
        if (section == null) return names;
        for (String key : section.getKeys(false)) names.add(data.getString("players." + key + ".name", key));
        names.sort(Comparator.comparing(String::toLowerCase));
        return names;
    }

    public void setCaptain(TeamColor color, String name) {
        for (TeamColor other : TeamColor.values()) {
            String previous = captain(other);
            if (previous != null && previous.equalsIgnoreCase(name) && other != color) {
                data.set("captains." + other.name(), null);
            }
        }
        String displaced = captain(color);
        if (displaced != null && !displaced.equalsIgnoreCase(name)) {
            data.set("players." + playerKey(displaced) + ".team-color", null);
        }
        data.set("captains." + color.name(), name);
        data.set("players." + playerKey(name) + ".name", name);
        data.set("players." + playerKey(name) + ".team-color", color.name());
        save();
    }

    public String captain(TeamColor color) { return data.getString("captains." + color.name()); }

    public TeamColor colorOf(String name) {
        String assigned = data.getString("players." + playerKey(name) + ".team-color");
        if (assigned != null) {
            try { return TeamColor.parse(assigned); } catch (IllegalArgumentException ignored) { }
        }
        for (TeamColor color : TeamColor.values()) {
            String captain = captain(color);
            if (captain != null && captain.equalsIgnoreCase(name)) return color;
        }
        return null;
    }

    public void assignTeam(String player, TeamColor color) {
        data.set("players." + playerKey(player) + ".name", player);
        data.set("players." + playerKey(player) + ".team-color", color.name());
        save();
    }

    public String teamOf(String player) {
        TeamColor color = colorOf(player);
        return color == null ? null : color.name();
    }

    public void setBudget(TeamColor color, int amount) {
        data.set("team-budgets." + color.name(), amount);
        save();
    }

    public int budget(TeamColor color, int defaultAmount) {
        return data.getInt("team-budgets." + color.name(), defaultAmount);
    }

    public boolean hasPosition(int number) { return data.contains("positions." + number + ".world"); }
    public List<Integer> positions(boolean configured) {
        List<Integer> out = new ArrayList<>();
        for (int i = 1; i <= 8; i++) if (hasPosition(i) == configured) out.add(i);
        return out;
    }

    public void setPosition(int number, Location location) { setLocation("positions." + number, location); }
    public Location position(int number) { return getLocation("positions." + number); }
    public void deletePosition(int number) { data.set("positions." + number, null); save(); }
    public void setTeamPosition(TeamColor color, Location location) { setLocation("team-spawns." + color.name(), location); }
    public Location teamPosition(TeamColor color) { return getLocation("team-spawns." + color.name()); }
    public void setJoinSpawn(String key, Location location) { setLocation("spawns." + key, location); }
    public Location joinSpawn(String key) { return getLocation("spawns." + key); }
    public void saveNpcId(String player, int id) { data.set("offline-npcs." + playerKey(player), id); save(); }
    public int npcId(String player) { return data.getInt("offline-npcs." + playerKey(player), -1); }
    public void clearNpcId(String player) { data.set("offline-npcs." + playerKey(player), null); save(); }

    public void setSoldTeam(String player, TeamColor color) {
        data.set("sold." + playerKey(player), color.name());
        assignTeam(player, color);
    }

    public TeamColor soldTeam(String player) {
        String value = data.getString("sold." + playerKey(player));
        if (value == null) return null;
        try { return TeamColor.parse(value); } catch (IllegalArgumentException ignored) { return null; }
    }

    public boolean isPvpOff() { return data.getBoolean("pvp-off", false); }
    public void setPvpOff(boolean value) { data.set("pvp-off", value); save(); }
    public boolean isAuctionRunning() { return data.getBoolean("auction.running", false); }
    public void setAuctionRunning(boolean value) { data.set("auction.running", value); save(); }
    public void setAuctionTier(Tier tier) { data.set("auction.tier", tier.name()); save(); }
    public Tier auctionTier() {
        try { return Tier.parse(data.getString("auction.tier", "A")); } catch (IllegalArgumentException e) { return Tier.A; }
    }

    private void setLocation(String path, Location loc) {
        if (loc == null || loc.getWorld() == null) return;
        data.set(path + ".world", loc.getWorld().getName());
        data.set(path + ".x", loc.getX());
        data.set(path + ".y", loc.getY());
        data.set(path + ".z", loc.getZ());
        data.set(path + ".yaw", loc.getYaw());
        data.set(path + ".pitch", loc.getPitch());
        save();
    }

    private Location getLocation(String path) {
        String worldName = data.getString(path + ".world");
        if (worldName == null) return null;
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return new Location(world, data.getDouble(path + ".x"), data.getDouble(path + ".y"),
                data.getDouble(path + ".z"), (float) data.getDouble(path + ".yaw"),
                (float) data.getDouble(path + ".pitch"));
    }

    private void save() {
        try { data.save(file); } catch (IOException e) { throw new IllegalStateException("Could not save data.yml", e); }
    }
}
