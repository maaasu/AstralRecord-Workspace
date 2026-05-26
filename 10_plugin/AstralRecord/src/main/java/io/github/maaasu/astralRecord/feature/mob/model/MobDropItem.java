package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob のドロップアイテム 1 エントリ。
 *
 * @param itemId        アイテム ID（prefix 除去済み）
 * @param rate          ドロップ確率（0.00 〜 100.00）
 * @param amount        ドロップ数量。固定値（{@code "1"}）または範囲（{@code "1~3"}）
 * @param luckAffected  {@code true} の場合、幸運補正の影響を受ける
 * @param hidden        {@code true} の場合、図鑑表示しない
 */
public record MobDropItem(
        String itemId,
        double rate,
        String amount,
        boolean luckAffected,
        boolean hidden
) {
}
