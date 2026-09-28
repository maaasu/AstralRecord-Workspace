package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

/** 安全な後退の出発地点へ三段の残像斬撃を残します。 */
public final class SwordmasterMirageRetreatExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_mirage_retreat";

    /**
     * 後退・残像攻撃の共有サービスを設定します。
     * @param services 発動スキル共有サービス
     */
    public SwordmasterMirageRetreatExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "range", "radius", "maxTargets", "damageRatio", "hitCount", "intervalTicks");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Vector facing = context.direction();
        var movement = context.services().movement().backstep(
                context.player(), context.attacker(), value(context, "range"));
        if (!movement.moved()) return SkillCastResult.failure(PlayerMsgId.P_5805);
        Location echo = movement.start().clone().add(0.0D, 0.8D, 0.0D);
        trail(context, echo, movement.end().clone().add(0.0D, 0.8D, 0.0D),
                SharedParticleDefinitions.SWORDMASTER_VIOLET);
        context.services().effects().sound(echo, Sound.ENTITY_ENDERMAN_TELEPORT, 0.45F, 1.7F);
        sequence(context, (int) value(context, "hitCount"), (int) value(context, "intervalTicks"), frame -> {
            slash(context, echo, facing.clone().rotateAroundY(frame * Math.PI * 2.0D / 3.0D),
                    value(context, "radius"), SharedParticleDefinitions.SWORDMASTER_VIOLET, frame % 2 == 0);
            context.services().effects().ring(echo, value(context, "radius"), 32,
                    SharedParticleDefinitions.SWORDMASTER_SILVER);
            for (AstEntity target : sphere(context, echo, value(context, "radius"))) {
                hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
            }
        });
        return context.success();
    }
}
