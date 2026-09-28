package io.github.maaasu.astralRecord.feature.combat.model;

/** 1回のスキル発動とその派生攻撃で共有する攻撃側の耐久消費状態です。 */
public final class SkillAttackWear {
    private boolean consumed;

    /**
     * 最初の有効命中だけに耐久消費判定を許可します。
     *
     * @return この発動で初めて判定する場合は true
     */
    public boolean claim() {
        if (consumed) {
            return false;
        }
        consumed = true;
        return true;
    }
}
