package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropPresentationService;
import io.github.maaasu.astralRecord.feature.mob.service.MobKnockbackService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.item.service.EquipmentDurabilityService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.projectiles.ProjectileSource;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * custom combat のダメージ適用を一元化するサービスです。
 */
public final class DamageService {

    private static final double MELEE_KNOCKBACK_MULTIPLIER = 1.0D;
    private static final double RANGED_KNOCKBACK_MULTIPLIER = 0.55D;
    private static final double MAGIC_KNOCKBACK_MULTIPLIER = 0.4D;

    private final StatusService statusService;
    private final MobService mobService;
    private final MobCombatService mobCombatService;
    private final MobKnockbackService knockbackService;
    private final DamageCalculator damageCalculator;
    private final DisplayTextService displayTextService;
    private final PlayerSettingService playerSettingService;
    private final ParticleDisplayService particleDisplayService;
    private final PlayerDeathService playerDeathService;
    private BossChallengeService bossChallengeService;
    private ConditionService conditionService;
    private EquipmentDurabilityService equipmentDurabilityService;

    /**
     * サービスを構築します。
     *
     * @param statusService プレイヤー HP 反映に使うサービス
     * @param mobService    Bukkit Entity から custom mob を解決するサービス
     */
    public DamageService(
            @NotNull StatusService statusService,
            @NotNull MobService mobService,
            @NotNull MobCombatService mobCombatService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerSettingService playerSettingService,
            @NotNull ParticleDisplayService particleDisplayService
    ) {
        this(statusService, mobService, mobCombatService, knockbackService, displayTextService, playerSettingService, particleDisplayService, null);
    }

    /**
     * サービスを構築します。
     *
     * @param statusService プレイヤー HP 反映に使うサービス
     * @param mobService    Bukkit Entity から custom mob を解決するサービス
     * @param playerDeathService プレイヤー死亡状態管理サービス。未設定時は死亡状態判定を行いません
     */
    public DamageService(
            @NotNull StatusService statusService,
            @NotNull MobService mobService,
            @NotNull MobCombatService mobCombatService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerSettingService playerSettingService,
            @NotNull ParticleDisplayService particleDisplayService,
            @Nullable PlayerDeathService playerDeathService
    ) {
        this.statusService = statusService;
        this.mobService = mobService;
        this.mobCombatService = mobCombatService;
        this.knockbackService = knockbackService;
        this.damageCalculator = new DamageCalculator();
        this.displayTextService = displayTextService;
        this.playerSettingService = playerSettingService;
        this.particleDisplayService = particleDisplayService;
        this.playerDeathService = playerDeathService;
    }

    /**
     * Sets the boss challenge service used to detect boss defeat.
     *
     * @param bossChallengeService boss challenge service, or null to disable the hook
     */
    public void setBossChallengeService(@Nullable BossChallengeService bossChallengeService) {
        this.bossChallengeService = bossChallengeService;
    }

    /**
     * 状態異常サービスを設定します。
     *
     * @param conditionService 状態異常サービス。null の場合は状態異常補正なし
     */
    public void setConditionService(@Nullable ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    public void setEquipmentDurabilityService(@Nullable EquipmentDurabilityService equipmentDurabilityService) {
        this.equipmentDurabilityService = equipmentDurabilityService;
    }

    /**
     * Bukkit の近接ダメージイベントを custom combat へ変換します。
     *
     * @param event Bukkit ダメージイベント
     */
    public void handleEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        AstEntity attacker = resolveEntity(event.getDamager());
        AstEntity victim = resolveEntity(event.getEntity());

        if (!attacker.isManaged() && !victim.isManaged()) {
            return;
        }

        event.setDamage(0.0D);
        event.setCancelled(true);

        if (!victim.isManaged()) {
            return;
        }

        if (victim.isMob() && victim.mob() != null && victim.mob().template().damageImmune()) {
            return;
        }

        if (attacker.isMob() && victim.isPlayer()) {
            return;
        }

        if (attacker.isManaged()) {
            attack(attacker, victim, AttackType.MELEE, DamageType.PHYSICAL);
            return;
        }

        applyDamage(attacker, victim, event.getDamage(), AttackType.MELEE, DamageType.PHYSICAL);
    }

