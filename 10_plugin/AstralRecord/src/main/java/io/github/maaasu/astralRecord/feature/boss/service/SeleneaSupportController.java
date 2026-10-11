package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobState;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.skill.active.service.TemporarySkillEffectService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Location;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
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
    private static final String WARD_EFFECT = "selenea_support_ward";
    private static final String WRATH_EFFECT = "selenea_support_wrath";
    private static final long HEAL_INTERVAL = 60L;
    private static final long ORB_DURATION = 40L;
    private static final long DETONATION_DELAY = 3_600L;
    private static final long GATE_COOLDOWN = 40L;
    private static final long SUMMON_RETRY_DELAY = 100L;
    private static final double SUMMON_HEALTH_RATIO = 0.5D;
    private static final double INNER_GATE_RADIUS = 26.0D;
    private static final double OUTER_GATE_RADIUS = 58.0D;
    private static final double ADD_RADIUS = 72.0D;
    private static final double OUTER_HEIGHT = 3.0D;
    private static final double GATE_VISUAL_RADIUS = 3.0D;
    private static final double GATE_CENTER_HEIGHT = 3.2D;
    private static final double GATE_INTERACTION_RADIUS = 3.2D;
    private static final double DETONATION_RADIUS_SQUARED = 60.0D * 60.0D;
    private static final double HEAL_RATIO = 0.03D;

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final BiConsumer<MobInstance, Player> lethalHit;
    private final SeleneaChunkLeaseRegistry chunkLeases;
    private final Map<UUID, Encounter> encounters = new HashMap<>();
    private final Map<UUID, UUID> addOwners = new HashMap<>();
    private Predicate<MobInstance> fieldResolver = boss -> false;
    private @Nullable TemporarySkillEffectService effects;
    private long currentTick;

    /**
     * メインスレッドで呼び出す支援処理を作成します。
     * @param plugin 必須chunkの同期保持ticketを所有するPlugin
     * @param mobs 召喚・固定・回復・破棄を行うMobサービス
     * @param particles 密度設定と近傍閲覧者を考慮する演出サービス
     * @param participants 専用フィールドの生存する確定参加者を返す関数
     * @param lethalHit 所有ボスと命中参加者を共通RPG致死処理へ委譲する関数
     */
    SeleneaSupportController(@NotNull JavaPlugin plugin, @NotNull MobService mobs, @NotNull ParticleDisplayService particles,
            @NotNull Function<MobInstance, List<Player>> participants,
            @NotNull BiConsumer<MobInstance, Player> lethalHit) {
        this.mobs = mobs;
        this.particles = particles;
        this.participants = participants;
        this.lethalHit = lethalHit;
        this.chunkLeases = new SeleneaChunkLeaseRegistry(plugin);
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
     * HP50%以下で初回召喚を試み、生存する写本守1体ごとに25%の軽減を返します。
     * 初回召喚前は軽減せず、召喚後に回復しても召喚済み状態を維持します。
     * Mob生成の副作用があるため、ダメージ処理のメインスレッドだけで呼び出します。
     * @param boss 被ダメージ対象
     * @param tick 現在のBossMechanicService共通時計。初召喚の180秒期限にも使用します
     * @return 通常Mobは1、対象魔女は0以上1以下の被ダメージ倍率
     */
    double incomingDamageMultiplier(@NotNull MobInstance boss, long tick) {
        currentTick = tick;
        if (!isField(boss) || boss.state() == MobState.DEAD || boss.currentHealth() <= 0.0D) return 1.0D;
        Encounter state = ensureEncounter(boss);
        try {
            summonIfRequired(state);
            if (encounters.get(boss.instanceId()) != state) return 1.0D;
            syncAddHealth(state);
            refreshBuffs(state);
            long alive = state.adds.values().stream().filter(this::isAlive).count();
            return Math.max(0.0D, 1.0D - alive * 0.25D);
        } catch (RuntimeException failure) {
            clearAfterFailure(boss.instanceId(), failure);
            throw failure;
        }
    }

    /**
     * フィールド登場時からボスとゲートを保持し、HP50%以下で一度召喚して時計を開始します。
     * IDLE中も進行し、追加のScheduler taskは作成しません。
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
        try {
            if (tick >= state.nextLeaseCheckAt) {
                state.nextLeaseCheckAt = tick + 20L;
                chunkLeases.refresh(boss.instanceId());
            }
            summonIfRequired(state);
            if (encounters.get(boss.instanceId()) != state) return;
            syncAddHealth(state);
            for (Add add : state.adds.values()) {
                if (isAlive(add)) {
                    MobInstance instance = mobs.getInstance(add.instanceId);
                    mobs.holdPosition(instance, add.anchor);
                    if (encounters.get(boss.instanceId()) != state) return;
                }
            }
            refreshBuffs(state);
            List<Player> active = participants.apply(boss).stream()
                    .filter(player -> player.isOnline() && player.isValid() && !player.isDead()
                            && player.getWorld() == state.center.getWorld()).toList();
            advanceOrbs(state, tick);
            if (encounters.get(boss.instanceId()) != state) return;
            updateSupportPresentation(state, active, tick);
            if (encounters.get(boss.instanceId()) != state) return;
            if (state.addsSummoned && !state.returnAnnounced && tick >= state.detonateAt && !state.detonated) {
                state.detonated = true;
                announce(state, active, PlayerMsgId.P_7684);
                if (encounters.get(boss.instanceId()) != state) return;
                detonate(state, active);
                if (encounters.get(boss.instanceId()) != state) return;
                refreshBuffs(state);
                active = participants.apply(boss);
                updateSupportPresentation(state, active, tick);
                if (encounters.get(boss.instanceId()) != state) return;
            }
            if (state.addsSummoned && tick >= state.nextHealAt) {
                state.nextHealAt = tick + HEAL_INTERVAL;
                launchHealingOrbs(state, tick);
            }
            teleportParticipants(state, active, tick);
            if (encounters.get(boss.instanceId()) != state) return;
            if (tick >= state.nextRenderAt) {
                state.nextRenderAt = tick + 10L;
                renderGates(state);
            }
        } catch (RuntimeException failure) {
            clearAfterFailure(boss.instanceId(), failure);
            throw failure;
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
        state.boss.keepWhenUnobserved(state.originalBossRetention);
        RuntimeException failure = null;
        if (state.supportBar != null) {
            try { state.supportBar.removeAll(); }
            catch (RuntimeException cleanupFailure) { failure = cleanupFailure; }
            state.supportBar = null;
        }
        state.orbs.clear();
        state.portalCooldowns.clear();
        for (Add add : state.adds.values()) addOwners.remove(add.instanceId);
        try { clearBuffs(state.boss.instanceId()); }
        catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        for (Add add : state.adds.values()) {
            try { mobs.destroy(add.instanceId); }
            catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        try { chunkLeases.release(instanceId); }
        catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else failure.addSuppressed(cleanupFailure);
        }
        if (failure != null) throw failure;
    }

    /** プラグイン停止時に全写本守とゲート・弾・一時補正を解除します。 */
    void clearAll() {
        RuntimeException failure = null;
        for (UUID bossId : List.copyOf(encounters.keySet())) {
            try { clear(bossId); }
            catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else failure.addSuppressed(cleanupFailure);
            }
        }
        addOwners.clear();
        if (failure != null) throw failure;
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

    /** フィールド登場時からゲートとボス保持を登録し、召喚前にも無人回収を防ぎます。 */
    private Encounter ensureEncounter(MobInstance boss) {
        Encounter existing = encounters.get(boss.instanceId());
        if (existing != null) return existing;
        Encounter state = new Encounter(boss, currentTick);
        List<Location> essential = new ArrayList<>(13);
        essential.add(state.center);
        for (Role role : Role.values()) {
            essential.add(offset(state.center, role, INNER_GATE_RADIUS, 0.0D));
            essential.add(offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT));
            essential.add(offset(state.center, role, ADD_RADIUS, OUTER_HEIGHT));
        }
        // 同期ChunkLoadEventからの被ダメージ再入も、同じ遭遇へ合流させます。
        encounters.put(boss.instanceId(), state);
        boss.keepWhenUnobserved(true);
        try {
            chunkLeases.acquire(boss.instanceId(), essential);
            if (encounters.get(boss.instanceId()) != state) {
                throw new IllegalStateException("Encounter was released during chunk loading");
            }
        } catch (RuntimeException failure) {
            if (encounters.get(boss.instanceId()) == state) clearAfterFailure(boss.instanceId(), failure);
            throw failure;
        }
        return state;
    }

    /** 元の例外を保持しながら、途中で残った遭遇・表示・chunk所有権を回収します。 */
    private void clearAfterFailure(UUID bossId, RuntimeException failure) {
        try { clear(bossId); }
        catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
    }

    /**
     * 確定参加者へ生存数・残秒を表示し、期限前の警告と全滅後の帰還案内を一度だけ送ります。
     * 召喚前は表示せず、爆発後も生存写本守の支援が残ることを表示します。
     */
    private void updateSupportPresentation(Encounter state, List<Player> active, long tick) {
        if (!state.addsSummoned || encounters.get(state.boss.instanceId()) != state) return;
        int alive = (int) state.adds.values().stream().filter(this::isAlive).count();
        if (alive == 0) {
            if (state.supportBar != null) {
                state.supportBar.removeAll();
                state.supportBar = null;
            }
            state.orbs.clear();
            clearBuffs(state.boss.instanceId());
            if (!state.returnAnnounced) {
                state.returnAnnounced = true;
                announce(state, active, PlayerMsgId.P_7686);
            }
            return;
        }
        long remainingTicks = Math.max(0L, state.detonateAt - tick);
        long seconds = (remainingTicks + 19L) / 20L;
        String title = state.detonated
                ? PlayerMsgResource.format(PlayerMsgId.P_7685.getId(), alive)
                : PlayerMsgResource.format(PlayerMsgId.P_7682.getId(), alive, seconds);
        if (state.supportBar == null) {
            state.supportBar = Bukkit.createBossBar(title, BarColor.PURPLE, BarStyle.SEGMENTED_6);
        }
        state.supportBar.setTitle(title);
        state.supportBar.setColor(state.detonated || seconds <= 10L ? BarColor.RED : BarColor.PURPLE);
        state.supportBar.setProgress(state.detonated ? 1.0D : Math.clamp((double) remainingTicks / DETONATION_DELAY, 0.0D, 1.0D));
        for (Player viewer : List.copyOf(state.supportBar.getPlayers())) {
            if (!active.contains(viewer)) state.supportBar.removePlayer(viewer);
        }
        for (Player viewer : active) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (viewer.isOnline() && viewer.isValid() && !viewer.isDead()
                    && viewer.getWorld() == state.center.getWorld()) state.supportBar.addPlayer(viewer);
        }
        if (state.detonated) return;
        if ((seconds == 30L || seconds == 10L || seconds >= 1L && seconds <= 5L)
                && state.warnedSeconds.add(seconds)) {
            announce(state, active, PlayerMsgId.P_7683, seconds);
        }
        if (encounters.get(state.boss.instanceId()) != state) return;
        if (seconds <= 10L && remainingTicks > 0L && tick >= state.nextDangerRenderAt) {
            state.nextDangerRenderAt = tick + 20L;
            renderDetonationDanger(state);
        }
    }

    /** 共通送信サービスで案内し、プレイヤー通知による破棄再入後は後続送信を止めます。 */
    private void announce(Encounter state, List<Player> active, PlayerMsgId message, Object... args) {
        for (Player player : active) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (player.isOnline() && player.isValid() && !player.isDead()
                    && player.getWorld() == state.center.getWorld()) {
                PlayerMessageService.getInstance().send(player, message, args);
            }
        }
    }

    /** 球形60mと同じ半径の三つの直交大円を、各生存個体144点・20tick周期で予告します。 */
    private void renderDetonationDanger(Encounter state) {
        for (Map.Entry<Role, Add> entry : state.adds.entrySet()) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!isAlive(entry.getValue())) continue;
            Location center = entry.getValue().anchor;
            List<Location> points = new ArrayList<>(144);
            for (int index = 0; index < 48; index++) {
                double angle = Math.PI * 2.0D * index / 48.0D;
                double a = 60.0D * Math.cos(angle);
                double b = 60.0D * Math.sin(angle);
                points.add(center.clone().add(a, 0.0D, b));
                points.add(center.clone().add(a, b, 0.0D));
                points.add(center.clone().add(0.0D, a, b));
            }
            particles.spawnForNearbyViewers(points, entry.getKey().particle(), true);
        }
    }

    /**
     * HP50%以下で四体の初回召喚を試み、成功した召喚だけで180秒期限を確定します。
     * 部分失敗はボスとゲートを保持して回収し、5秒後以降に再試行します。
     * 召喚成功後は回復や全滅によって再召喚しません。
     */
    private void summonIfRequired(Encounter state) {
        MobInstance boss = state.boss;
        if (state.addsSummoned || currentTick < state.nextSummonAttemptAt
                || !Double.isFinite(boss.maxHealth()) || boss.maxHealth() < 6.0D
                || !Double.isFinite(boss.currentHealth()) || boss.currentHealth() <= 0.0D
                || boss.currentHealth() > boss.maxHealth() * SUMMON_HEALTH_RATIO) return;
        state.nextSummonAttemptAt = currentTick + SUMMON_RETRY_DELAY;
        try {
            for (Role role : Role.values()) {
                Location anchor = offset(state.center, role, ADD_RADIUS, OUTER_HEIGHT);
                MobInstance add = mobs.spawn(role.templateId, anchor);
                if (add == null) {
                    clearPartialSummon(state);
                    return;
                }
                if (encounters.get(boss.instanceId()) != state) {
                    mobs.destroy(add.instanceId());
                    return;
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
            state.addsSummoned = true;
            state.detonateAt = currentTick + DETONATION_DELAY;
            state.nextHealAt = currentTick + HEAL_INTERVAL;
            List<Player> active = participants.apply(boss);
            announce(state, active, PlayerMsgId.P_7680);
            if (encounters.get(boss.instanceId()) != state) return;
            announce(state, active, PlayerMsgId.P_7681);
        } catch (RuntimeException exception) {
            if (encounters.get(boss.instanceId()) == state) clearPartialSummon(state);
            throw exception;
        }
    }

    /** 部分召喚だけを所有関係から外して破棄し、遭遇の保持と再試行期限を維持します。 */
    private void clearPartialSummon(Encounter state) {
        List<Add> partialAdds = List.copyOf(state.adds.values());
        state.adds.clear();
        state.orbs.clear();
        for (Add add : partialAdds) addOwners.remove(add.instanceId);
        clearBuffs(state.boss.instanceId());
        for (Add add : partialAdds) mobs.destroy(add.instanceId);
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
                if (recovered > 0.0D) particles.spawnForNearbyViewers(destination, SharedParticleDefinitions.SELENEA_SUPPORT_HEAL, true);
            }
        }
        particles.spawnForNearbyViewers(points, SharedParticleDefinitions.SELENEA_SUPPORT_ORB, true);
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
            particles.spawnForNearbyViewers(add.anchor, SharedParticleDefinitions.SELENEA_SUPPORT_DETONATION, true);
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
                Location source = offset(state.center, role, INNER_GATE_RADIUS, 0.0D);
                Location destination = offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT);
                Location location = player.getLocation();
                Location target = inGate(location, source) ? destination : inGate(location, destination) ? source : null;
                if (target == null) continue;
                if (PlayerTeleportService.teleport(player, target)) state.portalCooldowns.put(player.getUniqueId(), tick + GATE_COOLDOWN);
                break;
            }
        }
    }

    /** 足元位置を基準に大型ゲートの水平半径3.2m・高さ-0.5～6mの通過範囲を判定します。 */
    private static boolean inGate(Location location, Location base) {
        if (location.getWorld() != base.getWorld()) return false;
        double y = location.getY() - base.getY();
        double x = location.getX() - base.getX();
        double z = location.getZ() - base.getZ();
        return y >= -0.5D && y <= 6.0D && x * x + z * z <= GATE_INTERACTION_RADIUS * GATE_INTERACTION_RADIUS;
    }

    /** 八個の大型二重リングと回転する放射線を役割色で描き、白い光点を一括描画します。 */
    private void renderGates(Encounter state) {
        if (state.center.getWorld() == null || state.center.getWorld().getPlayers().isEmpty()) return;
        List<Location> accents = new ArrayList<>(96);
        double rotation = currentTick * Math.PI / 120.0D;
        for (Role role : Role.values()) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            List<Location> points = new ArrayList<>(224);
            portalRing(offset(state.center, role, INNER_GATE_RADIUS, 0.0D), role, rotation, points, accents);
            portalRing(offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT), role, rotation, points, accents);
            particles.spawnForNearbyViewers(points, role.particle(), true);
        }
        if (encounters.get(state.boss.instanceId()) != state) return;
        particles.spawnForNearbyViewers(accents, SharedParticleDefinitions.SELENEA_SUPPORT_ORB, true);
    }

    /** 縦面に半径3m・2.65mの二重輪と六本の回転放射線を固定上限点数で配置します。 */
    private static void portalRing(Location base, Role role, double rotation,
            List<Location> points, List<Location> accents) {
        for (int index = 0; index < 40; index++) {
            double angle = Math.PI * 2.0D * index / 40.0D;
            points.add(portalPoint(base, role, angle, GATE_VISUAL_RADIUS));
            points.add(portalPoint(base, role, angle, GATE_VISUAL_RADIUS - 0.35D));
        }
        for (int spoke = 0; spoke < 6; spoke++) {
            double angle = rotation + Math.PI * 2.0D * spoke / 6.0D;
            for (int index = 1; index <= 5; index++) {
                points.add(portalPoint(base, role, angle, GATE_VISUAL_RADIUS * index / 5.0D));
            }
            accents.add(portalPoint(base, role, angle, GATE_VISUAL_RADIUS));
            accents.add(portalPoint(base, role, angle + Math.PI / 6.0D, GATE_VISUAL_RADIUS - 0.35D));
        }
    }

    /** 方向へ直交する縦面で、床から3.2mの中心を持つ円周上の一点を返します。 */
    private static Location portalPoint(Location base, Role role, double angle, double radius) {
        double tangent = Math.cos(angle) * radius;
        return base.clone().add(-role.z * tangent, GATE_CENTER_HEIGHT + Math.sin(angle) * radius, role.x * tangent);
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
        private final boolean originalBossRetention;
        private final Location center;
        private final Map<Role, Add> adds = new EnumMap<>(Role.class);
        private final List<Orb> orbs = new ArrayList<>(4);
        private final Map<UUID, Long> portalCooldowns = new HashMap<>();
        private long detonateAt;
        private long nextSummonAttemptAt;
        private long nextHealAt;
        private long nextRenderAt;
        private long nextLeaseCheckAt;
        private long nextDangerRenderAt;
        private boolean addsSummoned;
        private boolean detonated;
        private boolean returnAnnounced;
        private @Nullable BossBar supportBar;
        private final Set<Long> warnedSeconds = new java.util.HashSet<>();

        /** 実際のboss.spawnLocationを保持し、召喚前は回復・爆発の時計を開始しません。 */
        private Encounter(MobInstance boss, long tick) {
            this.boss = boss;
            originalBossRetention = boss.keepWhenUnobserved();
            center = boss.spawnLocation();
            nextRenderAt = tick;
        }
    }

    /** 回復弾の発射元・対象・発射位置・開始tickです。 */
    private record Orb(UUID sourceId, UUID targetId, Location origin, long startedAt) { }
}
