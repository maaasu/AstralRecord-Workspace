package io.github.maaasu.astralRecord.feature.skill.model;

/** 装備スキルの表示専用スナップショット。slot=0は左クリック枠です。 */
public record SkillCooldownHudEntry(int slot, String name, long remainingTicks, State state) {
    /** 最大12発動枠と、重複しない左クリック1枠です。 */
    public static final int MAX_ENTRIES = SkillBindPreset.ACTIVE_SLOT_COUNT + 1;

    /** CTと現在の一般的な発動条件を区別します。対象固有の成否は保証しません。 */
    public enum State {
        READY, COOLDOWN, MANA, ENERGY, BLOCKED
    }
}
