package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageBreakdown;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.model.SuperStarCriticalMode;
import io.github.maaasu.astralRecord.feature.boss.service.BossChallengeService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.condition.model.ConditionType;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
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
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Consumer;

/**
 * custom combat のダメージ適用を一元化するサービスです。
 */
public final class DamageService {

    private static final double MELEE_KNOCKBACK_MULTIPLIER = 0.55D;
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
    private final SuperStarCriticalProjectileService superStarCriticalProjectileService;
    private BossChallengeService bossChallengeService;
    private DungeonService dungeonService;
    private ConditionService conditionService;
    private EquipmentDurabilityService equipmentDurabilityService;
    private TemporarySkillEffectService temporarySkillEffectService;
    private CombatDpsTrackerService combatDpsTrackerService;
    private Consumer<AstPlayer> playerDamageListener = player -> { };
    private Consumer<UUID> mobDeathListener = mobInstanceId -> { };

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
        this(statusService, mobService, mobCombatService, knockbackService, displayTextService,
                playerSettingService, particleDisplayService, null, null);
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
        this(statusService, mobService, mobCombatService, knockbackService, displayTextService,
                playerSettingService, particleDisplayService, playerDeathService, null);
    }

    /**
     * 超星会心追尾弾を含むサービスを構築します。
     *
     * @param statusService プレイヤー HP 反映に使うサービス
     * @param mobService Bukkit Entity から custom mob を解決するサービス
     * @param mobCombatService Mob の死亡・報酬処理サービス
     * @param knockbackService ノックバック適用サービス
     * @param displayTextService ダメージ表示サービス
     * @param playerSettingService 表示設定サービス
     * @param particleDisplayService パーティクル表示サービス
     * @param playerDeathService プレイヤー死亡状態管理サービス。未設定時は死亡状態判定を行いません
     * @param plugin 追尾弾の更新タスクを所有するプラグイン。未設定時は追尾弾を生成しません
     */
    public DamageService(
            @NotNull StatusService statusService,
            @NotNull MobService mobService,
            @NotNull MobCombatService mobCombatService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerSettingService playerSettingService,
            @NotNull ParticleDisplayService particleDisplayService,
            @Nullable PlayerDeathService playerDeathService,
            @Nullable Plugin plugin
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
        this.superStarCriticalProjectileService = plugin == null
                ? null
                : new SuperStarCriticalProjectileService(plugin, mobService, particleDisplayService);
    }

    /**
     * 実行中の超星会心追尾弾を除去して更新処理を停止します。
     */
    public void stop() {
        if (superStarCriticalProjectileService != null) {
            superStarCriticalProjectileService.stop();
        }
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
     * ダンジョン死亡・固定報酬対象の連携先を設定します。
     *
     * @param dungeonService ダンジョンサービス。{@code null} で連携無効
     */
    public void setDungeonService(@Nullable DungeonService dungeonService) {
        this.dungeonService = dungeonService;
    }

    /**
     * Mob の報酬・破棄処理が完了した直後に呼ぶリスナーを設定します。
     * ダンジョンなど、Mob インスタンス単位で全滅を追跡する機能が利用します。
     *
     * @param mobDeathListener Mob インスタンス UUID の通知先。null で無効化
     */
    public void setMobDeathListener(@Nullable Consumer<UUID> mobDeathListener) {
        this.mobDeathListener = mobDeathListener == null ? mobInstanceId -> { } : mobDeathListener;
    }

    /**
     * 状態異常サービスを設定します。
     *
     * @param conditionService 状態異常サービス。null の場合は状態異常補正なし
     */
    public void setConditionService(@Nullable ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * 発動スキル由来の一時ダメージ倍率を設定します。
     *
     * @param temporarySkillEffectService 一時効果サービス。null の場合は補正なし
     */
    public void setTemporarySkillEffectService(
            @Nullable TemporarySkillEffectService temporarySkillEffectService
    ) {
        this.temporarySkillEffectService = temporarySkillEffectService;
    }

    public void setEquipmentDurabilityService(@Nullable EquipmentDurabilityService equipmentDurabilityService) {
        this.equipmentDurabilityService = equipmentDurabilityService;
    }

    /**
     * 与ダメージトラッカーを設定します。
     *
     * @param combatDpsTrackerService DPS 集計サービス。null の場合は追跡を行いません。
     */
    public void setCombatDpsTrackerService(@Nullable CombatDpsTrackerService combatDpsTrackerService) {
        this.combatDpsTrackerService = combatDpsTrackerService;
    }

    /**
     * プレイヤーへ実ダメージを適用する直前に呼び出す listener を設定します。
     *
     * @param listener ダメージを受けたプレイヤーを受け取る listener
     */
    public void setPlayerDamageListener(@NotNull Consumer<AstPlayer> listener) {
        this.playerDamageListener = listener;
    }

    /**
     * Bukkit の近接ダメージイベントを custom combat へ変換します。
     *
     * @param event Bukkit ダメージイベント
     */
    public void handleEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        double originalDamage = event.getDamage();
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
            attack(attacker, victim, AttackType.MELEE);
            return;
        }

        applyDamage(attacker, victim, originalDamage, AttackType.MELEE);
    }

    /**
     * 攻撃者ステータスを使って通常攻撃ダメージを適用します。
     *
     * @param attacker   攻撃者
     * @param victim     被弾者
     * @param attackType 攻撃種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType
    ) {
        return attack(attacker, victim, attackType, List.of(DamageComponent.defaultComponent()));
    }

    /**
     * 攻撃者のステータスを使って属性成分付き攻撃ダメージを適用します。
     *
     * @param attacker      攻撃者
     * @param victim        被弾者
     * @param attackType    攻撃種別
     * @param components 属性別の攻撃倍率
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components
    ) {
        return attack(attacker, victim, attackType, components, DamageSource.NORMAL_ATTACK);
    }

    /**
     * 攻撃者のステータスを使って、発生元を明示した攻撃ダメージを適用します。
     *
     * @param attacker   攻撃者
     * @param victim     被弾者
     * @param attackType 攻撃種別
     * @param components 属性別の攻撃倍率
     * @param source     通常攻撃・スキルなどの発生元
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageSource source
    ) {
        return attack(attacker, victim, attackType, components, source, 1.0D);
    }

    /**
     * 攻撃者固有のダメージ倍率を含めて、発生元を明示した攻撃ダメージを適用します。
     *
     * @param attacker 攻撃者
     * @param victim 被弾者
     * @param attackType 攻撃種別
     * @param components 属性別の攻撃倍率
     * @param source 通常攻撃・スキルなどの発生元
     * @param attackerDamageMultiplier 攻撃者固有のダメージ倍率
     * @return ダメージ結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageSource source,
            double attackerDamageMultiplier
    ) {
        return applyDamage(
                attacker,
                victim,
                0.0D,
                attackType,
                components,
                DamageScaling.ATTACKER_STATUS,
                source,
                attackerDamageMultiplier
        );
    }

    /**
     * 外部で確定した基礎ダメージをそのまま適用します。
     *
     * @param attacker   攻撃者。存在しない場合は {@code null}
     * @param victim     被弾者
     * @param baseDamage 基礎ダメージ
     * @param attackType 攻撃種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType
    ) {
        return applyDamage(attacker, victim, baseDamage, attackType,
                List.of(DamageComponent.defaultComponent()), DamageScaling.FIXED, DamageSource.OTHER, 1.0D);
    }

    /**
     * 持続ダメージやデバフ起点の固定値ダメージを適用します。
     *
     * @param attacker   原因元の攻撃者。存在しない場合は {@code null}
     * @param victim     被弾者
     * @param baseDamage 固定ダメージ
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyEffectDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage
    ) {
        return applyDamage(attacker, victim, baseDamage, AttackType.MAGIC,
                List.of(DamageComponent.defaultComponent()), DamageScaling.FIXED, DamageSource.OTHER, 1.0D);
    }

    /**
     * 状態異常由来のダメージを custom combat 経路へ流します。
     *
     * @param attacker 付与元。環境由来なら null
     * @param victim 対象
     * @param baseDamage 基礎ダメージ
     * @param conditionType 状態異常種別
     * @return ダメージ結果
     */
    public @NotNull DamageResult applyConditionDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull ConditionType conditionType
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

        ensureStatusLoaded(attacker);
        ensureStatusLoaded(victim);
        double damage = Math.max(0.0D, baseDamage);
        if (conditionService != null) {
            damage *= conditionService.conditionDamageMultiplier(attacker, victim, conditionType);
            damage *= conditionService.damageDealtMultiplier(attacker);
        }
        damage *= temporaryDamageMultiplier(attacker, victim);
        damage *= finalDamageMultiplier(attacker);

        DamageResult result = new DamageResult(damage, false);
        applyDamageResult(attacker, victim, result, AttackType.MAGIC, false);
        spawnDamageDisplay(attacker, victim, result);
        return result;
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

    /**
     * 通常の発生率判定を使って共通ダメージ処理を実行します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param baseDamage 外部基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     * @return 適用結果
     */
    private @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            double attackerDamageMultiplier
    ) {
        return applyDamage(attacker, victim, baseDamage, attackType, components, scaling, source,
                attackerDamageMultiplier, SuperStarCriticalMode.ROLL);
    }

    /**
     * 超星会心モードを指定して共通ダメージ処理を実行します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param baseDamage 外部基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     * @param superStarCriticalMode 超星会心倍率の適用方法
     * @return 適用結果
     */
    private @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            double attackerDamageMultiplier,
            @NotNull SuperStarCriticalMode superStarCriticalMode
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

        DamageContext context = new DamageContext(
                attacker,
                victim,
                baseDamage,
                attackType,
                components,
                scaling,
                source,
                attackerDamageMultiplier,
                superStarCriticalMode
        );
        DamageResult calculated = damageCalculator.calculate(context);
        if (!calculated.evaded() && calculated.finalDamage() > 0.0D) {
            double multiplier = finalDamageMultiplier(attacker) * temporaryDamageMultiplier(attacker, victim);
            if (conditionService != null) {
                multiplier *= conditionService.damageTakenMultiplier(victim)
                        * conditionService.damageDealtMultiplier(attacker);
            }
            calculated = calculated.withFinalDamage(calculated.finalDamage() * multiplier);
        }
        Location superStarOrigin = shouldSpawnSuperStarCriticalProjectiles(attacker, victim, calculated, superStarCriticalMode)
                ? damageOrigin(victim)
                : null;
        long rechargeEventAtMs = System.currentTimeMillis();
        completeShieldRechargeIfReady(victim, rechargeEventAtMs);
        boolean shieldWasActive = hasActiveShield(victim);
        DamageResult result = applyShieldDamage(attacker, victim, calculated);
        if (!shieldWasActive && isDirectDamage(source) && !result.evaded()) {
            result = result.withAddedFixedHealthDamage(fixedHealthDamage(attacker));
        }
        boolean configuredPlayerRecharge = victim.isPlayer()
            && victim.player() != null
            && statusService.hasConfiguredShieldRecharge(victim.player());
        if (result.shieldBroken() || (configuredPlayerRecharge
            && !result.evaded()
            && (result.shieldDamage() > 0.0D || effectiveHealthDamage(victim, result) > 0.0D))) {
            startShieldRecharge(victim, rechargeEventAtMs);
        }
        applyShieldRechargeDelay(attacker, victim, result, source);
        completeShieldRechargeIfReady(victim, rechargeEventAtMs);
        playCriticalHitEffect(victim, result);
        boolean projectileDamage = superStarCriticalMode == SuperStarCriticalMode.FORCE;
        double victimCurrentHealthBefore = victim.currentHealth();
        double victimMaxHealth = victim.maxHealth();
        applyDamageResult(attacker, victim, result, attackType, !projectileDamage);
        double victimCurrentHealthAfter = victim.currentHealth();
        if (!projectileDamage) {
            applyDurabilityWear(attacker, victim, result);
        }
        spawnDamageDisplay(attacker, victim, result);
        sendDamageLog(attacker, victim, result, context, victimCurrentHealthBefore, victimMaxHealth, victimCurrentHealthAfter);
        if (superStarOrigin != null && attacker != null) {
            spawnSuperStarCriticalProjectiles(
                    attacker,
                    victim.mob(),
                    superStarOrigin,
                    baseDamage,
                    attackType,
                    components,
                    scaling,
                    source,
                    attackerDamageMultiplier
            );
        }
        return result;
    }

    /**
     * 主攻撃の結果から超星会心追尾弾を生成するか判定します。
     *
     * @param attacker 攻撃者
     * @param victim 被弾者
     * @param result 主攻撃の計算結果
     * @param mode 超星会心倍率の適用方法
     * @return 追尾弾を生成する場合は {@code true}
     */
    private boolean shouldSpawnSuperStarCriticalProjectiles(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result,
            @NotNull SuperStarCriticalMode mode
    ) {
        return superStarCriticalProjectileService != null
                && mode == SuperStarCriticalMode.ROLL
                && attacker != null
                && attacker.isPlayer()
                && victim.isMob()
                && result.superStarCritical()
                && !result.evaded()
                && result.finalDamage() > 0.0D;
    }

    /**
     * 元攻撃の再計算条件を保持した超星会心追尾弾を生成します。
     *
     * @param attacker 発生元プレイヤー
     * @param originVictim 追尾弾の生成元となった被弾 Mob
     * @param origin 生成位置
     * @param baseDamage 外部基礎ダメージ
     * @param attackType 攻撃種別
     * @param components 属性別ダメージ倍率
     * @param scaling 基礎ダメージの解決方法
     * @param source ダメージの発生元
     */
    private void spawnSuperStarCriticalProjectiles(
            @NotNull AstEntity attacker,
            @NotNull MobInstance originVictim,
            @NotNull Location origin,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull List<DamageComponent> components,
            @NotNull DamageScaling scaling,
            @NotNull DamageSource source,
            double attackerDamageMultiplier
    ) {
        if (superStarCriticalProjectileService == null) {
            return;
        }
        List<DamageComponent> componentSnapshot = List.copyOf(components);
        superStarCriticalProjectileService.spawn(attacker, originVictim, origin, target -> applyDamage(
                attacker,
                target,
                baseDamage,
                attackType,
                componentSnapshot,
                scaling,
                source,
                attackerDamageMultiplier,
                SuperStarCriticalMode.FORCE
        ));
    }

    /**
     * 被弾対象の身体中央に相当する追尾弾生成位置を返します。
     *
     * @param victim 被弾対象
     * @return 追尾弾生成位置
     */
    private @NotNull Location damageOrigin(@NotNull AstEntity victim) {
        Entity entity = victim.isMob() && victim.mob() != null
                ? resolveBukkitEntity(victim.mob().bukkitEntityId())
                : victim.bukkitEntity();
        double height = entity == null ? 1.8D : Math.max(0.2D, entity.getHeight());
        return victim.location().clone().add(0.0D, height * 0.5D, 0.0D);
    }

    /**
     * 成立した通常会心・超星会心のパーティクルとサウンドを被弾中心へ表示します。
     *
     * @param victim 被弾対象
     * @param result shield 反映後のダメージ結果
     */
    private void playCriticalHitEffect(@NotNull AstEntity victim, @NotNull DamageResult result) {
        if (result.evaded()
                || (result.finalDamage() <= 0.0D && result.shieldDamage() <= 0.0D)
                || (!result.critical() && !result.superStarCritical())) {
            return;
        }

        Location center = damageOrigin(victim);
        World world = center.getWorld();
        if (world == null) {
            return;
        }

        if (result.critical()) {
            particleDisplayService.spawnForNearbyViewers(center, SharedParticleDefinitions.CRITICAL_HIT_CRIT);
            world.playSound(center, Sound.ENTITY_PLAYER_ATTACK_CRIT, SoundCategory.PLAYERS, 0.9F, 1.0F);
        }
        if (result.superStarCritical()) {
            particleDisplayService.spawnForNearbyViewers(
                    center,
                    SharedParticleDefinitions.SUPER_STAR_CRITICAL_BURST_END_ROD
            );
            particleDisplayService.spawnForNearbyViewers(center, SharedParticleDefinitions.SUPER_STAR_CRITICAL_IMPACT);
            world.playSound(center, Sound.ENTITY_FIREWORK_ROCKET_TWINKLE, SoundCategory.PLAYERS, 1.0F, 1.2F);
        }
    }

    private double temporaryDamageMultiplier(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim
    ) {
        if (temporarySkillEffectService == null) {
            return 1.0D;
        }
        double outgoing = attacker == null
                ? 1.0D
                : temporarySkillEffectService.outgoingMultiplier(attacker);
        return outgoing * temporarySkillEffectService.incomingMultiplier(victim);
    }

    /**
     * 攻撃者の最終ダメージ倍率を返します。
     *
     * <p>管理対象の攻撃者だけが {@link StatusType#FINAL_DAMAGE_MULTIPLIER} を使用します。
     * Mob テンプレートで未定義の場合だけ 100% を既定値とし、明示された 0 以下の値は 0% と扱います。</p>
     *
     * @param attacker 攻撃者。環境ダメージの場合は {@code null}
     * @return 最終ダメージへ乗算する倍率
     */
    private double finalDamageMultiplier(@Nullable AstEntity attacker) {
        if (attacker == null || !attacker.isManaged()) {
            return 1.0D;
        }
        if (attacker.isMob() && attacker.mob() != null) {
            return Math.max(
                    0.0D,
                    attacker.mob().template().statValue(StatusType.FINAL_DAMAGE_MULTIPLIER.name(), 100.0D)
            ) / 100.0D;
        }
        double configured = attacker.statValue(StatusType.FINAL_DAMAGE_MULTIPLIER);
        return Math.max(0.0D, configured) / 100.0D;
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
        double baseShieldDamage = Math.max(
                1.0D,
                Math.floor(result.finalDamage() / Math.max(1.0D, victim.maxHealth() * 0.1D))
        );
        double calculatedShieldDamage = baseShieldDamage + shieldBreak;

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
            @NotNull AttackType attackType,
            boolean knockback
    ) {
        if (victim.isMob() && victim.mob() != null && victim.mob().state() == MobState.DEAD) {
            return;
        }

        if (result.shieldDamage() > 0.0D) {
            applyShieldThreat(attacker, victim, result.shieldDamage());
            playShieldEffect(victim, result.shieldBroken());
        }

        if (victim.isPlayer() && victim.player() != null
                && (result.shieldDamage() > 0.0D || result.finalDamage() > 0.0D)) {
            playerDamageListener.accept(victim.player());
        }
        if (result.finalDamage() <= 0.0D) {
            return;
        }
        if (attacker != null && attacker.isPlayer() && attacker.player() != null && combatDpsTrackerService != null) {
            combatDpsTrackerService.recordDamage(attacker.id(), result.finalDamage());
        }

        if (victim.isPlayer()) {
            if (victim.player() != null) {
                double effectiveLifeStealDamage = Math.min(
                        victim.currentHealth(),
                        Math.max(0.0D, result.finalDamage() - result.fixedHealthDamage())
                );
                var updated = statusService.consumeHp(victim.player(), result.finalDamage());
                applyLifeSteal(attacker, effectiveLifeStealDamage);
                playPlayerHurtEffect(
                        victim.player().getBukkit(),
                        result.critical() || result.superStarCritical()
                );
                if (knockback) {
                    applyDamageKnockback(attacker, victim, attackType);
                }
                if (updated.getCurrentHp() <= 0.0D && playerDeathService != null) {
                    boolean handledByBoss = bossChallengeService != null
                            && bossChallengeService.handleParticipantDeath(victim.player(), victim.location());
                    boolean handledByDungeon = !handledByBoss && dungeonService != null
                            && dungeonService.handleParticipantDeath(victim.player(), victim.location());
                    if (!handledByBoss && !handledByDungeon) {
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
        double effectiveHealthDamage = mob.nonLethal()
                ? Math.min(Math.max(0.0D, healthBefore - 1.0D), result.finalDamage())
                : Math.min(healthBefore, result.finalDamage());
        double effectiveLifeStealDamage = mob.nonLethal()
                ? Math.min(
                        Math.max(0.0D, healthBefore - 1.0D),
                        Math.max(0.0D, result.finalDamage() - result.fixedHealthDamage())
                )
                : Math.min(healthBefore, Math.max(0.0D, result.finalDamage() - result.fixedHealthDamage()));
        double remainingHealth = healthBefore - result.finalDamage();
        mob.currentHealth(mob.nonLethal()
                ? Math.max(1.0D, remainingHealth)
                : Math.max(0.0D, remainingHealth));
        applyLifeSteal(attacker, effectiveLifeStealDamage);
        playMobHurtEffect(mob.bukkitEntityId(), result.critical() || result.superStarCritical());
        if (knockback) {
            applyDamageKnockback(attacker, victim, attackType);
        }
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
            } else if (dungeonService != null && dungeonService.isDungeonMob(mob.instanceId())) {
                mobCombatService.handleDeath(mob, dungeonService.resolveMobRewardRecipients(mob.instanceId()));
            } else {
                mobCombatService.handleDeath(mob);
            }
            mobDeathListener.accept(mob.instanceId());
        }
    }

    /**
     * HP へ実際に与えたダメージを基準に、攻撃プレイヤーのライフスティールを回復へ反映します。
     *
     * @param attacker              攻撃者。プレイヤー以外は回復しません
     * @param effectiveHealthDamage シールドを除く実HPダメージ
     */
    private void applyLifeSteal(@Nullable AstEntity attacker, double effectiveHealthDamage) {
        if (attacker == null || !attacker.isPlayer() || attacker.player() == null || effectiveHealthDamage <= 0.0D) {
            return;
        }
        double rate = Math.max(0.0D, attacker.statValue(StatusType.LIFE_STEAL));
        statusService.recoverHp(attacker.player(), effectiveHealthDamage * rate / 100.0D);
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

    /**
     * 回避・シールド・HP ダメージに対応する表示を生成します。
     * 通常会心または超星会心が成立したダメージは会心表示を使用します。
     *
     * @param attacker 攻撃者。環境ダメージでは {@code null}
     * @param victim 被弾者
     * @param result 表示対象のダメージ結果
     */
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
        displayTextService.spawnDamageNumber(
                victim.location().clone().add(0.0D, 1.2D, 0.0D),
                result.finalDamage(),
                result.critical() || result.superStarCritical()
        );
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
            @NotNull DamageContext context,
            double victimCurrentHealthBefore,
            double victimMaxHealth,
            double victimCurrentHealthAfter
    ) {
        if (attacker != null && attacker.isPlayer() && attacker.player() != null
                && playerSettingService.isDamageLogMessageEnabled(attacker.player().getUser().getUuid())) {
            sendDamageLogMessage(
                    attacker.player(),
                    result,
                    context,
                    victimCurrentHealthBefore,
                    victimMaxHealth,
                    victimCurrentHealthAfter,
                    true
            );
        }

        if (victim.isPlayer() && victim.player() != null
                && (attacker == null || !attacker.id().equals(victim.id()))
                && playerSettingService.isDamageLogMessageEnabled(victim.player().getUser().getUuid())) {
            sendDamageLogMessage(
                    victim.player(),
                    result,
                    context,
                    victimCurrentHealthBefore,
                    victimMaxHealth,
                    victimCurrentHealthAfter,
                    false
            );
        }
    }

    /**
     * 与ダメージまたは被ダメージの詳細メッセージを送信します。
     *
     * @param recipient 送信先
     * @param result ダメージ結果
     * @param context ダメージ計算コンテキスト
     * @param victimCurrentHealthBefore 被弾者の最終反映前 HP
     * @param victimMaxHealth 被弾者の最大 HP
     * @param victimCurrentHealthAfter 被弾者の最終反映後 HP
     * @param outgoing 与ダメージログなら true
     */
    private void sendDamageLogMessage(
            @NotNull AstPlayer recipient,
            @NotNull DamageResult result,
            @NotNull DamageContext context,
            double victimCurrentHealthBefore,
            double victimMaxHealth,
            double victimCurrentHealthAfter,
            boolean outgoing
    ) {
        PlayerMessageService messages = PlayerMessageService.getInstance();
        if (result.evaded()) {
            messages.send(
                    recipient,
                    outgoing ? PlayerMsgId.P_5351 : PlayerMsgId.P_5353,
                    formatCompactNumber(result.hitChance()),
                formatCompactNumber(result.accuracy()),
                formatCompactNumber(result.evasion())
            );
            return;
        }

        messages.send(
                recipient,
                outgoing ? PlayerMsgId.P_5350 : PlayerMsgId.P_5352,
                damageSummary(result),
                attackTypeCode(context.attackType()),
                damageElementsCode(context.components()),
                formatCompactNumber(result.breakdown().resolvedAttackPower()),
                formatCompactNumber(result.breakdown().rawDefense()),
                formatCompactNumber(result.breakdown().effectiveDefense()),
                resistanceSummary(result.breakdown()),
                formatCompactNumber(result.hitChance()),
                formatCompactNumber(result.accuracy()),
                formatCompactNumber(result.evasion()),
                criticalSummary(result),
                healthSummary(victimCurrentHealthBefore, victimMaxHealth, victimCurrentHealthAfter)
        );
    }

    /**
     * 成立した通常会心・超星会心を短縮形式で返します。
     *
     * @param result ダメージ結果
     * @return CRIT、S-CRIT、両方の連結、または空文字
     */
    static @NotNull String criticalSummary(@NotNull DamageResult result) {
        if (result.critical() && result.superStarCritical()) {
            return " &eCRIT&d+S-CRIT";
        }
        if (result.superStarCritical()) {
            return " &dS-CRIT";
        }
        return result.critical() ? " &eCRIT" : "";
    }

    /**
     * ダメージの反映先と値を短縮形式で返します。
     *
     * @param result ダメージ結果
     * @return 数値のみの反映先別ダメージ表示
     */
    static @NotNull String damageSummary(@NotNull DamageResult result) {
        if (result.shieldDamage() > 0.0D) {
            return "&b" + formatCompactNumber(result.shieldDamage()) + (result.shieldBroken() ? "!" : "");
        }
        if (result.finalDamage() > 0.0D) {
            return "&c" + formatCompactNumber(result.finalDamage());
        }
        return "&70";
    }

    private static @NotNull String healthSummary(
            double currentHealth,
            double maxHealth,
            double afterHealth
    ) {
        double normalizedCurrent = Math.max(0.0D, currentHealth);
        double normalizedMax = Math.max(0.0D, maxHealth);
        double normalizedAfter = Math.max(0.0D, afterHealth);
        return "(" + formatCompactNumber(normalizedCurrent) + "/" + formatCompactNumber(normalizedMax)
                + "->" + formatCompactNumber(normalizedAfter) + ")";
    }

    /**
     * 攻撃種別の英語短縮コードを返します。
     *
     * @param attackType 攻撃種別
     * @return MEL、RNG、MAG のいずれか
     */
    static @NotNull String attackTypeCode(@NotNull AttackType attackType) {
        return switch (attackType) {
            case MELEE -> "MEL";
            case RANGED -> "RNG";
            case MAGIC -> "MAG";
        };
    }

    /**
     * ダメージ属性の英語短縮コードを返します。
     *
     * @param element ダメージ属性
     * @return NON、FIR、ICE、LTN のいずれか
     */
    static @NotNull String damageElementCode(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> "FIR";
            case ICE -> "ICE";
            case LIGHTNING -> "LTN";
            case NONE -> "NON";
        };
    }

    static @NotNull String damageElementsCode(@NotNull List<DamageComponent> components) {
        return components.stream()
                .filter(component -> component.ratio() > 0.0D)
                .map(DamageComponent::element)
                .distinct()
                .map(DamageService::damageElementCode)
                .reduce((left, right) -> left + "+" + right)
                .orElse("NON");
    }

    /**
     * 属性耐性を元値から実効値への短縮形式で返します。
     *
     * @param breakdown 計算時点の中間値
     * @return 属性なしは空文字、単属性は RESx&gt;y、複数属性は RES[F.../I...] 形式
     */
    static @NotNull String resistanceSummary(@NotNull DamageBreakdown breakdown) {
        List<DamageBreakdown.ElementResistance> resistances = breakdown.elementResistances();
        if (resistances.isEmpty()) {
            return "";
        }
        if (resistances.size() == 1) {
            DamageBreakdown.ElementResistance resistance = resistances.getFirst();
            return " RES" + formatCompactNumber(resistance.rawResistance())
                    + ">" + formatCompactNumber(resistance.effectiveResistance());
        }
        String values = resistances.stream()
                .map(resistance -> resistanceElementCode(resistance.element())
                        + formatCompactNumber(resistance.rawResistance())
                        + ">" + formatCompactNumber(resistance.effectiveResistance()))
                .reduce((left, right) -> left + "/" + right)
                .orElse("");
        return " RES[" + values + "]";
    }

    private static @NotNull String resistanceElementCode(@NotNull DamageElement element) {
        return switch (element) {
            case FIRE -> "F";
            case ICE -> "I";
            case LIGHTNING -> "L";
            case NONE -> "N";
        };
    }

    /**
     * 数値を小数第1位まで表示し、整数なら小数部を省略します。
     *
     * @param value 表示する値
     * @return 末尾の .0 を除いた文字列
     */
    static @NotNull String formatCompactNumber(double value) {
        String formatted = String.format(Locale.ROOT, "%.1f", value);
        return formatted.endsWith(".0") ? formatted.substring(0, formatted.length() - 2) : formatted;
    }

    private boolean hasActiveShield(@NotNull AstEntity victim) {
        return maxShield(victim) > 0.0D && currentShield(victim) > 0.0D;
    }

    private boolean isDirectDamage(@NotNull DamageSource source) {
        return source == DamageSource.NORMAL_ATTACK || source == DamageSource.SKILL;
    }

    private double fixedHealthDamage(@Nullable AstEntity attacker) {
        return attacker == null || !attacker.isManaged()
                ? 0.0D
                : Math.max(0.0D, attacker.statValue(StatusType.FIXED_HEALTH_DAMAGE));
    }

    private void startShieldRecharge(@NotNull AstEntity victim, long nowMs) {
        if (victim.isPlayer() && victim.player() != null) {
            statusService.startShieldRecharge(victim.player(), nowMs);
            return;
        }
        if (!victim.isMob() || victim.mob() == null || !victim.mob().template().shield().rechargeable()) {
            return;
        }
        double baseSeconds = Math.max(0.0D, victim.mob().template().shield().rechargeTimeSeconds());
        long durationMs = reducedDurationMs(
                baseSeconds * 1000.0D,
                victim.statValue(StatusType.SHIELD_RECHARGE_REDUCTION)
        );
        victim.mob().startShieldRecharge(nowMs, durationMs);
    }

    private void applyShieldRechargeDelay(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result,
            @NotNull DamageSource source
    ) {
        if (attacker == null || !attacker.isManaged() || !isDirectDamage(source) || result.evaded()) {
            return;
        }
        boolean eligibleHit = result.shieldBroken() || effectiveHealthDamage(victim, result) > 0.0D;
        if (!eligibleHit || !isShieldRecharging(victim)) {
            return;
        }
        double chance = attacker.statValue(StatusType.SHIELD_RECHARGE_DELAY_CHANCE);
        if (chance <= 0.0D
                || (chance < 100.0D && ThreadLocalRandom.current().nextDouble(100.0D) >= chance)) {
            return;
        }
        double rawSeconds = Math.max(0.0D, attacker.statValue(StatusType.SHIELD_RECHARGE_DELAY_SECONDS));
        if (rawSeconds <= 0.0D) {
            return;
        }
        if (victim.isPlayer() && victim.player() != null) {
            statusService.extendShieldRecharge(victim.player(), rawSeconds);
            return;
        }
        if (victim.isMob() && victim.mob() != null) {
            long additionalMs = reducedDurationMs(
                    rawSeconds * 1000.0D,
                    victim.statValue(StatusType.SHIELD_RECHARGE_REDUCTION)
            );
            victim.mob().extendShieldRecharge(additionalMs);
        }
    }

    private boolean isShieldRecharging(@NotNull AstEntity victim) {
        if (victim.isPlayer() && victim.player() != null) {
            return statusService.getShieldRechargeState(victim.player()) != null;
        }
        return victim.isMob() && victim.mob() != null && victim.mob().shieldRechargeState() != null;
    }

    private double effectiveHealthDamage(@NotNull AstEntity victim, @NotNull DamageResult result) {
        if (result.finalDamage() <= 0.0D) {
            return 0.0D;
        }
        if (victim.isPlayer()) {
            return Math.min(Math.max(0.0D, victim.currentHealth()), result.finalDamage());
        }
        if (victim.isMob() && victim.mob() != null) {
            double minimumHealth = victim.mob().nonLethal() ? 1.0D : 0.0D;
            return Math.min(
                    Math.max(0.0D, victim.mob().currentHealth() - minimumHealth),
                    result.finalDamage()
            );
        }
        return 0.0D;
    }

    private void completeShieldRechargeIfReady(@NotNull AstEntity victim, long nowMs) {
        if (victim.isPlayer() && victim.player() != null) {
            statusService.completeShieldRechargeIfReady(victim.player(), nowMs);
            return;
        }
        if (victim.isMob() && victim.mob() != null) {
            victim.mob().completeShieldRechargeIfReady(nowMs);
        }
    }

    private long reducedDurationMs(double rawDurationMs, double reductionPercent) {
        double reduction = Math.clamp(reductionPercent, 0.0D, 100.0D);
        double reduced = Math.max(0.0D, rawDurationMs) * (1.0D - reduction / 100.0D);
        return reduced >= Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, Math.round(reduced));
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
            return victim.mob().shieldDisplayCapacity();
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
        List<Location> particles = new ArrayList<>(18);
        for (int i = 0; i < 18; i++) {
            double yaw = Math.toRadians(i * 20.0D);
            double pitch = Math.toRadians((i % 6 - 2.5D) * 18.0D);
            double cosPitch = Math.cos(pitch);
            particles.add(center.clone().add(
                    Math.cos(yaw) * cosPitch * radius,
                    Math.sin(pitch) * radius,
                    Math.sin(yaw) * cosPitch * radius
            ));
        }
        particleDisplayService.spawnForNearbyViewers(
                center,
                particles,
                broken ? SharedParticleDefinitions.SHIELD_BREAK_DUST : SharedParticleDefinitions.SHIELD_HIT_DUST
        );
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
