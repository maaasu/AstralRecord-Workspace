package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;


import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 剣気を蓄積し、既に剣気があると回復とENG補給も増す明鏡止水です。 */
public final class SwordmasterSereneMendExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_serene_mend";
    private final SeijakuIssenSkillRuntimeService counter;

    /**
     * 共有サービスと反撃の剣気管理元で初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     * @param counter 静寂一閃の反撃成功状態
     */
    public SwordmasterSereneMendExecutor(@NotNull ActiveSkillServices services, @NotNull SeijakuIssenSkillRuntimeService counter) {
        super(ID, services, "healHpRatio", "riposteHealHpRatio", "energyRecovery", "riposteGain");
        this.counter = counter;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        double gain = new SkillParamReader(skill.getId(), skill.getParams()).getDouble("riposteGain", 0.0D);
        if (gain != Math.rint(gain) || gain > 10.0D) {
            throw new SkillParameterException("riposteGain", "剣気の獲得量は1〜10の整数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean empowered = counter.riposteStacks(context.caster().player()) > 0;
        heal(context, value(context, "healHpRatio") + (empowered ? value(context, "riposteHealHpRatio") : 0.0D));
        if (empowered) context.services().combat().recoverEnergy(context.caster().player(), value(context, "energyRecovery"));
        int gained = counter.addRiposte(context.caster().player(), (int) value(context, "riposteGain"));
        Location center = context.player().getLocation().add(0.0D, 0.3D, 0.0D);
        sequence(context, 4, 3, frame -> {
            context.services().effects().ring(center.clone().add(0.0D, frame * 0.35D, 0.0D),
                    1.2D - frame * 0.18D, 28, gained > 0 && frame == 3 ? SharedParticleDefinitions.SWORDMASTER_GOLD
                            : SharedParticleDefinitions.SWORDMASTER_JADE);
        });
        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_BLOCK_RESONATE, 1.0F, gained > 0 ? 1.5F : 0.9F);
        return context.success();
    }
}
