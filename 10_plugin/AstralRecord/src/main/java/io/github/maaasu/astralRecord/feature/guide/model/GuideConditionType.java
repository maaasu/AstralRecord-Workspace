package io.github.maaasu.astralRecord.feature.guide.model;

import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * ガイド手順の達成判定に使用できる条件種別です。
 */
public enum GuideConditionType {
    /** プレイヤーがゲームプレイ状態でログインした。 */
    PLAYER_LOGGED_IN,
    /** ガイドを開いた。 */
    GUIDE_OPENED,
    /** ログインボーナスを受け取った。 */
    LOGIN_BONUS_CLAIMED,
    /** メールを既読化して報酬を受け取った。 */
    MAIL_RECEIVED,
    /** bundle アイテムを開封した。 */
    BUNDLE_OPENED,
    /** ショップからアイテムを購入または交換した。 */
    SHOP_PURCHASED,
    /** スキルマネージャーからスキル個体を習得した。 */
    SKILL_LEARNED,
    /** スキルツリーノードを解放した。 */
    SKILLTREE_NODE_UNLOCKED,
    /** PPを消費するスキルツリーノードを解放した。 */
    SKILLTREE_PP_NODE_UNLOCKED,
    /** CPを消費するスキルツリーノードを解放した。 */
    SKILLTREE_CP_NODE_UNLOCKED,
    /** スキル効果を持つスキルツリーノードを解放した。 */
    SKILLTREE_SKILL_NODE_UNLOCKED,
    /** スキルを強化した。 */
    SKILL_ENHANCED,
    /** スキルをアクションリングへ設定した。 */
    SKILL_BOUND,
    /** オーブによる装備更新が確定した。 */
    ORB_USED,
    /** アクションリングを表示した。 */
    ACTION_RING_OPENED,
    /** プレイヤーによるスキル発動が成功した。 */
    SKILL_CAST,
    /** 装備中の武器に対応するタグを持つスキルの発動が成功した。 */
    WEAPON_SKILL_CAST,
    /** クラス変更が成功した。 */
    CLASS_CHANGED,
    /** 敵Mobを討伐した。 */
    MOB_DEFEATED,
    /** 採集オブジェクトを完了まで採集した。 */
    GATHERING_COMPLETED,
    /** ウェイストーンへのテレポートに成功した。 */
    WAYSTONE_TELEPORTED,
    /** クエストの受領に成功した。 */
    QUEST_ACCEPTED,
    /** クエストの報酬・状態確定に成功した。 */
    QUEST_COMPLETED,
    /** ダンジョンのボス部屋を攻略してクリアが確定した。 */
    DUNGEON_CLEARED;

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
