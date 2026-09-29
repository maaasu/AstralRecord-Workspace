package io.github.maaasu.astralRecord.feature.skill.executor.active.archmage;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;
import java.util.UUID;

/** 現存陣数で範囲が広がり、敵を押し出す重力爆発です。 */
public final class ArchmageGravityNovaExecutor extends ArchmageStrikeSkillExecutor {
    public static final String ID = "archmage_gravity_nova";

    /** @param services 共通発動サービス */
    public ArchmageGravityNovaExecutor(@NotNull ActiveSkillServices services) { super(ID, services); }

    /** {@inheritDoc} */
    @Override
    protected void strike(@NotNull PlayerActiveSkillContext context, @NotNull Location center,
                          double radius, double power, int circles) {
        double expanded = radius + circles * 0.75D;
        ArchmageBuildSupport.drawCircle(context, center, expanded, true);
        for (AstEntity target : context.services().targeting().inRadius(center, expanded, 3.0D, 8, true)) {
            DamageResult result = hitWithoutNormalKnockback(context, target, power);
            if (!result.evaded() && (result.finalDamage() > 0.0D || result.shieldDamage() > 0.0D)
                    && target.currentHealth() > 0.0D) {
                context.services().combat().knockback(target, center, 0.75D, 0.2D);
            }
        }
    }

    /** 通常の押し出しだけを同期命中中に抑止し、他の効果を保って直ちに解除します。 */
    private DamageResult hitWithoutNormalKnockback(PlayerActiveSkillContext context, AstEntity target, double power) {
        String effectId = ID + ":impact:" + UUID.randomUUID();
        context.services().temporaryEffects().apply(target.id(), effectId, 200L, 1.0D, 1.0D, 0.0D);
        try {
            return context.services().combat().hit(context.source().skill(), context.attacker(), target,
                    AttackType.MAGIC, DamageElement.NONE, power);
        } finally {
            context.services().temporaryEffects().clear(target.id(), effectId);
        }
    }
}
