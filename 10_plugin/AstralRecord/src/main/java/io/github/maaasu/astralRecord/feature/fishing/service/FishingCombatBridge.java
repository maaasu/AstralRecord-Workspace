package io.github.maaasu.astralRecord.feature.fishing.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.fishing.message.FishingMsgId;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import org.bukkit.entity.Fish;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.DoubleConsumer;

/** 釣りセッションの実魚を通常の Mob 戦闘へ一時的に接続します。 */
public final class FishingCombatBridge {
    private static final String TEMPLATE_ID = "__fishing_prototype_combat__";
    private final MobService mobService;
    private final Map<UUID, Registration> registrations = new HashMap<>();

    /** @param mobService 実魚を登録する Mob 管理サービス */
    public FishingCombatBridge(@NotNull MobService mobService) {
        this.mobService = mobService;
    }

    /**
     * 釣り魚の実体を通常戦闘へ登録します。呼び出し元はメインスレッドで魚の生存を確認します。
     * @param owner 釣りの所有者
     * @param fish 既に生成済みの実魚
     * @param maxHealth 独自最大 HP
     * @param canReceiveDamage 現在の釣り状態と接水状態を判定する関数
     * @param onHealthChanged 有効なダメージ後の現在 HP 通知
     * @param onDefeated 討伐確定通知。通常 Mob 報酬は発生しません
     * @return 登録解除と位置同期のハンドル
     * @throws IllegalArgumentException 魚が無効、または最大HPが正の有限値でない場合
     */
    public @NotNull Handle register(@NotNull AstPlayer owner, @NotNull Fish fish, double maxHealth,
                                    @NotNull BooleanSupplier canReceiveDamage,
                                    @NotNull DoubleConsumer onHealthChanged,
                                    @NotNull Runnable onDefeated) {
        if (!fish.isValid() || !Double.isFinite(maxHealth) || maxHealth <= 0.0D) {
            throw new IllegalArgumentException("Fishing combat requires a living fish and positive HP");
        }
        MobTemplate template = new MobTemplate(1, TEMPLATE_ID, MobCategory.ENEMY,
            FishingFeedback.text(FishingMsgId.FISH_NAME),
            null, 1, fish.getType(), false, null, List.of(), List.of(), null,
            MobEquipmentConfig.EMPTY, List.of(new MobBaseStat(StatusType.MAX_HEALTH.name(), maxHealth)),
            MobShieldConfig.EMPTY, MobIdleConfig.defaults(), false,
            MobInteractionsConfig.EMPTY, null, null, null);
        MobInstance instance = mobService.registerExistingEntity(template, fish);
        instance.scriptedAction(true);
        instance.keepWhenUnobserved(true);
        fish.setInvulnerable(false);
        registrations.put(instance.instanceId(), new Registration(owner.getBukkit().getUniqueId(),
            fish, instance, canReceiveDamage, onHealthChanged, onDefeated));
        return new Handle(instance.instanceId());
    }

    /** @param mob 対象Mob @return 釣り魚として登録済みならtrue */
    public boolean isFishingMob(@NotNull MobInstance mob) {
        return TEMPLATE_ID.equals(mob.template().id());
    }

    /** @param mob 対象Mob @return 通常Mobまたは現在戦闘可能な釣り魚ならtrue */
    public boolean isAvailable(@NotNull MobInstance mob) {
        Registration registration = registrations.get(mob.instanceId());
        return registration == null ? !isFishingMob(mob) : registration.active();
    }

    /**
     * 発動者が魚を現在攻撃できるかを判定します。通常Mobでは常にtrueです。
     * @param ownerId 攻撃側プレイヤーまたはペット所有者UUID
     * @param mob 攻撃先
     * @return 所有者と状態が一致する場合true
     */
    public boolean canTarget(@NotNull UUID ownerId, @NotNull MobInstance mob) {
        Registration registration = registrations.get(mob.instanceId());
        return registration == null ? !isFishingMob(mob)
            : registration.ownerId.equals(ownerId) && registration.active();
    }

    /**
     * ダメージ元と釣り魚の所有権を照合します。環境由来や他人からのダメージは拒否します。
     * @param attacker 攻撃元。環境由来ではnull
     * @param victim 被弾先
     * @return 通常Mobか、有効な所有者攻撃ならtrue
     */
    public boolean canDamage(AstEntity attacker, @NotNull AstEntity victim) {
        MobInstance mob = victim.mob();
        if (mob == null) return true;
        Registration registration = registrations.get(mob.instanceId());
        if (registration == null) return !isFishingMob(mob);
        AstPlayer owner = attacker == null ? null : attacker.combatOwner();
        return owner != null && canTarget(owner.getBukkit().getUniqueId(), mob);
    }

    /** @param mob ダメージを受けた魚。登録済みならHPを通知します。 */
    public void healthChanged(@NotNull MobInstance mob) {
        Registration registration = registrations.get(mob.instanceId());
        if (registration != null) registration.onHealthChanged.accept(mob.currentHealth());
    }

    /**
     * 魚を討伐として終了します。通常Mobの報酬経路は呼びません。
     * @param mob HPが0になった魚
     * @return 釣り魚を処理した場合true
     */
    public boolean defeat(@NotNull MobInstance mob) {
        Registration registration = registrations.remove(mob.instanceId());
        if (registration == null) {
            if (!isFishingMob(mob)) return false;
            mob.state(MobState.DEAD);
            mobService.destroy(mob.instanceId());
            return true;
        }
        mob.state(MobState.DEAD);
        mobService.destroy(mob.instanceId());
        registration.onDefeated.run();
        return true;
    }

    /**
     * MobService側の破棄に追従し、コールバック参照を解放します。
     * @param instance 破棄対象の個体
     */
    public void forget(@NotNull MobInstance instance) {
        if (isFishingMob(instance)) instance.state(MobState.DEAD);
        registrations.remove(instance.instanceId());
    }

    /** セッションが保持する釣り魚の登録ハンドルです。 */
    public final class Handle {
        private final UUID instanceId;

        private Handle(UUID instanceId) { this.instanceId = instanceId; }

        /** @return 登録中の通常戦闘個体。解除済みの場合null */
        public MobInstance instance() {
            Registration registration = registrations.get(instanceId);
            return registration == null ? null : registration.instance;
        }

        /** 実魚テレポート後の座標を戦闘のターゲット選択へ同期します。 */
        public void syncLocation() {
            Registration registration = registrations.get(instanceId);
            if (registration != null && registration.fish.isValid()) {
                registration.instance.currentLocation(registration.fish.getLocation());
            }
        }

        /** 未討伐の魚を通常戦闘から外して実体を破棄します。重複呼び出しは無視します。 */
        public void unregister() {
            Registration registration = registrations.remove(instanceId);
            if (registration != null) {
                registration.instance.state(MobState.DEAD);
                mobService.destroy(instanceId);
            }
        }
    }

    private record Registration(UUID ownerId, Fish fish, MobInstance instance,
                                BooleanSupplier canReceiveDamage, DoubleConsumer onHealthChanged,
                                Runnable onDefeated) {
        private boolean active() {
            return fish.isValid() && !fish.isDead() && instance.state() != MobState.DEAD
                && canReceiveDamage.getAsBoolean();
        }
    }
}
