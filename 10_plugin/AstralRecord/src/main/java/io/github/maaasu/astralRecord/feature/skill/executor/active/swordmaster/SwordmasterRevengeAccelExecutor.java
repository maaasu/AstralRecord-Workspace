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
import org.jetbrains.annotations.NotNull;

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
        slash(context, origin, context.direction(), value(context, "range"),
                empowered ? SharedParticleDefinitions.SWORDMASTER_GOLD : SharedParticleDefinitions.SWORDMASTER_SILVER,
                true);
        boolean landed = false;
        double ratio = value(context, "damageRatio") * (empowered ? value(context, "riposteMultiplier") : 1.0D);
        for (AstEntity target : cone(context)) {
            landed |= hit(context, target, DamageElement.NONE, ratio).landed();
        }
        if (empowered && landed) {
            skills.clearCooldown(context.player().getUniqueId(), SwordmasterGaleReaperExecutor.ID);
            skills.clearCooldown(context.player().getUniqueId(), SwordmasterCrimsonDriveExecutor.ID);
            burst(context, origin, 1.6D, SharedParticleDefinitions.SWORDMASTER_GOLD);
            context.services().effects().sound(origin, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 1.9F);
        }
        return context.success();
    }
}
