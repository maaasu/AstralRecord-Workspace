package io.github.maaasu.astralrecordlobby;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

final class ServerSelector {
    private final AstralRecordLobbyPlugin plugin;
    private final NamespacedKey npcKey;
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

    /**
     * サーバー選択GUIを即時表示し、APIから取得した人数情報で非同期更新する。
     *
     * @param player GUIを開くプレイヤー
     */
    void open(Player player) {
        int configuredSize = plugin.getConfig().getInt("selector.size", 27);
        int size = Math.max(9, Math.min(54, ((configuredSize + 8) / 9) * 9));
        SelectorHolder holder = new SelectorHolder();
        Inventory inventory = Bukkit.createInventory(
            holder, size, Component.text(plugin.getConfig().getString("selector.title", "サーバー選択")));
        holder.inventory = inventory;
        populateEntries(holder, player, Map.of(), StatusLoadState.LOADING);
        player.openInventory(inventory);

        UUID playerId = player.getUniqueId();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, LobbyApiClient.ServerPresence> presences;
            StatusLoadState loadState;
            try {
                presences = plugin.api().getServers();
                loadState = StatusLoadState.AVAILABLE;
            } catch (RuntimeException exception) {
                plugin.getLogger().warning("Failed to load server selector status: " + exception.getMessage());
                presences = Map.of();
                loadState = StatusLoadState.FAILED;
            }
            Map<String, LobbyApiClient.ServerPresence> result = presences;
            StatusLoadState resultState = loadState;
            Bukkit.getScheduler().runTask(plugin, () -> {
                Player current = Bukkit.getPlayer(playerId);
                if (current == null || !current.isOnline()
                    || current.getOpenInventory().getTopInventory().getHolder() != holder) return;
                populateEntries(holder, current, result, resultState);
            });
        });
    }

    /**
     * 設定済みサーバーアイコンへ閲覧者別の人数・接続可否を反映する。
     *
     * @param holder 更新対象GUI holder
     * @param player 閲覧プレイヤー
     * @param presences APIから取得したサーバー状態
     * @param loadState API取得状態
     */
    private void populateEntries(
        SelectorHolder holder,
        Player player,
        Map<String, LobbyApiClient.ServerPresence> presences,
        StatusLoadState loadState
    ) {
        holder.serversBySlot.clear();
        ConfigurationSection entries = plugin.getConfig().getConfigurationSection("selector.entries");
        if (entries != null) {
            for (String key : entries.getKeys(false)) {
                ConfigurationSection entry = entries.getConfigurationSection(key);
                if (entry == null) continue;
                int slot = entry.getInt("slot", -1);
                String server = entry.getString("server", key).trim();
                if (slot < 0 || slot >= holder.inventory.getSize() || server.isBlank()) continue;
                Material material = Material.matchMaterial(entry.getString("material", "COMPASS"));
                if (material == null || material.isAir()) material = Material.COMPASS;
                ItemStack item = new ItemStack(material);
                ItemMeta meta = item.getItemMeta();
                meta.displayName(Component.text(entry.getString("name", server)));
                List<Component> lore = new ArrayList<>(
                    entry.getStringList("lore").stream().map(Component::text).toList());
                if (!lore.isEmpty()) lore.add(Component.empty());
                LobbyApiClient.ServerPresence presence = presences.get(server.toLowerCase(Locale.ROOT));
                boolean connectable = appendServerStatus(
                    lore, presence, plugin.permissionOf(player.getUniqueId()), loadState);
                meta.lore(lore);
                item.setItemMeta(meta);
                holder.inventory.setItem(slot, item);
                if (connectable) holder.serversBySlot.put(slot, server);
            }
        }
    }

    /**
     * サーバー状態をアイコンloreへ追加する。
     *
     * @param lore 更新対象lore
     * @param presence APIサーバー状態
     * @param permission 閲覧者のAPI権限
     * @param loadState API取得状態
     * @return 現在接続操作を許可する場合true
     */
    private boolean appendServerStatus(
        List<Component> lore,
        LobbyApiClient.ServerPresence presence,
        int permission,
        StatusLoadState loadState
    ) {
        if (loadState == StatusLoadState.LOADING) {
            lore.add(Component.text("人数情報を取得しています...", NamedTextColor.GRAY));
            return false;
        }
        if (loadState == StatusLoadState.FAILED) {
            lore.add(Component.text("人数情報を利用できません", NamedTextColor.RED));
            return false;
        }
        if (presence == null || !presence.online()) {
            lore.add(Component.text("現在は接続できません", NamedTextColor.RED));
            return false;
        }

        int online = Math.max(0, presence.onlineCount());
        int baseCapacity = Math.max(0, presence.capacity());
        int extra = presence.extraFor(permission);
        int limit = presence.limitFor(permission);
        lore.add(Component.text("現在人数: " + online, NamedTextColor.WHITE));
        if (extra > 0) {
            lore.add(Component.text(
                "最大人数: " + limit + "（基本 " + baseCapacity + " +" + extra + "）",
                NamedTextColor.AQUA));
        } else {
            lore.add(Component.text("最大人数: " + limit, NamedTextColor.WHITE));
        }
        if (presence.fullFor(permission)) {
            lore.add(Component.text("満員のため接続できません", NamedTextColor.RED));
            return false;
        }
        lore.add(Component.text("クリックして接続", NamedTextColor.GREEN));
        return true;
    }

    /**
     * サーバー選択GUIのクリックを処理する。
     *
     * @param event インベントリクリックイベント
     * @return サーバー選択GUI内の操作だった場合true
     */
    boolean handleClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SelectorHolder holder)) return false;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) return true;
        String server = holder.serversBySlot.get(event.getRawSlot());
        if (server != null) {
            player.closeInventory();
            BackendProtocol.sendConnect(plugin, player, server, plugin.permissionOf(player.getUniqueId()));
        }
        return true;
    }

    private static final class SelectorHolder implements InventoryHolder {
        private final Map<Integer, String> serversBySlot = new HashMap<>();
        private Inventory inventory;
        @Override public Inventory getInventory() { return inventory; }
    }

    private enum StatusLoadState {
        LOADING,
        AVAILABLE,
        FAILED
    }
}
