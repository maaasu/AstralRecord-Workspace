package io.github.maaasu.astralRecord.feature.skill.executor;

import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.model.PassiveSkillStatusModifier;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastContext;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillKind;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.model.StatusModifierType;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.ShieldRechargeState;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** implementationId {@code administrator_shield_recharge} の管理者専用シールド再充填パッシブです。 */
public final class AdministratorShieldRechargeSkillExecutor implements SkillExecutor {
    public static final String ID = "administrator_shield_recharge";
    private final StatusService statusService;
    private final ParticleDisplayService particleDisplayService;

    /**
     * 再充填設定と表示サービスを受け取って executor を構築します。
     *
     * @param statusService シールド状態サービス
     * @param particleDisplayService 共通パーティクル表示サービス
     */
    public AdministratorShieldRechargeSkillExecutor(@NotNull StatusService statusService, @NotNull ParticleDisplayService particleDisplayService) {
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
    }

    @Override public @NotNull String implementationId() { return ID; }
    @Override public @NotNull SkillKind kind() { return SkillKind.PASSIVE; }
    @Override public @NotNull SkillCastResult cast(@NotNull SkillCastContext context) { return SkillCastResult.failure(null); }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "maxShield");
        requirePositive(params, "rechargeDelaySeconds");
        requirePositive(params, "rechargePercentPerSecond");
        requirePositiveInt(params, "particleIntervalTicks");
    }

    @Override public void onActivate(@NotNull PassiveSkillContext context) { configure(context); }
    @Override public void onDeactivate(@NotNull PassiveSkillContext context) { statusService.clearShieldRechargeConfiguration(context.player()); }

    @Override
    public void onTick(@NotNull PassiveSkillContext context) {
        configure(context);
        int interval = Math.max(1, new SkillParamReader(context.skill().getId(), context.skill().getParams()).getInt("particleIntervalTicks", 10));
        ShieldRechargeState state = statusService.getShieldRechargeState(context.player());
        if (context.activeTicks() % interval != 0L || state == null || state.remainingMs(System.currentTimeMillis()) > 0L) return;
        renderRecharge(context);
    }

    @Override public boolean requiresPassiveTick() { return true; }
    @Override public long passiveTickIntervalTicks() { return 1L; }

    @Override
    public @NotNull List<PassiveSkillStatusModifier> passiveStatusModifiers(@NotNull PassiveSkillContext context) {
        double maxShield = new SkillParamReader(context.skill().getId(), context.skill().getParams()).getDouble("maxShield", 0.0D);
        return List.of(new PassiveSkillStatusModifier(StatusType.MAX_SHIELD, StatusModifierType.FLAT, maxShield));
    }

    private void configure(@NotNull PassiveSkillContext context) {
        SkillParamReader params = new SkillParamReader(context.skill().getId(), context.skill().getParams());
        statusService.configureShieldRecharge(context.player(), params.getDouble("rechargeDelaySeconds", StatusService.PLAYER_SHIELD_RECHARGE_SECONDS), params.getDouble("rechargePercentPerSecond", 0.0D));
    }

    private void renderRecharge(@NotNull PassiveSkillContext context) {
        Location center = context.player().getBukkit().getLocation().clone().add(0.0D, 1.0D, 0.0D);
        List<Location> locations = new ArrayList<>(6);
        double phase = context.activeTicks() * 0.18D;
        for (int index = 0; index < 6; index++) {
            double angle = phase + Math.PI * 2.0D * index / 6.0D;
            locations.add(center.clone().add(Math.cos(angle) * 0.75D, Math.sin(angle) * 0.22D, Math.sin(angle) * 0.75D));
        }
        particleDisplayService.spawnForNearbyViewers(center, locations, SharedParticleDefinitions.SHIELD_RECHARGE_DUST);
    }

    private void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getDouble(key, 0.0D) <= 0.0D) throw new SkillParameterException(key, "正の数値を指定してください");
    }

    private void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) <= 0) throw new SkillParameterException(key, "1以上の整数を指定してください");
    }
}
