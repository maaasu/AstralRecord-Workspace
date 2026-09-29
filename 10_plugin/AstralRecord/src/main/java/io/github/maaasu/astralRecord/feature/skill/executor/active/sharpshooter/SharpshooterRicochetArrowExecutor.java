package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Location;
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
        Set<UUID> damaged = new HashSet<>();
        projectile(c, c.eyeLocation(), c.direction(), 1,
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL, (first, impact) -> {
                    damaged.add(first.id());
                    hit(c, first, DamageElement.NONE, p.getDouble("damageRatio", 1.0D));
                    Location origin = first.location().clone().add(0.0D, 1.0D, 0.0D);
                    double ratio = p.getDouble("damageRatio", 1.0D);
                    for (int i = 0; i < p.getInt("bounceCount", 2); i++) {
                        AstEntity next = c.services().targeting().inSphere(c.player(), origin,
                                p.getDouble("bounceRadius", 5.0D), 10, true).stream()
                                .filter(candidate -> !damaged.contains(candidate.id()))
                                .findFirst().orElse(null);
                        if (next == null) break;
                        damaged.add(next.id());
                        Location destination = next.location().clone().add(0.0D, 1.0D, 0.0D);
                        c.services().effects().line(origin, destination, 0.35D,
                                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL);
                        ratio *= p.getDouble("bounceDecay", 0.7D);
                        hit(c, next, DamageElement.NONE, ratio);
                        origin = destination;
                    }
                });
        return c.success();
    }
}
