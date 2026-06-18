package io.github.maaasu.astralRecord.feature.skill.model;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * {@link MobInstance} を発動主体として扱う {@link SkillCaster} 実装。
 *
 * <p>Mob は所持スキル・MP・レベル制限の管理対象外として扱い、
 * 共通クールダウンと executor への主体情報提供だけを行います。</p>
 */
public final class MobSkillCaster implements SkillCaster {

    private final MobInstance mob;

    /**
     * 発動主体を Mob インスタンスで初期化します。
     *
     * @param mob 発動主体 Mob
     */
    public MobSkillCaster(@NotNull MobInstance mob) {
        this.mob = mob;
    }

    /**
     * ラップしている Mob インスタンスを返します。
     *
     * @return Mob インスタンス
     */
    @NotNull
    public MobInstance mob() {
        return mob;
    }

    @Override
    @NotNull
    public UUID casterId() {
        return mob.instanceId();
    }

    @Override
    public int level() {
        return Integer.MAX_VALUE;
    }

    @Override
    @NotNull
    public StatusSnapshot statusSnapshot() {
        return StatusSnapshot.empty();
    }

    @Override
    public double currentMana() {
        return Double.MAX_VALUE;
    }

    @Override
    public double currentEnergy() {
        return Double.MAX_VALUE;
    }

    @Override
    public void consumeMana(double amount) {
        // Mob のリソース消費は現状管理しない。
    }

    @Override
    public void consumeEnergy(double amount) {
        // Mob のリソース消費は現状管理しない。
    }

    @Override
    public void notify(@NotNull PlayerMsgId messageId, Object... args) {
        // Mob には通知先がないため no-op。
    }
}
