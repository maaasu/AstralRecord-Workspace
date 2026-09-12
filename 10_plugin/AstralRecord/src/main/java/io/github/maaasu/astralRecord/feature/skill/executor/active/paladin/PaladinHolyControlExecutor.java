package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
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
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.feature.status.model.HealthRecoveryContext;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 最寄りのホーリースマイト聖柱を操り、周囲の味方と敵へ継続効果を与える発動スキルです。 */
public final class PaladinHolyControlExecutor extends PlayerActiveSkillExecutor {

    public static final String ID = "paladin_holy_control";
    private static final double DEFAULT_HP_COST = 200.0D;
    private static final double DEFAULT_PILLAR_SEARCH_RADIUS = 20.0D;
    private static final double DEFAULT_EFFECT_RADIUS = 12.0D;
    private static final double DEFAULT_PARTY_DAMAGE_INCREASE = 5.0D;
    private static final double DEFAULT_MOB_DAMAGE_TAKEN_INCREASE = 5.0D;
    private static final double DEFAULT_HP_RECOVERY = 87.0D;
    private static final double DEFAULT_MP_RECOVERY_MAX_MANA_RATIO = 0.10D;
    private static final int EFFECT_INTERVAL_TICKS = 20;
    private static final int RING_INTERVAL_TICKS = 4;
    private static final int RING_POINTS_PER_BLOCK = 8;
    private final PaladinHolySmiteRuntimeService holySmiteRuntimeService;
    private final StatusService statusService;
    private final PartyService partyService;
    private final Map<PaladinHolySmiteExecutor.HolyPillarState, ControlState> controlsByPillar = new HashMap<>();

