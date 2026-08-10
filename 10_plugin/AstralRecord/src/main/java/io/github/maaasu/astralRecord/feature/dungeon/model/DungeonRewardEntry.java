package io.github.maaasu.astralRecord.feature.dungeon.model;

import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** プレイヤーごとに抽選済みで未受取のダンジョンクリア報酬です。 */
public record DungeonRewardEntry(
        @NotNull UUID claimId,
        @NotNull String itemId,
        int amount,
        double configuredRate
) {
    public DungeonRewardEntry {
        amount = Math.max(1, amount);
        configuredRate = Math.max(0.0D, Math.min(100.0D, configuredRate));
    }

    /** @return 一部受取後の残数を保持する新しい報酬 */
    public @NotNull DungeonRewardEntry withAmount(int remaining) {
        return new DungeonRewardEntry(claimId, itemId, remaining, configuredRate);
    }
}