    /**
     * 攻撃者ステータスを使って通常攻撃ダメージを適用します。
     *
     * @param attacker   攻撃者
     * @param victim     被弾者
     * @param attackType 攻撃種別
     * @param damageType ダメージ種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType
    ) {
        return attack(attacker, victim, attackType, damageType, DamageElement.NEUTRAL);
    }

    /**
     * 攻撃者のステータスを使って属性付き攻撃ダメージを適用します。
     *
     * @param attacker      攻撃者
     * @param victim        被弾者
     * @param attackType    攻撃種別
     * @param damageType   ダメージ種別
     * @param damageElement ダメージ属性
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType,
            @NotNull DamageElement damageElement
    ) {
        return applyDamage(attacker, victim, 0.0D, attackType, damageType, damageElement, DamageScaling.ATTACKER_STATUS);
    }

    /**
     * 外部で確定した基礎ダメージをそのまま適用します。
     *
     * @param attacker   攻撃者。存在しない場合は {@code null}
     * @param victim     被弾者
     * @param baseDamage 基礎ダメージ
     * @param attackType 攻撃種別
     * @param damageType ダメージ種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType
    ) {
        return applyDamage(attacker, victim, baseDamage, attackType, damageType, DamageElement.NEUTRAL, DamageScaling.FIXED);
    }

    /**
     * 持続ダメージやデバフ起点の固定値ダメージを適用します。
     *
     * @param attacker   原因元の攻撃者。存在しない場合は {@code null}
     * @param victim     被弾者
     * @param baseDamage 固定ダメージ
     * @param damageType ダメージ種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyEffectDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull DamageType damageType
    ) {
        return applyDamage(attacker, victim, baseDamage, AttackType.MAGIC, damageType, DamageElement.NEUTRAL, DamageScaling.FIXED);
    }

    /**
     * 状態異常由来のダメージを custom combat 経路へ流します。
     *
     * @param attacker 付与元。環境由来なら null
     * @param victim 対象
     * @param baseDamage 基礎ダメージ
     * @param damageType ダメージ種別
     * @param damageElement ダメージ属性
     * @param conditionType 状態異常種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyConditionDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull DamageType damageType,
            @NotNull DamageElement damageElement,
            @NotNull ConditionType conditionType
    ) {
        return applyDamage(attacker, victim, baseDamage, AttackType.MAGIC, damageType, damageElement, DamageScaling.FIXED);
    }

    /**
     * Bukkit Entity を combat で扱う統一エンティティへ解決します。
     *
     * @param entity Bukkit Entity
     * @return 解決済みエンティティ
     */
    public @NotNull AstEntity resolveEntity(@NotNull Entity entity) {
        if (entity instanceof Projectile projectile) {
            ProjectileSource shooter = projectile.getShooter();
            if (shooter instanceof Entity shooterEntity) {
                return resolveEntity(shooterEntity);
            }
        }

        if (entity instanceof Player player) {
            if (isPlayerDead(player.getUniqueId())) {
                return AstEntity.bukkit(player);
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                return AstEntity.player(astPlayer);
            }
        }

        var mob = mobService.getInstanceByEntity(entity.getUniqueId());
        if (mob != null) {
            return AstEntity.mob(mob);
        }

        return AstEntity.bukkit(entity);
    }

