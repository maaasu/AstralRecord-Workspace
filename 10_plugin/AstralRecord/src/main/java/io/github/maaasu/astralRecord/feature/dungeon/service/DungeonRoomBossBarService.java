package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** 一つのダンジョンセッションの戦闘部屋ごとに、敵数またはボスHPを1本のバーで表示します。 */
final class DungeonRoomBossBarService {
    private final Map<Integer, BossBar> barsByRoom = new HashMap<>();
    private final Map<Integer, Integer> initialMobCounts = new HashMap<>();

    /**
     * 通常部屋の生成成功数を分母として記録し、満タンの敵数バーを作成します。
     * メインスレッドから、Mob生成完了後に呼び出します。
     *
     * @param roomId 戦闘部屋ID
     * @param mobCount 実際に生成できた敵数。0以下なら作成しない
     */
    void startNormalRoom(int roomId, int mobCount) {
        if (mobCount <= 0) {
            return;
        }
        removeRoom(roomId);
        initialMobCounts.put(roomId, mobCount);
        barsByRoom.put(roomId, Bukkit.createBossBar("", BarColor.YELLOW, BarStyle.SOLID));
        updateNormalRoom(roomId, mobCount);
    }

    /**
     * 死亡確定後の残敵数を表示し、生成時の敵数に対する割合を更新します。
     *
     * @param roomId 通常部屋ID
     * @param remaining 生存敵数
     */
    void updateNormalRoom(int roomId, int remaining) {
        BossBar bar = barsByRoom.get(roomId);
        Integer initial = initialMobCounts.get(roomId);
        if (bar == null || initial == null) {
            return;
        }
        bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7220.getId(), remaining, initial));
        bar.setProgress(progress(remaining, initial));
    }

    /**
     * ボス個体の現在HPと人数補正後の最大HPから、赤色のHPバーを作成・更新します。
     * メインスレッドから呼び出し、ダメージだけでなく回復も反映します。
     *
     * @param roomId ボス部屋ID
     * @param boss 表示対象の生存ボス個体
     */
    void updateBossRoom(int roomId, @NotNull MobInstance boss) {
        BossBar bar = barsByRoom.computeIfAbsent(roomId,
                ignored -> Bukkit.createBossBar("", BarColor.RED, BarStyle.SOLID));
        bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7221.getId(),
                boss.template().displayName(),
                Math.max(0L, (long) Math.ceil(boss.currentHealth())),
                Math.max(1L, (long) Math.ceil(boss.maxHealth()))));
        bar.setProgress(progress(boss.currentHealth(), boss.maxHealth()));
    }

    /**
     * 参加者には現在入室中の戦闘部屋のバーだけを表示します。
     * 通路・安全地帯・攻略済み部屋や表示対象外の参加者には全バーを非表示にします。
     *
     * @param player 表示を同期する参加者
     * @param roomId 表示対象の部屋ID。表示しない場合はnull
     */
    void showRoom(@NotNull Player player, @Nullable Integer roomId) {
        for (Map.Entry<Integer, BossBar> entry : barsByRoom.entrySet()) {
            BossBar bar = entry.getValue();
            if (entry.getKey().equals(roomId)) {
                if (!bar.getPlayers().contains(player)) {
                    bar.addPlayer(player);
                }
            } else if (bar.getPlayers().contains(player)) {
                bar.removePlayer(player);
            }
        }
    }

    /**
     * 離脱・死亡・ログアウトした参加者を全バーから即時除外します。
     *
     * @param playerId 表示を解除する参加者UUID
     */
    void removePlayer(@NotNull UUID playerId) {
        for (BossBar bar : barsByRoom.values()) {
            for (Player player : bar.getPlayers()) {
                if (player.getUniqueId().equals(playerId)) {
                    bar.removePlayer(player);
                }
            }
        }
    }

    /**
     * 攻略済みまたは表示対象の敵が失われた部屋のバーを破棄します。
     *
     * @param roomId 破棄する部屋ID
     */
    void removeRoom(int roomId) {
        initialMobCounts.remove(roomId);
        BossBar bar = barsByRoom.remove(roomId);
        if (bar != null) {
            bar.removeAll();
            bar.setVisible(false);
        }
    }

    /** セッション終了・Plugin停止時に、メインスレッド上で全バーと生成時の敵数を破棄します。 */
    void clear() {
        for (BossBar bar : barsByRoom.values()) {
            bar.removeAll();
            bar.setVisible(false);
        }
        barsByRoom.clear();
        initialMobCounts.clear();
    }

    /**
     * Bukkitの進捗値として使用できる有限な0〜1の割合へ補正します。
     *
     * @param remaining 現在の敵数またはHP
     * @param total 生成時の敵数または最大HP
     * @return 分母が不正なら0、それ以外は0〜1の割合
     */
    private static double progress(double remaining, double total) {
        if (!Double.isFinite(remaining) || !Double.isFinite(total) || total <= 0.0D) {
            return 0.0D;
        }
        return Math.max(0.0D, Math.min(1.0D, remaining / total));
    }
}
