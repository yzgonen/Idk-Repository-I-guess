package dev.wtuxy.auction;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Temporarily opens the configured auction barrier area for selected client groups. */
public final class BarrierPassService {
    private static final int VIEW_RADIUS = 12;
    private static final double OP_RAY_DISTANCE = 16.0;
    private final AuctionPlugin plugin;
    private final File file;
    private final YamlConfiguration snapshot;
    private final Map<String, SavedBarrier> barriers = new HashMap<>();
    private final Map<UUID, Set<String>> clientFakes = new HashMap<>();

    public BarrierPassService(AuctionPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "barriers.yml");
        this.snapshot = YamlConfiguration.loadConfiguration(file);
        for (Map<?, ?> row : snapshot.getMapList("barriers")) {
            Object world = row.get("world");
            Object x = row.get("x");
            Object y = row.get("y");
            Object z = row.get("z");
            Object data = row.get("data");
            if (!(world instanceof String worldName) || !(x instanceof Number bx)
                    || !(y instanceof Number by) || !(z instanceof Number bz)
                    || !(data instanceof String blockData)) continue;
            SavedBarrier barrier = new SavedBarrier(worldName, bx.intValue(), by.intValue(), bz.intValue(), blockData);
            barriers.put(barrier.key(), barrier);
        }
    }

    public void openPassages() {
        if (barriers.isEmpty()) scanArenaBarriers();
        if (barriers.isEmpty()) return;
        saveSnapshot();
        for (SavedBarrier barrier : barriers.values()) {
            World world = Bukkit.getWorld(barrier.world());
            if (world == null) continue;
            Block block = world.getBlockAt(barrier.x(), barrier.y(), barrier.z());
            if (block.getType() == Material.BARRIER) block.setType(Material.AIR, false);
        }
    }

    public void updateClientView(Player player) {
        if (player == null || !plugin.store().isPvpOff()) {
            clearClientView(player);
            return;
        }
        Set<String> desired = new HashSet<>();
        World world = player.getWorld();
        if (!player.isOp() && plugin.store().tierOf(player.getName()) != Tier.S) {
            int px = player.getLocation().getBlockX();
            int py = player.getLocation().getBlockY();
            int pz = player.getLocation().getBlockZ();
            for (SavedBarrier barrier : barriers.values()) {
                if (!barrier.world().equals(world.getName())) continue;
                int dx = barrier.x() - px;
                int dy = barrier.y() - py;
                int dz = barrier.z() - pz;
                if (Math.abs(dx) <= VIEW_RADIUS && Math.abs(dy) <= VIEW_RADIUS && Math.abs(dz) <= VIEW_RADIUS
                        && dx * dx + dy * dy + dz * dz <= VIEW_RADIUS * VIEW_RADIUS) desired.add(barrier.key());
            }
        } else if (player.isOp()) {
            SavedBarrier targeted = barrierUnderCursor(player);
            if (targeted != null) desired.add(targeted.key());
        }
        Set<String> previous = clientFakes.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
        for (String key : new HashSet<>(previous)) {
            if (desired.contains(key)) continue;
            SavedBarrier barrier = barriers.get(key);
            if (barrier != null && barrier.world().equals(world.getName())) {
                player.sendBlockChange(new Location(world, barrier.x(), barrier.y(), barrier.z()), Material.AIR.createBlockData());
            }
            previous.remove(key);
        }
        for (String key : desired) {
            if (previous.add(key)) {
                SavedBarrier barrier = barriers.get(key);
                if (barrier != null) player.sendBlockChange(new Location(world, barrier.x(), barrier.y(), barrier.z()), Material.BARRIER.createBlockData());
            }
        }
    }

    public void restore() {
        List<SavedBarrier> remaining = new ArrayList<>();
        for (SavedBarrier barrier : barriers.values()) {
            World world = Bukkit.getWorld(barrier.world());
            if (world == null) {
                remaining.add(barrier);
                continue;
            }
            Block block = world.getBlockAt(barrier.x(), barrier.y(), barrier.z());
            try {
                BlockData data = Bukkit.createBlockData(barrier.data());
                block.setBlockData(data, false);
            } catch (IllegalArgumentException ex) {
                block.setType(Material.BARRIER, false);
            }
        }
        clearAllClientViews();
        barriers.clear();
        for (SavedBarrier barrier : remaining) barriers.put(barrier.key(), barrier);
        saveSnapshot();
    }

    public void forgetClient(Player player) {
        if (player != null) clientFakes.remove(player.getUniqueId());
    }

    private SavedBarrier barrierUnderCursor(Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        for (double distance = 0.15; distance <= OP_RAY_DISTANCE; distance += 0.15) {
            int x = (int) Math.floor(eye.getX() + direction.getX() * distance);
            int y = (int) Math.floor(eye.getY() + direction.getY() * distance);
            int z = (int) Math.floor(eye.getZ() + direction.getZ() * distance);
            SavedBarrier barrier = barriers.get(key(player.getWorld().getName(), x, y, z));
            if (barrier != null) return barrier;
        }
        return null;
    }

    private void clearClientView(Player player) {
        if (player == null) return;
        Set<String> previous = clientFakes.remove(player.getUniqueId());
        if (previous == null) return;
        World world = player.getWorld();
        for (String key : previous) {
            SavedBarrier barrier = barriers.get(key);
            if (barrier == null || !barrier.world().equals(world.getName())) continue;
            Block block = world.getBlockAt(barrier.x(), barrier.y(), barrier.z());
            player.sendBlockChange(block.getLocation(), block.getBlockData());
        }
    }

    private void clearAllClientViews() {
        for (Player player : Bukkit.getOnlinePlayers()) clearClientView(player);
    }

    private void scanArenaBarriers() {
        Map<World, Bounds> boundsByWorld = new HashMap<>();
        for (int i = 1; i <= 8; i++) addAnchor(boundsByWorld, plugin.store().position(i));
        for (TeamColor color : TeamColor.values()) addAnchor(boundsByWorld, plugin.store().teamPosition(color));
        for (Map.Entry<World, Bounds> entry : boundsByWorld.entrySet()) {
            World world = entry.getKey();
            Bounds bounds = entry.getValue();
            for (int x = Math.max(-29_999_984, bounds.minX - 10); x <= Math.min(29_999_984, bounds.maxX + 10); x++)
                for (int y = Math.max(world.getMinHeight(), bounds.minY - 8); y <= Math.min(world.getMaxHeight() - 1, bounds.maxY + 8); y++)
                    for (int z = Math.max(-29_999_984, bounds.minZ - 10); z <= Math.min(29_999_984, bounds.maxZ + 10); z++) {
                        Block block = world.getBlockAt(x, y, z);
                        if (block.getType() != Material.BARRIER) continue;
                        SavedBarrier saved = new SavedBarrier(world.getName(), x, y, z, block.getBlockData().getAsString());
                        barriers.put(saved.key(), saved);
                    }
        }
    }

    private void addAnchor(Map<World, Bounds> bounds, Location location) {
        if (location == null || location.getWorld() == null) return;
        bounds.computeIfAbsent(location.getWorld(), ignored -> new Bounds()).include(location);
    }

    private void saveSnapshot() {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SavedBarrier barrier : barriers.values()) {
            Map<String, Object> row = new HashMap<>();
            row.put("world", barrier.world());
            row.put("x", barrier.x());
            row.put("y", barrier.y());
            row.put("z", barrier.z());
            row.put("data", barrier.data());
            rows.add(row);
        }
        snapshot.set("barriers", rows);
        try { snapshot.save(file); }
        catch (IOException e) { throw new IllegalStateException("Could not save barriers.yml", e); }
    }

    private static String key(String world, int x, int y, int z) { return world + ":" + x + ":" + y + ":" + z; }

    private record SavedBarrier(String world, int x, int y, int z, String data) {
        private String key() { return BarrierPassService.key(world, x, y, z); }
    }

    private static final class Bounds {
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private void include(Location location) {
            int x = location.getBlockX(), y = location.getBlockY(), z = location.getBlockZ();
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
    }
}
