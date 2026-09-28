package io.github.maaasu.astralRecord.feature.skill.executor.active.swordmaster;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 周囲を斬り、命中して生き残った敵を手元へ引き寄せる剣技です。 */
public final class SwordmasterVortexEdgeExecutor extends SwordmasterSkillSupport {
    public static final String ID = "swordmaster_vortex_edge";

    /**
     * 範囲攻撃と耐性を尊重した引き寄せに必要なサービスを設定します。
     * @param services 発動スキル共有サービス
     */
    public SwordmasterVortexEdgeExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services, "radius", "maxTargets", "damageRatio", "pullStrength", "stopDistance");
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        if (params.getDouble("pullStrength", 0.0D) > 1.5D
                || params.getDouble("stopDistance", 0.0D) >= params.getDouble("radius", 0.0D)) {
            throw new SkillParameterException("pullStrength", "引き寄せ速度は1.5以下、停止距離は半径未満が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().add(0.0D, 0.8D, 0.0D);
        List<Location> spiral = new ArrayList<>(96);
        for (int arm = 0; arm < 3; arm++) {
            for (int point = 0; point < 32; point++) {
                double progress = point / 31.0D;
                double angle = arm * Math.PI * 2.0D / 3.0D + progress * Math.PI * 1.5D;
                double radius = value(context, "radius") * (1.0D - progress * 0.85D);
                spiral.add(center.clone().add(Math.cos(angle) * radius,
                        Math.sin(progress * Math.PI) * 0.45D, Math.sin(angle) * radius));
            }
        }
        context.services().effects().points(center, spiral, SharedParticleDefinitions.SWORDMASTER_VIOLET);
        context.services().effects().ring(center, value(context, "radius"), 40, SharedParticleDefinitions.SWORDMASTER_SILVER);
        context.services().effects().sound(center, Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0F, 0.65F);
        for (AstEntity target : sphere(context, center, value(context, "radius"))) {
            Hit result = hitWithoutOutwardKnockback(context, target);
            if (!result.landed() || target.currentHealth() <= 0.0D) continue;
            Vector toward = center.toVector().subtract(target.location().toVector()).setY(0.0D);
            double distance = toward.length();
            if (distance <= value(context, "stopDistance")) continue;
            double speed = Math.min(value(context, "pullStrength"),
                    (distance - value(context, "stopDistance")) * 0.5D);
            context.services().combat().velocity(target, toward.multiply(speed / distance).setY(0.08D));
            context.services().effects().line(target.location().clone().add(0.0D, 0.8D, 0.0D),
                    center, 0.3D, SharedParticleDefinitions.SWORDMASTER_VIOLET);
        }
        return context.success();
    }

    /**
     * この同期命中の押し出しだけを抑え、引き寄せ用の受付枠を温存します。
     * 他の一時効果を上書きせず、正常終了・例外のどちらでも直ちに解除します。
     * @param context 発動時の共有サービスと倍率
     * @param target 命中対象
     * @return 回避・撃破を含む実際の命中結果
     */
    private Hit hitWithoutOutwardKnockback(PlayerActiveSkillContext context, AstEntity target) {
        String effectId = ID + ":impact:" + UUID.randomUUID();
        // 有効期限は保険とし、命中処理が完了した時点でfinallyから同期解除します。
        context.services().temporaryEffects().apply(target.id(), effectId, 200L, 1.0D, 1.0D, 0.0D);
        try {
            return hit(context, target, DamageElement.NONE, value(context, "damageRatio"));
        } finally {
            context.services().temporaryEffects().clear(target.id(), effectId);
        }
    }
}
