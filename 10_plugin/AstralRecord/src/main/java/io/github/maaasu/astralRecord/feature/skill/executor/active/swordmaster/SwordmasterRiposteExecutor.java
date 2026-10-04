package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.service.SeijakuIssenSkillRuntimeService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** カウンターの剣気を次の範囲斬撃へ乗せる返し刃です。 */
public final class SwordmasterRiposteExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_riposte";
    private final SeijakuIssenSkillRuntimeService counter;

    /**
     * 共有サービスと反撃の剣気管理元で初期化します。
     * @param services 戦闘・対象検索・有限タスクの共有サービス
     * @param counter 静寂一閃の反撃成功状態
     */
    public SwordmasterRiposteExecutor(@NotNull ActiveSkillServices services, @NotNull SeijakuIssenSkillRuntimeService counter) {
        super(ID, services, "range", "angle", "maxTargets", "damageRatio", "riposteMultiplier");
        this.counter = counter;
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        boolean empowered = counter.consumeRiposte(context.caster().player());
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        if (empowered) {
            Vector forward = direction.clone().setY(0.0D);
            if (forward.lengthSquared() < 1.0E-8D) {
                forward.setZ(1.0D);
            }
            Location crossCenter = origin.clone().add(forward.normalize().multiply(value(context, "range") * 0.6D));
            counterCross(context, crossCenter, direction, value(context, "range") * 0.4D, 0.9D);
        } else {
            context.services().effects().arcSegment(origin, direction, value(context, "range") * 0.75D,
                    -40.0D, 40.0D, 32, SharedParticleDefinitions.SWORDMASTER_SILVER);
        }
        context.services().effects().sound(origin, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 0.8F,
                empowered ? 1.5F : 1.0F);
        List<Location> impacts = new ArrayList<>();
        for (AstEntity target : cone(context)) {
            Location impact = empowered ? target.location().clone().add(0.0D, 0.9D, 0.0D) : null;
            Hit result = hit(context, target, DamageElement.NONE,
                    value(context, "damageRatio") * (empowered ? value(context, "riposteMultiplier") : 1.0D));
            if (impact != null && result.landed()) {
                impacts.add(impact);
            }
        }
        counterShockwaves(context, impacts);
        return context.success();
    }
}
