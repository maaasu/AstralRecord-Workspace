package io.github.maaasu.astralRecord.feature.skill.executor.active.mage;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 長い直線を五体まで貫く無属性魔法、メイジの星幽槍です。 */
public final class MageArcaneLanceExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "mage_arcane_lance";

    /** 共有発動スキルサービスで初期化します。 */
    public MageArcaneLanceExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Location end = context.services().targeting().clippedEnd(origin, context.direction(), 16.0D);
        context.services().effects().line(origin, end, 0.35D, SharedParticleDefinitions.SKILL_MAGE_ARCANE_DUST);
        context.services().targeting().inLine(context.player(), origin, context.direction(), 16.0D, 0.50D, 5)
                .forEach(target -> context.services().combat().hit(
                        context.attacker(), target, AttackType.MAGIC, DamageElement.NONE, 1.60D));
        context.services().effects().sound(origin, Sound.ENTITY_EVOKER_CAST_SPELL, 0.9F, 1.35F);
        return context.success();
    }
}
