package io.github.maaasu.astralRecord.feature.mob.model;

/**
 * Mob の戦闘・行動状態。
 */
public enum MobState {

    /** 非接敵。{@link IdleBehavior} に従って動く */
    IDLE,

    /** 接敵。{@link TargetStrategy} に基づきターゲットを追跡 */
    AGGRO,

    /** 戦闘中。{@code preferredRange} を維持しつつ {@code attackIntervalTicks} ごとに攻撃 */
    COMBAT,

    /** leashRange を超過したためスポーン地点へ帰還中 */
    LEASHED,

    /** 撃破済み。次 tick で完全消滅する */
    DEAD
}
