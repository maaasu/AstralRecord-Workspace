package io.github.maaasu.astralRecord.feature.mob.model;

import org.jetbrains.annotations.NotNull;

/**
 * Mob ドロップ抽選で当選したアイテム。
 *
 * @param itemId  アイテム ID
 * @param amount  ドロップ数量
 * @param dropRate 抽選に使用した設定上のドロップ確率（%）
 */
public record MobDropResultItem(
        @NotNull String itemId,
        int amount,
        double dropRate
) {

    public MobDropResultItem {
        amount = Math.max(1, amount);
        dropRate = Math.max(0.0D, Math.min(100.0D, dropRate));
    }
}
