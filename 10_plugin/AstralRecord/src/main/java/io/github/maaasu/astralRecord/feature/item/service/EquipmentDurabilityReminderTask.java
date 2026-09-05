package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 装備中の防具・アクセサリの耐久低下を定期的に通知するタスク。
 */
public final class EquipmentDurabilityReminderTask {
    /** 3 分間隔。 */
    public static final long DEFAULT_INTERVAL_TICKS = 3L * 60L * 20L;

    private final EquipmentDurabilityService equipmentDurabilityService;
    private final PlayerMessageService playerMessageService;
    private @Nullable BukkitTask task;

    /**
     * 装備耐久通知タスクを構築します。
     *
     * @param equipmentDurabilityService 装備中アイテムの耐久判定サービス
     * @param playerMessageService プレイヤー向けメッセージサービス
     */
    public EquipmentDurabilityReminderTask(
        @NotNull EquipmentDurabilityService equipmentDurabilityService,
        @NotNull PlayerMessageService playerMessageService
    ) {
        this.equipmentDurabilityService = equipmentDurabilityService;
        this.playerMessageService = playerMessageService;
    }

    /**
     * BukkitScheduler に同期 timer を登録して起動します。既に起動済みなら何もしません。
     *
     * @param plugin プラグインインスタンス
     * @param intervalTicks 実行間隔（tick）
     */
    public synchronized void start(@NotNull AstralRecord plugin, long intervalTicks) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::notifyDamagedEquipment,
            intervalTicks,
            intervalTicks
        );
    }

    /**
     * 装備耐久通知タスクを停止します。
     */
    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    void notifyDamagedEquipment() {
        for (AstPlayer astPlayer : AstPlayerCache.getAll()) {
            List<String> damagedNames = equipmentDurabilityService
                .getDamagedArmorAndAccessoryDisplayNames(astPlayer);
            if (damagedNames.isEmpty()) {
                continue;
            }
            playerMessageService.send(
                astPlayer,
                PlayerMsgId.P_5283,
                String.join("、", damagedNames)
            );
        }
    }
}
