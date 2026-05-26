package io.github.maaasu.astralRecord.feature.mob.model;

import java.util.List;

/**
 * Mob のドロップ設定。{@code NPC} カテゴリでは {@code null} となる。
 *
 * @param exp       撃破時獲得経験値
 * @param money     金銭ドロップ範囲（任意）
 * @param items     ドロップアイテム一覧
 * @param lootTable 既存 LootTable の ID（prefix 除去済み、任意）
 */
public record MobDropConfig(
        int exp,
        MobMoneyDrop money,
        List<MobDropItem> items,
        String lootTable
) {

    public MobDropConfig {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
