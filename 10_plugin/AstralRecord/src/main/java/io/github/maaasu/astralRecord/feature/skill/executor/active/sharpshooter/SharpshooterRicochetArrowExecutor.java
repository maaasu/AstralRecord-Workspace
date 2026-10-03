package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
import org.bukkit.util.Vector;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.jetbrains.annotations.NotNull;

/** 着弾先から敵へ跳ねる矢。 */
public final class SharpshooterRicochetArrowExecutor extends SharpshooterBuildSupport {
    public static final String ID = "sharpshooter_ricochet_arrow";

    /**
     * 共通サービスで構築します。
     * @param services 対象・戦闘・演出サービス
     */
    public SharpshooterRicochetArrowExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader p = new SkillParamReader(skill.getId(), skill.getParams());
        positive(p, "bounceRadius"); count(p, "bounceCount", 1, 4); fraction(p, "bounceDecay");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext c) {
        SkillParamReader p = c.params();
        Location origin = c.eyeLocation();
        SharpshooterSignatureEffects.refrainCast(c, origin, c.direction());
        c.services().projectiles().launch(c.player(), origin, c.direction(),
                new SkillProjectileSpec(p.getDouble("range", 19.0D),
                        p.getDouble("projectileSpeed", 1.8D),
                        p.getDouble("projectileHitRadius", 0.6D), false, 1,
                        SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_TRAIL,
                        SharedParticleDefinitions.SHARPSHOOTER_REFRAIN_IMPACT),
                (first, impact) -> chain(c, first, impact, 1.0D), ignored -> { });
        grantInheritance(c, this::hasEffectiveMobImpact, (impact, multiplier) ->
                chain(c, java.util.Objects.requireNonNull(impact.target()),
                        impact.location(), multiplier));
        return c.success();
    }

    /**
     * 最初の敵を起点に、未命中かつ遮蔽されない敵へ順に反跳します。
     * @param context 元スキルの実行値
     * @param first 起点の敵。継承時は通常攻撃と元スキル効果を別に適用する
     * @param impact 起点の着弾位置
     * @param multiplier 継承時だけ0.5になる倍率
     */
    private void chain(@NotNull PlayerActiveSkillContext context, @NotNull AstEntity first,
                       @NotNull Location impact, double multiplier) {
        SkillParamReader params = context.params();
        Set<UUID> damaged = new HashSet<>();
        damaged.add(first.id());
        SharpshooterSignatureEffects.refrainImpact(context, impact);
        double ratio = params.getDouble("damageRatio", 1.0D) * multiplier;
        if (first.currentHealth() > 0.0D) {
            hit(context, first, DamageElement.NONE, ratio);
        }
        Location origin = impact.clone();
        for (int index = 0; index < params.getInt("bounceCount", 2); index++) {
            Location from = origin;
            AstEntity next = context.services().targeting().inSphere(context.player(), from,
                    params.getDouble("bounceRadius", 5.0D), 10, true).stream()
                    .filter(candidate -> !damaged.contains(candidate.id()))
                    .filter(candidate -> visible(context, from, candidate))
                    .findFirst().orElse(null);
            if (next == null) break;
            damaged.add(next.id());
            Location destination = next.location().clone().add(0.0D, 1.0D, 0.0D);
            SharpshooterSignatureEffects.refrainBounce(context, origin, destination, index + 1);
            ratio *= params.getDouble("bounceDecay", 0.7D);
            hit(context, next, DamageElement.NONE, ratio);
            origin = destination;
        }
    }

    /**
     * 反跳経路が地形で遮られていないか判定します。
     * @param context 発動者
     * @param from 反跳元
     * @param target 次の候補
     * @return 地形衝突がなければtrue
     */
    private boolean visible(@NotNull PlayerActiveSkillContext context, @NotNull Location from,
                            @NotNull AstEntity target) {
        Location destination = target.location().clone().add(0.0D, 1.0D, 0.0D);
        if (destination.getWorld() != from.getWorld()) return false;
        Vector delta = destination.toVector().subtract(from.toVector());
        return delta.lengthSquared() > 1.0E-8D
                && context.services().targeting().blockImpact(from, delta.clone().normalize(),
                delta.length()) == null;
    }
}
