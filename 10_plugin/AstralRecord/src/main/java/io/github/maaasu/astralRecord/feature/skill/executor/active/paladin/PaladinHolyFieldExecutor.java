package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

/** 白い範囲を維持し、発動者とパーティーメンバーのシールドブレイクを補正する発動スキルです。 */
public final class PaladinHolyFieldExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_holy_field";
    private static final double DEFAULT_RADIUS = 2.0D;
    private static final int DEFAULT_DURATION_TICKS = 20;
    private static final double DEFAULT_CASTER_SHIELD_BREAK = 1.0D;
    private static final double DEFAULT_PARTY_SHIELD_BREAK = -10.0D;
    private static final int PARTICLE_INTERVAL_TICKS = 4;
    private static final int PARTICLE_POINTS_PER_BLOCK = 8;
    private final PaladinHolyFieldRuntimeService runtimeService;

    /**
     * 共有発動スキルサービスとフィールド実行時状態サービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param runtimeService フィールド実行時状態サービス
     */
    public PaladinHolyFieldExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinHolyFieldRuntimeService runtimeService
    ) {
        super(ID, services);
        this.runtimeService = runtimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "radius");
        requirePositiveInt(params, "durationTicks");
        requirePositive(params, "casterShieldBreak");
        if (!(params.getDouble("partyShieldBreak", Double.NaN) < 0.0D)) {
            throw new SkillParameterException("partyShieldBreak", "ホーリーフィールドの params[partyShieldBreak] は負数が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double radius = params.getDouble("radius", DEFAULT_RADIUS);
        int durationTicks = params.getInt("durationTicks", DEFAULT_DURATION_TICKS);
        double casterShieldBreak = params.getDouble("casterShieldBreak", DEFAULT_CASTER_SHIELD_BREAK);
        double partyShieldBreak = params.getDouble("partyShieldBreak", DEFAULT_PARTY_SHIELD_BREAK);
        Location center = context.player().getLocation().clone();
        String scope = ID;
        context.services().tasks().cancel(context.player().getUniqueId(), scope);
        runtimeService.start(
                context.caster().player(), scope, center, radius, durationTicks, casterShieldBreak, partyShieldBreak
        );
        try {
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    Integer.MAX_VALUE,
                    tick -> advance(context, tick, scope),
                    () -> runtimeService.end(context.player().getUniqueId())
            );
        } catch (RuntimeException exception) {
            runtimeService.end(context.player().getUniqueId());
            throw exception;
        }
        return context.success();
    }

    private void advance(@NotNull PlayerActiveSkillContext context, int tick, @NotNull String scope) {
        if (!runtimeService.advance(context.player().getUniqueId())) {
            context.services().tasks().cancel(context.player().getUniqueId(), scope);
            return;
        }
        if (tick % PARTICLE_INTERVAL_TICKS != 0) {
            return;
        }
        PaladinHolyFieldRuntimeService.FieldSnapshot field = runtimeService.snapshot(context.player().getUniqueId());
        if (field == null) {
            return;
        }
        int points = Math.max(16, (int) Math.ceil(field.radius() * PARTICLE_POINTS_PER_BLOCK));
        context.services().effects().ring(
                field.center().add(0.0D, 0.08D, 0.0D),
                field.radius(),
                points,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ホーリーフィールドの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ホーリーフィールドの params[" + key + "] は1以上の整数が必要です");
        }
    }
}
