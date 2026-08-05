package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 最初の標的から近くの敵へ最大四回跳ねるハンターの跳ね矢です。 */
public final class HunterRicochetExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_ricochet";
    /** 共有発動スキルサービスで初期化します。 */
    public HunterRicochetExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        AstEntity attacker = context.attacker();
        SkillParamReader params = new SkillParamReader(
                context.source().skill().getId(),
                context.source().skill().getParams()
        );
        java.util.List<Double> ratios = params.getDoubleList(
                "damageRatios",
                java.util.List.of(1.15D, 0.90D, 0.70D, 0.55D)
        );
        int bounceCount = Math.max(0, params.getInt("bounceCount", ratios.size() - 1));
        double searchRange = params.getDouble("bounceSearchRange", 5.0D);
        SkillProjectileSpec projectile = new SkillProjectileSpec(
                params.getDouble("projectileRange", 15.0D),
                params.getDouble("projectileSpeed", 1.6D),
                params.getDouble("projectileHitRadius", 0.45D),
                false, 1,
                SharedParticleDefinitions.SKILL_HUNTER_ARROW,
                SharedParticleDefinitions.SKILL_HUNTER_IMPACT
        );
        context.services().projectiles().launch(
                context.player(), context.eyeLocation(), context.direction(), projectile,
                (first, impact) -> chain(context, attacker, first, ratios, bounceCount, searchRange),
                ignored -> { }
        );
        context.services().effects().sound(context.eyeLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.05F);
        return context.success();
    }

    private void chain(
            @NotNull PlayerActiveSkillContext context,
            @NotNull AstEntity attacker,
            @NotNull AstEntity first,
            @NotNull java.util.List<Double> ratios,
            int bounceCount,
            double searchRange
    ) {
        Set<UUID> hitIds = new HashSet<>();
        AstEntity current = first;
        Location previous = context.eyeLocation();
        int maxHits = Math.min(ratios.size(), bounceCount + 1);
        for (int index = 0; index < maxHits && current != null; index++) {
            Location currentCenter = current.location().clone().add(0.0D, 1.0D, 0.0D);
            context.services().effects().line(
                    previous, currentCenter, 0.4D, SharedParticleDefinitions.SKILL_HUNTER_IMPACT);
            context.services().combat().hit(
                    attacker, current, AttackType.RANGED, DamageElement.NONE, ratios.get(index));
            hitIds.add(current.id());
            previous = currentCenter;
            current = context.services().targeting().nearestFrom(
                    context.player(), currentCenter, searchRange, hitIds);
        }
    }
}
