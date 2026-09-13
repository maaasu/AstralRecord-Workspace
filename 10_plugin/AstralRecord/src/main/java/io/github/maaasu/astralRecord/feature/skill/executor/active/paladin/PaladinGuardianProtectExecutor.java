package io.github.maaasu.astralRecord.feature.skill.executor.active.paladin;

import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.UUID;

/** 視線先のパーティーメンバーが受けるダメージの90%を肩代わりするスキルです。 */
public final class PaladinGuardianProtectExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "paladin_guardian_protect";
    private final PaladinGuardianProtectRuntimeService runtimeService;
    private final PartyService partyService;
    private final PlayerDeathService playerDeathService;

    /**
     * 共有サービス、プロテクト状態、パーティー、custom死亡状態で初期化します。
     *
     * @param services 発動スキル共通サービス
     * @param runtimeService プロテクト状態サービス
     * @param partyService パーティーメンバー解決サービス
     * @param playerDeathService custom死亡状態サービス
     */
    public PaladinGuardianProtectExecutor(
            @NotNull ActiveSkillServices services,
            @NotNull PaladinGuardianProtectRuntimeService runtimeService,
            @NotNull PartyService partyService,
            @NotNull PlayerDeathService playerDeathService
    ) {
        super(ID, services);
        this.runtimeService = runtimeService;
        this.partyService = partyService;
        this.playerDeathService = playerDeathService;
    }

    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "targetRange");
        requirePositive(params, "aimRadius");
        requirePositiveInt(params, "durationTicks");
        requirePositiveInt(params, "visualIntervalTicks");
        double redirectRatio = params.getDouble("damageRedirectRatio", Double.NaN);
        if (!(redirectRatio > 0.0D && redirectRatio <= 1.0D)) {
            throw new SkillParameterException(
                    "damageRedirectRatio", "ガーディアンプロテクトの肩代わり率は0より大きく1以下が必要です"
            );
        }
    }

    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double targetRange = params.getDouble("targetRange", 30.0D);
        double aimRadius = params.getDouble("aimRadius", 2.0D);
        int durationTicks = params.getInt("durationTicks", 200);
        int visualIntervalTicks = params.getInt("visualIntervalTicks", 10);
        double damageRedirectRatio = params.getDouble("damageRedirectRatio", 0.90D);
        Player target = findTarget(context.player(), targetRange, aimRadius);
        if (target == null) {
            return SkillCastResult.failure(PlayerMsgId.P_7201);
        }

        Player caster = context.player();
        runtimeService.protect(caster, target, durationTicks, damageRedirectRatio);
        PlayerMessageService.getInstance().send(
                target, PlayerMsgId.P_7202, caster.getName(), Math.max(1, durationTicks / 20)
        );
        UUID targetId = target.getUniqueId();
        UUID casterId = caster.getUniqueId();
        int executions = Math.max(
                1,
                (durationTicks + visualIntervalTicks - 1) / visualIntervalTicks + 1
        );
        context.services().tasks().repeat(
                casterId, ID, 0L, visualIntervalTicks, executions,
                frame -> renderLink(context, caster, target, casterId, targetId),
                () -> runtimeService.clear(targetId, casterId)
        );
        return context.success();
    }

    private void renderLink(
            @NotNull PlayerActiveSkillContext context,
            @NotNull Player caster,
            @NotNull Player target,
            @NotNull UUID casterId,
            @NotNull UUID targetId
    ) {
        if (!runtimeService.isActive(targetId, casterId)
                || !caster.isOnline() || caster.isDead()
                || !target.isOnline() || target.isDead()
                || playerDeathService.isDead(casterId) || playerDeathService.isDead(targetId)
                || caster.getWorld() != target.getWorld()) {
            context.services().tasks().cancel(casterId, ID);
            return;
        }
        context.services().effects().line(
                caster.getEyeLocation(), target.getEyeLocation(), 0.35D,
                SharedParticleDefinitions.SKILL_PALADIN_GUARDIAN_PROTECT_END_ROD
        );
    }

    private @Nullable Player findTarget(@NotNull Player caster, double range, double aimRadius) {
        Party party = partyService.findParty(caster.getUniqueId());
        if (party == null) {
            return null;
        }
        Location eye = caster.getEyeLocation();
        Vector direction = eye.getDirection().normalize();
        return party.members().stream()
                .filter(memberId -> !memberId.equals(caster.getUniqueId()))
                .map(Bukkit::getPlayer)
                .filter(player -> player != null && player.isOnline() && !player.isDead())
                .filter(player -> player.getWorld() == caster.getWorld())
                .map(player -> aimedTarget(eye, direction, player, range, aimRadius))
                .filter(candidate -> candidate != null)
                .min(Comparator.comparingDouble(AimedTarget::distanceAlongView))
                .map(AimedTarget::player)
                .orElse(null);
    }

    private @Nullable AimedTarget aimedTarget(
            @NotNull Location eye,
            @NotNull Vector direction,
            @NotNull Player target,
            double range,
            double aimRadius
    ) {
        Vector offset = target.getEyeLocation().toVector().subtract(eye.toVector());
        double along = offset.dot(direction);
        if (along < 0.0D || along > range) {
            return null;
        }
        double perpendicularSquared = offset.clone().subtract(direction.clone().multiply(along)).lengthSquared();
        return perpendicularSquared <= aimRadius * aimRadius ? new AimedTarget(target, along) : null;
    }

    private static void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "ガーディアンプロテクトの params[" + key + "] は正数が必要です");
        }
    }

    private static void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "ガーディアンプロテクトの params[" + key + "] は1以上が必要です");
        }
    }

    private record AimedTarget(@NotNull Player player, double distanceAlongView) {
    }
}
