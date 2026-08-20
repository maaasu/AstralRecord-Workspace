package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * ガイド詳細画面から実行できる案内アクションの種別です。
 */
public enum GuideActionType {
    /** 指定 NPC の場所を案内し、対象を一時的に発光させる。 */
    NAVIGATE_NPC,
    /** 指定したゲーム内メニューを開く。 */
    OPEN_MENU;

    /**
     * マスターデータ上の文字列からアクション種別を解決します。
     *
     * @param value アクション種別文字列
     * @return 解決したアクション種別
     * @throws IllegalArgumentException 未対応の種別の場合
     */
    public static @NotNull GuideActionType parse(@NotNull String value) {
        return valueOf(value.trim().toUpperCase(Locale.ROOT));
    }
}
