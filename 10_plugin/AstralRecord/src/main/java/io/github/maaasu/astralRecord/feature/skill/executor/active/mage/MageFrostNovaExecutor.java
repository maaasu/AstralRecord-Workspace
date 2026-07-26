package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.skill.active.model.ActiveSkillCondition;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 周囲へ冷気を放ち、敵を冷気状態にするメイジの氷輪です。 */
public final class MageFrostNovaExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_frost_nova";

    /** 共有発動スキルサービスで初期化します。 */
    public MageFrostNovaExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 0.2D, 0.0D);
        context.services().effects().ring(center, 4.0D, 22, SharedParticleDefinitions.SKILL_MAGE_ICE);
        context.services().targeting().inRadius(context.player(), center, 4.0D, 3.0D, 12, true)
                .forEach(target -> context.services().combat().hit(
                        context.attacker(),
                        target,
                        AttackType.MAGIC,
                        DamageElement.ICE,
                        0.75D,
                        ActiveSkillCondition.certain(ConditionType.CHILLED, 80L)
                ));
        context.services().effects().sound(center, Sound.BLOCK_GLASS_BREAK, 0.9F, 1.25F);
        return context.success();
    }
}
