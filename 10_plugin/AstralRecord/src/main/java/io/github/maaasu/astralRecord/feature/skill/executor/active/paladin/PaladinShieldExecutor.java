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

/** 自身のシールドを消費し、近くのパーティーメンバーへ一時シールドを配る発動スキルです。 */
public final class PaladinShieldExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_shield";
    private static final double DEFAULT_MINIMUM_CURRENT_SHIELD = 2.0D;
    private static final double DEFAULT_TRANSFER_RATIO = 0.50D;
    private static final double DEFAULT_TARGET_RANGE = 30.0D;
    private static final int DEFAULT_TEMPORARY_SHIELD_DURATION_TICKS = 400;
    private static final double DEFAULT_PILLAR_HEIGHT = 5.0D;
    private static final int AURA_PARTICLE_POINTS = 24;
    private final StatusService statusService;
    private final PartyService partyService;

    /**
     * 共有発動スキルサービス、ステータスサービス、パーティーサービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param statusService 一時シールド付与と現在シールド消費を行うサービス
     * @param partyService 付与対象を解決するパーティーサービス
     */
    public PaladinShieldExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull StatusService statusService,
            @NotNull PartyService partyService
    ) {
        super(ID, services);
        this.statusService = statusService;
        this.partyService = partyService;
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
            buildFallbackHolyPillar(context, pillarHeight, temporaryShieldDurationTicks);
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

    private void buildFallbackHolyPillar(
            @NotNull PlayerActiveSkillContext context,
            double pillarHeight,
            int durationTicks
    ) {
        Location center = context.services().targeting().groundAt(context.player().getLocation(), 3, 16);
        PaladinHolySmiteExecutor.HolyPillarState state = new PaladinHolySmiteExecutor.HolyPillarState(center, pillarHeight);
        String scope = ID + ":fallback:" + UUID.randomUUID();
        try {
            state.spawnDisplays();
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    durationTicks,
                    tick -> renderFallbackHolyPillar(context, state, tick),
                    state::destroy
            );
        } catch (RuntimeException exception) {
            state.destroy();
            throw exception;
        }
    }

    private void renderFallbackHolyPillar(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState state,
            int tick
    ) {
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