    private @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType,
            @NotNull DamageElement damageElement,
            @NotNull DamageScaling scaling
    ) {
        if (attacker != null && attacker.isPlayer() && isPlayerDead(attacker.id())) {
            return new DamageResult(0.0D);
        }
        if (victim.isPlayer() && isPlayerDead(victim.id())) {
            return new DamageResult(0.0D);
        }
        if (victim.isMob() && victim.mob() != null && victim.mob().template().damageImmune()) {
            return new DamageResult(0.0D);
        }
        if (conditionService != null && conditionService.isDamageImmune(victim)) {
            return new DamageResult(0.0D);
        }

        ensureStatusLoaded(attacker);
        ensureStatusLoaded(victim);

        DamageContext context = new DamageContext(attacker, victim, baseDamage, attackType, damageType, damageElement, scaling);
        DamageResult calculated = damageCalculator.calculate(context);
        if (!calculated.evaded() && conditionService != null && calculated.finalDamage() > 0.0D) {
            calculated = calculated.withFinalDamage(
                    calculated.finalDamage() * conditionService.damageTakenMultiplier(victim)
            );
        }
        DamageResult result = applyShieldDamage(attacker, victim, calculated);
        applyDamageResult(attacker, victim, result, attackType);
        applyDurabilityWear(attacker, victim, result);
        spawnDamageDisplay(attacker, victim, result);
        sendDamageLog(attacker, victim, result, context);
        return result;
    }

    private void applyDurabilityWear(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result
    ) {
        if (equipmentDurabilityService == null) {
            return;
        }
        equipmentDurabilityService.consumeOnAttackHit(attacker, result);
        equipmentDurabilityService.consumeOnDamageTaken(victim, result);
    }

    private @NotNull DamageResult applyShieldDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result
    ) {
        if (result.finalDamage() <= 0.0D || !hasActiveShield(victim)) {
            return result;
        }

        double shieldBreak = attacker == null ? 0.0D : Math.max(0.0D, attacker.statValue(StatusType.SHIELD_BREAK));
        double baseShieldDamage = Math.floor(result.finalDamage() / Math.max(1.0D, victim.maxHealth() * 0.1D));
        double calculatedShieldDamage = baseShieldDamage + shieldBreak;
        if (calculatedShieldDamage < 1.0D) {
            return DamageResult.shield(0.0D, false, result);
        }

        double currentShield = currentShield(victim);
        double shieldDamage = Math.min(currentShield, calculatedShieldDamage);
        boolean shieldBroken = currentShield > 0.0D && currentShield - shieldDamage <= 0.0D;
        consumeShield(victim, shieldDamage);
        return DamageResult.shield(shieldDamage, shieldBroken, result);
    }

    private void applyDamageResult(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result,
            @NotNull AttackType attackType
    ) {
        if (result.shieldDamage() > 0.0D) {
            applyShieldThreat(attacker, victim, result.shieldDamage());
            playShieldEffect(victim, result.shieldBroken());
        }

        if (result.finalDamage() <= 0.0D) {
            return;
        }

        if (victim.isPlayer()) {
            if (victim.player() != null) {
                var updated = statusService.consumeHp(victim.player(), result.finalDamage());
                playPlayerHurtEffect(victim.player().getBukkit(), result.critical());
                applyDamageKnockback(attacker, victim, attackType);
                if (updated.getCurrentHp() <= 0.0D && playerDeathService != null) {
                    boolean handledByBoss = bossChallengeService != null
                            && bossChallengeService.handleParticipantDeath(victim.player(), victim.location());
                    if (!handledByBoss) {
                        playerDeathService.startDeath(victim.player(), victim.location());
                    }
                }
            }
            return;
        }

        if (!victim.isMob()) {
            return;
        }

        var mob = victim.mob();
        if (mob == null) return;
        double healthBefore = mob.currentHealth();
        double effectiveHealthDamage = Math.min(healthBefore, result.finalDamage());
        mob.currentHealth(Math.max(0.0D, healthBefore - result.finalDamage()));
        playMobHurtEffect(mob.bukkitEntityId(), result.critical());
        applyDamageKnockback(attacker, victim, attackType);
        if (attacker != null && attacker.isPlayer()) {
            if (isPlayerDead(attacker.id())) {
                return;
            }
            mob.threatTable().add(attacker.id(), result.finalDamage());
            mob.lastAttackerUuid(attacker.id());
            if (bossChallengeService != null && bossChallengeService.isBossMob(mob.instanceId())) {
                bossChallengeService.recordBossDamage(
                        mob.instanceId(),
                        attacker.id(),
                        effectiveHealthDamage + result.shieldDamage()
                );
            }
            if (mob.state() == MobState.IDLE) {
                mob.state(MobState.AGGRO);
                mob.targetId(attacker.id());
            }
        }
        if (mob.currentHealth() <= 0.0D) {
            mob.state(MobState.DEAD);
            Location deathLocation = mob.currentLocation();
            playMobDeathEffect(mob.bukkitEntityId(), deathLocation);
            boolean bossMob = bossChallengeService != null && bossChallengeService.isBossMob(mob.instanceId());
            if (bossMob) {
                mobCombatService.handleDeath(mob, bossChallengeService.resolveRewardRecipients(mob.instanceId()));
                bossChallengeService.handleBossDefeated(mob.instanceId(), deathLocation);
            } else {
                mobCombatService.handleDeath(mob);
            }
        }
    }

    private void applyShieldThreat(@Nullable AstEntity attacker, @NotNull AstEntity victim, double shieldDamage) {
        if (attacker == null || !attacker.isPlayer() || !victim.isMob() || victim.mob() == null) {
            return;
        }
        if (isPlayerDead(attacker.id())) {
            return;
        }
        var mob = victim.mob();
        mob.threatTable().add(attacker.id(), shieldDamage);
        mob.lastAttackerUuid(attacker.id());
        if (mob.state() == MobState.IDLE) {
            mob.state(MobState.AGGRO);
            mob.targetId(attacker.id());
        }
    }

    private void ensureStatusLoaded(@Nullable AstEntity entity) {
        if (entity != null && entity.isPlayer()) {
            statusService.getStatus(entity.player());
        }
    }

    private void spawnDamageDisplay(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result
    ) {
        if (result.evaded()) {
            if (shouldDisplayDamage(attacker, victim)) {
                displayTextService.spawnEvadedText(victim.location().clone().add(0.0D, 1.2D, 0.0D));
            }
            return;
        }
        if (result.shieldDamage() > 0.0D) {
            if (!shouldDisplayDamage(attacker, victim)) {
                return;
            }
            displayTextService.spawnShieldDamageNumber(victim.location().clone().add(0.0D, 1.2D, 0.0D), result.shieldDamage());
            return;
        }
        if (result.finalDamage() <= 0.0D) {
            return;
        }
        if (!shouldDisplayDamage(attacker, victim)) {
            return;
        }
        displayTextService.spawnDamageNumber(victim.location().clone().add(0.0D, 1.2D, 0.0D), result.finalDamage(), result.critical());
    }

    /**
     * 攻撃者・被弾者それぞれの設定に従って詳細ダメージログを送信します。
     *
     * @param attacker 攻撃者。環境ダメージでは null
     * @param victim 被弾者
     * @param result ダメージ結果
     * @param context ダメージ計算コンテキスト
     */
    private void sendDamageLog(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result,
            @NotNull DamageContext context
    ) {
        if (attacker != null && attacker.isPlayer() && attacker.player() != null
                && playerSettingService.isDamageLogMessageEnabled(attacker.player().getUser().getUuid())) {
            sendDamageLogMessage(attacker.player(), victim.name(), result, context, true);
        }

        if (victim.isPlayer() && victim.player() != null
                && (attacker == null || !attacker.id().equals(victim.id()))
                && playerSettingService.isDamageLogMessageEnabled(victim.player().getUser().getUuid())) {
            String sourceName = attacker == null ? "環境" : attacker.name();
            sendDamageLogMessage(victim.player(), sourceName, result, context, false);
        }
    }

    /**
     * 与ダメージまたは被ダメージの詳細メッセージを送信します。
     *
     * @param recipient 送信先
     * @param counterpartName 相手側の表示名
     * @param result ダメージ結果
     * @param context ダメージ計算コンテキスト
     * @param outgoing 与ダメージログなら true
     */
    private void sendDamageLogMessage(
            @NotNull AstPlayer recipient,
            @NotNull String counterpartName,
            @NotNull DamageResult result,
            @NotNull DamageContext context,
            boolean outgoing
    ) {
        PlayerMessageService messages = PlayerMessageService.getInstance();
        if (result.evaded()) {
            messages.send(
                    recipient,
                    outgoing ? PlayerMsgId.P_5351 : PlayerMsgId.P_5353,
                    counterpartName,
                    formatOneDecimal(result.hitChance()),
                    formatOneDecimal(result.accuracy()),
                    formatOneDecimal(result.evasion())
            );
            return;
        }

        messages.send(
                recipient,
                outgoing ? PlayerMsgId.P_5350 : PlayerMsgId.P_5352,
                counterpartName,
                formatOneDecimal(result.finalDamage()),
                formatOneDecimal(result.shieldDamage()),
                attackTypeName(context.attackType()),
                damageTypeName(context.damageType()),
                damageElementName(context.damageElement()),
                formatOneDecimal(result.hitChance()),
                formatOneDecimal(result.accuracy()),
                formatOneDecimal(result.evasion()),
                result.critical() ? " &eCRITICAL" : ""
        );
    }

    /**
     * 攻撃種別のプレイヤー向け表示名を返します。
     *
     * @param attackType 攻撃種別
     * @return 日本語表示名
     */
    private @NotNull String attackTypeName(@NotNull AttackType attackType) {
        return switch (attackType) {
            case MELEE -> "近接";
            case RANGED -> "遠隔";
            case MAGIC -> "魔法";
        };
    }

    /**
     * ダメージ種別のプレイヤー向け表示名を返します。
     *
     * @param damageType ダメージ種別
     * @return 日本語表示名
     */
    private @NotNull String damageTypeName(@NotNull DamageType damageType) {
        return switch (damageType) {
            case PHYSICAL -> "物理";
            case MAGIC -> "魔法";
            case TRUE -> "純粋";
        };
    }

    /**
     * ダメージ属性のプレイヤー向け表示名を返します。
     *
     * @param element ダメージ属性
     * @return 日本語表示名
     */
    private @NotNull String damageElementName(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> "火";
            case ICE -> "氷";
            case POISON -> "毒";
            case LIGHTNING -> "雷";
            case HOLY -> "聖";
            case DARK -> "闇";
            case NEUTRAL -> "無属性";
        };
    }

    /**
     * 数値を小数第1位で表示します。
     *
     * @param value 表示する値
     * @return 小数第1位の文字列
     */
    private @NotNull String formatOneDecimal(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private boolean hasActiveShield(@NotNull AstEntity victim) {
        return maxShield(victim) > 0.0D && currentShield(victim) > 0.0D;
    }

    private double currentShield(@NotNull AstEntity victim) {
        if (victim.isPlayer() && victim.player() != null) {
            return statusService.getStatus(victim.player()).getCurrentShield();
        }
        if (victim.isMob() && victim.mob() != null) {
            return victim.mob().currentShield();
        }
        return 0.0D;
    }

    private double maxShield(@NotNull AstEntity victim) {
        if (victim.isPlayer() && victim.player() != null) {
            return statusService.getStatus(victim.player()).getMaxValue(StatusType.MAX_SHIELD);
        }
        if (victim.isMob() && victim.mob() != null && victim.mob().template().shield().active()) {
            return victim.mob().template().shield().max();
        }
        return 0.0D;
    }

    private void consumeShield(@NotNull AstEntity victim, double amount) {
        if (amount <= 0.0D) {
            return;
        }
        if (victim.isPlayer() && victim.player() != null) {
            statusService.consumeShield(victim.player(), amount);
            return;
        }
        if (victim.isMob() && victim.mob() != null) {
            victim.mob().currentShield(victim.mob().currentShield() - amount, System.currentTimeMillis());
        }
    }

    private boolean shouldDisplayDamage(@Nullable AstEntity attacker, @NotNull AstEntity victim) {
        if (attacker != null && attacker.isPlayer()
                && !playerSettingService.isDamageLogDisplayEnabled(attacker.player().getUser().getUuid())) {
            return false;
        }
        return !victim.isPlayer() || playerSettingService.isDamageLogDisplayEnabled(victim.player().getUser().getUuid());
    }

    private void playMobHurtEffect(@Nullable UUID entityId, boolean critical) {
        Entity entity = resolveBukkitEntity(entityId);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.playHurtAnimation(0.0F);
            livingEntity.setNoDamageTicks(0);
            Location location = livingEntity.getLocation();
            World world = location.getWorld();
            if (world != null) {
                particleDisplayService.spawnForNearbyViewers(
                        location.clone().add(0.0D, Math.max(0.6D, livingEntity.getHeight() * 0.5D), 0.0D),
                        SharedParticleDefinitions.DAMAGE_HIT_INDICATOR
                );
                world.playSound(location, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.75F, critical ? 1.55F : 1.0F);
            }
        }
    }

    private void playPlayerHurtEffect(@NotNull Player player, boolean critical) {
        player.playHurtAnimation(0.0F);
        player.setNoDamageTicks(0);
        Location location = player.getLocation();
        particleDisplayService.spawnForNearbyViewers(
                location.clone().add(0.0D, Math.max(0.6D, player.getHeight() * 0.5D), 0.0D),
                SharedParticleDefinitions.DAMAGE_HIT_INDICATOR
        );
        player.getWorld().playSound(location, Sound.ENTITY_PLAYER_HURT, SoundCategory.PLAYERS, 0.75F, critical ? 1.55F : 1.0F);
    }

    private void applyDamageKnockback(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType
    ) {
        if (attacker == null || !attacker.isManaged() || !victim.isManaged()) {
            return;
        }
        knockbackService.apply(attacker, victim, knockbackMultiplier(attackType));
    }

    private double knockbackMultiplier(@NotNull AttackType attackType) {
        return switch (attackType) {
            case MELEE -> MELEE_KNOCKBACK_MULTIPLIER;
            case RANGED -> RANGED_KNOCKBACK_MULTIPLIER;
            case MAGIC -> MAGIC_KNOCKBACK_MULTIPLIER;
        };
    }

    private void playMobDeathEffect(@Nullable UUID entityId, @NotNull Location location) {
        Entity entity = resolveBukkitEntity(entityId);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        particleDisplayService.spawnForNearbyViewers(
            location.clone().add(0.0D, 0.8D, 0.0D),
            SharedParticleDefinitions.MOB_DEATH_POOF
        );
        particleDisplayService.spawnForNearbyViewers(
            location.clone().add(0.0D, 0.9D, 0.0D),
            SharedParticleDefinitions.MOB_DEATH_CRIT
        );
        world.playSound(location, Sound.ENTITY_ARROW_HIT_PLAYER, SoundCategory.PLAYERS, 0.8F, 1.65F);
    }

    private void playShieldEffect(@NotNull AstEntity victim, boolean broken) {
        Entity entity = victim.isPlayer() && victim.player() != null
                ? victim.player().getBukkit()
                : victim.isMob() && victim.mob() != null
                    ? resolveBukkitEntity(victim.mob().bukkitEntityId())
                    : victim.bukkitEntity();
        Location location = victim.location();
        World world = location.getWorld();
        if (world == null) {
            return;
        }

        double height = entity == null ? 1.8D : Math.max(0.8D, entity.getHeight());
        double width = entity == null ? 0.6D : Math.max(0.6D, entity.getWidth());
        double radius = Math.max(width, height * 0.5D) * 0.65D + 0.25D;
        Location center = location.clone().add(0.0D, height * 0.5D, 0.0D);
        for (int i = 0; i < 18; i++) {
            double yaw = Math.toRadians(i * 20.0D);
            double pitch = Math.toRadians((i % 6 - 2.5D) * 18.0D);
            double cosPitch = Math.cos(pitch);
            Location point = center.clone().add(
                    Math.cos(yaw) * cosPitch * radius,
                    Math.sin(pitch) * radius,
                    Math.sin(yaw) * cosPitch * radius
            );
            particleDisplayService.spawnForNearbyViewers(
                    point,
                    broken ? SharedParticleDefinitions.SHIELD_BREAK_DUST : SharedParticleDefinitions.SHIELD_HIT_DUST
            );
        }
        world.playSound(location, broken ? Sound.ITEM_SHIELD_BREAK : Sound.ITEM_SHIELD_BLOCK, 0.85F, broken ? 0.8F : 1.2F);
    }

    private void spawnMobDeathResult(
            @NotNull Location deathLocation,
            @NotNull String mobName,
            @NotNull MobDropResult result
    ) {
        displayTextService.spawnResultText(deathLocation.clone().add(0.0D, 1.8D, 0.0D), formatMobDeathResult(mobName, result));
    }

    /**
     * Mob 討伐結果をアイテム数量と設定上のドロップ確率を含む文字列へ整形します。
     *
     * @param mobName Mob 表示名
     * @param result ドロップ結果
     * @return legacy color code を含むリザルト文字列
     */
    private @NotNull String formatMobDeathResult(@NotNull String mobName, @NotNull MobDropResult result) {
        StringBuilder text = new StringBuilder("&6&l討伐: &f").append(mobName);
        text.append("\n&eEXP &f+").append(result.exp());
        text.append("  &6Money &f+").append(result.money());
        text.append("\n&aDrop &f");
        if (result.items().isEmpty()) {
            text.append("なし");
            return text.toString();
        }
        boolean first = true;
        for (MobDropResultItem item : result.items()) {
            if (!first) {
                text.append("&7, &f");
            }
            text.append(item.itemId()).append(" x").append(item.amount())
                    .append(" &7(").append(MobDropPresentationService.formatDropRate(item.dropRate())).append("%)&f");
            first = false;
        }
        return text.toString();
    }

    private @Nullable Entity resolveBukkitEntity(@Nullable UUID entityId) {
        return entityId == null ? null : Bukkit.getEntity(entityId);
    }

    private boolean isPlayerDead(@NotNull UUID playerId) {
        return playerDeathService != null && playerDeathService.isDead(playerId);
    }
}
