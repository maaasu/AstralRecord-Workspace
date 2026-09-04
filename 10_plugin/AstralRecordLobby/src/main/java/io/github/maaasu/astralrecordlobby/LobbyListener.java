package io.github.maaasu.astralrecordlobby;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.GameRule;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.world.WorldLoadEvent;

final class LobbyListener implements Listener {
    private final AstralRecordLobbyPlugin plugin;
    private final ServerSelector selector;

    LobbyListener(AstralRecordLobbyPlugin plugin, ServerSelector selector) {
        this.plugin = plugin;
        this.selector = selector;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            LobbyApiClient.Admission admission = plugin.api().getAdmission(event.getUniqueId());
            if (!admission.admitted()) {
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "このサーバーへの参加は禁止されています。");
                return;
            }
            plugin.cachePermission(event.getUniqueId(), admission.permission());
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Admission check failed for " + event.getName() + ": " + exception.getMessage());
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                "認証サーバーに接続できません。しばらくしてから再度お試しください。");
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        plugin.applyLobbyPermission(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        plugin.clearPermission(event.getPlayer().getUniqueId());
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onChat(AsyncChatEvent event) {
        event.setCancelled(true);
        String message = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        if (message.isEmpty()) return;
        plugin.getServer().getScheduler().runTask(plugin,
            () -> BackendProtocol.sendChat(plugin, event.getPlayer(), plugin.channelName(), message));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (selector.handleClick(event)) return;
        if (event.getWhoClicked() instanceof Player player && !plugin.isAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player && !plugin.isAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!plugin.isAdmin(event.getPlayer())) event.setCancelled(true);
        selector.openIfLookingAt(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (selector.isSelector(event.getRightClicked())) selector.open(event.getPlayer());
        if (!plugin.isAdmin(event.getPlayer())) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onAnimation(PlayerAnimationEvent event) {
        selector.openIfLookingAt(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBreak(BlockBreakEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlace(BlockPlaceEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(PlayerPickupItemEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) { cancelUnlessAdmin(event.getPlayer(), event); }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onIgnite(BlockIgniteEvent event) {
        Player player = event.getPlayer();
        if (player == null || !plugin.isAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        Player attacker = event.getDamager() instanceof Player player ? player
            : event.getDamager() instanceof Projectile projectile && projectile.getShooter() instanceof Player player
                ? player : null;
        if (attacker != null) {
            if (!plugin.isAdmin(attacker)) event.setCancelled(true);
            return;
        }
        if (event.getEntity() instanceof Player player && !plugin.isAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !plugin.isAdmin(player)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFood(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player && !plugin.isAdmin(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExplosion(EntityExplodeEvent event) {
        Player source = event.getEntity() instanceof TNTPrimed tnt && tnt.getSource() instanceof Player player
            ? player : null;
        if (source == null || !plugin.isAdmin(source)) event.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplosion(BlockExplodeEvent event) {
        event.setCancelled(true);
    }

    @EventHandler
    public void onWorldLoad(WorldLoadEvent event) {
        event.getWorld().setGameRule(GameRule.DO_IMMEDIATE_RESPAWN, true);
    }

    private void cancelUnlessAdmin(Player player, org.bukkit.event.Cancellable event) {
        if (!plugin.isAdmin(player)) event.setCancelled(true);
    }
}
