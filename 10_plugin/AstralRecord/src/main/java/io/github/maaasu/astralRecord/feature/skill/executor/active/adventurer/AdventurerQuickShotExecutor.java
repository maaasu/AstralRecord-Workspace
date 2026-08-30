package io.github.maaasu.astralRecord.feature.skill.executor.active.adventurer;

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

/** 照準方向へ高速の一矢を放つ冒険者の基礎射撃です。 */
public final class AdventurerQuickShotExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "adventurer_quick_shot";
    static final double DAMAGE_RATIO = 2.25D;
    static final double RANGE = 12.0D;
    static final double SPEED = 2.2D;
    static final double HIT_RADIUS = 0.45D;

    /** 共有発動スキルサービスで初期化します。 */
    public AdventurerQuickShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        context.services().projectiles().launch(
                context.player(), context.eyeLocation(), context.direction(), quickShotProjectile(),
                (target, ignored) -> context.services().combat().hit(
                        attacker, target, AttackType.RANGED, DamageElement.NONE, DAMAGE_RATIO),
                ignored -> { }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 0.8F, 1.25F);
        return context.success();
    }

    /**
     * クイックショットの非貫通仮想飛翔体仕様を返します。
     *
     * @return 射程・速度・当たり半径・軽量演出を固定した飛翔体仕様
     */
    static @NotNull SkillProjectileSpec quickShotProjectile() {
        return new SkillProjectileSpec(
                RANGE, SPEED, HIT_RADIUS, false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
    }
}
