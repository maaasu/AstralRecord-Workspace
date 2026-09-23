package io.github.maaasu.astralRecord.feature.skill.executor.active.sharpshooter;

import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.service.CombatTimingCalculator;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileSpec;
import io.github.maaasu.astralRecord.feature.skill.active.model.SkillProjectileTermination;
import io.github.maaasu.astralRecord.feature.skill.active.service.ActiveSkillServices;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillContext;
import io.github.maaasu.astralRecord.feature.skill.executor.active.support.PlayerActiveSkillExecutor;
import io.github.maaasu.astralRecord.feature.skill.model.SkillCastResult;
import io.github.maaasu.astralRecord.feature.skill.model.SkillDefinition;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParamReader;
import io.github.maaasu.astralRecord.feature.skill.model.SkillParameterException;
import io.github.maaasu.astralRecord.feature.skill.service.InheritanceBuffService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** 幻影の大きな弾を5ティック間隔で3発放つ、両射手職共用スキルです。 */
public final class SharpshooterPhantomShotExecutor extends PlayerActiveSkillExecutor {
    public static final String ID = "sharpshooter_phantom_shot";
    private static final int SHOT_COUNT = 3;
    private InheritanceBuffService inheritanceBuffService;
    private StatusService statusService;

    /**
     * 共通発動サービスでexecutorを構築します。
     * @param services 発射・命中・演出・タスクの共有サービス
     */
    public SharpshooterPhantomShotExecutor(@NotNull ActiveSkillServices services) {
        super(ID, services);
    }

    /**
     * 継承バフサービスを設定します。
     * @param service 継承バフの付与と所持判定に使うサービス
     */
    public void setInheritanceBuffService(@NotNull InheritanceBuffService service) {
        this.inheritanceBuffService = service;
    }

    /**
     * 最大リソースに応じた回復処理を設定します。
     * @param service MP・ENG回復に使うステータスサービス
     */
    public void setStatusService(@NotNull StatusService service) {
        this.statusService = service;
    }

    /** {@inheritDoc} */
    @Override
    public void validateParams(@NotNull SkillDefinition skill) {
        super.validateParams(skill);
        SkillParamReader params = new SkillParamReader(skill.getId(), skill.getParams());
        requirePositive(params, "range");
        requirePositive(params, "damageRatio");
        requirePositive(params, "projectileSpeed");
        requirePositive(params, "projectileHitRadius");
        requirePositiveInt(params, "shotIntervalTicks");
        requirePositiveInt(params, "successiveHitWindowTicks");
        double recovery = params.getDouble("resourceRecoveryRatio", -1.0D);
        if (!Double.isFinite(recovery) || recovery < 0.0D || recovery > 1.0D) {
            throw new SkillParameterException("resourceRecoveryRatio", "0以上1以下が必要です");
        }
    }

    /** {@inheritDoc} */
    @Override
    protected @NotNull SkillCastResult castPlayer(@NotNull PlayerActiveSkillContext context) {
        SkillParamReader params = context.params();
        double range = params.getDouble("range", 16.0D);
        double damageRatio = params.getDouble("damageRatio", 1.70D);
        double speed = params.getDouble("projectileSpeed", 1.80D);
        double hitRadius = params.getDouble("projectileHitRadius", 1.10D);
        int interval = params.getInt("shotIntervalTicks", 5);
        long windowMillis = params.getInt("successiveHitWindowTicks", 40) * 50L;
        double recoveryRatio = params.getDouble("resourceRecoveryRatio", 0.0D);
        boolean inheritedCooldown = inheritanceBuffService != null
                && inheritanceBuffService.hasActiveInheritanceBuff(context.caster().player());
        long[] lastHitMillis = {-1L};
        Location initialOrigin = context.eyeLocation();
        UUID casterId = context.caster().casterId();

        fireShot(context, range, damageRatio, speed, hitRadius, windowMillis, recoveryRatio, lastHitMillis);
        for (int index = 1; index < SHOT_COUNT; index++) {
            int shot = index;
            context.services().tasks().later(casterId, "phantom-shot:" + UUID.randomUUID(),
                    (long) interval * shot, () -> {
                        Player player = context.player();
                        if (player.isOnline() && !player.isDead()
                                && player.getWorld() == initialOrigin.getWorld()) {
                            fireShot(context, range, damageRatio, speed, hitRadius,
                                    windowMillis, recoveryRatio, lastHitMillis);
                        }
                    });
        }
        if (inheritanceBuffService != null) {
            inheritanceBuffService.grant(context, (impact, multiplier) -> { });
        }
        if (!inheritedCooldown) {
            return context.success();
        }
        long doubledCooldown = CombatTimingCalculator.resolveCooldownTicks(
                context.source().skill().getCooldownTicks() * 2L,
                context.source().statusSnapshot().rollValue(StatusType.COOLDOWN_REDUCTION)
        );
        return context.successWithCooldownTicks(doubledCooldown);
    }

