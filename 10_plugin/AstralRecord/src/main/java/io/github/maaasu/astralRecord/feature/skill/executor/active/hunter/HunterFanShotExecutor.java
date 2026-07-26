package io.github.maaasu.astralRecord.feature.skill.executor.active.hunter;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 五本の矢を扇状に放つハンターの扇射ちです。 */
public final class HunterFanShotExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "hunter_fan_shot";
    private static final double[] ANGLES = {-24.0D, -12.0D, 0.0D, 12.0D, 24.0D};

    /** 共有発動スキルサービスで初期化します。 */
    public HunterFanShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location origin = context.eyeLocation();
        Set<UUID> hitIds = new HashSet<>();
        for (double angle : ANGLES) {
            Vector direction = context.direction().rotateAroundY(Math.toRadians(angle));
            Location end = context.services().targeting().clippedEnd(origin, direction, 10.0D);
            context.services().effects().line(origin, end, 0.65D, SharedParticleDefinitions.SKILL_HUNTER_ARROW);
            context.services().targeting().inLine(context.player(), origin, direction, 10.0D, 0.45D, 1)
                    .stream()
                    .filter(target -> hitIds.add(target.id()))
                    .forEach(target -> context.services().combat().hit(
                            context.attacker(), target, AttackType.RANGED, DamageElement.NONE, 0.90D));
        }
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.0F, 1.15F);
        return context.success();
    }
}
