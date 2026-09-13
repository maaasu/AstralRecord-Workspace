package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 自身のシールドを消費し、仲間への一時シールドまたは対象不在時の攻撃聖柱へ変換する発動スキルです。 */
public final class PaladinShieldExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_shield";
    private static final double DEFAULT_MINIMUM_CURRENT_SHIELD = 2.0D;
    private static final double DEFAULT_TRANSFER_RATIO = 0.50D;
    private static final double DEFAULT_TARGET_RANGE = 30.0D;
    private static final int DEFAULT_TEMPORARY_SHIELD_DURATION_TICKS = 400;
    private static final double DEFAULT_PILLAR_HEIGHT = 5.0D;
    private static final double DEFAULT_PILLAR_DAMAGE_RATIO = 0.50D;
    private static final double DEFAULT_PILLAR_RADIUS = 15.0D;
    private static final int DEFAULT_PILLAR_IMPACT_INTERVAL_TICKS = 20;
    private static final int DEFAULT_PILLAR_MAX_TARGETS = 1;
    private static final double DEFAULT_PILLAR_SLASH_RING_RADIUS = 1.0D;
    private static final int AURA_PARTICLE_POINTS = 24;
    private final StatusService statusService;
    private final PartyService partyService;
    private final PaladinHolySmiteRuntimeService holySmiteRuntimeService;

    /**
     * 共有発動スキルサービス、ステータスサービス、パーティーサービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param statusService 一時シールド付与と現在シールド消費を行うサービス
     * @param partyService 付与対象を解決するパーティーサービス
     * @param holySmiteRuntimeService 聖柱の検索と残り持続時間を管理するサービス
     */
    public PaladinShieldExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull StatusService statusService,
            @NotNull PartyService partyService,
            @NotNull PaladinHolySmiteRuntimeService holySmiteRuntimeService
    ) {
        super(ID, services);
        this.statusService = statusService;
        this.partyService = partyService;
        this.holySmiteRuntimeService = holySmiteRuntimeService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "minimumCurrentShield");
        requireRatio(params, "transferRatio");
        requirePositive(params, "targetRange");
        requirePositiveInt(params, "temporaryShieldDurationTicks");
        requirePositive(params, "pillarHeight");
        requirePositive(params, "pillarDamageRatio");
        requirePositive(params, "pillarRadius");
        requirePositiveInt(params, "pillarImpactIntervalTicks");
        requirePositiveInt(params, "pillarMaxTargets");
        requirePositive(params, "pillarSlashRingRadius");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double minimumCurrentShield = params.getDouble("minimumCurrentShield", DEFAULT_MINIMUM_CURRENT_SHIELD);
        double transferRatio = params.getDouble("transferRatio", DEFAULT_TRANSFER_RATIO);
        double targetRange = params.getDouble("targetRange", DEFAULT_TARGET_RANGE);
        int temporaryShieldDurationTicks = params.getInt(
                "temporaryShieldDurationTicks", DEFAULT_TEMPORARY_SHIELD_DURATION_TICKS);
        double pillarHeight = params.getDouble("pillarHeight", DEFAULT_PILLAR_HEIGHT);
        AstPlayer caster = context.caster().player();
        double currentShield = statusService.getStatus(caster).getCurrentShield();
        if (currentShield < minimumCurrentShield) {
            return SkillCastResult.failure(PlayerMsgId.P_5881);
        }

        double transferredShield = currentShield * transferRatio;
        statusService.consumeShield(caster, transferredShield);
        renderCasterAura(context);
        List<Player> targets = findTargets(context.player(), targetRange);
        if (targets.isEmpty()) {
            buildFallbackHolyPillar(context, params, pillarHeight, temporaryShieldDurationTicks);
            return context.success();
        }
        for (Player target : targets) {
            statusService.grantTemporaryShield(
                    AstPlayerCache.get(target), transferredShield, temporaryShieldDurationTicks);
            context.services().effects().line(
                    context.player().getEyeLocation(),
                    target.getEyeLocation(),
                    0.35D,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMASH_END_ROD
            );
        }
        return context.success();
    }

    private @NotNull List<Player> findTargets(@NotNull Player caster, double targetRange) {
        Party party = partyService.findParty(caster.getUniqueId());
        if (party == null) {
            return List.of();
        }
        double maxDistanceSquared = targetRange * targetRange;
        List<Player> targets = new ArrayList<>();
        for (UUID memberId : party.members()) {
            if (memberId.equals(caster.getUniqueId())) {
                continue;
            }
            Player member = Bukkit.getPlayer(memberId);
            if (member == null || !member.isOnline() || member.getWorld() != caster.getWorld()) {
                continue;
            }
            if (member.getLocation().distanceSquared(caster.getLocation()) <= maxDistanceSquared) {
                targets.add(member);
            }
        }
        return List.copyOf(targets);
    }

    private void renderCasterAura(@NotNull PlayerActiveSkillContext context) {
        Location center = context.player().getLocation().clone().add(0.0D, 1.0D, 0.0D);
        context.services().effects().ring(
                center,
                0.85D,
                AURA_PARTICLE_POINTS,
                SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
        );
    }

    /**
     * 付与対象がいない場合の聖柱を生成し、指定期間だけ表示と周期攻撃を実行します。
     *
     * @param context 発動スキル実行コンテキスト
     * @param params パラディンシールドの解決済みパラメータ
     * @param pillarHeight 聖柱の高さ
     * @param durationTicks 聖柱の持続tick
     */
    private void buildFallbackHolyPillar(
            @NotNull PlayerActiveSkillContext context,
            @NotNull SkillParamReader params,
            double pillarHeight,
            int durationTicks
    ) {
        Location center = context.services().targeting().groundAt(context.player().getLocation(), 3, 16);
        PaladinHolySmiteExecutor.HolyPillarState state = new PaladinHolySmiteExecutor.HolyPillarState(center, pillarHeight);
        UUID pillarId = UUID.randomUUID();
        String scope = ID + ":fallback:" + pillarId;
        try {
            state.spawnDisplays();
            holySmiteRuntimeService.register(pillarId, state, durationTicks, false);
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    Integer.MAX_VALUE,
                    tick -> {
                        renderFallbackHolyPillar(context, params, state, tick);
                        if (!holySmiteRuntimeService.consumeTick(pillarId)) {
                            context.services().tasks().cancel(context.player().getUniqueId(), scope);
                        }
                    },
                    () -> {
                        holySmiteRuntimeService.unregister(pillarId);
                        state.destroy();
                    }
            );
        } catch (RuntimeException exception) {
            holySmiteRuntimeService.unregister(pillarId);
            state.destroy();
            throw exception;
        }
    }

    /**
     * 対象不在時の聖柱を1tick進め、表示更新と攻撃間隔に応じた断罪を実行します。
     *
     * @param context 発動スキル実行コンテキスト
     * @param params パラディンシールドの解決済みパラメータ
     * @param state 表示中の聖柱
     * @param tick 発動からの経過tick index
     */
    private void renderFallbackHolyPillar(
            @NotNull PlayerActiveSkillContext context,
            @NotNull SkillParamReader params,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState state,
            int tick
    ) {
        if (tick % 4 == 0) {
            state.updateDisplays(tick);
        }
        if ((tick & 1) == 0) {
            context.services().effects().line(
                    state.center(),
                    state.center().add(0.0D, state.pillarHeight(), 0.0D),
                    0.40D,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
            );
        }
        if (tick % 4 == 0) {
            context.services().effects().ring(
                    state.center().add(0.0D, 0.10D, 0.0D),
                    state.altarRadius() + 0.35D,
                    24,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_SMITE_DUST
            );
        }
        if (tick % 6 == 0) {
            context.services().effects().ring(
                    state.center().add(0.0D, 1.65D, 0.0D),
                    state.altarRadius(),
                    16,
                    SharedParticleDefinitions.TELEPORTER_UNLOCK_RING_END_ROD
            );
        }
        int impactIntervalTicks = params.getInt(
                "pillarImpactIntervalTicks", DEFAULT_PILLAR_IMPACT_INTERVAL_TICKS);
        if (tick % impactIntervalTicks == 0) {
            PaladinHolySmiteExecutor.impact(
                    context,
                    state,
                    params.getDouble("pillarRadius", DEFAULT_PILLAR_RADIUS),
                    params.getInt("pillarMaxTargets", DEFAULT_PILLAR_MAX_TARGETS),
                    params.getDouble("pillarDamageRatio", DEFAULT_PILLAR_DAMAGE_RATIO),
                    params.getDouble("pillarSlashRingRadius", DEFAULT_PILLAR_SLASH_RING_RADIUS)
            );
        }
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "パラディンシールドの params[" + key + "] は正数が必要です");
        }
    }

    private static void requireRatio(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!(value > 0.0D && value <= 1.0D)) {
            throw new SkillParameterException(key, "パラディンシールドの params[" + key + "] は0より大きく1以下が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "パラディンシールドの params[" + key + "] は1以上の整数が必要です");
        }
    }
}
