package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketListener;
import com.comphenix.protocol.wrappers.EnumWrappers;
import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * アクションリング長押し選択の解除入力を管理します。
 *
 * <p>サーバー側の AstralRecord アイテムは常に Paper のまま維持します。設定有効時だけ
 * {@link io.github.maaasu.astralRecord.feature.item.view.ItemStackPacketAdapter} が選択中武器を
 * クライアント専用のトライデントとして送信し、このサービスはその解除パケットだけを処理します。</p>
 */
public final class SkillActionRingHoldService extends AbstractEventHandler {
    private final AstralRecord plugin;
    private final SkillActionRingService actionRingService;
    private final PlayerSettingService playerSettingService;
    private final Map<UUID, HoldSession> sessions = new ConcurrentHashMap<>();
    private PacketListener releaseListener;

    /**
     * 長押し選択サービスを生成します。
     *
     * @param plugin Bukkit scheduler と ProtocolLib listener に使用するプラグイン
     * @param actionRingService 選択表示と確定を管理するアクションリングサービス
     * @param playerSettingService 長押し選択設定を cache-only で参照するサービス
     */
    public SkillActionRingHoldService(
        @NotNull AstralRecord plugin,
        @NotNull SkillActionRingService actionRingService,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.actionRingService = actionRingService;
        this.playerSettingService = playerSettingService;
    }

    @Override
    public void initialize() {
        super.initialize();
        registerReleaseListener();
    }

    @Override
    public void cleanup() {
        stop();
        super.cleanup();
    }

    /**
     * 指定プレイヤーのアクションリングを長押し選択として開始します。
     *
     * <p>主手 slot と AstralRecord 武器の識別子を固定し、解除時に同じ武器を持つ場合だけ
     * 選択を確定します。サーバーの ItemStack を置換しないため、Paper 管理と保存状態には
     * 一切影響しません。</p>
     *
     * @param astPlayer 対象プレイヤー
     * @return 長押し待機を開始できた場合は {@code true}
     */
    public boolean begin(@NotNull AstPlayer astPlayer) {
        Player player = astPlayer.getBukkit();
        UUID playerId = player.getUniqueId();
        if (!player.isOnline()
            || !playerSettingService.isActionRingHoldSelectEnabled(playerId)
            || sessions.containsKey(playerId)) {
            return false;
        }

        int heldSlot = player.getInventory().getHeldItemSlot();
        ItemStack heldItem = player.getInventory().getItem(heldSlot);
        if (heldItem == null || !ItemStackFactory.isWeapon(heldItem)) {
            return false;
        }
        String itemId = ItemStackFactory.getAstralItemId(heldItem);
        if (itemId == null || itemId.isBlank()) {
            return false;
        }
        if (!actionRingService.open(astPlayer, PlayerMsgId.P_5871)) {
            return false;
        }

        HoldSession session = new HoldSession(
            UUID.randomUUID().toString(),
            heldSlot,
            itemId,
            ItemStackFactory.getEquipmentInstanceId(heldItem)
        );
        if (sessions.putIfAbsent(playerId, session) != null) {
            actionRingService.close(player);
            return false;
        }
        return true;
    }

    /**
     * 指定プレイヤーが解除入力待ちの長押し選択中かを返します。
     *
     * @param player 対象プレイヤー
     * @return 右クリック解除を待っている場合は {@code true}
     */
    public boolean isHolding(@NotNull Player player) {
        return sessions.containsKey(player.getUniqueId());
    }

    /**
     * 指定プレイヤーの解除待機を破棄します。
     *
     * @param player 対象プレイヤー
     */
    public void cancel(@NotNull Player player) {
        sessions.remove(player.getUniqueId());
    }

