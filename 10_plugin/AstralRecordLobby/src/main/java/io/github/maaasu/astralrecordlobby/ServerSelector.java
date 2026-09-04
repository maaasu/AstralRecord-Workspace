package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.FluidCollisionMode;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.RayTraceResult;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class ServerSelector {
    private final AstralRecordLobbyPlugin plugin;
    private final NamespacedKey npcKey;
    private final Map<Integer, String> serversBySlot = new HashMap<>();
    private Entity npc;

    ServerSelector(AstralRecordLobbyPlugin plugin) {
        this.plugin = plugin;
        this.npcKey = new NamespacedKey(plugin, "server_selector");
    }

    void spawnNpc() {
        if (!plugin.getConfig().getBoolean("selector.npc.enabled", true)) return;
        String worldName = plugin.getConfig().getString("selector.npc.world", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("Server selector NPC world is not loaded: " + worldName);
            return;
        }
        EntityType type;
        try {
            type = EntityType.valueOf(plugin.getConfig().getString("selector.npc.type", "VILLAGER").toUpperCase());
        } catch (IllegalArgumentException exception) {
            type = EntityType.VILLAGER;
        }
        if (!type.isSpawnable() || !type.isAlive()) type = EntityType.VILLAGER;
        var location = new org.bukkit.Location(
            world,
            plugin.getConfig().getDouble("selector.npc.x", 0.5),
            plugin.getConfig().getDouble("selector.npc.y", 64.0),
            plugin.getConfig().getDouble("selector.npc.z", 0.5),
            (float) plugin.getConfig().getDouble("selector.npc.yaw", 0.0),
            (float) plugin.getConfig().getDouble("selector.npc.pitch", 0.0));
        world.getNearbyEntities(location, 16.0, 16.0, 16.0, this::isSelector).forEach(Entity::remove);
        npc = world.spawnEntity(location, type);
        npc.getPersistentDataContainer().set(npcKey, PersistentDataType.BYTE, (byte) 1);
        npc.customName(Component.text(plugin.getConfig().getString("selector.npc.name", "サーバー選択")));
        npc.setCustomNameVisible(true);
        npc.setInvulnerable(true);
        if (npc instanceof LivingEntity living) {
            living.setAI(false);
            living.setSilent(true);
            living.setCollidable(false);
            living.setRemoveWhenFarAway(false);
        }
    }

    void removeNpc() {
        if (npc != null && npc.isValid()) npc.remove();
        npc = null;
    }

    boolean isSelector(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(npcKey, PersistentDataType.BYTE);
    }

    boolean openIfLookingAt(Player player) {
        double distance = Math.max(0.1, Math.min(6.0,
            plugin.getConfig().getDouble("selector.npc.raycastDistance", 6.0)));
        RayTraceResult hit = player.getWorld().rayTrace(
            player.getEyeLocation(), player.getEyeLocation().getDirection(), distance,
            FluidCollisionMode.NEVER, true, 0.3, this::isSelector);
        if (hit == null || hit.getHitEntity() == null) return false;
        open(player);
        return true;
    }

    void open(Player player) {
        int configuredSize = plugin.getConfig().getInt("selector.size", 27);
        int size = Math.max(9, Math.min(54, ((configuredSize + 8) / 9) * 9));
        SelectorHolder holder = new SelectorHolder();
        Inventory inventory = Bukkit.createInventory(
            holder, size, Component.text(plugin.getConfig().getString("selector.title", "サーバー選択")));
        holder.inventory = inventory;
        serversBySlot.clear();
        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("selector.entries");
        if (entries != null) {
            for (String key : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(key);
                if (entry == null) continue;
                int slot = entry.getInt("slot", -1);
                String server = entry.getString("server", key).trim();
                if (slot < 0 || slot >= size || server.isBlank()) continue;
                Material material = Material.matchMaterial(entry.getString("material", "COMPASS"));
                if (material == null || material.isAir()) material = Material.COMPASS;
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text(entry.getString("name", server)));
                List<String> lore = entry.getStringList("lore");
                if (!lore.isEmpty()) meta.lore(lore.stream().map(Component::text).toList());
                item.setItemMeta(meta);
                inventory.setItem(slot, item);
                serversBySlot.put(slot, server);
            }
        }
        player.openInventory(inventory);
    }

    boolean handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SelectorHolder)) return false;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return true;
        String server = serversBySlot.get(event.getRawSlot());
        if (server != null) {
            player.closeInventory();
            BackendProtocol.sendConnect(plugin, player, server);
        }
        return true;
    }

    private static final class SelectorHolder implements InventoryHolder {
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }
}
