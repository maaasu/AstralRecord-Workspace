package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** 反撃の剣気を命中へつなぎ、二つの突進技を再使用可能にします。 */
public final class SwordmasterRevengeAccelExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_revenge_accel";
    private final SeijakuIssenSkillRuntimeService counter;
    private final SkillService skills;

    /**
     * 剣気と突進スキルのクールダウン管理元を設定します。
     * @param services 発動スキル共有サービス
     * @param counter 静寂一閃の剣気管理元
     * @param skills 発動者ごとのスキルクールダウン管理元
     */
    public SwordmasterRevengeAccelExecutor(@NotNull ActiveSkillServices services,
                                          @NotNull SeijakuIssenSkillRuntimeService counter,
                                          @NotNull SkillService skills) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "riposteMultiplier");
        this.counter = counter;
        this.skills = skills;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean empowered = counter.consumeRiposte(context.caster().player());
        Location origin = context.eyeLocation();
        Vector forward = context.direction().clone().setY(0.0D);
        if (forward.lengthSquared() < 1.0E-8D) {
            forward.setZ(1.0D);
        }
        forward.normalize();
        Location end = origin.clone().add(forward.clone().multiply(value(context, "range")));
        if (empowered) {
            context.services().effects().line(origin, end, 0.18D, SharedParticleDefinitions.SWORDMASTER_GOLD);
            counterCross(context, end, forward, 0.95D, 0.9D);
        } else {
            Vector right = new Vector(-forward.getZ(), 0.0D, forward.getX());
            context.services().effects().line(origin.clone().add(right.clone().multiply(-0.3D)).add(0.0D, 0.35D, 0.0D),
                    end.clone().add(right.multiply(0.3D)).add(0.0D, -0.35D, 0.0D),
                    0.18D, SharedParticleDefinitions.SWORDMASTER_SILVER);
        }
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F,
                empowered ? 1.6F : 1.1F);
        List<Location> impacts = new ArrayList<>();
        double ratio = value(context, "damageRatio") * (empowered ? value(context, "riposteMultiplier") : 1.0D);
        for (AstEntity target : cone(context)) {
            Location impact = empowered ? target.location().clone().add(0.0D, 0.9D, 0.0D) : null;
            Hit result = hit(context, target, DamageElement.NONE, ratio);
            if (impact != null && result.landed()) {
                impacts.add(impact);
            }
        }
        if (!impacts.isEmpty()) {
            skills.clearCooldown(context.player().getUniqueId(), SwordmasterGaleReaperExecutor.ID);
            skills.clearCooldown(context.player().getUniqueId(), SwordmasterCrimsonDriveExecutor.ID);
            counterShockwaves(context, impacts);
            context.services().effects().sound(impacts.get(0), Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.9F);
        }
        return context.success();
    }
}