    /**
     * 共有サービスと聖柱・ステータス・パーティーの実行時サービスで初期化します。
     *
     * @param services 共有発動スキルサービス
     * @param holySmiteRuntimeService 発動中のホーリースマイト聖柱を管理するサービス
     * @param statusService HP・MPと一時バフを管理するサービス
     * @param partyService パーティー所属を解決するサービス
     */
    public PaladinHolyControlExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinHolySmiteRuntimeService holySmiteRuntimeService,
            @NotNull StatusService statusService,
            @NotNull PartyService partyService
    ) {
        super(ID, services);
        this.holySmiteRuntimeService = holySmiteRuntimeService;
        this.statusService = statusService;
        this.partyService = partyService;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "hpCost");
        requirePositive(params, "pillarSearchRadius");
        requirePositive(params, "effectRadius");
        requirePositive(params, "partyDamageIncrease");
        requirePositive(params, "mobDamageTakenIncrease");
        requirePositive(params, "hpRecovery");
        requirePositive(params, "mpRecoveryMaxManaRatio");
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double hpCost = params.getDouble("hpCost", DEFAULT_HP_COST);
        double searchRadius = params.getDouble("pillarSearchRadius", DEFAULT_PILLAR_SEARCH_RADIUS);
        double effectRadius = params.getDouble("effectRadius", DEFAULT_EFFECT_RADIUS);
        double partyDamageIncrease = params.getDouble("partyDamageIncrease", DEFAULT_PARTY_DAMAGE_INCREASE);
        double mobDamageTakenIncrease = params.getDouble(
                "mobDamageTakenIncrease", DEFAULT_MOB_DAMAGE_TAKEN_INCREASE);
        double hpRecovery = params.getDouble("hpRecovery", DEFAULT_HP_RECOVERY);
        double mpRecoveryMaxManaRatio = params.getDouble(
                "mpRecoveryMaxManaRatio", DEFAULT_MP_RECOVERY_MAX_MANA_RATIO);
        AstPlayer caster = context.caster().player();
        if (statusService.getStatus(caster).getCurrentHp() <= hpCost) {
            return SkillCastResult.failure(PlayerMsgId.P_5882);
        }

        PaladinHolySmiteExecutor.HolyPillarState pillar = holySmiteRuntimeService.findNearest(
                context.player().getLocation(), searchRadius);
        if (pillar == null) {
            statusService.consumeHp(caster, hpCost);
            return SkillCastResult.failureWithCostAndCooldown(PlayerMsgId.P_5883);
        }

        Location destination = context.services().targeting().groundAt(context.player().getLocation(), 3, 16);
        holySmiteRuntimeService.moveTo(pillar, destination);
        statusService.consumeHp(caster, hpCost);
        String scope = ID + ":" + UUID.randomUUID();
        ControlState control = new ControlState(context.player().getUniqueId(), scope);
        replaceControl(context, pillar, control);
        try {
            context.services().tasks().repeat(
                    context.player().getUniqueId(),
                    scope,
                    0L,
                    1L,
                    Integer.MAX_VALUE,
                    tick -> advance(
                            context,
                            pillar,
                            control,
                            tick,
                            effectRadius,
                            partyDamageIncrease,
                            mobDamageTakenIncrease,
                            hpRecovery,
                            mpRecoveryMaxManaRatio
                    ),
                    () -> clearControl(context, pillar, control)
            );
        } catch (RuntimeException exception) {
            clearControl(context, pillar, control);
            throw exception;
        }
        return context.success();
    }

    private void advance(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState pillar,
            @NotNull ControlState control,
            int tick,
            double effectRadius,
            double partyDamageIncrease,
            double mobDamageTakenIncrease,
            double hpRecovery,
            double mpRecoveryMaxManaRatio
    ) {
        if (controlsByPillar.get(pillar) != control || !holySmiteRuntimeService.isActive(pillar)) {
            context.services().tasks().cancel(control.casterId, control.scope);
            return;
        }
        Location center = pillar.center();
        if (tick % RING_INTERVAL_TICKS == 0) {
            int points = Math.max(24, (int) Math.ceil(effectRadius * RING_POINTS_PER_BLOCK));
            context.services().effects().ring(
                    center.clone().add(0.0D, 0.08D, 0.0D),
                    effectRadius,
                    points,
                    SharedParticleDefinitions.SKILL_PALADIN_HOLY_CONTROL_RING
            );
        }
        if (tick % EFFECT_INTERVAL_TICKS != 0) {
            return;
        }

        String effectId = control.scope + ":effect";
        HealthRecoveryContext recoveryContext = HealthRecoveryContext.by(
                context.caster().player(), SkillPresentationUtil.plainName(context.source().skill(), "スキル"));
        double mpRecovery = statusService.getStatus(context.caster().player()).getMaxValue(StatusType.MAX_MANA)
                * mpRecoveryMaxManaRatio;
        for (AstPlayer target : partyTargets(context.player(), center, effectRadius)) {
            UUID targetId = target.getBukkit().getUniqueId();
            String buffId = control.scope + ":buff:" + targetId;
            control.buffedPlayers.put(targetId, target);
            statusService.applyTemporaryFlatBuff(
                    target,
                    buffId,
                    "ホーリーコントロール",
                    StatusType.SKILL_DAMAGE_INCREASE,
                    partyDamageIncrease,
                    1L
            );
            context.services().combat().recoverHp(target, hpRecovery, recoveryContext);
            statusService.recoverMp(target, mpRecovery);
        }
        for (AstEntity target : context.services().targeting().inRadius(
                context.player(), center, effectRadius, effectRadius, Integer.MAX_VALUE, true
        )) {
            control.affectedMobIds.add(target.id());
            context.services().temporaryEffects().apply(
                    target.id(), effectId, EFFECT_INTERVAL_TICKS,
                    1.0D + mobDamageTakenIncrease / 100.0D, 1.0D, 1.0D
            );
        }
    }

    private void replaceControl(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState pillar,
            @NotNull ControlState next
    ) {
        ControlState previous = controlsByPillar.get(pillar);
        if (previous != null) {
            context.services().tasks().cancel(previous.casterId, previous.scope);
        }
        controlsByPillar.put(pillar, next);
    }

    private void clearControl(
            @NotNull PlayerActiveSkillContext context,
            @NotNull PaladinHolySmiteExecutor.HolyPillarState pillar,
            @NotNull ControlState control
    ) {
        if (!controlsByPillar.remove(pillar, control)) {
            return;
        }
        for (Map.Entry<UUID, AstPlayer> entry : control.buffedPlayers.entrySet()) {
            statusService.removeBuff(entry.getValue(), control.scope + ":buff:" + entry.getKey());
        }
        for (UUID mobId : control.affectedMobIds) {
            context.services().temporaryEffects().clear(mobId, control.scope + ":effect");
        }
    }

    private @NotNull List<AstPlayer> partyTargets(
            @NotNull Player caster,
            @NotNull Location center,
            double radius
    ) {
        List<AstPlayer> targets = new ArrayList<>();
        AstPlayer astCaster = AstPlayerCache.get(caster);
        if (isWithin(center, caster, radius)) {
            targets.add(astCaster);
        }
        Party party = partyService.findParty(caster.getUniqueId());
        if (party == null) {
            return List.copyOf(targets);
        }
        for (UUID memberId : party.members()) {
            if (memberId.equals(caster.getUniqueId())) {
                continue;
            }
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && isWithin(center, member, radius)) {
                targets.add(AstPlayerCache.get(member));
            }
        }
        return List.copyOf(targets);
    }

    private boolean isWithin(@NotNull Location center, @NotNull Player player, double radius) {
        if (!player.isOnline() || player.isDead() || center.getWorld() == null || player.getWorld() != center.getWorld()) {
            return false;
        }
        Location location = player.getLocation();
        double deltaX = location.getX() - center.getX();
        double deltaZ = location.getZ() - center.getZ();
        return deltaX * deltaX + deltaZ * deltaZ <= radius * radius;
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        double value = params.getDouble(key, Double.NaN);
        if (!Double.isFinite(value) || value <= 0.0D) {
            throw new SkillParameterException(key, "ホーリーコントロールの params[" + key + "] は正数が必要です");
        }
    }

    private static final class ControlState {
        private final UUID casterId;
        private final String scope;
        private final Map<UUID, AstPlayer> buffedPlayers = new HashMap<>();
        private final Set<UUID> affectedMobIds = new HashSet<>();

        private ControlState(@NotNull UUID casterId, @NotNull String scope) {
            this.casterId = casterId;
            this.scope = scope;
        }
    }
}
