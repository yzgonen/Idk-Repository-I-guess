package dev.wtuxy.auction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public final class AuctionManager {
    private static final List<Tier> AUCTION_TIERS = List.of(Tier.A, Tier.B, Tier.C, Tier.D);
    private final AuctionPlugin plugin;
    private Tier tier = Tier.A;
    private final List<String> lineup = new ArrayList<>();
    private int index;
    private boolean running;
    private boolean biddingOpen;
    private String currentPlayer;
    private String highestCaptain;
    private TeamColor highestColor;
    private int highestBid;
    private BukkitTask timerTask;
    private BossBar bossBar;
    private long biddingEndsAt;
    private int countdownGeneration;

    public AuctionManager(AuctionPlugin plugin) {
        this.plugin = plugin;
        this.running = plugin.store().isAuctionRunning();
        this.tier = plugin.store().auctionTier();
        if (running) {
            running = false;
            plugin.store().setAuctionRunning(false);
            plugin.getLogger().info("Previous auction state was safely closed after restart.");
        }
    }

    public boolean isRunning() { return running; }
    public boolean isBiddingOpen() { return biddingOpen; }
    public String currentPlayer() { return currentPlayer; }

    public Location currentPosition(String playerName) {
        if (currentPlayer == null || !currentPlayer.equalsIgnoreCase(playerName)) return null;
        return plugin.store().position(index + 1);
    }

    public void start() {
        if (running) {
            Bukkit.broadcast(Component.text("An auction is already running."));
            return;
        }
        for (TeamColor color : TeamColor.values()) {
            if (plugin.store().captain(color) == null) {
                Bukkit.broadcast(Component.text("Auction not started: assign all eight S-tier captains first. Missing " + color.name() + " Team."));
                return;
            }
            if (plugin.store().teamPosition(color) == null) {
                Bukkit.broadcast(Component.text("Auction not started: save the " + color.name() + " Team destination with /posteam " + color.name().toLowerCase() + "."));
                return;
            }
        }
        running = true;
        plugin.store().setAuctionRunning(true);
        tier = Tier.A;
        plugin.store().setAuctionTier(tier);
        index = 0;
        for (TeamColor color : TeamColor.values()) {
            plugin.store().setBudget(color, plugin.getConfig().getInt("starting-budget", 250));
        }
        beginTier();
    }

    public void stop() {
        cancelTimer();
        running = false;
        biddingOpen = false;
        currentPlayer = null;
        lineup.clear();
        plugin.store().setAuctionRunning(false);
        if (bossBar != null) bossBar.removeAll();
        bossBar = null;
        Bukkit.broadcast(Component.text("The auction has been stopped."));
    }

    public void startBidding() {
        if (!running) {
            start();
            return;
        }
        if (biddingOpen) {
            Bukkit.broadcast(Component.text("Bidding is already open."));
            return;
        }
        if (currentPlayer == null) {
            Bukkit.broadcast(Component.text("There is no current player to auction."));
            return;
        }
        beginBidding();
    }

    private void beginTier() {
        lineup.clear();
        lineup.addAll(plugin.store().playersIn(tier));
        index = 0;
        Bukkit.broadcast(Component.text("Bidding " + tier.name() + "-Tiers"));
        if (lineup.size() > 8) {
            Bukkit.broadcast(Component.text(tier.name() + "-Tier has more than 8 registered players. The auction was stopped; fix the tier list first."));
            stop();
            return;
        }
        if (lineup.isEmpty()) {
            advanceTier();
            return;
        }
        boolean ready = true;
        for (int i = 0; i < lineup.size() && i < 8; i++) {
            Location position = plugin.store().position(i + 1);
            if (position == null) {
                Bukkit.broadcast(Component.text("Position " + (i + 1) + " is not set. An admin must use /pos add " + (i + 1) + " first."));
                ready = false;
                break;
            }
        }
        if (!ready) {
            stop();
            return;
        }
        prepareCurrentPlayer();
    }

    private void prepareCurrentPlayer() {
        if (!running) return;
        if (index >= lineup.size()) {
            advanceTier();
            return;
        }
        currentPlayer = lineup.get(index);
        highestCaptain = null;
        highestColor = null;
        highestBid = 0;
        Location position = plugin.store().position(index + 1);
        Player player = Bukkit.getPlayerExact(currentPlayer);
        if (player != null) {
            plugin.npcs().remove(currentPlayer);
            player.teleportAsync(position).thenAccept(success -> {
                if (success) Bukkit.getScheduler().runTask(plugin, () -> plugin.setMovementAnchor(player, position));
            });
        } else if (!plugin.npcs().spawn(currentPlayer, position)) {
            Bukkit.broadcast(Component.text("Could not create the offline player stand-in for " + currentPlayer + ". Check Citizens and the player's cached skin."));
        }
        Bukkit.broadcast(Component.text("Bidding " + currentPlayer + " (" + ordinal(index + 1) + ")"));
        beginBidding();
    }

    private void beginBidding() {
        if (!running || currentPlayer == null || biddingOpen) return;
        int generation = ++countdownGeneration;
        int countdown = Math.max(0, plugin.getConfig().getInt("countdown-seconds", 3));
        int seconds = Math.max(1, plugin.getConfig().getInt("bidding-seconds", 30));
        for (int i = countdown; i > 0; i--) {
            final int shown = i;
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (generation != countdownGeneration || !running) return;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showTitle(Title.title(Component.text(String.valueOf(shown)), Component.text("Get ready to bid"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(100))));
                }
            }, (long) (countdown - i) * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (generation != countdownGeneration || !running || currentPlayer == null || biddingOpen) return;
            biddingOpen = true;
            biddingEndsAt = System.currentTimeMillis() + seconds * 1000L;
            bossBar = Bukkit.createBossBar("Bidding: " + currentPlayer, BarColor.PURPLE, BarStyle.SOLID);
            bossBar.setProgress(1.0);
            for (Player player : Bukkit.getOnlinePlayers()) bossBar.addPlayer(player);
            timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
                long millis = Math.max(0L, biddingEndsAt - System.currentTimeMillis());
                double progress = millis / (seconds * 1000.0);
                if (bossBar != null) {
                    bossBar.setProgress(Math.max(0, Math.min(1, progress)));
                    bossBar.setTitle("Bidding: " + currentPlayer + " — " + ((millis + 999) / 1000) + "s");
                }
                if (millis <= 0) sellCurrent(false);
            }, 0L, 2L);
        }, (long) countdown * 20L);
    }

    public void acceptBid(Player bidder, int amount) {
        if (!biddingOpen || !running) {
            bidder.sendMessage(Component.text("Bidding is not open right now."));
            return;
        }
        if (plugin.store().tierOf(bidder.getName()) != Tier.S) {
            bidder.sendMessage(Component.text("Only S-tier players can bid."));
            return;
        }
        TeamColor color = plugin.store().colorOf(bidder.getName());
        if (color == null) {
            bidder.sendMessage(Component.text("Choose your team color before bidding."));
            return;
        }
        int funds = plugin.store().budget(color, plugin.getConfig().getInt("starting-budget", 250));
        if (amount <= highestBid) {
            bidder.sendMessage(Component.text("Your bid must be higher than the current bid of " + highestBid + "."));
            return;
        }
        if (amount > funds) {
            bidder.sendMessage(Component.text("Your team has " + funds + " auction money left."));
            return;
        }
        if (teamAlreadyHasTier(color, tier)) {
            bidder.sendMessage(Component.text("Your team already has a " + tier + "-tier player."));
            return;
        }
        highestCaptain = bidder.getName();
        highestColor = color;
        highestBid = amount;
        Bukkit.broadcast(Component.text("I bid " + amount + "!").color(plugin.adventureColor(color)));
    }

    public void sellCommand() { sellCurrent(true); }

    private void sellCurrent(boolean manual) {
        if (!running || currentPlayer == null) return;
        if (!biddingOpen && !manual) return;
        cancelTimer();
        biddingOpen = false;
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }
        if (highestColor != null) {
            String sold = currentPlayer;
            TeamColor winner = highestColor;
            int price = highestBid;
            int left = plugin.store().budget(winner, plugin.getConfig().getInt("starting-budget", 250)) - price;
            plugin.store().setBudget(winner, left);
            plugin.store().setSoldTeam(sold, winner);
            Player player = Bukkit.getPlayerExact(sold);
            if (player != null) plugin.refreshName(player);
            Bukkit.broadcast(Component.text("Sold to Mr " + highestCaptain + " — " + sold + " won by " + winner.name() + " Team for " + price)
                    .color(plugin.adventureColor(winner)));
            Location target = plugin.store().teamPosition(winner);
            if (player != null) {
                plugin.npcs().remove(sold);
                if (target != null) player.teleportAsync(target).thenAccept(success -> {
                    if (success) Bukkit.getScheduler().runTask(plugin, () -> plugin.setMovementAnchor(player, target));
                });
            } else if (target != null) {
                plugin.npcs().move(sold, target);
            }
        } else {
            TeamColor leftoverTeam = teamForLeftover(tier);
            if (leftoverTeam == null) {
                Bukkit.broadcast(Component.text(currentPlayer + " received no bids and remains unassigned."));
            } else {
                String unbidPlayer = currentPlayer;
                plugin.store().setSoldTeam(unbidPlayer, leftoverTeam);
                Player player = Bukkit.getPlayerExact(unbidPlayer);
                if (player != null) plugin.refreshName(player);
                Bukkit.broadcast(Component.text(unbidPlayer + " was assigned to the " + leftoverTeam.name() + " Team as a leftover.")
                        .color(plugin.adventureColor(leftoverTeam)));
                Location target = plugin.store().teamPosition(leftoverTeam);
                if (player != null && target != null) {
                    player.teleportAsync(target).thenAccept(success -> {
                        if (success) Bukkit.getScheduler().runTask(plugin, () -> plugin.setMovementAnchor(player, target));
                    });
                } else if (target != null) plugin.npcs().move(unbidPlayer, target);
            }
        }
        index++;
        Bukkit.getScheduler().runTaskLater(plugin, this::prepareCurrentPlayer, 20L);
    }

    private void advanceTier() {
        int next = AUCTION_TIERS.indexOf(tier) + 1;
        if (next >= AUCTION_TIERS.size()) {
            running = false;
            plugin.store().setAuctionRunning(false);
            currentPlayer = null;
            Bukkit.broadcast(Component.text("The auction is complete."));
            return;
        }
        tier = AUCTION_TIERS.get(next);
        plugin.store().setAuctionTier(tier);
        beginTier();
    }

    private boolean teamAlreadyHasTier(TeamColor color, Tier requestedTier) {
        for (String name : plugin.store().playersIn(requestedTier)) {
            if (color.name().equalsIgnoreCase(plugin.store().teamOf(name))) return true;
        }
        return false;
    }

    private TeamColor teamForLeftover(Tier requestedTier) {
        return java.util.Arrays.stream(TeamColor.values())
                .filter(color -> !teamAlreadyHasTier(color, requestedTier))
                .max(java.util.Comparator.comparingInt(color -> plugin.store().budget(color,
                        plugin.getConfig().getInt("starting-budget", 250))))
                .orElse(null);
    }

    public boolean parseBid(Player player, String message) {
        if (!running || !biddingOpen || plugin.store().tierOf(player.getName()) != Tier.S) return false;
        if (!message.matches("\\d{1,9}")) return false;
        int amount;
        try { amount = Integer.parseInt(message); } catch (NumberFormatException e) { return false; }
        acceptBid(player, amount);
        return true;
    }

    public void onJoin(Player player) {
        if (bossBar != null) bossBar.addPlayer(player);
    }

    private void cancelTimer() {
        countdownGeneration++;
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
    }

    private String ordinal(int number) {
        int mod100 = number % 100;
        if (mod100 >= 11 && mod100 <= 13) return number + "th";
        return switch (number % 10) {
            case 1 -> number + "st";
            case 2 -> number + "nd";
            case 3 -> number + "rd";
            default -> number + "th";
        };
    }
}
