package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * ガイド手順の達成判定に使用できる条件種別です。
 */
public enum GuideConditionType {
    /** プレイヤーがゲームプレイ状態でログインした。 */
    PLAYER_LOGGED_IN,
    /** ログインボーナスを受け取った。 */
    LOGIN_BONUS_CLAIMED,
    /** メールを既読化して報酬を受け取った。 */
    MAIL_RECEIVED,
    /** bundle アイテムを開封した。 */
    BUNDLE_OPENED,
    /** ショップからアイテムを購入または交換した。 */
    SHOP_PURCHASED,
    /** スキルツリーノードを解放した。 */
    SKILLTREE_NODE_UNLOCKED,
    /** スキルを強化した。 */
    SKILL_ENHANCED,
    /** アクションリングを表示した。 */
    ACTION_RING_OPENED,
    /** プレイヤーによるスキル発動が成功した。 */
    SKILL_CAST,
    /** 敵Mobを討伐した。 */
    MOB_DEFEATED,
    /** 採集オブジェクトを完了まで採集した。 */
    GATHERING_COMPLETED;

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
