package com.pof.plugin.util;

import com.sk89q.worldedit.util.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Utility for checking if a player is inside a WorldGuard region.
 * Safely handles the case where WorldGuard is not installed or is disabled.
 */
public class WorldGuardHelper {

    private static final Logger logger = org.bukkit.Bukkit.getLogger();
    private static boolean worldGuardAvailable = false;

    static {
        try {
            Class.forName("com.sk89q.worldguard.WorldGuard");
            worldGuardAvailable = true;
        } catch (ClassNotFoundException e) {
            worldGuardAvailable = false;
        }
    }

    /**
     * Check if a player is currently inside a specific WorldGuard region.
     * Returns true only if WorldGuard is available AND the player is in the region.
     * Returns false if WorldGuard is not available or the region doesn't exist.
     */
    public static boolean isPlayerInRegion(Player player, String regionName) {
        if (!worldGuardAvailable || regionName == null || regionName.isEmpty()) {
            return false;
        }

        try {
            Location loc = player.getLocation();
            World world = loc.getWorld();
            if (world == null) {
                return false;
            }

            RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
            RegionManager regionManager = container.get(world);
            if (regionManager == null) {
                return false;
            }

            // Get the region by name from the manager
            ProtectedRegion region = regionManager.getRegion(regionName);
            if (region == null) {
                // Region doesn't exist - can't be in it
                return false;
            }

            // Check if player's block position is inside this region
            BlockVector3 playerBlock = BlockVector3.at(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            return region.contains(playerBlock);

        } catch (Exception e) {
            logger.log(Level.WARNING, "Error checking WorldGuard region: " + regionName, e);
            return false;
        }
    }

    /**
     * Check if WorldGuard is loaded and available on this server.
     */
    public static boolean isWorldGuardAvailable() {
        return worldGuardAvailable;
    }
}