    /**
     * ProtocolLib listener と全解除待機を停止します。
     */
    public void stop() {
        unregisterReleaseListener();
        sessions.clear();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
        if (!isHolding(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        actionRingService.close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerSwapHandItems(@NotNull PlayerSwapHandItemsEvent event) {
        if (!isHolding(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        actionRingService.close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerDropItem(@NotNull PlayerDropItemEvent event) {
        if (!isHolding(event.getPlayer())) {
            return;
        }
        event.setCancelled(true);
        actionRingService.close(event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryOpen(@NotNull InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && isHolding(player)) {
            actionRingService.close(player);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerChangedWorld(@NotNull PlayerChangedWorldEvent event) {
        if (isHolding(event.getPlayer())) {
            actionRingService.close(event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerDeath(@NotNull PlayerDeathEvent event) {
        if (isHolding(event.getEntity())) {
            actionRingService.close(event.getEntity());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        actionRingService.close(event.getPlayer());
    }

    private void registerReleaseListener() {
        if (releaseListener != null) {
            return;
        }
        ProtocolManager protocolManager = ProtocolLibrary.getProtocolManager();
        releaseListener = new PacketAdapter(
            plugin,
            ListenerPriority.HIGHEST,
            PacketType.Play.Client.BLOCK_DIG
        ) {
            @Override
            public void onPacketReceiving(@NotNull PacketEvent event) {
                if (event.isCancelled()
                    || event.getPacket().getPlayerDigTypes().readSafely(0)
                        != EnumWrappers.PlayerDigType.RELEASE_USE_ITEM) {
                    return;
                }
                UUID playerId = event.getPlayer().getUniqueId();
                if (!playerSettingService.isActionRingHoldSelectEnabled(playerId)) {
                    return;
                }
                HoldSession session = sessions.get(playerId);
                if (session == null) {
                    return;
                }

                // クライアント専用トライデントの解除を通常の Paper 使用処理へ渡さず、選択確定に振り替えます。
                event.setCancelled(true);
                if (session.markReleaseRequested()) {
                    plugin.getServer().getScheduler().runTask(plugin, () -> completeRelease(playerId, session.token()));
                }
            }
        };
        protocolManager.addPacketListener(releaseListener);
    }

    private void unregisterReleaseListener() {
        if (releaseListener == null) {
            return;
        }
        try {
            ProtocolLibrary.getProtocolManager().removePacketListener(releaseListener);
        } catch (RuntimeException ignored) {
            // ProtocolLib 停止順序で manager が既に破棄済みでも、Bukkit 停止処理は継続します。
        } finally {
            releaseListener = null;
        }
    }

    private void completeRelease(@NotNull UUID playerId, @NotNull String token) {
        HoldSession session = sessions.get(playerId);
        if (session == null || !session.token().equals(token) || !sessions.remove(playerId, session)) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (player == null
            || !player.isOnline()
            || !playerSettingService.isActionRingHoldSelectEnabled(playerId)
            || !session.matches(player)) {
            if (player != null) {
                actionRingService.close(player);
            }
            return;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || !actionRingService.isOpen(player)) {
            actionRingService.close(player);
            return;
        }
        if (!actionRingService.confirmSelected(astPlayer)) {
            actionRingService.close(player);
        }
    }

    private static final class HoldSession {
        private final String token;
        private final int heldSlot;
        private final String itemId;
        private final String equipmentInstanceId;
        private boolean releaseRequested;

        private HoldSession(
            @NotNull String token,
            int heldSlot,
            @NotNull String itemId,
            @Nullable String equipmentInstanceId
        ) {
            this.token = token;
            this.heldSlot = heldSlot;
            this.itemId = itemId;
            this.equipmentInstanceId = equipmentInstanceId;
        }

        private @NotNull String token() {
            return token;
        }

        private synchronized boolean markReleaseRequested() {
            if (releaseRequested) {
                return false;
            }
            releaseRequested = true;
            return true;
        }

        private boolean matches(@NotNull Player player) {
            return matchesHeldWeapon(player, heldSlot, itemId, equipmentInstanceId);
        }
    }

    /**
     * 解除時の主手が開始時と同じ AstralRecord 武器かを確認します。
     *
     * <p>クライアントへは仮想トライデントを送っていても、ここで検査するサーバー側 ItemStack は
     * 常に Paper のままです。</p>
     *
     * @param player 検査対象プレイヤー
     * @param heldSlot 開始時の hotbar slot
     * @param itemId 開始時の AstralRecord item ID
     * @param equipmentInstanceId 開始時の装備 instance ID
     * @return 同一主武器なら {@code true}
     */
    static boolean matchesHeldWeapon(
        @NotNull Player player,
        int heldSlot,
        @NotNull String itemId,
        @Nullable String equipmentInstanceId
    ) {
        if (player.getInventory().getHeldItemSlot() != heldSlot) {
            return false;
        }
        ItemStack heldItem = player.getInventory().getItem(heldSlot);
        return heldItem != null
            && ItemStackFactory.isWeapon(heldItem)
            && itemId.equals(ItemStackFactory.getAstralItemId(heldItem))
            && Objects.equals(equipmentInstanceId, ItemStackFactory.getEquipmentInstanceId(heldItem));
    }
}
