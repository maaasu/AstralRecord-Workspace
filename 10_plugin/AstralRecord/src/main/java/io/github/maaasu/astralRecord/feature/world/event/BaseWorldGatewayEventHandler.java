package io.github.maaasu.astralRecord.feature.world.event;

import io.github.maaasu.astralRecord.core.event.AbstractEventHandler;
import io.papermc.paper.event.entity.EntityInsideBlockEvent;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.feature.player.event.PlayerRuntimeDiscardEvent;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 拠点のネザーポータル接触を抑止し、GUI を開かず初期スポーンへ退避させます。
 */
public final class BaseWorldGatewayEventHandler extends AbstractEventHandler {
    private final Plugin plugin;
    private static final long RETRY_DELAY_TICKS = 20L;
    private final WorldService worldService;
    private final Map<UUID, UUID> pendingEvacuations = new HashMap<>();

    /**
     * ゲート接触時の退避処理を初期化します。
     *
     * @param plugin メインスレッド上の退避タスクを登録するプラグイン
     * @param worldService 拠点判定とマスタースポーンへの転送サービス
     */
    public BaseWorldGatewayEventHandler(
            @NotNull Plugin plugin,
            @NotNull WorldService worldService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * ポータルの待機処理が始まる前に接触効果を取り消し、次 tick の退避を予約します。
     * 移動イベントやプレイヤー全体の定期走査は使用しません。
     *
     * @param event ブロック接触イベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityInsideBlock(@NotNull EntityInsideBlockEvent event) {
        if (event.getBlock().getType() != Material.NETHER_PORTAL
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        runSafely(() -> {
            if (!isBaseWorld(player.getWorld())) {
                return;
            }
            event.setCancelled(true);
            requestEvacuation(player);
        }, LogId.E_5754, player.getName(), "portal-contact");
    }

    /**
     * 既にポータル転送段階へ進んだ場合も、拠点からのバニラ転送を取り消します。
     *
     * @param event プレイヤーポータルイベント
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerPortal(@NotNull PlayerPortalEvent event) {
        runSafely(() -> {
            if (event.getCause() != PlayerTeleportEvent.TeleportCause.NETHER_PORTAL
                    || !isBaseWorld(event.getFrom().getWorld())) {
                return;
            }
            event.setCancelled(true);
            requestEvacuation(event.getPlayer());
        }, LogId.E_5754, event.getPlayer().getName(), "portal");
    }

    /**
     * 切断したプレイヤーの退避要求を無効化します。
     *
     * @param event プレイヤー切断イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
        runSafely(() -> {
            clearPlayerRuntime(event.getPlayer());
        }, LogId.E_5754, event.getPlayer().getName(), "quit");
    }

    /**
     * ランタイム破棄前の退避要求が後から実行されることを防ぎます。
     *
     * @param event プレイヤーランタイム破棄イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerRuntimeDiscard(@NotNull PlayerRuntimeDiscardEvent event) {
        clearPlayerRuntime(event.getPlayer());
    }

    /**
     * 対象プレイヤーの予約済み退避要求を無効化します。
     *
     * @param player 破棄対象プレイヤー
     */
    private void clearPlayerRuntime(@NotNull Player player) {
        pendingEvacuations.remove(player.getUniqueId());
    }

    /** 停止時に予約済み要求をすべて無効化します。 */
    @Override
    public void cleanup() {
        pendingEvacuations.clear();
        super.cleanup();
    }

    /**
     * ロード済みマスターから拠点ワールドか判定します。
     *
     * @param world 判定対象ワールド。null の場合は false
     * @return BASE 定義に対応する場合は true
     */
    private boolean isBaseWorld(World world) {
        if (world == null) {
            return false;
        }
        WorldMasterData data = worldService.findByBukkitWorld(world);
        return data != null && data.worldType() == WorldType.BASE;
    }

    /**
     * 衝突処理から抜けた次 tick に退避します。転送中と失敗後20 tick以内の連続接触を抑止します。
     *
     * @param player メインスレッド上の退避対象
     */
    private void requestEvacuation(@NotNull Player player) {
        UUID playerId = player.getUniqueId();
        UUID requestId = UUID.randomUUID();
        if (pendingEvacuations.putIfAbsent(playerId, requestId) != null) {
            return;
        }

        World sourceWorld = player.getWorld();
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> pendingEvacuations.remove(playerId, requestId), RETRY_DELAY_TICKS);
        Bukkit.getScheduler().runTask(plugin, () -> runSafely(
                () -> evacuate(player, sourceWorld, requestId),
                LogId.E_5754, player.getName(), "portal-evacuate"));
    }

    /**
     * 有効な同一セッションの要求だけを初期スポーンへ転送し、成功時にスニーク操作を案内します。
     * 失敗時にも GUI は開かず、再試行抑止期間の経過後に次の接触で再試行できます。
     *
     * @param player 接触したプレイヤー
     * @param sourceWorld 接触時のワールド
     * @param requestId 切断・ランタイム破棄前の要求と区別する識別子
     */
    private void evacuate(@NotNull Player player, @NotNull World sourceWorld, @NotNull UUID requestId) {
        if (!requestId.equals(pendingEvacuations.get(player.getUniqueId()))
                || !player.isOnline() || player.isDead() || !player.getWorld().equals(sourceWorld)) {
            return;
        }
        WorldMasterData data = worldService.findByBukkitWorld(sourceWorld);
        if (data == null || data.worldType() != WorldType.BASE) {
            return;
        }
        boolean success = worldService.teleportToSpawnInWorld(player, data, sourceWorld);
        if (success) {
            pendingEvacuations.remove(player.getUniqueId(), requestId);
        }
        PlayerMessageService.getInstance().send(player, success ? PlayerMsgId.P_5778 : PlayerMsgId.P_5779);
    }
}
