package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

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

/** 周囲を一回転して斬るソードマンの旋回斬りです。 */
public final class SwordsmanWhirlwindExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_whirlwind";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanWhirlwindExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 1.0D, 0.0D);
        context.services().effects().ring(center, 3.25D, 18, SharedParticleDefinitions.SKILL_SWORD_SWEEP);
        context.services().targeting().inRadius(context.player(), center, 3.25D, 2.5D, 8, false)
                .forEach(target -> context.services().combat().hit(
                        context.attacker(), target, AttackType.MELEE, DamageElement.NONE, 0.95D));
        context.services().effects().sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.75F);
        return context.success();
    }
}
