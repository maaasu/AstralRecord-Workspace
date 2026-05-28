package io.github.maaasu.astralRecord.feature.combat.model;

/**
 * AstralRecord のダメージ処理で扱うエンティティ種別です。
 */
public enum AstEntityType {

    /** AstralRecord のプレイヤーセッション。 */
    PLAYER,

    /** AstralRecord の独自 Mob インスタンス。 */
    MOB,

    /** AstPlayer / MobInstance へ解決できない Bukkit エンティティ。 */
    BUKKIT
}
