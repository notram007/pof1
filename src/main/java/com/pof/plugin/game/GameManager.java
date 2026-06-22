package com.pof.plugin.game;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.loot.LootManager;
import com.pof.plugin.util.MessageUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Manages the live game lifecycle for a single Arena: waiting room,
 * countdown, loot ticking, off-map watching, duration timeout, and
 * win/elimination handling. Each Arena owns exactly one GameManager,
 * so multiple arenas run fully independently of one another.
 */
public class GameManager {

    private final PillarsOfFortune plugin;
    private final Arena arena;
    private final LootManager lootManager;
    private final GameStateStore stateStore;
    private final MessageUtil messages;

    private GameState state = GameState.WAITING;
    private final Set<Player> waitingPlayers = new LinkedHashSet<>();
    private final Set<Player> alivePlayers = new LinkedHashSet<>();
    private final Set<Player> spectators = new LinkedHashSet<>();
    private final Map<Player, Pillar> playerPillars = new HashMap<>();
    private final Map<Player, ItemStack> lastItemGiven = new HashMap<>();
    private final Map<Player, InventorySnapshot> savedInventories = new HashMap<>();

    /**
     * Captures everything about a player's inventory/state that needs to
     * be restored after a game, so clearing their inventory for the round
     * never permanently destroys their items.
     */
    private static class InventorySnapshot {
        final ItemStack[] contents;
        final ItemStack[] armorContents;
        final ItemStack offHand;
        final GameMode previousGameMode;
        final float exp;
        final int level;
        final double health;
        final int foodLevel;

        InventorySnapshot(Player player) {
            PlayerInventory inv = player.getInventory();
            this.contents = inv.getContents().clone();
            this.armorContents = inv.getArmorContents().clone();
            this.offHand = inv.getItemInOffHand().clone();
            this.previousGameMode = player.getGameMode();
            this.exp = player.getExp();
            this.level = player.getLevel();
            this.health = player.getHealth();
            this.foodLevel = player.getFoodLevel();
        }