    /**
     * 現在の視線方向へ一発発射し、命中後の回復と地形破片演出を処理します。
     * @param context 発動時の倍率・攻撃者を保持する文脈
     * @param range 最大射程
     * @param damageRatio 間接攻撃倍率
     * @param speed 1ティックの進行距離
     * @param hitRadius 衝突判定半径
     * @param windowMillis 再命中判定時間
     * @param recoveryRatio 最大MP・ENGの回復率
     * @param lastHitMillis 同一発動内の直前有効命中時刻
     */
    private void fireShot(@NotNull PlayerActiveSkillContext context, double range, double damageRatio,
                          double speed, double hitRadius, long windowMillis, double recoveryRatio,
                          long @NotNull [] lastHitMillis) {
        Location origin = context.eyeLocation();
        Vector direction = context.direction();
        context.services().effects().ring(origin.clone().add(direction.clone().multiply(0.5D)),
                0.65D, 14, SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_RING);
        context.services().effects().sound(origin, Sound.ENTITY_ARROW_SHOOT, 1.1F, 0.75F);
        context.services().effects().sound(origin, Sound.ENTITY_PHANTOM_FLAP, 0.8F, 1.5F);
        context.services().projectiles().launchWithTermination(
                context.player(), origin, direction,
                new SkillProjectileSpec(range, speed, hitRadius, false, 1,
                        SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_TRAIL,
                        SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_IMPACT),
                (target, hitLocation) -> {
                    DamageResult result = context.services().combat().hit(
                            context.source().skill(), context.attacker(), target,
                            AttackType.RANGED, DamageElement.NONE, damageRatio
                    );
                    renderImpact(context, hitLocation, false);
                    if (result.evaded() || result.finalDamage() <= 0.0D && result.shieldDamage() <= 0.0D) {
                        return;
                    }
                    long now = System.currentTimeMillis();
                    if (recoveryRatio > 0.0D && lastHitMillis[0] >= 0L
                            && now - lastHitMillis[0] <= windowMillis) {
                        recoverResources(context, recoveryRatio);
                    }
                    lastHitMillis[0] = now;
                },
                termination -> {
                    if (termination.type() == SkillProjectileTermination.Type.BLOCK) {
                        renderImpact(context, termination.location(), true);
                    }
                }
        );
    }

    /**
     * 地形を変更せず、ブロック破片・幻影粒子・着弾音を描画します。
     * @param context 演出サービス
     * @param location 衝突位置
     * @param blockHit 地形衝突ならtrue
     */
    private void renderImpact(@NotNull PlayerActiveSkillContext context,
                              @NotNull Location location, boolean blockHit) {
        Block block = blockHit ? location.getBlock() : location.clone().add(0.0D, -1.0D, 0.0D).getBlock();
        context.services().effects().blockDust(location,
                block.getType().isAir() ? Material.DEEPSLATE_TILES.createBlockData() : block.getBlockData());
        context.services().effects().ring(location, 1.0D, 18,
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_RING);
        context.services().effects().sound(location, Sound.BLOCK_AMETHYST_BLOCK_CHIME, 0.9F, 0.6F);
    }

    /**
     * 最大値の指定率だけMPとENGをそれぞれ回復します。
     * @param context 発動者
     * @param recoveryRatio 最大値に対する回復率
     */
    private void recoverResources(@NotNull PlayerActiveSkillContext context, double recoveryRatio) {
        if (statusService == null) {
            return;
        }
        var player = context.caster().player();
        var snapshot = statusService.getStatus(player);
        statusService.recoverMp(player, snapshot.getMaxValue(StatusType.MAX_MANA) * recoveryRatio);
        statusService.recoverEnergy(player, snapshot.getMaxValue(StatusType.MAX_ENERGY) * recoveryRatio);
        context.services().effects().point(context.player().getLocation().add(0.0D, 1.0D, 0.0D),
                SharedParticleDefinitions.SHARPSHOOTER_PHANTOM_SHOT_RECOVERY);
    }

    private void requirePositive(@NotNull SkillParamReader params, @NotNull String key) {
        if (!(params.getDouble(key, 0.0D) > 0.0D)) {
            throw new SkillParameterException(key, "正数が必要です");
        }
    }

    private void requirePositiveInt(@NotNull SkillParamReader params, @NotNull String key) {
        if (params.getInt(key, 0) < 1) {
            throw new SkillParameterException(key, "1以上の整数が必要です");
        }
    }
}
