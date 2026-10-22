package io.github.maaasu.astralRecord.feature.guide.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * オンラインプレイヤーへ定期的にガイド導線を案内します。
 */
public final class GuideReminderTask {
    /** 10 分間隔。 */
    public static final long DEFAULT_INTERVAL_TICKS = 10L * 60L * 20L;
    private static final String GUIDE_COMMAND = "/menu guide";

    private final PlayerMessageService playerMessageService;
    private final GuideService guideService;
    private final PlayerSettingService playerSettingService;
    private @Nullable BukkitTask task;

    /**
     * 定期ガイド案内を生成します。
     *
     * @param playerMessageService ガイド画面への導線通知
     * @param guideService 読込済み進行と次手順の案内
     * @param playerSettingService 定期通知のユーザー設定
     */
    public GuideReminderTask(
        @NotNull PlayerMessageService playerMessageService,
        @NotNull GuideService guideService,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.playerMessageService = playerMessageService;
        this.guideService = guideService;
        this.playerSettingService = playerSettingService;
    }

    /**
     * 定期案内タスクを開始します。
     *
     * @param plugin プラグインインスタンス
     * @param intervalTicks 案内間隔
     */
    public synchronized void start(@NotNull AstralRecord plugin, long intervalTicks) {
        if (task != null) {
            return;
        }
        task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            this::sendReminder,
            intervalTicks,
            intervalTicks
        );
    }

    /** 定期案内タスクを停止します。 */
    public synchronized void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /** 読込済みの未完了進行がある通知有効ユーザーへ、主スレッドで導線と次手順を送ります。 */
    void sendReminder() {
        for (var astPlayer : AstPlayerCache.getAll()) {
            if (!playerSettingService.isGuideReminderEnabled(astPlayer.getBukkit().getUniqueId())
                || !guideService.hasIncompleteGuide(astPlayer.getAccount().getUuid())) {
                continue;
            }
            playerMessageService.sendClickable(
                astPlayer.getBukkit(),
                PlayerMsgId.P_5600,
                GUIDE_COMMAND
            );
            guideService.sendNextStepHint(astPlayer);
        }
    }
}
