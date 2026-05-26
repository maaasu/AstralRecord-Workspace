package io.github.maaasu.astralRecord.feature.mob.model;

import java.util.List;
import java.util.Map;

/**
 * Mob 撃破時のドロップ抽選結果。
 *
 * @param items アイテム ID と数量の組
 * @param exp   経験値合計
 * @param money 金銭合計
 */
public record MobDropResult(
        List<Map.Entry<String, Integer>> items,
        int exp,
        int money
) {

    public MobDropResult {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
