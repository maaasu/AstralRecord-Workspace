package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.active.service.SkillMovementService;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 正面へ射撃しながら安全に後退するハンターの退き撃ちです。 */
public final class HunterBackstepShotExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_backstep_shot";

    /** 共有発動スキルサービスで初期化します。 */
    public HunterBackstepShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                13.0D, 1.6D, 0.45D, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().projectiles().launch(
                context.player(), origin, direction, projectile,
                (target, ignored) -> context.services().combat().hit(
                        attacker, target, AttackType.RANGED, DamageElement.NONE, 1.05D),
                ignored -> { }
        );
        SkillMovementService.MovementResult movement = context.services().movement().backstep(
                context.player(), attacker, 3.5D);
        context.services().effects().line(
                movement.start().add(0.0D, 0.2D, 0.0D),
                movement.end().add(0.0D, 0.2D, 0.0D),
                0.5D,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW
        );
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.0F);
        return context.success();
    }
}
