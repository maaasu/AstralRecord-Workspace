package io.github.maaasu.astralRecord.feature.skill.executor.active.wizard;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifierType;
import io.github.maaasu.astralRecord.feature.buff.model.BuffType;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 攻勢を抑えつつ被ダメージを軽減し、MP自然回復を高める表示付きの自己バフです。 */
public final class WizardManaVeilExecutor extends WizardSkillSupport {
    public static final String ID = "wizard_mana_veil";
    private static final String BUFF_ID = "skill:" + ID;
    private static final String BUFF_TYPE = "BUFF";
    private static final int VISUAL_INTERVAL_TICKS = 5;
    private final StatusService statusService;

    /**
     * @param services 共通のタスク・演出サービス
     * @param statusService バフ付与・期限・ステータス再計算の正本
     */
    public WizardManaVeilExecutor(@NotNull ActiveSkillServices services, @NotNull StatusService statusService) {
        super(ID, services, "durationTicks", "incomingMultiplier", "outgoingMultiplier");
        this.statusService = statusService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        double manaRegenBonus = params.getDouble("manaRegenBonus", Double.NaN);
        if (!Double.isFinite(manaRegenBonus) || manaRegenBonus <= 0.0D || manaRegenBonus > 100.0D) {
            throw new SkillParameterException("manaRegenBonus", "MP回復力の加算は0より大きく100以下が必要です");
        }
        if (params.getDouble("outgoingMultiplier", Double.NaN) > 1.0D) {
            throw new SkillParameterException("outgoingMultiplier", "与ダメージ倍率は1以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        BuffType type = new BuffType(BUFF_ID, BUFF_TYPE, context.source().skill().getName(),
                (int) value(context, "durationTicks"), false, true, BUFF_ID, List.of(
                new BuffModifier(StatusType.DAMAGE_REDUCTION_RATE, BuffModifierType.FLAT,
                        BigDecimal.ONE.subtract(BigDecimal.valueOf(value(context, "incomingMultiplier")))
                                .movePointRight(2).setScale(8, RoundingMode.HALF_UP).doubleValue()),
                new BuffModifier(StatusType.FINAL_DAMAGE_MULTIPLIER, BuffModifierType.FINAL_SCALAR,
                        BigDecimal.valueOf(value(context, "outgoingMultiplier")).subtract(BigDecimal.ONE).doubleValue()),
                new BuffModifier(StatusType.MP_REGEN, BuffModifierType.FLAT, value(context, "manaRegenBonus"))));
        ActiveBuff buff = statusService.applyBuff(context.caster().player(), type);
        var world = context.player().getWorld();
        long remainingMillis = Math.max(0L, Duration.between(LocalDateTime.now(), buff.getExpiresAt()).toMillis());
        int executions = (int) Math.min(Integer.MAX_VALUE, remainingMillis / (VISUAL_INTERVAL_TICKS * 50L) + 2L);
        drawVeil(context, 0);
        context.services().tasks().repeat(context.player().getUniqueId(), BUFF_ID,
                VISUAL_INTERVAL_TICKS, VISUAL_INTERVAL_TICKS, executions, index -> {
                    if (!context.player().isOnline() || context.player().isDead()
                            || context.player().getWorld() != world
                            || context.caster().player().getStatusSnapshot().getCurrentHp() <= 0.0D
                            || !isCurrentBuff(context, buff)) {
                        context.services().tasks().cancel(context.player().getUniqueId(), BUFF_ID);
                        return;
                    }
                    drawVeil(context, index + 1);
                }, () -> {
                    if (isCurrentBuff(context, buff)) {
                        statusService.removeBuff(context.caster().player(), BUFF_ID);
                    } else if (buff.isExpired(LocalDateTime.now())) {
                        // 他のステータス照会が先に一覧から削除していても、失効した補正を残しません。
                        statusService.refreshStatus(context.caster().player());
                    }
                    if (buff.isExpired(LocalDateTime.now()) && context.player().isOnline()
                            && !context.player().isDead() && context.player().getWorld() == world) {
                        Location center = context.player().getLocation().add(0.0D, 0.9D, 0.0D);
                        context.services().effects().ring(center, 0.45D, 16, SharedParticleDefinitions.WIZARD_MANA_VEIL_SHELL);
                        context.services().effects().sound(center, Sound.BLOCK_AMETHYST_CLUSTER_BREAK, 0.35F, 1.7F);
                    }
                });
        return context.success();
    }

    /** 今回付与した個体だけを追跡し、解除・期限切れ・再付与後の描画を止めます。 */
    private boolean isCurrentBuff(PlayerActiveSkillContext context, ActiveBuff expected) {
        return statusService.getActiveBuffs(context.caster().player()).stream().anyMatch(buff -> buff == expected);
    }

    /** 身体に追従する三段の魔力殻と、上昇する回復光を5tickごとに一括描画します。 */
    private static void drawVeil(PlayerActiveSkillContext context, int frame) {
        Location base = context.player().getLocation();
        List<Location> shell = new ArrayList<>(60);
        double rotation = frame * 0.16D;
        for (int band = 0; band < 3; band++) {
            double radius = band == 1 ? 0.95D : 0.68D;
            for (int point = 0; point < 12; point++) {
                double angle = rotation + Math.PI * 2.0D * point / 12;
                shell.add(base.clone().add(Math.cos(angle) * radius, 0.25D + band * 0.65D, Math.sin(angle) * radius));
            }
        }
        for (int arc = 0; arc < 4; arc++) {
            double angle = rotation + Math.PI * 0.5D * arc;
            for (int point = 0; point < 6; point++) {
                double latitude = -Math.PI * 0.4D + Math.PI * 0.8D * point / 5;
                double radius = Math.cos(latitude) * 0.95D;
                shell.add(base.clone().add(Math.cos(angle) * radius, 0.9D + Math.sin(latitude) * 0.85D,
                        Math.sin(angle) * radius));
            }
        }
        context.services().effects().points(base, shell, SharedParticleDefinitions.WIZARD_MANA_VEIL_SHELL);
        List<Location> lights = new ArrayList<>(8);
        for (int point = 0; point < 8; point++) {
            double angle = -rotation * 2.0D + Math.PI * 2.0D * point / 8;
            double height = ((frame + point * 3) % 20) / 20.0D * 1.8D;
            lights.add(base.clone().add(Math.cos(angle) * 0.78D, height, Math.sin(angle) * 0.78D));
        }
        context.services().effects().points(base, lights, SharedParticleDefinitions.WIZARD_MANA_VEIL_RECOVERY);
    }
}
