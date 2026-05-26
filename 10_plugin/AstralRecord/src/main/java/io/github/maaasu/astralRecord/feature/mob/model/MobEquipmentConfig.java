package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の表示装備設定。表示のみに使用し、ステータス計算には影響しない。
 *
 * @param mainHand   メインハンドのアイテム ID（prefix 除去済み）
 * @param offHand    オフハンドのアイテム ID
 * @param helmet     ヘルメットのアイテム ID
 * @param chestplate チェストプレートのアイテム ID
 * @param leggings   レギンスのアイテム ID
 * @param boots      ブーツのアイテム ID
 */
public record MobEquipmentConfig(
        String mainHand,
        String offHand,
        String helmet,
        String chestplate,
        String leggings,
        String boots
) {

    /** すべて未指定の空装備設定。 */
    public static final MobEquipmentConfig EMPTY = new MobEquipmentConfig(null, null, null, null, null, null);
}
