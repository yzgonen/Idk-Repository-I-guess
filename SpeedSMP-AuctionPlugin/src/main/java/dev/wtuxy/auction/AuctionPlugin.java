package dev.wtuxy.auction;

import io.papermc.paper.event.player.AsyncChatEvent;
import io.papermc.paper.event.player.AsyncPlayerSpawnLocationEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.BlockState;
import org.bukkit.boss.BossBar;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuctionPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private AuctionStore store;
    private AuctionManager auction;
    private OfflineNpcService npcs;
    private BarrierPassService barrierPass;
    private final Map<UUID, Location> movementAnchors = new HashMap<>();
    private final Map<UUID, GameMode> modeBeforePvpOff = new HashMap<>();
    private final Map<UUID, List<PotionEffect>> effectsBeforePvpOff = new HashMap<>();
    private final Map<UUID, Integer> lastBarrierScan = new HashMap<>();
    private final Map<UUID, String> tierViewerNames = new ConcurrentHashMap<>();
    private NamespacedKey tierMenuKey;
    private static final String TIER_MENU_TITLE = ChatColor.DARK_PURPLE + "Player Tiers";

    @Override
    public void onEnable() {
        saveDefaultConfig();
        tierMenuKey = new NamespacedKey(this, "tier-menu-item");
        store = new AuctionStore(this);
        barrierPass = new BarrierPassService(this);
        npcs = new OfflineNpcService(this);
        auction = new AuctionManager(this);
        getServer().getPluginManager().registerEvents(this, this);
        for (String command : List.of("tiers", "tier", "pos", "posteam", "axs", "assign", "assignp", "credits", "addnpc", "auction", "bidding", "sell", "pvp")) {
            if (getCommand(command) != null) {
                getCommand(command).setExecutor(this);
                getCommand(command).setTabCompleter(this);
            }
        }
        if (store.isPvpOff()) barrierPass.openPassages();
        else barrierPass.restore();
        for (Player player : Bukkit.getOnlinePlayers()) {
            refreshName(player);
            if (store.isPvpOff()) applyPvpOff(player);
        }
        if (store.isPvpOff()) {
            getLogger().info("PvP protection mode is enabled server-wide.");
        }
        if (!npcs.available()) getLogger().warning("Citizens is not installed; offline player stand-ins will not spawn until Citizens is installed.");
    }

    @Override
    public void onDisable() {
        if (auction != null && auction.isRunning()) auction.stop();
    }

    AuctionStore store() { return store; }
    OfflineNpcService npcs() { return npcs; }
    net.kyori.adventure.text.format.TextColor adventureColor(TeamColor color) {
        return switch (color) {
            case PURPLE -> net.kyori.adventure.text.format.NamedTextColor.DARK_PURPLE;
            case BLUE -> net.kyori.adventure.text.format.NamedTextColor.BLUE;
            case WHITE -> net.kyori.adventure.text.format.NamedTextColor.WHITE;
            case YELLOW -> net.kyori.adventure.text.format.NamedTextColor.YELLOW;
            case ORANGE -> net.kyori.adventure.text.format.NamedTextColor.GOLD;
            case RED -> net.kyori.adventure.text.format.NamedTextColor.RED;
            case GREEN -> net.kyori.adventure.text.format.NamedTextColor.GREEN;
            case BLACK -> net.kyori.adventure.text.format.NamedTextColor.BLACK;
        };
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("tiers")) return tiersCommand(sender, args);
        if (cmd.equals("tier")) return tierCommand(sender, args);
        if (cmd.equals("pos")) return posCommand(sender, args);
        if (cmd.equals("posteam")) return teamPositionCommand(sender, args);
        if (cmd.equals("axs")) return axsCommand(sender, args);
        if (cmd.equals("assign")) return assignCommand(sender, args);
        if (cmd.equals("assignp")) return assignPlayerCommand(sender, args);
        if (cmd.equals("credits")) return creditsCommand(sender, args);
        if (cmd.equals("addnpc")) return addNpcCommand(sender, args);
        if (cmd.equals("auction")) return auctionCommand(sender, args);
        if (cmd.equals("bidding")) return biddingCommand(sender, args);
        if (cmd.equals("sell")) return sellCommand(sender);
        if (cmd.equals("pvp")) return pvpCommand(sender, args);
        return false;
    }

    private boolean tiersCommand(CommandSender sender, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("add")) {
            if (!admin(sender)) return true;
            if (args.length != 3) {
                sender.sendMessage(ChatColor.RED + "Usage: /tiers add <name> <S|A|B|C|D>");
                return true;
            }
            String name = args[1];
            Tier tier;
            try {
                if (isTier(args[1])) { tier = Tier.parse(args[1]); name = args[2]; }
                else tier = Tier.parse(args[2]);
            } catch (IllegalArgumentException ex) {
                sender.sendMessage(ChatColor.RED + "Tier must be S, A, B, C, or D.");
                return true;
            }
            addTieredPlayer(sender, name, tier);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("colors")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Players only.");
                return true;
            }
            openTierMenu(player);
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Players only. Use /tiers add <name> <tier> to register a player.");
            return true;
        }
        openTierMenu(player);
        return true;
    }

    private void addTieredPlayer(CommandSender sender, String name, Tier tier) {
        if (!validPlayerName(name)) {
            sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username.");
            return;
        }
        store.setTier(name, tier);
        Bukkit.getOfflinePlayer(name).setWhitelisted(true);
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) refreshName(online);
        sender.sendMessage(ChatColor.GREEN + name + " is now " + tier + "-Tier and has been whitelisted.");
    }

    private boolean tierCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length == 2 && args[1].equalsIgnoreCase("remove")) {
            if (!validPlayerName(args[0])) {
                sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username.");
                return true;
            }
            Tier oldTier = store.tierOf(args[0]);
            if (oldTier == null) {
                sender.sendMessage(ChatColor.YELLOW + args[0] + " does not currently have a tier.");
                return true;
            }
            store.removeTier(args[0]);
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) refreshName(online);
            sender.sendMessage(ChatColor.GREEN + "Removed " + args[0] + "'s " + oldTier + "-Tier assignment.");
            if (oldTier == Tier.S) sender.sendMessage(ChatColor.GRAY + "Their S-tier captain color was also freed.");
            return true;
        }
        if (args.length != 3 || !args[1].equalsIgnoreCase("set")) {
            sender.sendMessage(ChatColor.RED + "Usage: /tier <name> <set <S|A|B|C|D>|remove>");
            return true;
        }
        try {
            Tier tier = Tier.parse(args[2]);
            if (!validPlayerName(args[0])) throw new IllegalArgumentException();
            store.setTier(args[0], tier);
            Player online = Bukkit.getPlayerExact(args[0]);
            if (online != null) refreshName(online);
            sender.sendMessage(ChatColor.GREEN + args[0] + " is now " + tier + "-Tier.");
        } catch (IllegalArgumentException ex) {
            sender.sendMessage(ChatColor.RED + "Use a valid username and tier S, A, B, C, or D.");
        }
        return true;
    }

    private boolean posCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (!(sender instanceof Player player) || args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /pos <add|delete> <1-8>");
            return true;
        }
        int number;
        try { number = Integer.parseInt(args[1]); } catch (NumberFormatException e) { sender.sendMessage(ChatColor.RED + "Position must be 1-8."); return true; }
        if (number < 1 || number > 8) { sender.sendMessage(ChatColor.RED + "Position must be 1-8."); return true; }
        if (args[0].equalsIgnoreCase("add")) {
            if (store.hasPosition(number)) { sender.sendMessage(ChatColor.RED + "Position " + number + " is already saved."); return true; }
            store.setPosition(number, player.getLocation());
            sender.sendMessage(ChatColor.GREEN + "Saved auction position " + number + ".");
        } else if (args[0].equalsIgnoreCase("delete")) {
            if (!store.hasPosition(number)) { sender.sendMessage(ChatColor.RED + "Position " + number + " is not set."); return true; }
            store.deletePosition(number);
            sender.sendMessage(ChatColor.GREEN + "Deleted auction position " + number + ".");
        } else sender.sendMessage(ChatColor.RED + "Usage: /pos <add|delete> <1-8>");
        return true;
    }

    private boolean teamPositionCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (!(sender instanceof Player player) || args.length < 1 || args.length > 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /posteam <color>");
            return true;
        }
        String rawColor = args[args.length - 1];
        try {
            TeamColor color = TeamColor.parse(rawColor);
            store.setTeamPosition(color, player.getLocation());
            sender.sendMessage(color.chatColor() + "Saved the " + color.name() + " Team teleport position.");
        } catch (IllegalArgumentException ex) { sender.sendMessage(ChatColor.RED + "Unknown team color."); }
        return true;
    }

    private boolean axsCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (!(sender instanceof Player player) || args.length != 2 || !args[0].equalsIgnoreCase("set")) {
            sender.sendMessage(ChatColor.RED + "Usage: /axs set <all|S>");
            return true;
        }
        String key = args[1].equalsIgnoreCase("s") ? "S" : args[1].equalsIgnoreCase("all") ? "all" : null;
        if (key == null) { sender.sendMessage(ChatColor.RED + "Choose all or S."); return true; }
        store.setJoinSpawn(key, player.getLocation());
        sender.sendMessage(ChatColor.GREEN + "Saved the " + (key.equals("S") ? "S-tier" : "normal player") + " join spawn.");
        return true;
    }

    private boolean assignCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length != 2) { sender.sendMessage(ChatColor.RED + "Usage: /assign <color> <S-tier-player> or /assign remove <S-tier-player>"); return true; }
        if (args[0].equalsIgnoreCase("remove")) {
            String playerName = args[1];
            if (!validPlayerName(playerName)) { sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username."); return true; }
            if (store.tierOf(playerName) != Tier.S) { sender.sendMessage(ChatColor.RED + "Use /assignp remove <player> for a normal player."); return true; }
            if (!store.clearCaptainAssignment(playerName)) { sender.sendMessage(ChatColor.YELLOW + playerName + " has no assigned captain color."); return true; }
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) refreshName(online);
            sender.sendMessage(ChatColor.GREEN + "Removed " + playerName + "'s S-tier team color. The color is free again.");
            return true;
        }
        TeamColor color;
        try { color = TeamColor.parse(args[0]); } catch (IllegalArgumentException e) { sender.sendMessage(ChatColor.RED + "Choose purple, blue, white, yellow, orange, red, green, or black."); return true; }
        String playerName = args[1];
        if (!validPlayerName(playerName)) { sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username."); return true; }
        if (store.tierOf(playerName) != Tier.S) { sender.sendMessage(ChatColor.RED + playerName + " must be registered as S-tier first."); return true; }
        String currentCaptain = store.captain(color);
        if (currentCaptain != null && !currentCaptain.equalsIgnoreCase(playerName)) {
            sender.sendMessage(ChatColor.RED + color.name() + " is already assigned to " + currentCaptain + ".");
            return true;
        }
        store.setCaptain(color, playerName);
        refreshOnlineNames();
        sender.sendMessage(color.chatColor() + playerName + " is now the " + color.name() + " Team captain.");
        return true;
    }

    private boolean assignPlayerCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length != 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /assignp <color> <player> or /assignp remove <player>");
            return true;
        }
        String playerName = args[1];
        if (!validPlayerName(playerName)) {
            sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username.");
            return true;
        }
        if (args[0].equalsIgnoreCase("remove")) {
            if (store.tierOf(playerName) == Tier.S) {
                sender.sendMessage(ChatColor.RED + "Use /assign remove <S-tier-player> to free an S-tier color.");
                return true;
            }
            if (store.colorOf(playerName) == null && store.soldTeam(playerName) == null) {
                sender.sendMessage(ChatColor.YELLOW + playerName + " has no team assignment.");
                return true;
            }
            store.clearTeamAssignment(playerName);
            Player online = Bukkit.getPlayerExact(playerName);
            if (online != null) refreshName(online);
            sender.sendMessage(ChatColor.GREEN + "Removed " + playerName + "'s team assignment.");
            return true;
        }
        TeamColor color;
        try { color = TeamColor.parse(args[0]); }
        catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Choose purple, blue, white, yellow, orange, red, green, or black.");
            return true;
        }
        if (store.tierOf(playerName) == Tier.S) {
            sender.sendMessage(ChatColor.RED + "Use /assign <color> <S-tier-player> for S-tier captains.");
            return true;
        }
        store.assignTeam(playerName, color);
        Player online = Bukkit.getPlayerExact(playerName);
        if (online != null) refreshName(online);
        sender.sendMessage(color.chatColor() + playerName + " is assigned to the " + color.name() + " Team.");
        return true;
    }

    private boolean creditsCommand(CommandSender sender, String[] args) {
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage(ChatColor.RED + "Usage: /credits <player> or /credits <add|remove> <player> <amount>");
                return true;
            }
            showCredits(sender, player.getName());
            return true;
        }
        if (args.length == 1) {
            if (!admin(sender)) return true;
            showCredits(sender, args[0]);
            return true;
        }
        if (args.length != 3 || !(args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
            sender.sendMessage(ChatColor.RED + "Usage: /credits [player|add <player> <amount>|remove <player> <amount>]");
            return true;
        }
        if (!admin(sender)) return true;
        String playerName = args[1];
        if (!validPlayerName(playerName)) {
            sender.sendMessage(ChatColor.RED + "Enter a valid Minecraft username.");
            return true;
        }
        TeamColor color = store.colorOf(playerName);
        if (color == null) {
            sender.sendMessage(ChatColor.RED + playerName + " is not assigned to a team. Assign them first with /assign or /assignp.");
            return true;
        }
        int amount;
        try { amount = Integer.parseInt(args[2]); }
        catch (NumberFormatException ex) { sender.sendMessage(ChatColor.RED + "Amount must be a positive whole number."); return true; }
        if (amount <= 0) { sender.sendMessage(ChatColor.RED + "Amount must be greater than zero."); return true; }
        int budget = store.budget(color, getConfig().getInt("starting-budget", 250));
        if (args[0].equalsIgnoreCase("add")) {
            long updated = (long) budget + amount;
            if (updated > Integer.MAX_VALUE) { sender.sendMessage(ChatColor.RED + "That amount is too large."); return true; }
            store.setBudget(color, (int) updated);
        } else {
            int available = auction.availableCredits(color);
            if (amount > available) {
                sender.sendMessage(ChatColor.RED + "Only " + available + " credits are available to remove; a leading bid may be reserved.");
                return true;
            }
            store.setBudget(color, budget - amount);
        }
        sender.sendMessage(color.chatColor().toString() + playerName + "'s " + color.name() + " Team balance is now "
                + auction.availableCredits(color) + " available credits (" + store.budget(color, 250) + " total remaining).");
        return true;
    }

    private void showCredits(CommandSender sender, String playerName) {
        TeamColor color = store.colorOf(playerName);
        if (color == null) {
            sender.sendMessage(ChatColor.RED + playerName + " is not assigned to a team.");
            return;
        }
        int total = store.budget(color, getConfig().getInt("starting-budget", 250));
        int available = auction.availableCredits(color);
        sender.sendMessage(color.chatColor() + playerName + "'s " + color.name() + " Team has " + available
                + " auction credits available" + (available == total ? "." : " (" + (total - available) + " currently reserved in the leading bid; total remaining: " + total + ")."));
    }

    private boolean addNpcCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (!(sender instanceof Player player) || args.length != 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /addnpc <registered-player>");
            return true;
        }
        String name = args[0];
        if (!validPlayerName(name) || store.tierOf(name) == null) {
            sender.sendMessage(ChatColor.RED + "That player is not registered in the tier list.");
            return true;
        }
        if (Bukkit.getPlayerExact(name) != null) {
            sender.sendMessage(ChatColor.YELLOW + name + " is online already; no stand-in NPC was added.");
            return true;
        }
        if (!npcs.spawn(name, player.getLocation())) {
            sender.sendMessage(ChatColor.RED + "Could not add the NPC. Make sure Citizens is installed and enabled.");
            return true;
        }
        sender.sendMessage(store.colorOf(name) == null ? ChatColor.GREEN + "Added " + name + "'s NPC at your location."
                : store.colorOf(name).chatColor() + "Added " + name + "'s NPC at your location.");
        return true;
    }

    private boolean auctionCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("start")) auction.start();
        else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) auction.stop();
        else if (args.length == 2 && args[0].equalsIgnoreCase("npcs") && args[1].equalsIgnoreCase("clear")) {
            int removed = npcs.removeAllStandIns();
            sender.sendMessage(ChatColor.GREEN + "Removed " + removed + " auction stand-in NPC(s). Roster entries were kept.");
        } else sender.sendMessage(ChatColor.RED + "Usage: /auction <start|stop|npcs clear>");
        return true;
    }

    private boolean biddingCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length == 1 && args[0].equalsIgnoreCase("start")) auction.startBidding();
        else if (args.length == 1 && args[0].equalsIgnoreCase("pause")) auction.pauseBidding();
        else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) auction.stop();
        else if (args.length == 2 && args[0].equalsIgnoreCase("restart")) auction.restartTier(args[1]);
        else sender.sendMessage(ChatColor.RED + "Usage: /bidding <start|pause|stop|restart <A|B|C|D>>");
        return true;
    }

    private boolean sellCommand(CommandSender sender) {
        if (!admin(sender)) return true;
        auction.sellCommand();
        return true;
    }

    private boolean pvpCommand(CommandSender sender, String[] args) {
        if (!admin(sender)) return true;
        if (args.length != 1) { sender.sendMessage(ChatColor.RED + "Usage: /pvp <on|off>"); return true; }
        if (args[0].equalsIgnoreCase("off")) {
            if (store.isPvpOff()) { sender.sendMessage(ChatColor.YELLOW + "PvP mode is already off."); return true; }
            store.setPvpOff(true);
            barrierPass.openPassages();
            for (Player player : Bukkit.getOnlinePlayers()) applyPvpOff(player);
            Bukkit.broadcast(Component.text("PvP is off. Server protection mode is enabled."));
        } else if (args[0].equalsIgnoreCase("on")) {
            if (!store.isPvpOff()) { sender.sendMessage(ChatColor.YELLOW + "PvP mode is already on."); return true; }
            store.setPvpOff(false);
            barrierPass.restore();
            for (Player player : Bukkit.getOnlinePlayers()) restorePvpOn(player);
            Bukkit.broadcast(Component.text("PvP is on. Normal server behavior is restored."));
        } else sender.sendMessage(ChatColor.RED + "Usage: /pvp <on|off>");
        return true;
    }

    private boolean admin(CommandSender sender) {
        if (sender.hasPermission("auction.admin") || sender.isOp()) return true;
        sender.sendMessage(ChatColor.RED + "You don’t have permission to use that command.");
        return false;
    }

    private boolean isTier(String value) { return value.matches("(?i)[SABCD]"); }
    private boolean validPlayerName(String name) { return name.matches("[A-Za-z0-9_]{1,16}"); }

    private void openTierMenu(Player player) {
        TierMenuHolder holder = new TierMenuHolder();
        Inventory inv = Bukkit.createInventory(holder, 54, TIER_MENU_TITLE);
        holder.inventory = inv;
        for (TeamColor color : TeamColor.values()) {
            ItemStack item = new ItemStack(color.dye());
            ItemMeta meta = item.getItemMeta();
            meta.setDisplayName(color.chatColor() + color.name() + " Team");
            meta.setLore(List.of(ChatColor.GRAY + "S-tier players: click to choose this color"));
            meta.getPersistentDataContainer().set(tierMenuKey, PersistentDataType.STRING, color.name());
            item.setItemMeta(meta);
            inv.setItem(color.ordinal(), item);
        }
        Tier[] tiers = Tier.values();
        for (int row = 0; row < tiers.length; row++) {
            Tier tier = tiers[row];
            int base = 9 + row * 9;
            ItemStack header = new ItemStack(tier == Tier.S ? Material.NETHER_STAR : Material.NAME_TAG);
            ItemMeta hm = header.getItemMeta();
            hm.setDisplayName(ChatColor.GOLD + tier.name() + "-Tier");
            header.setItemMeta(hm);
            inv.setItem(base, header);
            List<String> names = store.playersIn(tier);
            for (int i = 0; i < names.size() && i < 8; i++) {
                String name = names.get(i);
                ItemStack head = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta sm = (SkullMeta) head.getItemMeta();
                OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
                sm.setOwningPlayer(offline);
                TeamColor color = store.colorOf(name);
                ChatColor nameColor = color == null ? ChatColor.WHITE : color.chatColor();
                sm.setDisplayName(nameColor + "(" + tier.name() + ") " + name);
                TeamColor team = color;
                String status = team == null ? "No team yet" : team.name() + " Team";
                sm.setLore(List.of(ChatColor.GRAY + status, offline.isOnline() ? ChatColor.GREEN + "Online" : ChatColor.DARK_GRAY + "Offline"));
                head.setItemMeta(sm);
                inv.setItem(base + i + 1, head);
            }
        }
        player.openInventory(inv);
    }

    private void chooseColor(Player player, TeamColor color) {
        if (store.tierOf(player.getName()) != Tier.S) {
            player.sendMessage(ChatColor.RED + "Only S-tier players can choose a team color.");
            return;
        }
        String current = store.captain(color);
        if (current != null && !current.equalsIgnoreCase(player.getName())) {
            player.sendMessage(ChatColor.RED + color.name() + " is already chosen by " + current + ".");
            return;
        }
        store.setCaptain(color, player.getName());
        refreshOnlineNames();
        player.sendMessage(color.chatColor() + "You chose the " + color.name() + " Team color.");
        player.closeInventory();
    }

    private void refreshOnlineNames() {
        for (Player player : Bukkit.getOnlinePlayers()) refreshName(player);
    }

    void refreshName(Player player) {
        Tier tier = store.tierOf(player.getName());
        TeamColor color = store.colorOf(player.getName());
        ChatColor nameColor = color == null ? ChatColor.WHITE : color.chatColor();
        String prefix = tier == null ? "" : nameColor + "(" + tier.name() + ") ";
        String tabName = prefix + player.getName();
        player.setDisplayName(tabName);
        player.setPlayerListName(tabName);
        try {
            Scoreboard scoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
            String teamName = "auc_" + Integer.toHexString(player.getName().toLowerCase(Locale.ROOT).hashCode());
            if (teamName.length() > 16) teamName = teamName.substring(0, 16);
            Team team = scoreboard.getTeam(teamName);
            if (team == null) team = scoreboard.registerNewTeam(teamName);
            Team existing = scoreboard.getEntryTeam(player.getName());
            if (existing != null && existing != team) existing.removeEntry(player.getName());
            team.setPrefix(prefix);
            team.setColor(nameColor);
            team.addEntry(player.getName());
        } catch (IllegalArgumentException ignored) { }
    }

    private void applyPvpOff(Player player) {
        UUID id = player.getUniqueId();
        FileConfiguration config = getConfig();
        String path = "previous-modes." + id;
        GameMode original;
        if (!config.contains(path)) {
            config.set(path, player.getGameMode().name());
            original = player.getGameMode();
            saveEffectSnapshot(id, player.getActivePotionEffects());
            saveConfig();
        } else {
            try { original = GameMode.valueOf(config.getString(path, "SURVIVAL")); }
            catch (IllegalArgumentException ex) { original = GameMode.SURVIVAL; }
        }
        modeBeforePvpOff.put(id, original);
        effectsBeforePvpOff.putIfAbsent(id, loadEffectSnapshot(id));
        boolean hadInvisibilityBeforePvpOff = effectsBeforePvpOff.get(id).stream()
                .anyMatch(effect -> effect.getType().equals(PotionEffectType.INVISIBILITY));
        Tier tier = store.tierOf(player.getName());
        if (tier == Tier.S || player.isOp()) player.setGameMode(original);
        else player.setGameMode(GameMode.ADVENTURE);
        // Remove invisibility left behind by earlier plugin versions, while preserving a player's original effect.
        if (!hadInvisibilityBeforePvpOff) player.removePotionEffect(PotionEffectType.INVISIBILITY);
        player.addPotionEffect(new PotionEffect(PotionEffectType.SATURATION, Integer.MAX_VALUE, 255, false, false, false));
        player.setInvulnerable(false);
        if (tier != Tier.S && !player.isOp()) movementAnchors.put(id, player.getLocation().clone());
        refreshName(player);
        barrierPass.updateClientView(player);
    }

    private void restorePvpOn(Player player) {
        UUID id = player.getUniqueId();
        GameMode mode = modeBeforePvpOff.remove(id);
        if (mode == null) {
            String saved = getConfig().getString("previous-modes." + id, "SURVIVAL");
            try { mode = GameMode.valueOf(saved); } catch (IllegalArgumentException e) { mode = GameMode.SURVIVAL; }
        }
        player.setGameMode(mode);
        player.removePotionEffect(PotionEffectType.SATURATION);
        List<PotionEffect> previousEffects = effectsBeforePvpOff.remove(id);
        if (previousEffects == null) previousEffects = loadEffectSnapshot(id);
        if (previousEffects != null) {
            for (PotionEffect effect : previousEffects) {
                player.addPotionEffect(effect);
            }
        }
        getConfig().set("previous-modes." + id, null);
        getConfig().set("previous-effects." + id, null);
        saveConfig();
        refreshName(player);
        barrierPass.updateClientView(player);
    }

    private void saveEffectSnapshot(UUID id, Collection<PotionEffect> effects) {
        List<Map<String, Object>> serialized = new ArrayList<>();
        for (PotionEffect effect : effects) {
            Map<String, Object> row = new HashMap<>();
            row.put("type", effect.getType().getKey().getKey());
            row.put("duration", effect.getDuration());
            row.put("amplifier", effect.getAmplifier());
            row.put("ambient", effect.isAmbient());
            row.put("particles", effect.hasParticles());
            row.put("icon", effect.hasIcon());
            serialized.add(row);
        }
        getConfig().set("previous-effects." + id, serialized);
    }

    private List<PotionEffect> loadEffectSnapshot(UUID id) {
        List<?> values = getConfig().getList("previous-effects." + id);
        if (values == null) return List.of();
        List<PotionEffect> effects = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> raw)) continue;
            String typeKey = String.valueOf(raw.get("type"));
            PotionEffectType type = Registry.POTION_EFFECT_TYPE.get(NamespacedKey.minecraft(typeKey));
            if (type == null) continue;
            int duration = number(raw.get("duration"), 0);
            int amplifier = number(raw.get("amplifier"), 0);
            boolean ambient = Boolean.TRUE.equals(raw.get("ambient"));
            boolean particles = !Boolean.FALSE.equals(raw.get("particles"));
            boolean icon = !Boolean.FALSE.equals(raw.get("icon"));
            if (duration > 0) effects.add(new PotionEffect(type, duration, amplifier, ambient, particles, icon));
        }
        return effects;
    }

    private int number(Object value, int fallback) {
        return value instanceof Number number ? number.intValue() : fallback;
    }

    private Location joinLocation(Player player) {
        return joinLocation(player.getName());
    }

    private Location joinLocation(String playerName) {
        Location auctionLocation = auction.currentPosition(playerName);
        if (auctionLocation != null) return auctionLocation;
        return configuredJoinLocation(playerName);
    }

    private Location configuredJoinLocation(String playerName) {
        TeamColor sold = store.soldTeam(playerName);
        if (sold != null && store.teamPosition(sold) != null) return store.teamPosition(sold);
        Tier tier = store.tierOf(playerName);
        if (tier == Tier.S && store.joinSpawn("S") != null) return store.joinSpawn("S");
        return store.joinSpawn("all");
    }

    @EventHandler
    public void onAsyncSpawnLocation(AsyncPlayerSpawnLocationEvent event) {
        String playerName = event.getConnection().getProfile().getName();
        if (playerName == null) return;
        Location configured = configuredJoinLocation(playerName);
        if (configured != null) event.setSpawnLocation(configured);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        Location configured = joinLocation(event.getPlayer());
        if (configured != null) event.setRespawnLocation(configured);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        refreshName(player);
        if (store.isPvpOff()) applyPvpOff(player);
        else if (getConfig().contains("previous-modes." + player.getUniqueId())) restorePvpOn(player);
        auction.onJoin(player);
        npcs.remove(player.getName());
        Location join = joinLocation(player);
        if (join != null) Bukkit.getScheduler().runTaskLater(this, () -> player.teleportAsync(join).thenAccept(success -> {
            if (success && store.isPvpOff() && store.tierOf(player.getName()) != Tier.S && !player.isOp()
                    && auction.currentPosition(player.getName()) == null) {
                Bukkit.getScheduler().runTask(this, () -> movementAnchors.put(player.getUniqueId(), join.clone()));
            }
            if (success && store.isPvpOff() && store.tierOf(player.getName()) != Tier.S && !player.isOp()
                    && auction.currentPosition(player.getName()) != null) {
                Bukkit.getScheduler().runTask(this, () -> setMovementAnchor(player, join));
            }
            if (success && store.isPvpOff()) Bukkit.getScheduler().runTask(this, () -> barrierPass.updateClientView(player));
        }), 2L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        movementAnchors.remove(event.getPlayer().getUniqueId());
        barrierPass.forgetClient(event.getPlayer());
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (auction.isRunning() && auction.isBiddingOpen()) {
            Tier playerTier = store.tierOf(player.getName());
            if (playerTier != Tier.S) {
                // Auctioned A-D players cannot send chat while bidding is open.
                if (playerTier != null) event.setCancelled(true);
                return;
            }
            if (text.matches("\\d{1,9}")) {
                event.setCancelled(true);
                try {
                    int amount = Integer.parseInt(text);
                    Bukkit.getScheduler().runTask(this, () -> auction.acceptBid(player, amount));
                } catch (NumberFormatException ignored) { }
                return;
            }
        }
        if (store.tierOf(player.getName()) != null) {
            TeamColor color = store.colorOf(player.getName());
            net.kyori.adventure.text.format.TextColor displayColor = color == null
                    ? net.kyori.adventure.text.format.NamedTextColor.WHITE : adventureColor(color);
            Component display = Component.text("(" + store.tierOf(player.getName()).name() + ") " + player.getName() + ": ", displayColor)
                    .append(event.message());
            event.renderer((source, sourceDisplayName, message, viewer) -> display);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (npcs.isStandIn(event.getEntity())) {
            event.setCancelled(true);
            if (event instanceof EntityDamageByEntityEvent byEntity) {
                Entity source = byEntity.getDamager();
                if (source instanceof Projectile projectile && projectile.getShooter() instanceof Entity shooter) source = shooter;
                if (source instanceof Player attacker) {
                    Vector push = event.getEntity().getLocation().toVector().subtract(attacker.getLocation().toVector());
                    if (push.lengthSquared() < 0.0001) push = attacker.getLocation().getDirection().multiply(-1);
                    push.normalize().multiply(0.45).setY(0.28);
                    event.getEntity().setVelocity(push);
                }
            }
            return;
        }
        if (!(event instanceof EntityDamageByEntityEvent byEntity)) return;
        Entity damager = byEntity.getDamager();
        Player attacker = damager instanceof Player direct ? direct
                : damager instanceof Projectile projectile && projectile.getShooter() instanceof Player shooter ? shooter : null;
        Player target = event.getEntity() instanceof Player player ? player : null;
        boolean podiumProtection = auction.isRunning()
                && ((attacker != null && isAuctionPodium(attacker)) || (target != null && isAuctionPodium(target)));
        if ((store.isPvpOff() && attacker != null) || podiumProtection) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (store.isPvpOff() || isAuctionPodium(event.getPlayer()) || npcs.isStandIn(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (store.isPvpOff() || isAuctionPodium(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (store.isPvpOff() || isAuctionPodium(event.getPlayer())) event.setCancelled(true);
        if (store.isPvpOff() && event.getClickedBlock() != null && event.getClickedBlock().getType() == Material.BARRIER
                && store.tierOf(event.getPlayer().getName()) == Tier.S) {
            event.getPlayer().sendBlockChange(event.getClickedBlock().getLocation(), Material.AIR.createBlockData());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (store.isPvpOff() || isAuctionPodium(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player player) || (!store.isPvpOff() && !isAuctionPodium(player))) return;
        InventoryHolder holder = event.getInventory().getHolder();
        if (holder instanceof BlockState) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (store.isPvpOff() || isAuctionPodium(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TierMenuHolder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || event.getCurrentItem() == null || !event.getCurrentItem().hasItemMeta()) return;
        String color = event.getCurrentItem().getItemMeta().getPersistentDataContainer().get(tierMenuKey, PersistentDataType.STRING);
        if (color == null) return;
        try { chooseColor(player, TeamColor.parse(color)); } catch (IllegalArgumentException ignored) { }
    }

    @EventHandler
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        if (event.getTo() == null) return;
        if (!(event instanceof PlayerTeleportEvent) && auction.isHeldAtAuctionSpot(player.getName())) {
            Location from = event.getFrom();
            Location to = event.getTo();
            if (from.getWorld() == to.getWorld()
                    && (Math.abs(from.getX() - to.getX()) > 0.0001 || Math.abs(from.getZ() - to.getZ()) > 0.0001)) {
                Location locked = from.clone();
                locked.setY(to.getY());
                locked.setYaw(to.getYaw());
                locked.setPitch(to.getPitch());
                event.setTo(locked);
            }
        }
        if (store.isPvpOff()) barrierPass.updateClientView(player);
        if (store.isPvpOff() && !player.isOp() && store.tierOf(player.getName()) != Tier.S) {
            Location anchor = movementAnchors.get(player.getUniqueId());
            if (anchor != null && anchor.getWorld().equals(event.getTo().getWorld()) && anchor.distanceSquared(event.getTo()) > 9.0) {
                Location safe = event.getFrom().clone();
                safe.setYaw(event.getTo().getYaw());
                safe.setPitch(event.getTo().getPitch());
                event.setTo(safe);
            }
        }
    }

    private boolean isAuctionPodium(Player player) {
        return player != null && auction != null && auction.isHeldAtAuctionSpot(player.getName());
    }

    private List<String> complete(CommandSender sender, String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return store.allNames().stream().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    void setMovementAnchor(Player player, Location location) {
        if (player == null || location == null || player.isOp() || store.tierOf(player.getName()) == Tier.S) return;
        movementAnchors.put(player.getUniqueId(), location.clone());
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String cmd = command.getName().toLowerCase(Locale.ROOT);
        if (cmd.equals("tiers")) {
            if (args.length == 1) return List.of("add", "colors").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) return complete(sender, args[1]);
            if (args.length == 3 && args[0].equalsIgnoreCase("add")) return List.of("S", "A", "B", "C", "D").stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        if (cmd.equals("tier")) {
            if (args.length == 1) return complete(sender, args[0]);
            if (args.length == 2) return List.of("set", "remove").stream().filter(s -> s.startsWith(args[1].toLowerCase())).toList();
            if (args.length == 3 && args[1].equalsIgnoreCase("set")) return List.of("S", "A", "B", "C", "D").stream().filter(s -> s.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        if (cmd.equals("assign") || cmd.equals("assignp")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(Arrays.stream(TeamColor.values()).map(c -> c.name().toLowerCase()).toList());
                options.add("remove");
                return options.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            }
            if (args.length == 2 && cmd.equals("assign")) return store.playersIn(Tier.S).stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            if (args.length == 2 && cmd.equals("assignp")) return store.allNames().stream().filter(s -> store.tierOf(s) != Tier.S && s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        }
        if (cmd.equals("credits")) {
            if (args.length == 1) {
                List<String> options = new ArrayList<>(List.of("add", "remove"));
                if (sender.hasPermission("auction.admin") || sender.isOp()) options.addAll(store.allNames());
                return options.stream().filter(s -> s.toLowerCase().startsWith(args[0].toLowerCase())).distinct().toList();
            }
            if (args.length == 2 && (args[0].equalsIgnoreCase("add") || args[0].equalsIgnoreCase("remove"))) {
                return store.allNames().stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
            }
        }
        if (cmd.equals("addnpc") && args.length == 1) {
            return store.allNames().stream().filter(name -> store.tierOf(name) != null)
                    .filter(name -> name.toLowerCase().startsWith(args[0].toLowerCase())).toList();
        }
        if (cmd.equals("pos")) {
            if (args.length == 1) return List.of("add", "delete").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("add")) return store.positions(false).stream().map(String::valueOf).filter(s -> s.startsWith(args[1])).toList();
            if (args.length == 2 && args[0].equalsIgnoreCase("delete")) return store.positions(true).stream().map(String::valueOf).filter(s -> s.startsWith(args[1])).toList();
        }
        if (cmd.equals("posteam") && args.length == 1) return Arrays.stream(TeamColor.values()).map(c -> c.name().toLowerCase()).filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (cmd.equals("axs") && args.length == 1) return "set".startsWith(args[0].toLowerCase()) ? List.of("set") : List.of();
        if (cmd.equals("axs") && args.length == 2) return List.of("all", "S").stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        if (cmd.equals("auction") && args.length == 1) return List.of("start", "stop", "npcs").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (cmd.equals("auction") && args.length == 2 && args[0].equalsIgnoreCase("npcs")) return "clear".startsWith(args[1].toLowerCase()) ? List.of("clear") : List.of();
        if (cmd.equals("bidding") && args.length == 1) return List.of("start", "pause", "stop", "restart").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        if (cmd.equals("bidding") && args.length == 2 && args[0].equalsIgnoreCase("restart")) return List.of("A", "B", "C", "D").stream().filter(s -> s.toLowerCase().startsWith(args[1].toLowerCase())).toList();
        if (cmd.equals("pvp") && args.length == 1) return List.of("on", "off").stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        return List.of();
    }

    private static final class TierMenuHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
