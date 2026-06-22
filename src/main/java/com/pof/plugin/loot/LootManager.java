package com.pof.plugin.loot;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * Loads loot configuration from config.yml and rolls random items
 * based on weighted tiers (junk, common, useful, rare).
 */
public class LootManager {

    private static class LootItem {
        Material material;
        int minAmount;
        int maxAmount;

        LootItem(Material material, int minAmount, int maxAmount) {
            this.material = material;
            this.minAmount = minAmount;
            this.maxAmount = maxAmount;
        }
    }

    private static class LootTier {
        int weight;
        List<LootItem> items = new ArrayList<>();
    }

    private final Random random = new Random();
    private final Map<String, LootTier> tiers = new HashMap<>();
    private int totalWeight = 0;

    public LootManager(FileConfiguration config) {
        loadFromConfig(config);
    }

    /**
     * Load loot configuration from config.yml
     */
    private void loadFromConfig(FileConfiguration config) {
        ConfigurationSection lootSection = config.getConfigurationSection("loot");
        if (lootSection == null) {
            Bukkit.getLogger().warning("[PoF] No loot section in config.yml, using defaults");
            loadDefaults();
            return;
        }

        // Load each tier (junk, common, useful, rare)
        for (String tierName : lootSection.getKeys(false)) {
            ConfigurationSection tierSection = lootSection.getConfigurationSection(tierName);
            if (tierSection == null) continue;

            LootTier tier = new LootTier();
            tier.weight = tierSection.getInt("weight", 0);
            totalWeight += tier.weight;

            // Load items in this tier
            List<Map<?, ?>> itemList = tierSection.getMapList("items");
            for (Map<?, ?> itemMap : itemList) {
                try {
                    String materialName = (String) itemMap.get("material");
                    int minAmount = ((Number) itemMap.get("min-amount")).intValue();
                    int maxAmount = ((Number) itemMap.get("max-amount")).intValue();

                    Material material = Material.matchMaterial(materialName);
                    if (material == null || !material.isItem()) {
                        Bukkit.getLogger().warning("[PoF] Invalid material in loot config: " + materialName);
                        continue;
                    }

                    tier.items.add(new LootItem(material, minAmount, maxAmount));
                } catch (Exception e) {
                    Bukkit.getLogger().warning("[PoF] Error parsing loot item: " + e.getMessage());
                }
            }

            if (!tier.items.isEmpty()) {
                tiers.put(tierName, tier);
            }
        }

        if (tiers.isEmpty()) {
            Bukkit.getLogger().warning("[PoF] No valid loot tiers loaded, using defaults");
            loadDefaults();
        }
    }

    /**
     * Fallback default loot if config is missing
     */
    private void loadDefaults() {
        tiers.clear();
        totalWeight = 100;

        LootTier junk = new LootTier();
        junk.weight = 50;
        junk.items.add(new LootItem(Material.DIRT, 1, 1));
        junk.items.add(new LootItem(Material.STONE, 1, 1));
        junk.items.add(new LootItem(Material.STICK, 1, 1));
        tiers.put("junk", junk);

        LootTier common = new LootTier();
        common.weight = 30;
        common.items.add(new LootItem(Material.OAK_PLANKS, 1, 1));
        common.items.add(new LootItem(Material.COBBLESTONE, 1, 1));
        tiers.put("common", common);

        LootTier useful = new LootTier();
        useful.weight = 15;
        useful.items.add(new LootItem(Material.IRON_SWORD, 1, 1));
        useful.items.add(new LootItem(Material.BOW, 1, 1));
        tiers.put("useful", useful);

        LootTier rare = new LootTier();
        rare.weight = 5;
        rare.items.add(new LootItem(Material.DIAMOND_SWORD, 1, 1));
        rare.items.add(new LootItem(Material.GOLDEN_APPLE, 1, 1));
        tiers.put("rare", rare);
    }

    /**
     * Roll a random loot item based on tier weights
     */
    public ItemStack rollItem() {
        if (tiers.isEmpty()) {
            return new ItemStack(Material.DIRT, 1);
        }

        int roll = random.nextInt(totalWeight);
        int accumulated = 0;

        for (LootTier tier : tiers.values()) {
            accumulated += tier.weight;
            if (roll < accumulated) {
                if (tier.items.isEmpty()) {
                    return new ItemStack(Material.DIRT, 1);
                }
                LootItem item = tier.items.get(random.nextInt(tier.items.size()));
                int amount = item.minAmount;
                if (item.maxAmount > item.minAmount) {
                    amount = item.minAmount + random.nextInt(item.maxAmount - item.minAmount + 1);
                }
                return new ItemStack(item.material, amount);
            }
        }

        // Fallback
        return new ItemStack(Material.DIRT, 1);
    }
}
