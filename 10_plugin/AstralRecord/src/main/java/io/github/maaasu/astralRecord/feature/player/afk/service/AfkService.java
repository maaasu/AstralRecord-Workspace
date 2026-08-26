package io.github.maaasu.astralRecord.feature.player.afk.service;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.afk.model.AfkActivityState;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.PlayerClassService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤーのAFK状態をメモリ上で管理します。
 * <p>
 * 状態はログインセッションだけで保持し、AFK遷移時と復帰時だけ表示を更新します。
 */
public final class AfkService {

    /** 操作がないとAFKへ遷移する時間。 */
    public static final long AFK_TIMEOUT_MS = Duration.ofMinutes(5).toMillis();
    private static final long CHECK_INTERVAL_TICKS = 20L;
    private static final Title.Times AFK_TITLE_TIMES = Title.Times.times(
        Duration.ofMillis(100L),
        Duration.ofDays(1L),
        Duration.ofMillis(100L)
    );

    private final PlayerClassService playerClassService;
    private final Map<UUID, AfkActivityState> states = new HashMap<>();
    private BukkitTask checkTask;

    /**
     * AFKサービスを構築します。
     *
     * @param playerClassService AFK接頭辞を含むTab表示名の更新先
     */
    public AfkService(@NotNull PlayerClassService playerClassService) {
        this.playerClassService = playerClassService;
    }

    /**
     * AFK判定の定期実行を開始します。
     *
     * @param plugin schedulerを起動するプラグイン
     */
    public void start(@NotNull Plugin plugin) {
        if (checkTask != null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        for (Player player : Bukkit.getOnlinePlayers()) {
            track(player, nowMs);
        }
        checkTask = Bukkit.getScheduler().runTaskTimer(plugin, this::enterInactivePlayers, CHECK_INTERVAL_TICKS, CHECK_INTERVAL_TICKS);
    }

    /**
     * AFK判定を停止し、セッション状態を破棄します。
     */
    public void stop() {
        if (checkTask != null) {
            checkTask.cancel();
            checkTask = null;
        }
        states.clear();
    }

    /**
     * 参加したプレイヤーのAFK判定を開始します。
     *
     * @param player 参加プレイヤー
     */
    public void onPlayerJoin(@NotNull Player player) {
        track(player, System.currentTimeMillis());
    }

    /**
     * 退出したプレイヤーのAFK判定を破棄します。
     *
     * @param player 退出プレイヤー
     */
    public void onPlayerQuit(@NotNull Player player) {
        states.remove(player.getUniqueId());
    }

    /**
     * 前後左右入力の現在状態を記録します。
     *
     * @param player 入力プレイヤー
     * @param directionalInput 前後左右のいずれかが押下中なら {@code true}
     */
    public void onDirectionalInput(@NotNull Player player, boolean directionalInput) {
        stateFor(player, System.currentTimeMillis()).setDirectionalInput(directionalInput);
    }

    /**
     * 移動後の位置を記録し、前後左右入力を伴う1m以上の移動ならAFKを解除します。
     *
     * @param player 移動プレイヤー
     * @param to 移動後の位置。{@code null}なら何もしない
     * @param teleport 転送イベントなら {@code true}
     */
    public void onPlayerMove(@NotNull Player player, @Nullable Location to, boolean teleport) {
        if (to == null) {
            return;
        }
        long nowMs = System.currentTimeMillis();
        AfkActivityState state = stateFor(player, nowMs);
        if (state.recordMovement(to, teleport, nowMs) && state.isAfk()) {
            changeState(player, state, false);
        }
    }

    /**
     * 対象プレイヤーがAFK中かを返します。
     *
     * @param player 対象プレイヤー
     * @return AFK中なら {@code true}
     */
    public boolean isAfk(@NotNull Player player) {
        AfkActivityState state = states.get(player.getUniqueId());
        return state != null && state.isAfk();
    }

    /**
     * 対象セッションがAFK中かを返します。
     *
     * @param player 対象セッション
     * @return AFK中なら {@code true}
     */
    public boolean isAfk(@NotNull AstPlayer player) {
        return isAfk(player.getBukkit());
    }

    private void enterInactivePlayers() {
        long nowMs = System.currentTimeMillis();
        var iterator = states.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, AfkActivityState> entry = iterator.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                iterator.remove();
                continue;
            }
            AfkActivityState state = entry.getValue();
            if (state.isInactiveFor(nowMs, AFK_TIMEOUT_MS)) {
                changeState(player, state, true);
            }
        }
    }

    private @NotNull AfkActivityState stateFor(@NotNull Player player, long nowMs) {
        return states.computeIfAbsent(
            player.getUniqueId(),
            ignored -> new AfkActivityState(player.getLocation(), nowMs)
        );
    }

    private void track(@NotNull Player player, long nowMs) {
        states.put(player.getUniqueId(), new AfkActivityState(player.getLocation(), nowMs));
    }

    private void changeState(@NotNull Player player, @NotNull AfkActivityState state, boolean afk) {
        state.setAfk(afk);
        var astPlayer = io.github.maaasu.astralRecord.feature.player.AstPlayerCache.get(player);
        if (astPlayer != null) {
            playerClassService.updatePlayerListName(astPlayer);
        }
        if (afk) {
            player.showTitle(Title.title(
                PlayerMsgResource.getComponent(PlayerMsgId.P_7120.getId()),
                Component.empty(),
                AFK_TITLE_TIMES
            ));
            return;
        }
        player.clearTitle();
    }
}
