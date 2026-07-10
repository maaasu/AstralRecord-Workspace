package io.github.maaasu.astralRecord.feature.mob.model;

import java.util.List;

/**
 * Mob 撃破時のドロップ抽選結果。
 *
 * @param items 当選アイテム、数量、ドロップ確率の組
 * @param exp   経験値合計
 * @param money 金銭合計
 */
public record MobDropResult(
        List<MobDropResultItem> items,
        int exp,
        int money
) {

    public MobDropResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
