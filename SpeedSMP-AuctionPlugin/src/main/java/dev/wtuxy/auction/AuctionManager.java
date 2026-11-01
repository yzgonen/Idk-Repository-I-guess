package dev.wtuxy.auction;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.Sound;
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
    private final List<String> pendingLeftovers = new ArrayList<>();
    private int index;
    private boolean running;
    private boolean biddingOpen;
    private boolean paused;
    private String currentPlayer;
    private String highestCaptain;
    private TeamColor highestColor;
    private int highestBid;
    private int acceptedBidCount;
    private BukkitTask timerTask;
    private BossBar bossBar;
    private long biddingEndsAt;
    private long biddingDurationMillis;
    private long pausedRemainingMillis;
    private int countdownGeneration;
    private boolean skipPreviouslySoldPlayers;

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
    public boolean isPaused() { return paused; }
    public String currentPlayer() { return currentPlayer; }
    public int availableCredits(TeamColor color) {
        int total = plugin.store().budget(color, plugin.getConfig().getInt("starting-budget", 250));
        int reserved = biddingOpen && highestColor == color ? highestBid : 0;
        return Math.max(0, total - reserved);
    }

    public boolean isHeldAtAuctionSpot(String playerName) {
        return (currentPlayer != null && currentPlayer.equalsIgnoreCase(playerName)
                && plugin.store().soldTeam(playerName) == null)
                || pendingLeftovers.stream().anyMatch(name -> name.equalsIgnoreCase(playerName));
    }

    public Location currentPosition(String playerName) {
        int playerIndex = -1;
        for (int i = 0; i < lineup.size(); i++) {
            if (lineup.get(i).equalsIgnoreCase(playerName)) { playerIndex = i; break; }
        }
        if (playerIndex < 0 || !isHeldAtAuctionSpot(playerName)) return null;
        return plugin.store().position(playerIndex + 1);
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
        for (Tier auctionTier : AUCTION_TIERS) {
            for (String name : plugin.store().playersIn(auctionTier)) {
                plugin.store().clearSoldTeam(name);
                plugin.store().clearPurchase(name);
            }
        }
        running = true;
        pendingLeftovers.clear();
        skipPreviouslySoldPlayers = false;
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
        paused = false;
        currentPlayer = null;
        lineup.clear();
        pendingLeftovers.clear();
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
        if (paused) {
            resumeBidding();
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

    public void pauseBidding() {
        if (!running || !biddingOpen || paused) {
            Bukkit.broadcast(Component.text("There is no active bidding timer to pause."));
            return;
        }
        pausedRemainingMillis = Math.max(0L, biddingEndsAt - System.currentTimeMillis());
        paused = true;
        if (timerTask != null) {
            timerTask.cancel();
            timerTask = null;
        }
        if (bossBar != null) bossBar.setTitle("Bidding paused: " + currentPlayer + " — " + ((pausedRemainingMillis + 999) / 1000) + "s");
        Bukkit.broadcast(Component.text("Bidding is paused. Use /bidding start to resume."));
    }

    private void resumeBidding() {
        if (!paused || !running || !biddingOpen) return;
        paused = false;
        biddingEndsAt = System.currentTimeMillis() + pausedRemainingMillis;
        startTimerTask();
        Bukkit.broadcast(Component.text("Bidding has resumed."));
    }

    public void restartTier(String requested) {
        Tier restartTier;
        try {
            restartTier = Tier.parse(requested);
        } catch (IllegalArgumentException e) {
            Bukkit.broadcast(Component.text("Choose a tier: A, B, C, or D."));
            return;
        }
        if (restartTier == Tier.S) {
            Bukkit.broadcast(Component.text("Only auction tiers A, B, C, and D can be restarted."));
            return;
        }
        for (TeamColor color : TeamColor.values()) {
            if (plugin.store().captain(color) == null || plugin.store().teamPosition(color) == null) {
                Bukkit.broadcast(Component.text("Set up all eight team captains and /posteam destinations before restarting a tier."));
                return;
            }
        }

        cancelTimer();
        biddingOpen = false;
        paused = false;
        if (bossBar != null) {
            bossBar.removeAll();
            bossBar = null;
        }

        int refunded = 0;
        int untracked = 0;
        for (String name : plugin.store().playersIn(restartTier)) {
            if (plugin.store().hasPurchase(name, restartTier)) {
                int amount = plugin.store().purchaseAmount(name, restartTier);
                TeamColor team = plugin.store().purchaseTeam(name, restartTier);
                if (team != null && amount > 0) {
                    int balance = plugin.store().budget(team, plugin.getConfig().getInt("starting-budget", 250));
                    plugin.store().setBudget(team, balance + amount);
                    refunded += amount;
                }
            } else if (plugin.store().soldTeam(name) != null) {
                untracked++;
            }
            plugin.store().clearSoldTeam(name);
            plugin.store().clearPurchase(name);
        }
        pendingLeftovers.removeIf(name -> plugin.store().tierOf(name) == restartTier);

        tier = restartTier;
        plugin.store().setAuctionTier(tier);
        index = 0;
        currentPlayer = null;
        highestCaptain = null;
        highestColor = null;
        highestBid = 0;
        acceptedBidCount = 0;
        running = true;
        skipPreviouslySoldPlayers = true;
        plugin.store().setAuctionRunning(true);
        Bukkit.broadcast(Component.text("Restarting " + tier + "-Tiers. Refunded " + refunded + " auction money."));
        if (untracked > 0) {
            Bukkit.broadcast(Component.text(untracked + " earlier sale(s) had no saved winning price, so those amounts could not be refunded."));
        }
        beginTier();
    }

    private void beginTier() {
        lineup.clear();
        for (String name : plugin.store().playersIn(tier)) {
            if (!skipPreviouslySoldPlayers || plugin.store().soldTeam(name) == null) lineup.add(name);
        }
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
        acceptedBidCount = 0;
        Location position = plugin.store().position(index + 1);
        Player player = Bukkit.getPlayerExact(currentPlayer);
        if (player != null) {
            plugin.npcs().remove(currentPlayer);
            player.teleportAsync(position);
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
        int seconds = Math.max(1, plugin.getConfig().getInt("bidding-seconds", 20));
        for (int i = countdown; i > 0; i--) {
            final int shown = i;
            final float pitch = Math.min(2.0f, 0.8f + (countdown - i) * 0.2f);
            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (generation != countdownGeneration || !running) return;
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.showTitle(Title.title(Component.text(String.valueOf(shown)), Component.text("Get ready to bid"),
                            Title.Times.times(Duration.ZERO, Duration.ofMillis(700), Duration.ofMillis(100))));
                    player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_PLING, 1.0f, pitch);
                }
            }, (long) (countdown - i) * 20L);
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (generation != countdownGeneration || !running || currentPlayer == null || biddingOpen) return;
            biddingOpen = true;
            biddingDurationMillis = seconds * 1000L;
            biddingEndsAt = System.currentTimeMillis() + biddingDurationMillis;
            bossBar = Bukkit.createBossBar("Bidding: " + currentPlayer, BarColor.PURPLE, BarStyle.SOLID);
            bossBar.setProgress(1.0);
            for (Player player : Bukkit.getOnlinePlayers()) bossBar.addPlayer(player);
            startTimerTask();
        }, (long) countdown * 20L);
    }

    public void acceptBid(Player bidder, int amount) {
        if (!biddingOpen || !running || paused) {
            bidder.sendMessage(Component.text("Bidding is not open right now."));
            return;
        }
        if (plugin.store().tierOf(bidder.getName()) != Tier.S) {
            bidder.sendMessage(Component.text("Only S-tier players can bid."));
            return;
        }
        if (System.currentTimeMillis() >= biddingEndsAt) {
            bidder.sendMessage(Component.text("Bidding time has expired."));
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
        String previousCaptain = highestCaptain;
        TeamColor previousColor = highestColor;
        int previousBid = highestBid;
        highestCaptain = bidder.getName();
        highestColor = color;
        highestBid = amount;
        int extensionSeconds = acceptedBidCount == 0
                ? plugin.getConfig().getInt("first-bid-extension-seconds", 5)
                : plugin.getConfig().getInt("bid-extension-seconds", 10);
        acceptedBidCount++;
        long extensionMillis = Math.max(0, extensionSeconds) * 1000L;
        biddingEndsAt += extensionMillis;
        biddingDurationMillis += extensionMillis;
        Bukkit.broadcast(Component.text("I bid " + amount + "!").color(plugin.adventureColor(color)));
        bidder.sendMessage(Component.text("You bid " + amount + "! Your team has " + availableCredits(color)
                + " auction credits left while your bid is leading. If another team outbids you, your bid is returned."));
        if (previousCaptain != null && previousColor != null && previousColor != color) {
            Player outbid = Bukkit.getPlayerExact(previousCaptain);
            if (outbid != null) outbid.sendMessage(Component.text("Your " + previousBid
                    + " credit bid was outbid. Those credits are available again; your team now has "
                    + availableCredits(previousColor) + " available."));
        }
    }

    public void sellCommand() { sellCurrent(true); }

    private void sellCurrent(boolean manual) {
        if (!running || currentPlayer == null) return;
        if (!biddingOpen && !manual) return;
        cancelTimer();
        biddingOpen = false;
        paused = false;
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
            plugin.store().recordPurchase(sold, tier, winner, price);
            Player player = Bukkit.getPlayerExact(sold);
            if (player != null) plugin.refreshName(player);
            Bukkit.broadcast(Component.text("Sold to Mr " + highestCaptain + " — " + sold + " won by " + winner.name() + " Team for " + price)
                    .color(plugin.adventureColor(winner)));
            Location target = teamArrival(winner, sold);
            if (player != null) {
                plugin.npcs().remove(sold);
                if (target != null) player.teleportAsync(target);
            } else if (target != null) {
                plugin.npcs().move(sold, target);
            }
        } else {
            pendingLeftovers.add(currentPlayer);
            Bukkit.broadcast(Component.text(currentPlayer + " received no bids and will stay at their auction position until "
                    + tier.name() + "-Tier bidding is finished."));
        }
        index++;
        Bukkit.getScheduler().runTaskLater(plugin, this::prepareCurrentPlayer, 20L);
    }

    private void advanceTier() {
        distributeLeftovers();
        int next = AUCTION_TIERS.indexOf(tier) + 1;
        if (next >= AUCTION_TIERS.size()) {
            running = false;
            skipPreviouslySoldPlayers = false;
            plugin.store().setAuctionRunning(false);
            currentPlayer = null;
            Bukkit.broadcast(Component.text("The auction is complete."));
            return;
        }
        tier = AUCTION_TIERS.get(next);
        plugin.store().setAuctionTier(tier);
        beginTier();
    }

    private void distributeLeftovers() {
        if (pendingLeftovers.isEmpty()) return;
        List<String> unbid = new ArrayList<>(pendingLeftovers);
        pendingLeftovers.clear();
        for (String name : unbid) {
            TeamColor team = teamForLeftover(tier);
            if (team == null) {
                Bukkit.broadcast(Component.text(name + " received no bids and remains unassigned."));
                continue;
            }
            Location target = teamArrival(team, name);
            plugin.store().setSoldTeam(name, team);
            plugin.store().recordPurchase(name, tier, team, 0);
            Player player = Bukkit.getPlayerExact(name);
            if (player != null) {
                plugin.refreshName(player);
                if (target != null) player.teleportAsync(target);
            } else if (target != null) plugin.npcs().move(name, target);
            Bukkit.broadcast(Component.text(name + " was assigned to the " + team.name() + " Team as a leftover.")
                    .color(plugin.adventureColor(team)));
        }
    }

    private Location teamArrival(TeamColor team, String playerName) {
        Location base = plugin.store().teamPosition(team);
        if (base == null) return null;
        int slot = 0;
        for (String name : plugin.store().allNames()) {
            if (name.equalsIgnoreCase(playerName)) continue;
            if (plugin.store().soldTeam(name) == team) slot++;
        }
        if (slot == 0) return base;
        double angle = (Math.PI * 2.0 * (slot - 1)) / 8.0;
        return base.clone().add(Math.cos(angle) * 1.1, 0, Math.sin(angle) * 1.1);
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

    private void startTimerTask() {
        if (timerTask != null) timerTask.cancel();
        timerTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            if (paused || !biddingOpen) return;
            long millis = Math.max(0L, biddingEndsAt - System.currentTimeMillis());
            double progress = millis / (double) Math.max(1L, biddingDurationMillis);
            if (bossBar != null) {
                bossBar.setProgress(Math.max(0, Math.min(1, progress)));
                bossBar.setTitle("Bidding: " + currentPlayer + " — " + ((millis + 999) / 1000) + "s");
            }
            if (millis <= 0) sellCurrent(false);
        }, 0L, 2L);
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
