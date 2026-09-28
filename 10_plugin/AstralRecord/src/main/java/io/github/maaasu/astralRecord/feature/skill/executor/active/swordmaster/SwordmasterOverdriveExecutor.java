package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

/** 被ダメージ増加と引き換えに短時間の攻撃を強化します。 */
public final class SwordmasterOverdriveExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_overdrive";
    private static final int VISUAL_INTERVAL_TICKS = 10;

    /**
     * 自己強化と有限の演出を適用するサービスを設定します。
     * @param services 発動スキル共有サービス
     */
    public SwordmasterOverdriveExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "durationTicks", "outgoingMultiplier", "incomingMultiplier");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        int duration = (int) value(context, "durationTicks");
        context.services().temporaryEffects().apply(context.player().getUniqueId(), ID, duration,
                value(context, "incomingMultiplier"), value(context, "outgoingMultiplier"), 1.0D);
        Location center = context.player().getLocation().add(0.0D, 0.7D, 0.0D);
        burst(context, center, 1.5D, SharedParticleDefinitions.SWORDMASTER_FIRE);
        context.services().effects().sound(center, Sound.ENTITY_BLAZE_SHOOT, 0.6F, 1.3F);
        int pulses = (duration + VISUAL_INTERVAL_TICKS - 1) / VISUAL_INTERVAL_TICKS;
        sequence(context, pulses, VISUAL_INTERVAL_TICKS, frame -> {
            Location current = context.player().getLocation().add(0.0D, 0.35D, 0.0D);
            context.services().effects().ring(current, 0.75D, 24, SharedParticleDefinitions.SWORDMASTER_FIRE);
            slash(context, current.clone().add(0.0D, 0.5D, 0.0D),
                    context.direction().rotateAroundY(frame * Math.PI / 2.0D),
                    1.0D, SharedParticleDefinitions.SWORDMASTER_GOLD, true);
        });
        return context.success();
    }
}
