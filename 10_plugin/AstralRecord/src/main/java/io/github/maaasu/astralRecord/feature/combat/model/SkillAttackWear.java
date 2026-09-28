package io.github.maaasu.astralRecord.feature.combat.model;

import java.util.UUID;
import org.jetbrains.annotations.NotNull;

/** 1回のスキル発動とその派生攻撃で共有する攻撃側の耐久消費状態です。 */
public final class SkillAttackWear {
    private final UUID ownerId;
    private boolean consumed;

    /**
     * 発動者を指定して耐久消費状態を作成します。
     *
     * @param ownerId 発動者 ID
     */
    public SkillAttackWear(@NotNull UUID ownerId) {
        this.ownerId = ownerId;
    }

    /**
     * この発動の攻撃者かを判定します。
     *
     * @param attackerId 攻撃者 ID
     * @return 発動者と一致すれば true
     */
    public boolean belongsTo(@NotNull UUID attackerId) {
        return ownerId.equals(attackerId);
    }

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
