package io.github.maaasu.astralRecord.feature.dungeon.service;

import org.bukkit.Material;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ダンジョン報酬 CHEST の操作可否を副作用なしで判定します。 */
final class DungeonRewardChestPolicy {
    private DungeonRewardChestPolicy() {
    }

    /**
     * 現在のセッション状態と報酬 CHEST から操作可否を判定します。
     *
     * @param cleared ボス部屋クリア済みか
     * @param ending セッション終了処理中か
     * @param participants 現在参加中のプレイヤー UUID
     * @param rewardsByPlayer クリア時に固定した受取対象と未受取報酬
     * @param playerId 操作プレイヤー UUID
     * @param rewardWorldId 報酬 CHEST の World UUID。World が失効している場合は null
     * @param playerWorldId 操作プレイヤーの現在 World UUID
     * @param material 報酬 CHEST 位置の現在 Material
     * @return 現在参加者かつ受取対象で、クリア待機中の同一 World に CHEST が残っていれば true
     */
    static boolean canAccess(
            boolean cleared,
            boolean ending,
            @NotNull Set<UUID> participants,
            @NotNull Map<UUID, ?> rewardsByPlayer,
            @NotNull UUID playerId,
            @Nullable UUID rewardWorldId,
            @NotNull UUID playerWorldId,
            @NotNull Material material
    ) {
        return cleared
                && !ending
                && participants.contains(playerId)
                && rewardsByPlayer.containsKey(playerId)
                && rewardWorldId != null
                && rewardWorldId.equals(playerWorldId)
                && material == Material.CHEST;
    }
}
