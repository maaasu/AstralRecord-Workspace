package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 八秒間被ダメージを45%軽減するメイジの魔力障壁です。 */
public final class MageManaBarrierExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_mana_barrier";

    /** 共有発動スキルサービスで初期化します。 */
    public MageManaBarrierExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        context.services().temporaryEffects().apply(
                context.player().getUniqueId(), ID, 160L, 0.55D, 1.0D, 1.0D);
        Location center = context.player().getLocation().add(0.0D, 0.2D, 0.0D);
        context.services().effects().ring(center, 1.45D, 16, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST);
        context.services().effects().sound(center, Sound.BLOCK_BEACON_ACTIVATE, 0.8F, 1.3F);
        return context.success();
    }
}
