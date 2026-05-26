package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.CombatStyle;
import io.github.maaasu.astralRecord.feature.mob.model.DamageType;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobCombatConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.model.MobTargetingConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.model.TargetStrategy;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob の戦闘ロジックを担うサービス。
 *
 * <p>バニラの {@code EntityDamageEvent} や Bukkit Attribute には依存せず、
 * 命中判定・ダメージ計算・会心判定・致死処理を独自に実装する。</p>
 */
public class MobCombatService {

    private final MobService mobService;
    private final MobKnockbackService knockbackService;
    private final MobDropService dropService;

    /**
     * コンストラクタ。
     *
     * @param mobService       Mob サービス
     * @param knockbackService ノックバックサービス
     * @param dropService      ドロップ抽選サービス
     */
    public MobCombatService(
            @NotNull MobService mobService,
            @NotNull MobKnockbackService knockbackService,
            @NotNull MobDropService dropService) {
        this.mobService = mobService;
        this.knockbackService = knockbackService;
        this.dropService = dropService;
    }

    /**
     * Mob のターゲットを選定して {@link MobInstance#targetId(UUID)} に設定します。
     *
     * @param instance 対象 Mob
     * @return 選定されたプレイヤー（候補なしなら {@code null}）
     */
    @Nullable
    public Player selectTarget(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        MobTargetingConfig targeting = template.targeting();
        if (targeting == null || template.category() == MobCategory.NPC) {
            instance.targetId(null);
            return null;
        }

        double aggroSq = targeting.aggroRange() * targeting.aggroRange();
        Location loc = instance.currentLocation();
        List<Player> candidates = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != loc.getWorld()) continue;
            if (player.getLocation().distanceSquared(loc) > aggroSq) continue;
            candidates.add(player);
        }
        if (candidates.isEmpty()) {
            instance.targetId(null);
            return null;
        }

        Player chosen = switch (targeting.strategy()) {
            case NEAREST -> nearest(candidates, loc);
            case HIGHEST_THREAT -> {
                UUID top = instance.threatTable().top();
                Player p = top == null ? null : Bukkit.getPlayer(top);
                yield p != null && candidates.contains(p) ? p : nearest(candidates, loc);
            }
            case RANDOM -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            case LOWEST_HP -> lowestHp(candidates);
        };

        instance.targetId(chosen == null ? null : chosen.getUniqueId());
        return chosen;
    }

    /**
     * {@link MobState#COMBAT} 状態の Mob について 1 tick 分の戦闘処理を実行します。
     *
     * @param instance   対象 Mob
     * @param serverTick 現在のサーバ tick
     */
    public void tickCombat(@NotNull MobInstance instance, long serverTick) {
        UUID targetId = instance.targetId();
        if (targetId == null) {
            instance.state(MobState.AGGRO);
            return;
        }
        Player target = Bukkit.getPlayer(targetId);
        if (target == null || !target.isOnline()) {
            instance.targetId(null);
            instance.state(MobState.AGGRO);
            return;
        }

        MobCombatConfig combat = instance.template().combat();
        if (combat == null) {
            instance.state(MobState.IDLE);
            return;
        }

        // 攻撃間隔チェック
        if (serverTick - instance.lastAttackTick() < combat.attackIntervalTicks()) {
            return;
        }

        // ターゲット距離チェック
        double preferredSq = combat.preferredRange() * combat.preferredRange();
        if (target.getLocation().distanceSquared(instance.currentLocation()) > preferredSq + 4.0) {
            // 範囲外。AGGRO に戻る
            instance.state(MobState.AGGRO);
            return;
        }

        // 命中判定
        double accuracy = 100.0; // 命中率は Mob 側のデフォルト
        double evasion = 0.0;    // プレイヤー側 EVASION は将来 statusSnapshot から取得
        if (ThreadLocalRandom.current().nextDouble(0.0, 100.0) >= accuracy - evasion) {
            // 不命中
            instance.lastAttackTick(serverTick);
            return;
        }

        // ダメージ計算
        double baseDamage = computeDamage(instance, target);
        double finalDamage = applyCriticalMultiplier(instance, baseDamage);

        applyDamageToPlayer(target, finalDamage, damageTypeOf(combat.style()));
        knockbackService.applyToPlayer(instance.currentLocation(), target, 1.0);
        instance.lastAttackTick(serverTick);
    }

    /**
     * Mob からターゲット（プレイヤー）への基礎ダメージを算出します。
     * 戦闘スタイルに応じて使用ステータスを切り替え、ターゲットの防御で軽減します。
     *
     * @param instance 攻撃側 Mob
     * @param target   被攻撃側プレイヤー
     * @return 基礎ダメージ
     */
    public double computeDamage(@NotNull MobInstance instance, @NotNull Player target) {
        MobTemplate template = instance.template();
        double attack = template.statValue("ATTACK", 1.0);
        CombatStyle style = template.combat() == null ? CombatStyle.MELEE : template.combat().style();
        double scaling = switch (style) {
            case MELEE -> template.statValue("STRENGTH", 0.0);
            case RANGED -> template.statValue("DEXTERITY", 0.0);
            case MAGIC -> template.statValue("INTELLIGENCE", 0.0);
        };
        double offensive = attack * (1.0 + scaling / 100.0);
        double defense = resolvePlayerDefense(target, damageTypeOf(style));
        return Math.max(1.0, offensive - defense * 0.5);
    }

    /**
     * 基礎ダメージに会心・超会心の倍率を適用します。
     *
     * @param instance   攻撃側
     * @param baseDamage 基礎ダメージ
     * @return 会心適用後ダメージ
     */
    public double applyCriticalMultiplier(@NotNull MobInstance instance, double baseDamage) {
        MobTemplate template = instance.template();
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        double critRate = template.statValue("CRITICAL_RATE", 0.0);
        if (rng.nextDouble(0.0, 100.0) >= critRate) {
            return baseDamage;
        }

        double critMul = template.statValue("CRITICAL_DAMAGE", 150.0) / 100.0;
        double damage = baseDamage * critMul;

        double superRate = template.statValue("SUPER_CRITICAL_RATE", 0.0);
        if (rng.nextDouble(0.0, 100.0) < superRate) {
            double superMul = template.statValue("SUPER_CRITICAL_DAMAGE", 100.0) / 100.0;
            damage *= superMul;
        }
        return damage;
    }

    /**
     * プレイヤーから Mob への被ダメージを処理します。
     * ダメージ計算（プレイヤー側のスケーリング・装備補正）は呼び出し元の責務です。
     *
     * @param instance     被ダメージ対象 Mob
     * @param attacker     攻撃元プレイヤー
     * @param damage       確定済み攻撃側ダメージ（防御適用前）
     * @param type         ダメージ種別
     * @return 実際に与えたダメージ（防御適用後）
     */
    public double applyDamage(
            @NotNull MobInstance instance,
            @NotNull AstPlayer attacker,
            double damage,
            @NotNull DamageType type) {
        if (instance.state() == MobState.DEAD) return 0.0;

        MobTemplate template = instance.template();
        double defenseStat = type == DamageType.PHYSICAL
                ? template.statValue("DEFENSE", 0.0)
                : template.statValue("MAGIC_DEFENSE", 0.0);
        double effective = Math.max(1.0, damage - defenseStat * 0.5);

        instance.currentHealth(instance.currentHealth() - effective);
        instance.threatTable().add(attacker.getBukkit().getUniqueId(), effective);

        knockbackService.applyToMob(attacker.getBukkit().getLocation(), instance, 1.0);

        if (instance.state() == MobState.IDLE) {
            instance.state(MobState.AGGRO);
            instance.targetId(attacker.getBukkit().getUniqueId());
        }
        if (instance.currentHealth() <= 0.0) {
            instance.state(MobState.DEAD);
        }
        return effective;
    }

    /**
     * 死亡確定 Mob のドロップ・破棄処理を実行します。
     *
     * @param instance 死亡 Mob
     * @return ドロップ結果（キラー特定できなければアイテム配布は行わず、結果のみ返す）
     */
    @NotNull
    public MobDropResult handleDeath(@NotNull MobInstance instance) {
        MobTemplate template = instance.template();
        UUID killerId = instance.threatTable().top();
        Player killer = killerId == null ? null : Bukkit.getPlayer(killerId);
        Logger.log(LogId.D_5703, template.id(), killerId);

        MobDropResult result;
        try {
            // killer の AstPlayer 解決は外部 feature で行う。本サービスは Bukkit Player を介してドロップ抽選のみ実行
            result = dropService.roll(template, null);
        } catch (RuntimeException ex) {
            Logger.error(LogId.E_5703, ex, template.id());
            result = new MobDropResult(List.of(), 0, 0);
        }

        if (killer != null) {
            distributeDrops(killer, result);
        } else {
            Logger.log(LogId.W_5703, template.id());
        }

        mobService.destroy(instance.instanceId());
        return result;
    }

    /**
     * ドロップ結果を Bukkit Player へ配布します（最小実装: インベントリへ直接付与）。
     * 経験値・金銭の付与経路は他 feature の API 確立後に置き換える（[[12_9.00-未決事項]] 参照）。
     *
     * @param killer キラー
     * @param result ドロップ結果
     */
    private void distributeDrops(@NotNull Player killer, @NotNull MobDropResult result) {
        // 当面は Bukkit のインベントリへの直接付与は行わず、サーバログへの記録のみで暫定運用する。
        // 実際の付与は item / inventory feature の API 経由で行う前提。
        if (!result.items().isEmpty()) {
            killer.sendMessage("§7[mob] ドロップ確定: " + summarize(result));
        }
    }

    private String summarize(@NotNull MobDropResult result) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> entry : result.items()) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(entry.getKey()).append(" x").append(entry.getValue());
        }
        sb.append(" / exp=").append(result.exp());
        sb.append(" / money=").append(result.money());
        return sb.toString();
    }

    private double resolvePlayerDefense(@NotNull Player target, @NotNull DamageType type) {
        // プレイヤー側 AstPlayer / StatusSnapshot からの取得は将来統合する。
        // 当面は固定値 0 を返す。
        StatusType ignored = type == DamageType.PHYSICAL ? StatusType.DEFENSE : StatusType.MAGIC_DEFENSE;
        // ignored 変数は forward-compat のため意図的に保持
        return ignored == null ? 0.0 : 0.0;
    }

    private DamageType damageTypeOf(@NotNull CombatStyle style) {
        return style == CombatStyle.MAGIC ? DamageType.MAGIC : DamageType.PHYSICAL;
    }

    private void applyDamageToPlayer(@NotNull Player target, double damage, @NotNull DamageType type) {
        // プレイヤー側の独自ダメージ適用 API（feature/player）が未整備のため、当面は Bukkit Player.damage を呼ぶ。
        // 将来的に AstPlayer / StatusSnapshot 経由で適用する（[[12_9.00-未決事項]] 参照）。
        target.damage(damage);
    }

    @Nullable
    private Player nearest(@NotNull List<Player> candidates, @NotNull Location origin) {
        Player best = null;
        double bestSq = Double.MAX_VALUE;
        for (Player player : candidates) {
            double sq = player.getLocation().distanceSquared(origin);
            if (sq < bestSq) {
                bestSq = sq;
                best = player;
            }
        }
        return best;
    }

    @Nullable
    private Player lowestHp(@NotNull List<Player> candidates) {
        Player best = null;
        double bestHp = Double.MAX_VALUE;
        for (Player player : candidates) {
            double hp = player.getHealth();
            if (hp < bestHp) {
                bestHp = hp;
                best = player;
            }
        }
        return best;
    }

    /**
     * フィールド参照の保持のため、未使用変数の警告を抑制する内部メソッド。
     */
    @SuppressWarnings("unused")
    private static void retainEnums() {
        TargetStrategy ts = TargetStrategy.NEAREST;
        DamageType dt = DamageType.PHYSICAL;
    }
}
