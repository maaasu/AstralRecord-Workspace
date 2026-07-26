package io.github.maaasu.astralRecord.feature.skill.executor.active.swordsman;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 三体まで貫く剣気を飛ばすソードマンの剣気波です。 */
public final class SwordsmanBladeWaveExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "swordsman_blade_wave";

    /** 共有発動スキルサービスで初期化します。 */
    public SwordsmanBladeWaveExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                12.0D,
                1.25D,
                0.75D,
                true,
                3,
                SharedParticleDefinitions.SKILL_SWORD_EDGE,
                SharedParticleDefinitions.SKILL_SWORD_SWEEP
        );
        context.services().projectiles().launch(
                context.player(),
                context.eyeLocation(),
                context.direction(),
                projectile,
                (target, ignored) -> context.services().combat().hit(
                        attacker, target, AttackType.MELEE, DamageElement.NONE, 1.25D),
                ignored -> { }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 1.3F);
        return context.success();
    }
}
