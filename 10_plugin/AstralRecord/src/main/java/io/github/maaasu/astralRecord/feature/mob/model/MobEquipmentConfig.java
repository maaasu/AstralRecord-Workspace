package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の表示装備設定。表示のみに使用し、ステータス計算には影響しない。
 *
 * @param mainHand   メインハンドへ表示する Minecraft Material 名
 * @param offHand    オフハンドへ表示する Minecraft Material 名
 * @param helmet     ヘルメットへ表示する Minecraft Material 名
 * @param chestplate チェストプレートへ表示する Minecraft Material 名
 * @param leggings   レギンスへ表示する Minecraft Material 名
 * @param boots      ブーツへ表示する Minecraft Material 名
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
