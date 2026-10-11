package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * 専用フィールドの写本守・往復ゲート・回復弾・180秒の爆発を共通5tick時計で管理します。
 * 実体とHPはMobService、致死ダメージと参加権限は呼び出し側を正本とします。
 */
final class SeleneaSupportController {
    static final String FIELD_WORLD_ID = "vanaheim_boss_selenea_field";
    private static final String WARD_EFFECT = "selenea_support_ward";
    private static final String WRATH_EFFECT = "selenea_support_wrath";
    private static final long HEAL_INTERVAL = 60L;
    private static final long ORB_DURATION = 40L;
    private static final long DETONATION_DELAY = 3_600L;
    private static final long GATE_COOLDOWN = 40L;
    private static final double DETONATION_RADIUS_SQUARED = 60.0D * 60.0D;
    private static final double HEAL_RATIO = 0.03D;

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final BiConsumer<MobInstance, Player> lethalHit;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final Map<UUID, UUID> addOwners = new HashMap<>();
    private Predicate<MobInstance> fieldResolver = boss -> false;
    private @Nullable TemporarySkillEffectService effects;
    private long currentTick;

    /**
     * メインスレッドで呼び出す支援処理を作成します。
     * @param mobs 召喚・固定・回復・破棄を行うMobサービス
     * @param particles 密度設定と近傍閲覧者を考慮する演出サービス
     * @param participants 専用フィールドの生存する確定参加者を返す関数
     * @param lethalHit 所有ボスと命中参加者を共通RPG致死処理へ委譲する関数
     */
    SeleneaSupportController(@NotNull MobService mobs, @NotNull ParticleDisplayService particles,
            @NotNull Function<MobInstance, List<Player>> participants,
            @NotNull BiConsumer<MobInstance, Player> lethalHit) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.lethalHit = lethalHit;
    }

    /**
     * マスタIDと実行時world登録を使う専用フィールド判定を設定します。
     * @param resolver ボスのspawnLocationに対応するマスタworldを確認する関数
     */
    void setFieldResolver(@NotNull Predicate<MobInstance> resolver) { fieldResolver = resolver; }

    /**
     * 他の一時効果と乗算できる補正サービスを設定します。
     * @param service 防御3倍・与ダメージ2倍を適用するサービス。nullでは補正を付与しません
     */
    void setTemporarySkillEffectService(@Nullable TemporarySkillEffectService service) { effects = service; }

    /**
     * 専用マスタworldまたはその登録済み実行時worldで召喚された魔女か判定します。
     * @param boss 判定するMob
     * @return 対象ボスIDと専用フィールドの両方が一致すればtrue
     */
    boolean isField(@NotNull MobInstance boss) {
        return SeleneaEncounterController.BOSS_ID.equals(boss.template().id())
                && boss.spawnLocation().getWorld() != null && fieldResolver.test(boss);
    }

    /**
     * 写本守の所有関係を返します。
     * @param instanceId MobインスタンスID
     * @return 現在の遭遇が所有する写本守ならtrue
     */
    boolean ownsAdd(@NotNull UUID instanceId) { return addOwners.containsKey(instanceId); }

    /**
     * 写本守への攻撃権限を所有ボスの確定参加者で確認するため、所有ボスを返します。
     * @param instanceId 写本守のMobインスタンスID
     * @return 所有ボス。追跡対象外または既に解除された場合はnull
     */
    @Nullable MobInstance ownerForAdd(@NotNull UUID instanceId) {
        UUID owner = addOwners.get(instanceId);
        Encounter state = owner == null ? null : encounters.get(owner);
        return state == null ? null : state.boss;
    }

    /**
     * 初撃前にも召喚を確定し、生存する写本守1体ごとに25%の軽減を返します。
     * Mob生成の副作用があるため、ダメージ処理のメインスレッドだけで呼び出します。
     * @param boss 被ダメージ対象
     * @param tick 現在のBossMechanicService共通時計。初召喚の180秒期限にも使用します
     * @return 通常Mobは1、対象魔女は0以上1以下の被ダメージ倍率
     */
    double incomingDamageMultiplier(@NotNull MobInstance boss, long tick) {
        currentTick = tick;
        if (!isField(boss) || boss.state() == MobState.DEAD || boss.currentHealth() <= 0.0D) return 1.0D;
        Encounter state = ensureEncounter(boss);
        if (state == null) return 1.0D;
        syncAddHealth(state);
        refreshBuffs(state);
        long alive = state.adds.values().stream().filter(this::isAlive).count();
        return Math.max(0.0D, 1.0D - alive * 0.25D);
    }

    /**
     * IDLE中も召喚とカウントダウンを進めます。追加のScheduler taskは作成しません。
     * @param boss 生存する専用フィールドの魔女
     * @param entity 有効なボス実体
     * @param tick 単調増加する共通5tick時計
     */
    void tick(@NotNull MobInstance boss, @NotNull Entity entity, long tick) {
        currentTick = tick;
        if (!isField(boss) || boss.state() == MobState.DEAD || boss.currentHealth() <= 0.0D
                || !entity.isValid() || entity.isDead() || entity.getWorld() != boss.spawnLocation().getWorld()) {
            clear(boss.instanceId());
            return;
        }
        Encounter state = ensureEncounter(boss);
        if (state == null) return;
        syncAddHealth(state);
        for (Add add : state.adds.values()) {
            if (isAlive(add)) {
                MobInstance instance = mobs.getInstance(add.instanceId);
                mobs.holdPosition(instance, add.anchor);
            }
        }
        refreshBuffs(state);
        List<Player> active = participants.apply(boss).stream()
                .filter(player -> player.isOnline() && player.isValid() && !player.isDead()
                        && player.getWorld() == state.center.getWorld()).toList();
        advanceOrbs(state, tick);
        if (encounters.get(boss.instanceId()) != state) return;
        if (tick >= state.detonateAt && !state.detonated) {
            state.detonated = true;
            detonate(state, active);
            if (encounters.get(boss.instanceId()) != state) return;
            refreshBuffs(state);
        }
        if (tick >= state.nextHealAt) {
            state.nextHealAt = tick + HEAL_INTERVAL;
            launchHealingOrbs(state, tick);
        }
        teleportParticipants(state, active, tick);
        if (encounters.get(boss.instanceId()) != state) return;
        if (tick >= state.nextRenderAt) {
            state.nextRenderAt = tick + 10L;
            renderGates(state);
        }
    }

    /**
     * ボスの遭遇または個別写本守の所有関係を解除します。破棄通知の再入に対応します。
     * @param instanceId ボスまたは写本守のMobインスタンスID
     */
    void clear(@NotNull UUID instanceId) {
        Encounter state = encounters.remove(instanceId);
        if (state == null) {
            UUID owner = addOwners.remove(instanceId);
            Encounter owningState = owner == null ? null : encounters.get(owner);
            if (owningState != null) {
                owningState.orbs.removeIf(orb -> orb.sourceId.equals(instanceId) || orb.targetId.equals(instanceId));
                refreshBuffs(owningState);
            }
            return;
        }
        clearBuffs(state.boss.instanceId());
        state.orbs.clear();
        state.portalCooldowns.clear();
        for (Add add : state.adds.values()) addOwners.remove(add.instanceId);
        for (Add add : state.adds.values()) mobs.destroy(add.instanceId);
    }

    /** プラグイン停止時に全写本守とゲート・弾・一時補正を解除します。 */
    void clearAll() {
        for (UUID bossId : List.copyOf(encounters.keySet())) clear(bossId);
        addOwners.clear();
    }

    /**
     * ボスの死亡・実体消失・worldアンロードで追跡から外れた遭遇を回収します。
     * @param activeBossIds 今回の走査で実体の生存を確認したボスID
     */
    void retain(@NotNull Set<UUID> activeBossIds) {
        for (UUID bossId : List.copyOf(encounters.keySet())) {
            if (!activeBossIds.contains(bossId)) clear(bossId);
        }
    }

    /** 四体を一度だけ生成し、一体でも生成できなければ部分召喚を回収します。 */
    private @Nullable Encounter ensureEncounter(MobInstance boss) {
        Encounter existing = encounters.get(boss.instanceId());
        if (existing != null) return existing;
        // MobInstanceのHP下限1を避け、要求の実行時HP/6を厳密に設定できる個体だけを扱います。
        if (!Double.isFinite(boss.maxHealth()) || boss.maxHealth() < 6.0D) return null;
        Encounter state = new Encounter(boss, currentTick);
        encounters.put(boss.instanceId(), state);
        try {
            for (Role role : Role.values()) {
                Location anchor = offset(state.center, role, 60.0D, 3.0D);
                MobInstance add = mobs.spawn(role.templateId, anchor);
                if (add == null) {
                    clear(boss.instanceId());
                    return null;
                }
                state.adds.put(role, new Add(add.instanceId(), anchor));
                addOwners.put(add.instanceId(), boss.instanceId());
                add.maxHealth(boss.maxHealth() / 6.0D);
                add.currentHealth(add.maxHealth());
                add.rewardEligible(false);
                add.keepWhenUnobserved(true);
                add.scriptedAction(true);
                mobs.stopPathfinding(add);
                Entity entity = mobs.entityController().getEntity(add);
                if (entity != null) entity.setGravity(false);
                mobs.holdPosition(add, anchor);
            }
            refreshBuffs(state);
            return state;
        } catch (RuntimeException exception) {
            clear(boss.instanceId());
            throw exception;
        }
    }

    /** 登録済みMobとBukkit実体の両方で、生存を確認します。 */
    private boolean isAlive(@Nullable Add add) {
        if (add == null) return false;
        MobInstance instance = mobs.getInstance(add.instanceId);
        if (instance == null || instance.state() == MobState.DEAD || instance.currentHealth() <= 0.0D) return false;
        Entity entity = mobs.entityController().getEntity(instance);
        return entity != null && entity.isValid() && !entity.isDead();
    }

    /** 後から適用された参加人数HP倍率にも、現在HP割合を維持して最大HP/6を同期します。 */
    private void syncAddHealth(Encounter state) {
        double maximum = state.boss.maxHealth() / 6.0D;
        if (!Double.isFinite(maximum) || maximum < 1.0D) return;
        for (Add add : state.adds.values()) {
            MobInstance instance = mobs.getInstance(add.instanceId);
            if (instance == null || instance.state() == MobState.DEAD || instance.currentHealth() <= 0.0D
                    || Double.compare(instance.maxHealth(), maximum) == 0) continue;
            double fraction = Math.clamp(instance.currentHealth() / instance.maxHealth(), 0.0D, 1.0D);
            instance.maxHealth(maximum);
            instance.currentHealth(maximum * fraction);
        }
    }

    /** 封護は防御+200%、烈怒は与ダメージ+100%。異なる既存効果を消去しません。 */
    private void refreshBuffs(Encounter state) {
        if (effects == null) return;
        UUID bossId = state.boss.instanceId();
        if (isAlive(state.adds.get(Role.WARD))) effects.applyDefenseMultiplier(bossId, WARD_EFFECT, 40L, 3.0D);
        else effects.clear(bossId, WARD_EFFECT);
        if (isAlive(state.adds.get(Role.WRATH))) effects.apply(bossId, WRATH_EFFECT, 40L, 1.0D, 2.0D, 1.0D);
        else effects.clear(bossId, WRATH_EFFECT);
    }

    /** このコントローラーが付与した二種類の効果だけを解除します。 */
    private void clearBuffs(UUID bossId) {
        if (effects == null) return;
        effects.clear(bossId, WARD_EFFECT);
        effects.clear(bossId, WRATH_EFFECT);
    }

    /** 月癒はボスへ一弾、慈癒は自分を除く三体の生存写本守へ各一弾を発射します。 */
    private void launchHealingOrbs(Encounter state, long tick) {
        Add lunar = state.adds.get(Role.LUNAR);
        if (isAlive(lunar)) launchOrb(state, lunar, state.boss.instanceId(), tick);
        Add mercy = state.adds.get(Role.MERCY);
        if (isAlive(mercy)) {
            for (Map.Entry<Role, Add> entry : state.adds.entrySet()) {
                if (entry.getKey() != Role.MERCY && isAlive(entry.getValue())) {
                    launchOrb(state, mercy, entry.getValue().instanceId, tick);
                }
            }
        }
    }

    /** 発射時の始点と対象を保持し、上限四弾・2秒の放物線軌道を登録します。 */
    private void launchOrb(Encounter state, Add source, UUID targetId, long tick) {
        MobInstance target = mobs.getInstance(targetId);
        if (target == null || state.orbs.size() >= 4) return;
        state.orbs.add(new Orb(source.instanceId, targetId, source.anchor.clone().add(0, 1.2D, 0), tick));
    }

    /** 弾の着弾時に対象の実行時最大HPの3%を共通回復通知経由で回復します。 */
    private void advanceOrbs(Encounter state, long tick) {
        List<Location> points = new ArrayList<>(16);
        Iterator<Orb> iterator = state.orbs.iterator();
        while (iterator.hasNext()) {
            Orb orb = iterator.next();
            Add source = state.adds.values().stream().filter(add -> add.instanceId.equals(orb.sourceId))
                    .findFirst().orElse(null);
            MobInstance target = mobs.getInstance(orb.targetId);
            if (!isAlive(source) || target == null || target.state() == MobState.DEAD
                    || target.currentHealth() <= 0.0D || target.currentLocation().getWorld() != state.center.getWorld()) {
                iterator.remove();
                continue;
            }
            Location destination = target.currentLocation().add(0, 1.2D, 0);
            double progress = Math.clamp((double) (tick - orb.startedAt) / ORB_DURATION, 0.0D, 1.0D);
            for (int index = 0; index < 3; index++) {
                double tailProgress = Math.max(0.0D, progress - index * 0.025D);
                points.add(arc(orb.origin, destination, tailProgress));
            }
            if (progress >= 1.0D) {
                iterator.remove();
                double recovered = mobs.recoverHealth(target, target.maxHealth() * HEAL_RATIO);
                if (encounters.get(state.boss.instanceId()) != state) return;
                if (recovered > 0.0D) particles.spawnForNearbyViewers(destination, SharedParticleDefinitions.SELENEA_SUPPORT_HEAL);
            }
        }
        particles.spawnForNearbyViewers(points, SharedParticleDefinitions.SELENEA_SUPPORT_ORB);
    }

    /** 始点と追尾終点をつなぐ、最大12mの放物線上の一点を計算します。 */
    private static Location arc(Location from, Location to, double progress) {
        return from.clone().add((to.getX() - from.getX()) * progress,
                (to.getY() - from.getY()) * progress + 48.0D * progress * (1.0D - progress),
                (to.getZ() - from.getZ()) * progress);
    }

    /** 各生存写本守の球形60m範囲へ一度だけ致死判定を委譲します。写本守と地形は維持します。 */
    private void detonate(Encounter state, List<Player> active) {
        List<Add> survivors = state.adds.values().stream().filter(this::isAlive).toList();
        for (Add add : survivors) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!isAlive(add)) continue;
            particles.spawnForNearbyViewers(add.anchor, SharedParticleDefinitions.SELENEA_SUPPORT_DETONATION);
            for (Player player : active) {
                if (!player.isDead() && player.getWorld() == add.anchor.getWorld()
                        && player.getLocation().distanceSquared(add.anchor) <= DETONATION_RADIUS_SQUARED) {
                    lethalHit.accept(state.boss, player);
                    if (encounters.get(state.boss.instanceId()) != state) return;
                }
            }
        }
    }

    /** プレイヤーごとに2秒の再転送制限を設け、同色ゲート間を往復させます。 */
    private void teleportParticipants(Encounter state, List<Player> active, long tick) {
        state.portalCooldowns.entrySet().removeIf(entry -> entry.getValue() <= tick);
        for (Player player : active) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!player.isOnline() || !player.isValid() || player.isDead()
                    || player.getWorld() != state.center.getWorld()) continue;
            if (state.portalCooldowns.containsKey(player.getUniqueId())) continue;
            for (Role role : Role.values()) {
                Location source = offset(state.center, role, 12.0D, 0.0D);
                Location destination = offset(state.center, role, 46.0D, 3.0D);
                Location location = player.getLocation();
                Location target = inGate(location, source) ? destination : inGate(location, destination) ? source : null;
                if (target == null) continue;
                if (PlayerTeleportService.teleport(player, target)) state.portalCooldowns.put(player.getUniqueId(), tick + GATE_COOLDOWN);
                break;
            }
        }
    }

    /** 足元位置を基準にゲートの水平半径1.6m・上下の通過範囲を判定します。 */
    private static boolean inGate(Location location, Location base) {
        if (location.getWorld() != base.getWorld()) return false;
        double y = location.getY() - base.getY();
        double x = location.getX() - base.getX();
        double z = location.getZ() - base.getZ();
        return y >= -0.5D && y <= 3.0D && x * x + z * z <= 1.6D * 1.6D;
    }

    /** 八個の縦リングを同じ形状で描画し、各方向に対応する色だけを変えます。 */
    private void renderGates(Encounter state) {
        if (state.center.getWorld() == null || state.center.getWorld().getPlayers().isEmpty()) return;
        for (Role role : Role.values()) {
            List<Location> points = new ArrayList<>(64);
            portalRing(offset(state.center, role, 12.0D, 0.0D), role, points);
            portalRing(offset(state.center, role, 46.0D, 3.0D), role, points);
            particles.spawnForNearbyViewers(points, role.particle());
        }
    }

    /** 方向へ直交する縦面に半径1.4mのリングを32点で配置します。 */
    private static void portalRing(Location base, Role role, List<Location> points) {
        for (int index = 0; index < 32; index++) {
            double angle = Math.PI * 2.0D * index / 32.0D;
            double tangent = Math.cos(angle) * 1.4D;
            points.add(base.clone().add(-role.z * tangent, 1.5D + Math.sin(angle) * 1.4D, role.x * tangent));
        }
    }

    /** ボスの実際の召喚位置から方向別の相対座標を生成します。 */
    private static Location offset(Location center, Role role, double radius, double y) {
        return center.clone().add(role.x * radius, y, role.z * radius);
    }

    /** マスタID、役割、座標方向の固定対応です。 */
    private enum Role {
        WARD("selenea_ward_page", 1, 0),
        LUNAR("selenea_lunar_page", -1, 0),
        WRATH("selenea_wrath_page", 0, 1),
        MERCY("selenea_mercy_page", 0, -1);

        private final String templateId;
        private final int x;
        private final int z;

        /** 役割に対応する召喚マスタと単位方向を保持します。 */
        Role(String templateId, int x, int z) { this.templateId = templateId; this.x = x; this.z = z; }

        /** 同形ゲートへ適用する役割別の共通色定義を返します。 */
        private SharedParticleDefinition particle() {
            return switch (this) {
                case WARD -> SharedParticleDefinitions.SELENEA_GATE_WARD;
                case LUNAR -> SharedParticleDefinitions.SELENEA_GATE_LUNAR;
                case WRATH -> SharedParticleDefinitions.SELENEA_GATE_WRATH;
                case MERCY -> SharedParticleDefinitions.SELENEA_GATE_MERCY;
            };
        }
    }

    /** 位置を固定する一体の写本守です。 */
    private static final class Add {
        private final UUID instanceId;
        private final Location anchor;

        /** 管理Mob IDと不変の固定位置を保存します。 */
        private Add(UUID instanceId, Location anchor) { this.instanceId = instanceId; this.anchor = anchor.clone(); }
    }

    /** 四体、最大四弾、参加者ごとの転送制限を所有する一遭遇です。 */
    private static final class Encounter {
        private final MobInstance boss;
        private final Location center;
        private final Map<Role, Add> adds = new EnumMap<>(Role.class);
        private final List<Orb> orbs = new ArrayList<>(4);
        private final Map<UUID, Long> portalCooldowns = new HashMap<>();
        private final long detonateAt;
        private long nextHealAt;
        private long nextRenderAt;
        private boolean detonated;

        /** 召喚基準を実際のboss.spawnLocationに固定し、全体の180秒期限を設定します。 */
        private Encounter(MobInstance boss, long tick) {
            this.boss = boss;
            center = boss.spawnLocation();
            detonateAt = tick + DETONATION_DELAY;
            nextHealAt = tick + HEAL_INTERVAL;
            nextRenderAt = tick;
        }
    }

    /** 回復弾の発射元・対象・発射位置・開始tickです。 */
    private record Orb(UUID sourceId, UUID targetId, Location origin, long startedAt) { }
}
