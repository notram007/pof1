package com.pof.plugin.listener;

import com.pof.plugin.PillarsOfFortune;
import com.pof.plugin.game.GameManager;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

public class GameListener implements Listener {

    private final PillarsOfFortune plugin;

    public GameListener(PillarsOfFortune plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player player = (Player) event.getEntity();

        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null) return;

        // Allow all damage, including fall damage
        // Players can take damage and die normally during the game
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm == null) return;

        // Handle death in any game state (waiting, countdown, or running)
        event.getDrops().clear();
        event.setDeathMessage(null);
        event.setDroppedExp(0);

        gm.handleDeath(player);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            GameManager gm = plugin.getArenaService().findGameForPlayer(player);
            // Player already eliminated by the time they respawn; nothing further needed here,
            // but kept as an extension point (e.g. forcing them back to a lobby location).
        });
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        if (gm != null) {
            gm.handleQuit(player);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        for (GameManager gm : plugin.getArenaService().getAllGameManagers()) {
            gm.checkPendingCrashRecovery(player);
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        GameManager gm = plugin.getArenaService().findGameForPlayer(player);
        
        // If player is in waiting state (countdown or waiting for players to join),
        // prevent them from moving. Teleport them back to their pillar if they try.
        if (gm != null && (gm.getState() == com.pof.plugin.game.GameState.WAITING || 
                            gm.getState() == com.pof.plugin.game.GameState.COUNTDOWN)) {
            // Check if they moved more than just a look direction change
            org.bukkit.Location from = event.getFrom();
            org.bukkit.Location to = event.getTo();
            
            if (from.getBlockX() != to.getBlockX() || 
                from.getBlockY() != to.getBlockY() || 
                from.getBlockZ() != to.getBlockZ()) {
                // They moved to a different block - teleport them back to their pillar
                event.setCancelled(true);
                // The teleport will happen on next tick to avoid conflicts
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.teleport(from);
                });
            }
        }
    }
}
