package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * ガイド手順の達成判定に使用できる条件種別です。
 */
public enum GuideConditionType {
    /** アクションリングを表示した。 */
    ACTION_RING_OPENED,
    /** プレイヤーによるスキル発動が成功した。 */
    SKILL_CAST;

    /**
     * マスターデータ上の文字列から条件種別を解決します。
     *
     * @param value 条件種別文字列
     * @return 解決した条件種別
     * @throws IllegalArgumentException 未対応の条件種別の場合
     */
    public static @NotNull GuideConditionType parse(@NotNull String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
