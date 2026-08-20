package io.github.maaasu.astralRecord.feature.hud.service;

import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 管理者メッセージを全オンラインプレイヤーへ共有する BossBar を管理します。
 * <p>
 * 同時に表示できる管理者メッセージは1件で、新しい表示要求は既存の表示を置き換えます。
 */
public final class AdminMessageBossBarService {
    private static final long TICKS_PER_SECOND = 20L;
    private static final long UPDATE_INTERVAL_TICKS = 1L;

    private final Plugin plugin;
    private @Nullable BossBar bossBar;
    private @Nullable BukkitTask updateTask;
    private long durationTicks;
    private long remainingTicks;

    /**
     * 管理者メッセージ BossBar サービスを初期化します。
     *
     * @param plugin task を登録するプラグイン
     */
    public AdminMessageBossBarService(@NotNull Plugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 指定したメッセージを全プレイヤーへ表示します。
     *
     * @param message 表示するメッセージ。{@code &} 形式の legacy color code を利用できます
     * @param durationSeconds 表示時間（秒）。1秒以上で指定します
     * @throws IllegalArgumentException 表示時間が0以下、またはtick数へ変換できない場合
     */
    public void show(@NotNull String message, long durationSeconds) {
        if (durationSeconds <= 0L || durationSeconds > Long.MAX_VALUE / TICKS_PER_SECOND) {
            throw new IllegalArgumentException("表示時間は1秒以上で指定してください");
        }

        clear();

        durationTicks = durationSeconds * TICKS_PER_SECOND;
        remainingTicks = durationTicks;
        BossBar nextBossBar = Bukkit.createBossBar(
                ColorCodeUtil.translateAlternateColorCodes(message),
                BarColor.YELLOW,
                BarStyle.SOLID
        );
        nextBossBar.setProgress(1.0D);
        nextBossBar.setVisible(true);
        for (Player player : Bukkit.getOnlinePlayers()) {
            nextBossBar.addPlayer(player);
        }

        bossBar = nextBossBar;
        updateTask = plugin.getServer().getScheduler().runTaskTimer(
                plugin,
                this::update,
                UPDATE_INTERVAL_TICKS,
                UPDATE_INTERVAL_TICKS
        );
    }

    /**
     * 表示中の BossBar にオンラインプレイヤーを追加します。
     * 表示が終了している場合は何もしません。
     *
     * @param player 追加対象プレイヤー
     */
    public void addPlayer(@NotNull Player player) {
        BossBar activeBossBar = bossBar;
        if (activeBossBar != null) {
            activeBossBar.addPlayer(player);
        }
    }

    /**
     * 表示中の BossBar からプレイヤーを削除します。
     * 表示が終了している場合は何もしません。
     *
     * @param player 削除対象プレイヤー
     */
    public void removePlayer(@NotNull Player player) {
        BossBar activeBossBar = bossBar;
        if (activeBossBar != null) {
            activeBossBar.removePlayer(player);
        }
    }

    /**
     * 表示中の管理者メッセージを停止し、BossBar と task を破棄します。
     */
    public void stop() {
        clear();
    }

    /**
     * 管理者メッセージが表示中かを返します。
     *
     * @return 表示中の場合は {@code true}
     */
    public boolean isActive() {
        return bossBar != null;
    }

    private void update() {
        BossBar activeBossBar = bossBar;
        if (activeBossBar == null) {
            cancelUpdateTask();
            return;
        }

        remainingTicks -= UPDATE_INTERVAL_TICKS;
        if (remainingTicks <= 0L) {
            clear();
            return;
        }
        activeBossBar.setProgress(calculateProgress(remainingTicks, durationTicks));
    }

    private void clear() {
        cancelUpdateTask();
        BossBar activeBossBar = bossBar;
        bossBar = null;
        durationTicks = 0L;
        remainingTicks = 0L;
        if (activeBossBar == null) {
            return;
        }
        activeBossBar.removeAll();
        activeBossBar.setVisible(false);
    }

    private void cancelUpdateTask() {
        BukkitTask activeTask = updateTask;
        updateTask = null;
        if (activeTask != null) {
            activeTask.cancel();
        }
    }

    /**
     * 残りtick数から BossBar の進捗率を計算します。
     *
     * @param remainingTicks 残りtick数
     * @param durationTicks 開始時の総tick数
     * @return 0.0以上1.0以下の進捗率
     */
    static double calculateProgress(long remainingTicks, long durationTicks) {
        if (durationTicks <= 0L) {
            return 0.0D;
        }
        double progress = (double) Math.max(0L, remainingTicks) / (double) durationTicks;
        return Math.max(0.0D, Math.min(1.0D, progress));
    }

    /**
     * テストと同一feature内の表示確認に使用する現在の BossBar を返します。
     *
     * @return 表示中の BossBar、表示がなければ {@code null}
     */
    @Nullable
    BossBar activeBossBar() {
        return bossBar;
    }
}
