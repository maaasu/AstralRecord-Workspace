package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/** 現存陣数に応じた星弾を近い敵へ分配します。 */
public final class ArchmageOrbitalVolleyExecutor extends ArchmageStrikeSkillExecutor {
    public static final String ID = "archmage_orbital_volley";

    /** @param services 共通発動サービス */
    public ArchmageOrbitalVolleyExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override
    protected void strike(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                          double radius, double power, int circles) {
        UUID casterId = context.player().getUniqueId();
        String scope = ID + ":volley:" + UUID.randomUUID();
        context.services().tasks().repeat(casterId, scope, 0L, 3L, 2 + circles, index -> {
            if (!context.player().isOnline() || context.player().isDead()
                    || context.player().getWorld() != center.getWorld()
                    || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D) {
                context.services().tasks().cancel(casterId, scope);
                return;
            }
            List<AstEntity> targets = context.services().targeting().inRadius(center, radius, 3.0D, 4, true);
            if (targets.isEmpty()) {
                return;
            }
            AstEntity target = targets.get(index % targets.size());
            Location impact = context.services().targeting().center(target);
            context.services().effects().point(impact, SharedParticleDefinitions.SKILL_ASTRAL_RAY_IMPACT);
            context.services().combat().hit(context.source().skill(), context.attacker(), target,
                    AttackType.MAGIC, DamageElement.NONE, power);
        });
    }
}
