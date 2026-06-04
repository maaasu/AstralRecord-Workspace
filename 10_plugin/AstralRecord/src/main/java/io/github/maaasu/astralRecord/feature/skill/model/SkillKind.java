package io.github.maaasu.astralRecord.feature.skill.model;

/**
 * スキル実装が発動系かパッシブ系かを表します。
 */
public enum SkillKind {
    ACTIVE,
    PASSIVE;

    /**
     * パッシブ系かどうかを返します。
     *
     * @return パッシブ系の場合 true
     */
    public boolean isPassive() {
        return this == PASSIVE;
    }
}
