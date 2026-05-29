package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageScaling;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobCombatService;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;

/**
 * custom combat のダメージ適用を一元化するサービスです。
 */
public final class DamageService {

    private final StatusService statusService;
    private final MobService mobService;
    private final MobCombatService mobCombatService;
    private final DamageCalculator damageCalculator;
    private final DisplayTextService displayTextService;
    private final PlayerSettingService playerSettingService;

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
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerSettingService playerSettingService
    ) {
        this.statusService = statusService;
        this.mobService = mobService;
        this.mobCombatService = mobCombatService;
        this.damageCalculator = new DamageCalculator();
        this.displayTextService = displayTextService;
        this.playerSettingService = playerSettingService;
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
        return applyDamage(attacker, victim, 0.0D, attackType, damageType, DamageScaling.ATTACKER_STATUS);
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
        return applyDamage(attacker, victim, baseDamage, attackType, damageType, DamageScaling.FIXED);
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
        return applyDamage(attacker, victim, baseDamage, AttackType.MAGIC, damageType, DamageScaling.FIXED);
    }

    /**
     * Bukkit Entity を combat で扱う統一エンティティへ解決します。
     *
     * @param entity Bukkit Entity
     * @return 解決済みエンティティ
     */
    public @NotNull AstEntity resolveEntity(@NotNull Entity entity) {
        if (entity instanceof Player player) {
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
            @NotNull DamageScaling scaling
    ) {
        ensureStatusLoaded(attacker);
        ensureStatusLoaded(victim);

        DamageContext context = new DamageContext(attacker, victim, baseDamage, attackType, damageType, scaling);
        DamageResult result = damageCalculator.calculate(context);
        applyDamageResult(attacker, victim, result);
        spawnDamageDisplay(attacker, victim, result);
        return result;
    }

    private void applyDamageResult(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull DamageResult result
    ) {
        if (result.finalDamage() <= 0.0D) {
            return;
        }

        if (victim.isPlayer()) {
            statusService.consumeHp(victim.player(), result.finalDamage());
            return;
        }

        if (!victim.isMob()) {
            return;
        }

        var mob = victim.mob();
        mob.currentHealth(Math.max(0.0D, mob.currentHealth() - result.finalDamage()));
        playMobHurtEffect(mob.bukkitEntityId());
        if (attacker != null && attacker.isPlayer()) {
            mob.threatTable().add(attacker.id(), result.finalDamage());
            if (mob.state() == MobState.IDLE) {
                mob.state(MobState.AGGRO);
                mob.targetId(attacker.id());
            }
        }
        if (mob.currentHealth() <= 0.0D) {
            mob.state(MobState.DEAD);
            Location deathLocation = mob.currentLocation();
            playMobDeathEffect(mob.bukkitEntityId(), deathLocation);
            MobDropResult dropResult = mobCombatService.handleDeath(mob);
            spawnMobDeathResult(deathLocation, victim.name(), dropResult);
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
        if (result.finalDamage() <= 0.0D) {
            return;
        }
        if (!shouldDisplayDamage(attacker, victim)) {
            return;
        }
        displayTextService.spawnDamageNumber(victim.location().clone().add(0.0D, 1.2D, 0.0D), result.finalDamage(), false);
    }

    private boolean shouldDisplayDamage(@Nullable AstEntity attacker, @NotNull AstEntity victim) {
        if (attacker != null && attacker.isPlayer()
                && !playerSettingService.isDamageLogDisplayEnabled(attacker.player().getUser().getUuid())) {
            return false;
        }
        return !victim.isPlayer() || playerSettingService.isDamageLogDisplayEnabled(victim.player().getUser().getUuid());
    }

    private void playMobHurtEffect(@Nullable UUID entityId) {
        Entity entity = resolveBukkitEntity(entityId);
        if (entity instanceof LivingEntity livingEntity) {
            livingEntity.playHurtAnimation(0.0F);
            livingEntity.setNoDamageTicks(0);
        }
    }

    private void playMobDeathEffect(@Nullable UUID entityId, @NotNull Location location) {
        Entity entity = resolveBukkitEntity(entityId);
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        world.spawnParticle(Particle.POOF, location.clone().add(0.0D, 0.8D, 0.0D), 28, 0.45D, 0.35D, 0.45D, 0.02D);
        world.spawnParticle(Particle.CRIT, location.clone().add(0.0D, 0.9D, 0.0D), 18, 0.35D, 0.3D, 0.35D, 0.1D);
        world.playSound(location, Sound.ENTITY_GENERIC_DEATH, 0.8F, 1.1F);
    }

    private void spawnMobDeathResult(
            @NotNull Location deathLocation,
            @NotNull String mobName,
            @NotNull MobDropResult result
    ) {
        displayTextService.spawnResultText(deathLocation.clone().add(0.0D, 1.8D, 0.0D), formatMobDeathResult(mobName, result));
    }

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
        for (Map.Entry<String, Integer> item : result.items()) {
            if (!first) {
                text.append("&7, &f");
            }
            text.append(item.getKey()).append(" x").append(item.getValue());
            first = false;
        }
        return text.toString();
    }

    private @Nullable Entity resolveBukkitEntity(@Nullable UUID entityId) {
        return entityId == null ? null : Bukkit.getEntity(entityId);
    }
}
