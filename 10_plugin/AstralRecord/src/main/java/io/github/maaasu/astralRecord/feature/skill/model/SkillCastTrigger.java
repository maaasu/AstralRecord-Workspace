package io.github.maaasu.astralRecord.feature.skill.model;

/**
 * スキル発動経路の種別。
 * <p>
 * 同一の {@link io.github.maaasu.astralRecord.feature.skill.executor.SkillExecutor} 実装が
 * 複数の経路から発動される場合、実装側で経路ごとの差分処理が必要になる可能性があるため、
 * 発動契機をコンテキストに保持できるようにする。
 */
public enum SkillCastTrigger {

    /** プレイヤーのコマンド / GUI / ショートカット入力。 */
    PLAYER_COMMAND,

    /** 通常攻撃派生など、攻撃モーションを起点に発動する経路。 */
    AUTO_ATTACK,

    /** Mob AI による発動。 */
    MOB_AI,

    /** 上記以外の内部発動経路。 */
    SYSTEM,
}