        void restore(Player player) {
            PlayerInventory inv = player.getInventory();
            inv.clear();
            inv.setContents(contents);
            inv.setArmorContents(armorContents);
            inv.setItemInOffHand(offHand);
            player.setExp(exp);
            player.setLevel(level);
            double maxHealth = player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH) != null
                    ? player.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH).getValue()
                    : 20.0;
            player.setHealth(Math.min(health, maxHealth));
            player.setFoodLevel(foodLevel);
        }
    }

    private BukkitTask countdownTask;
    private BukkitTask lootTask;
    private BukkitTask durationTask;
    private BukkitTask offMapTask;

    public GameManager(PillarsOfFortune plugin, Arena arena, LootManager lootManager, GameStateStore stateStore) {
        this.plugin = plugin;
        this.arena = arena;
        this.lootManager = lootManager;
        this.stateStore = stateStore;
        this.messages = plugin.getMessages();
    }

    public Arena getArena() {
        return arena;
    }

    public GameState getState() {
        return state;
    }

    public int getCurrentCount() {
        return state == GameState.WAITING || state == GameState.COUNTDOWN
                ? waitingPlayers.size()
                : alivePlayers.size();
    }

    public boolean isPlayerInGame(Player player) {
        return waitingPlayers.contains(player) || alivePlayers.contains(player);
    }

    private Map<String, String> arenaPlaceholder() {
        Map<String, String> map = new HashMap<>();
        map.put("arena", arena.getName());
        return map;
    }

    // ---------------- JOIN / LEAVE ----------------

    public void joinGame(Player player) {
        if (isPlayerInGame(player)) {
            messages.send(player, "already-in-game", arenaPlaceholder());
            return;
        }

        if (state == GameState.RUNNING || state == GameState.ENDING) {
            messages.send(player, "game-already-running", arenaPlaceholder());
            return;
        }

        FileConfiguration config = plugin.getConfig();
        int maxPlayers = config.getInt("game.max-players", 16);
        if (waitingPlayers.size() >= maxPlayers) {
            messages.send(player, "game-full", arenaPlaceholder());
            return;
        }

        int registered = arena.getPillarCount();
        if (registered < waitingPlayers.size() + 1) {
            Map<String, String> ph = arenaPlaceholder();
            ph.put("registered", String.valueOf(registered));
            messages.send(player, "not-enough-pillars", ph);
            return;
        }

        // Assign a random available pillar to this player
        java.util.List<Pillar> availablePillars = new java.util.ArrayList<>(arena.getPillars());
        java.util.Collections.shuffle(availablePillars);
        
        Pillar assignedPillar = null;
        for (Pillar pillar : availablePillars) {
            // Find a pillar that's not already assigned to someone waiting
            boolean taken = false;
            for (Player waitingPlayer : waitingPlayers) {
                if (playerPillars.containsKey(waitingPlayer) && playerPillars.get(waitingPlayer).equals(pillar)) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                assignedPillar = pillar;
                break;
            }
        }

        if (assignedPillar == null) {
            // Fallback: shouldn't happen if we checked registered >= waiting.size() + 1
            messages.send(player, "not-enough-pillars", arenaPlaceholder());
            return;
        }

        waitingPlayers.add(player);
        stateStore.markInGame(player.getUniqueId());
        playerPillars.put(player, assignedPillar);
        
        // Teleport directly to their pillar
        player.teleport(assignedPillar.getLocation());
        player.setGameMode(GameMode.ADVENTURE);
        
        // Apply Slow Falling for 3 seconds so they land safely
        player.addPotionEffect(new PotionEffect(
                PotionEffectType.SLOW_FALLING,
                60, // 3 seconds = 60 ticks
                0,
                true,
                false));

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(waitingPlayers.size()));
        ph.put("max", String.valueOf(maxPlayers));
        broadcastToWaiting("join", ph);

        checkCountdownStart();
    }

    public void leaveGame(Player player) {
        boolean wasWaiting = waitingPlayers.remove(player);
        boolean wasAlive = alivePlayers.remove(player);

        if (!wasWaiting && !wasAlive) {
            messages.send(player, "not-in-any-game", arenaPlaceholder());
            return;
        }

        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        if (wasAlive) {
            teleportToLobby(player);
            restorePlayer(player);
        }

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("current", String.valueOf(getCurrentCount()));
        ph.put("max", String.valueOf(plugin.getConfig().getInt("game.max-players", 16)));
        broadcastToWaiting("leave", ph);

        if (wasWaiting && state == GameState.COUNTDOWN && waitingPlayers.size() < plugin.getConfig().getInt("game.min-players", 2)) {
            cancelCountdown();
        }

        if (wasAlive && state == GameState.RUNNING) {
            checkForWinner();
        }
    }

    public void handleQuit(Player player) {
        if (isPlayerInGame(player)) {
            leaveGame(player);
        }
    }

    // ---------------- COUNTDOWN ----------------

    private void checkCountdownStart() {
        int minPlayers = plugin.getConfig().getInt("game.min-players", 2);
        if (state == GameState.WAITING && waitingPlayers.size() >= minPlayers) {
            startCountdown();
        }
    }

    private void startCountdown() {
        state = GameState.COUNTDOWN;
        int seconds = plugin.getConfig().getInt("game.countdown-seconds", 15);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("seconds", String.valueOf(seconds));
        broadcastToWaiting("countdown-start", ph);

        countdownTask = new BukkitRunnable() {
            int remaining = seconds;

            @Override
            public void run() {
                if (state != GameState.COUNTDOWN) {
                    cancel();
                    return;
                }
                remaining--;
                if (remaining <= 0) {
                    cancel();
                    startGame();
                    return;
                }
                if (remaining <= 5 || remaining % 5 == 0) {
                    Map<String, String> tickPh = arenaPlaceholder();
                    tickPh.put("seconds", String.valueOf(remaining));
                    broadcastToWaiting("countdown-tick", tickPh);
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    private void cancelCountdown() {
        if (countdownTask != null) {
            countdownTask.cancel();
            countdownTask = null;
        }
        state = GameState.WAITING;
    }

    // ---------------- GAME START ----------------

    private void startGame() {
        state = GameState.RUNNING;

        // Players are already on their assigned pillars (from joinGame).
        // Just move them from waiting to alive and set them to SURVIVAL mode.
        for (Player player : waitingPlayers) {
            if (player.isOnline()) {
                alivePlayers.add(player);
                player.setGameMode(GameMode.SURVIVAL);
                player.getInventory().clear();
                player.setExp(0);
                player.setLevel(0);
                player.setHealth(20.0);
                player.setFoodLevel(20);

                // Save their pre-game inventory state
                savedInventories.put(player, new InventorySnapshot(player));
            }
        }
        waitingPlayers.clear();

        int interval = plugin.getConfig().getInt("game.loot-interval-seconds", 5);
        Map<String, String> ph = arenaPlaceholder();
        ph.put("interval", String.valueOf(interval));
        broadcastToAlive("game-start", ph);

        startLootTicking(plugin.getConfig().getInt("game.items-per-drop", 1));
        startDurationTimer();
        startOffMapWatcher();
    }

    public void forceStartFromAdmin(CommandSender sender) {
        if (state == GameState.RUNNING || state == GameState.COUNTDOWN) {
            sender.sendMessage(messages.getPrefix() + "Cannot force start - a game/countdown is already active.");
            return;
        }
        if (waitingPlayers.isEmpty()) {
            sender.sendMessage(messages.getPrefix() + "No players are waiting to start.");
            return;
        }
        if (countdownTask != null) {
            countdownTask.cancel();
        }
        startGame();
    }

    // ---------------- LOOT TICKING ----------------

    private void startLootTicking(int itemsPerDrop) {
        int intervalTicks = plugin.getConfig().getInt("game.loot-interval-seconds", 5) * 20;

        lootTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                for (Player player : new java.util.ArrayList<>(alivePlayers)) {
                    if (!player.isOnline()) continue;
                    ItemStack[] drops = new ItemStack[itemsPerDrop];
                    ItemStack lastItem = null;
                    for (int i = 0; i < itemsPerDrop; i++) {
                        ItemStack item = lootManager.rollItem();
                        drops[i] = item;
                        lastItem = item;
                    }
                    player.getInventory().addItem(drops);
                    if (lastItem != null) {
                        lastItemGiven.put(player, lastItem);
                        Map<String, String> ph = arenaPlaceholder();
                        ph.put("item", describeLastItem(player));
                        messages.send(player, "loot-received", ph);
                    }
                }
            }
        }.runTaskTimer(plugin, intervalTicks, intervalTicks);
    }

    private String describeLastItem(Player player) {
        ItemStack item = lastItemGiven.get(player);
        if (item == null) return "item";
        return item.getAmount() + "x " + item.getType().name();
    }

    // ---------------- DURATION TIMEOUT ----------------

    private void startDurationTimer() {
        int maxDuration = plugin.getConfig().getInt("game.max-duration-seconds", 300);
        if (maxDuration <= 0) {
            return;
        }
        durationTask = new BukkitRunnable() {
            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                forceEndByTimeout();
            }
        }.runTaskLater(plugin, maxDuration * 20L);
    }

    private void forceEndByTimeout() {
        if (state != GameState.RUNNING) return;
        endGame(new java.util.ArrayList<>(alivePlayers));
    }

    // ---------------- OFF-MAP / VOID WATCHER ----------------

    private void startOffMapWatcher() {
        String regionName = arena.getRegionName();
        double voidY = plugin.getConfig().getDouble("game.void-y-level", -10);

        // Grace period: don't eliminate anyone for the first few seconds after
        // the game starts. This prevents instant eliminations while teleports settle.
        final int graceTicks = 60; // 3 seconds

        offMapTask = new BukkitRunnable() {
            int ticksElapsed = 0;

            @Override
            public void run() {
                if (state != GameState.RUNNING) {
                    cancel();
                    return;
                }
                ticksElapsed += 20;
                if (ticksElapsed <= graceTicks) {
                    return;
                }

                // Check alive players
                java.util.List<Player> snapshot = new java.util.ArrayList<>(alivePlayers);
                for (Player player : snapshot) {
                    if (!player.isOnline()) continue;
                    Location playerLoc = player.getLocation();

                    // Check void fall first (always eliminates)
                    if (playerLoc.getY() < voidY) {
                        putPlayerInSpectator(player);
                        continue;
                    }

                    // If a region is set for this arena, check if player is still in it
                    if (regionName != null && !regionName.isEmpty()) {
                        if (!com.pof.plugin.util.WorldGuardHelper.isPlayerInRegion(player, regionName)) {
                            putPlayerInSpectator(player);
                        }
                    }
                    // If no region is set, only void check applies (region boundary is optional)
                }

                // Check spectators - kick them if they leave region or disconnect
                java.util.List<Player> spectatorSnapshot = new java.util.ArrayList<>(spectators);
                for (Player spectator : spectatorSnapshot) {
                    // If spectator is offline (disconnected/force quit), remove them
                    if (!spectator.isOnline()) {
                        spectators.remove(spectator);
                        stateStore.markOutOfGame(spectator.getUniqueId());
                        continue;
                    }

                    // If a region is set, check if spectator is still in it
                    if (regionName != null && !regionName.isEmpty()) {
                        if (!com.pof.plugin.util.WorldGuardHelper.isPlayerInRegion(spectator, regionName)) {
                            // Spectator left the region - kick them back to lobby
                            spectators.remove(spectator);
                            stateStore.markOutOfGame(spectator.getUniqueId());
                            
                            Location lobby = arena.getLobby();
                            if (lobby != null) {
                                spectator.teleport(lobby);
                            }
                            spectator.setGameMode(GameMode.SURVIVAL);
                            
                            Map<String, String> ph = arenaPlaceholder();
                            ph.put("player", spectator.getName());
                            messages.send(spectator, "spectator-kicked-region", ph);
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 20L, 20L);
    }

    // (Remove the old effectiveRadius method - no longer needed)

    // ---------------- DAMAGE / FALL HANDLING ----------------

    public boolean shouldCancelFallDamage() {
        return plugin.getConfig().getBoolean("game.cancel-fall-damage", true);
    }

    public void handleDeath(Player player) {
        // If they're alive in the game, put them in spectator mode
        if (alivePlayers.contains(player)) {
            putPlayerInSpectator(player);
            return;
        }
        
        // If they're just waiting to join (waiting/countdown state), remove them from waiting
        if (waitingPlayers.contains(player)) {
            waitingPlayers.remove(player);
            playerPillars.remove(player);
            stateStore.markOutOfGame(player.getUniqueId());
            
            Map<String, String> ph = arenaPlaceholder();
            ph.put("player", player.getName());
            ph.put("current", String.valueOf(getCurrentCount()));
            ph.put("max", String.valueOf(plugin.getConfig().getInt("game.max-players", 16)));
            broadcastToWaiting("leave", ph);
            
            // If we drop below min-players during countdown, cancel it
            if (state == GameState.COUNTDOWN && waitingPlayers.size() < plugin.getConfig().getInt("game.min-players", 2)) {
                cancelCountdown();
            }
        }
    }

    /**
     * Put a dead player in spectator mode to watch the rest of the game.
     */
    private void putPlayerInSpectator(Player player) {
        alivePlayers.remove(player);
        playerPillars.remove(player);
        spectators.add(player);  // Track as spectator
        
        // Set to spectator mode so they can watch
        player.setGameMode(GameMode.SPECTATOR);
        
        // Tell them they're now spectating
        messages.send(player, "spectator-mode", arenaPlaceholder());
        
        // Restore their inventory so they don't lose items
        restorePlayer(player);
        
        // Broadcast that they died
        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAll("player-died-spectator", ph);
        
        // Check if there's a winner (only 1 alive left)
        checkForWinner();
    }

    // ---------------- ELIMINATION / WIN ----------------

    private void eliminatePlayer(Player player) {
        if (!alivePlayers.remove(player)) return;
        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        messages.send(player, "eliminated", arenaPlaceholder());
        teleportToLobby(player);
        restorePlayer(player);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAlive("player-eliminated-broadcast", ph);

        checkForWinner();
    }

    private void eliminatePlayerVoid(Player player) {
        if (!alivePlayers.remove(player)) return;
        playerPillars.remove(player);
        stateStore.markOutOfGame(player.getUniqueId());

        messages.send(player, "eliminated-void", arenaPlaceholder());
        teleportToLobby(player);
        restorePlayer(player);

        Map<String, String> ph = arenaPlaceholder();
        ph.put("player", player.getName());
        ph.put("remaining", String.valueOf(alivePlayers.size()));
        broadcastToAlive("player-eliminated-broadcast", ph);

        checkForWinner();
    }

    private void checkForWinner() {
        if (state != GameState.RUNNING) return;
        if (alivePlayers.size() <= 1) {
            endGame(new java.util.ArrayList<>(alivePlayers));
        }
    }

    private void endGame(java.util.List<Player> winners) {
        state = GameState.ENDING;

        if (!winners.isEmpty()) {
            Player winner = winners.get(0);
            Map<String, String> ph = arenaPlaceholder();
            ph.put("player", winner.getName());
            messages.send(winner, "win", ph);
            broadcastToAlive("win-broadcast", ph);
        }

        for (Player player : new java.util.ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            restorePlayer(player);
        }

        // Clean up spectators too
        for (Player spectator : new java.util.ArrayList<>(spectators)) {
            stateStore.markOutOfGame(spectator.getUniqueId());
            teleportToLobby(spectator);
            restorePlayer(spectator);
        }

        alivePlayers.clear();
        waitingPlayers.clear();
        spectators.clear();
        playerPillars.clear();
        lastItemGiven.clear();

        cleanupTasks();
        state = GameState.WAITING;
    }

    public void forceStop(CommandSender sender) {
        if (state == GameState.WAITING) {
            sender.sendMessage(messages.getPrefix() + messages.formatNoPrefix("no-game-running", arenaPlaceholder()));
            return;
        }
        for (Player player : new java.util.ArrayList<>(alivePlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
            teleportToLobby(player);
            restorePlayer(player);
        }
        for (Player spectator : new java.util.ArrayList<>(spectators)) {
            stateStore.markOutOfGame(spectator.getUniqueId());
            teleportToLobby(spectator);
            restorePlayer(spectator);
        }
        for (Player player : new java.util.ArrayList<>(waitingPlayers)) {
            stateStore.markOutOfGame(player.getUniqueId());
        }
        alivePlayers.clear();
        waitingPlayers.clear();
        spectators.clear();
        playerPillars.clear();
        lastItemGiven.clear();
        cleanupTasks();
        state = GameState.WAITING;
    }

    private void cleanupTasks() {
        if (countdownTask != null) { countdownTask.cancel(); countdownTask = null; }
        if (lootTask != null) { lootTask.cancel(); lootTask = null; }
        if (durationTask != null) { durationTask.cancel(); durationTask = null; }
        if (offMapTask != null) { offMapTask.cancel(); offMapTask = null; }
    }

    // ---------------- HELPERS ----------------

    /**
     * Restores a player's pre-game inventory, armor, offhand, XP, health and
     * food, then puts them in adventure mode for the lobby. Safe to call even
     * if no snapshot exists (e.g. they left before the game ever started).
     */
    private void restorePlayer(Player player) {
        InventorySnapshot snapshot = savedInventories.remove(player);
        if (snapshot != null) {
            snapshot.restore(player);
            player.setGameMode(snapshot.previousGameMode == GameMode.SURVIVAL || snapshot.previousGameMode == null
                    ? GameMode.ADVENTURE
                    : snapshot.previousGameMode);
        } else {
            player.setGameMode(GameMode.ADVENTURE);
        }
    }

    private void teleportToLobby(Player player) {
        Location lobby = arena.getLobby();
        if (lobby != null) {
            player.teleport(lobby);
        }
    }

    private void broadcastToWaiting(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player player : waitingPlayers) {
            player.sendMessage(msg);
        }
    }

    private void broadcastToAlive(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        for (Player player : alivePlayers) {
            player.sendMessage(msg);
        }
    }

    private void broadcastToAll(String key, Map<String, String> placeholders) {
        String msg = messages.format(key, placeholders);
        // Send to all waiting players
        for (Player player : waitingPlayers) {
            player.sendMessage(msg);
        }
        // Send to all alive players
        for (Player player : alivePlayers) {
            player.sendMessage(msg);
        }
    }

    public void checkPendingCrashRecovery(Player player) {
        if (stateStore.isMarkedInGame(player.getUniqueId())) {
            stateStore.markOutOfGame(player.getUniqueId());
            player.setGameMode(GameMode.SURVIVAL);
        }
    }
}
