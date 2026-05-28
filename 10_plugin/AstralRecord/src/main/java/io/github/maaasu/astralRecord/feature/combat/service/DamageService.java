package io.github.maaasu.astralRecord.feature.combat.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageContext;
import io.github.maaasu.astralRecord.feature.combat.model.DamageResult;
import io.github.maaasu.astralRecord.feature.combat.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 戦闘ダメージ処理の入口となるサービスクラスです。
 * <p>
 * イベント・スキル・Mob 攻撃などからの呼び出しを受け、{@link AstEntity} を使って
 * プレイヤー / Mob の組み合わせに依らないダメージ確定処理を行います。
 */
public final class DamageService {

    private final StatusService statusService;
    private final DamageCalculator damageCalculator;

    /**
     * {@link DamageService} を構築します。
     *
     * @param statusService 被弾者の現在ステータス参照・更新に使用するサービス
     */
    public DamageService(@NotNull StatusService statusService) {
        this.statusService = statusService;
        this.damageCalculator = new DamageCalculator();
    }

    /**
     * Bukkit のエンティティ間ダメージイベントを処理します。
     * <p>
     * AstPlayer に解決できるプレイヤーが被弾者の場合、バニラダメージはキャンセルし、
     * AstralRecord 側の HP へダメージを反映します。
     *
     * @param event Bukkit のエンティティ間ダメージイベント
     */
    public void handleEntityDamage(@NotNull EntityDamageByEntityEvent event) {
        AstEntity attacker = resolveBukkitEntity(event.getDamager());
        AstEntity victim = resolveBukkitEntity(event.getEntity());
        DamageResult result = applyDamage(attacker, victim, event.getDamage(), AttackType.MELEE, DamageType.PHYSICAL);

        if (victim.isManaged()) {
            event.setDamage(0.0D);
            event.setCancelled(true);
            return;
        }

        event.setDamage(result.finalDamage());
    }

    /**
     * 攻撃者のステータスを基礎値としてダメージを確定し、被弾者へ適用します。
     * <p>
     * プレイヤー対プレイヤー、プレイヤー対 Mob、Mob 対プレイヤー、Mob 対 Mob の
     * いずれも同じ API で処理できます。
     *
     * @param attacker   攻撃者
     * @param victim     被弾者
     * @param attackType 攻撃種別
     * @param damageType ダメージ種別
     * @return ダメージ計算結果
     */
    public @NotNull DamageResult attack(
            @NotNull AstEntity attacker,
            @NotNull AstEntity victim,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType
    ) {
        return applyDamage(attacker, victim, 0.0D, attackType, damageType);
    }

    /**
     * 指定基礎ダメージを元にダメージを確定し、被弾者へ適用します。
     * <p>
     * 被弾者がプレイヤーの場合は {@link StatusService#consumeHp(AstPlayer, double)} で現在 HP を減算します。
     * 被弾者が Mob の場合は {@code MobInstance.currentHealth} を減算し、0 以下で DEAD へ遷移します。
     * Bukkit エンティティのみの場合、呼び出し元イベントへ反映するため計算結果だけを返します。
     *
     * @param attacker   攻撃者。環境ダメージなど攻撃者がない場合は null
     * @param victim     被弾者
     * @param baseDamage 外部から与えられた基礎ダメージ。0 以下の場合は攻撃者ステータスのみで計算
     * @param attackType 攻撃種別
     * @param damageType ダメージ種別
     * @return ダメージ計算結果
     */
    public @NotNull DamageResult applyDamage(
            @Nullable AstEntity attacker,
            @NotNull AstEntity victim,
            double baseDamage,
            @NotNull AttackType attackType,
            @NotNull DamageType damageType
    ) {
        ensureStatusLoaded(attacker);
        ensureStatusLoaded(victim);

        DamageContext context = new DamageContext(attacker, victim, baseDamage, attackType, damageType);
        DamageResult result = damageCalculator.calculate(context);
        applyDamageResult(attacker, victim, result);
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

        if (victim.isMob()) {
            var mob = victim.mob();
            mob.currentHealth(Math.max(0.0D, mob.currentHealth() - result.finalDamage()));
            if (attacker != null && attacker.isPlayer()) {
                mob.threatTable().add(attacker.id(), result.finalDamage());
                if (mob.state() == MobState.IDLE) {
                    mob.state(MobState.AGGRO);
                    mob.targetId(attacker.id());
                }
            }
            if (mob.currentHealth() <= 0.0D) {
                mob.state(MobState.DEAD);
            }
        }
    }

    private void ensureStatusLoaded(@Nullable AstEntity entity) {
        if (entity != null && entity.isPlayer()) {
            statusService.getStatus(entity.player());
        }
    }

    private @NotNull AstEntity resolveBukkitEntity(@NotNull Entity entity) {
        if (entity instanceof Player player) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                return AstEntity.player(astPlayer);
            }
        }
        return AstEntity.bukkit(entity);
    }
}
