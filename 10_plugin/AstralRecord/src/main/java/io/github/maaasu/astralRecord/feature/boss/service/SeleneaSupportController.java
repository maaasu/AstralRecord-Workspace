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
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
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
    private static final long GATE_WARNING_INTERVAL = 40L;
    private static final long SUMMON_RETRY_DELAY = 100L;
    private static final double INNER_GATE_RADIUS = 26.0D;
    private static final double OUTER_GATE_RADIUS = 63.0D;
    private static final double ADD_RADIUS = 77.0D;
    private static final double OUTER_HEIGHT = 3.0D;
    private static final double GATE_VISUAL_RADIUS = 3.0D;
    private static final double GATE_CENTER_HEIGHT = 3.2D;
    private static final double GATE_INTERACTION_RADIUS = 1.4D;
    private static final double DETONATION_RADIUS = 60.0D;
    private static final int MAX_SURFACE_POINTS = 1_200;
    private static final int MAX_FLOOR_POINTS = 900;
    private static final double DETONATION_RADIUS_SQUARED = 60.0D * 60.0D;
    private static final double HEAL_RATIO = 0.03D;
    private static final int MAX_ORBS = 5;

    private final MobService mobs;
    private final ParticleDisplayService particles;
    private final Function<MobInstance, List<Player>> participants;
    private final BiConsumer<MobInstance, Player> lethalHit;
    private final SeleneaChunkLeaseRegistry chunkLeases;
    private final SeleneaGatePlatformRegistry gatePlatforms;
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
        this.gatePlatforms = new SeleneaGatePlatformRegistry(plugin);
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
     * HP50%未満・10%未満への到達を記録し、各波一回の召喚を試みます。
     * 生存する戦闘用写本守1体ごとに25%を軽減し、爆発後の残像は数えません。
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
            if (state.summoning || state.detonating) return 1.0D;
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
     * フィールド登場時からボスと床を保持し、各召喚波で独立した180秒時計を開始します。
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
            for (Add remnant : List.copyOf(state.remnants)) {
                MobInstance instance = mobs.getInstance(remnant.instanceId);
                if (instance != null) mobs.holdPosition(instance, remnant.anchor);
                if (encounters.get(boss.instanceId()) != state) return;
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
                state.detonating = true;
                announce(state, active, PlayerMsgId.P_7684);
                if (encounters.get(boss.instanceId()) != state) return;
                try { detonate(state, active); }
                finally { state.detonating = false; }
                if (encounters.get(boss.instanceId()) != state) return;
                refreshBuffs(state);
                active = participants.apply(boss);
                updateSupportPresentation(state, active, tick);
                if (encounters.get(boss.instanceId()) != state) return;
            }
            if ((state.addsSummoned || !state.permanentRoles.isEmpty()) && tick >= state.nextHealAt) {
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
        state.portalLocks.clear();
        state.blockedPortalWarnings.clear();
        for (Add add : state.adds.values()) addOwners.remove(add.instanceId);
        for (Add remnant : state.remnants) addOwners.remove(remnant.instanceId);
        try { clearBuffs(state.boss.instanceId()); }
        catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
        }
        List<Add> owned = new ArrayList<>(state.adds.values());
        owned.addAll(state.remnants);
        for (Add add : owned) {
            try { mobs.destroy(add.instanceId); }
            catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        try { gatePlatforms.release(instanceId); }
        catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
        }
        try { chunkLeases.release(instanceId); }
        catch (RuntimeException cleanupFailure) {
            if (failure == null) failure = cleanupFailure;
            else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
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
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
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
        List<Location> essential = new ArrayList<>(29);
        List<Location> outerGates = new ArrayList<>(4);
        essential.add(state.center);
        for (Role role : Role.values()) {
            essential.add(offset(state.center, role, INNER_GATE_RADIUS, 0.0D));
            Location outer = offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT);
            essential.add(outer);
            outerGates.add(outer);
            for (double x : new double[]{-3.0D, 3.0D}) {
                for (double z : new double[]{-3.0D, 3.0D}) essential.add(outer.clone().add(x, -1.0D, z));
            }
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
            gatePlatforms.acquire(boss.instanceId(), outerGates);
            if (encounters.get(boss.instanceId()) != state) {
                throw new IllegalStateException("Encounter was released during gate preparation");
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
        catch (RuntimeException cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        }
    }

    /**
     * 確定参加者へ生存数・残秒を表示し、期限前の警告と全滅後の帰還案内を一度だけ送ります。
     * 召喚前と爆発後は表示せず、永久支援と戦闘用写本守の全滅を区別して案内します。
     */
    private void updateSupportPresentation(Encounter state, List<Player> active, long tick) {
        if (!state.addsSummoned || encounters.get(state.boss.instanceId()) != state) return;
        int alive = (int) state.adds.values().stream().filter(this::isAlive).count();
        if (alive == 0) {
            if (state.supportBar != null) {
                state.supportBar.removeAll();
                state.supportBar = null;
            }
            state.orbs.removeIf(orb -> state.remnants.stream().noneMatch(add -> add.instanceId.equals(orb.sourceId)));
            refreshBuffs(state);
            if (!state.returnAnnounced) {
                state.returnAnnounced = true;
                announce(state, active, state.detonated ? PlayerMsgId.P_7717 : PlayerMsgId.P_7686);
            }
            return;
        }
        long remainingTicks = Math.max(0L, state.detonateAt - tick);
        long seconds = (remainingTicks + 19L) / 20L;
        String title = PlayerMsgResource.format(PlayerMsgId.P_7682.getId(), alive, seconds);
        if (state.supportBar == null) {
            state.supportBar = Bukkit.createBossBar(title, BarColor.PURPLE, BarStyle.SEGMENTED_6);
        }
        state.supportBar.setTitle(title);
        state.supportBar.setColor(seconds <= 10L ? BarColor.RED : BarColor.PURPLE);
        state.supportBar.setProgress(Math.clamp((double) remainingTicks / DETONATION_DELAY, 0.0D, 1.0D));
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

    /** 球形60mの表面と床面へ範囲比例・有限上限の予告を20tick周期で描きます。 */
    private void renderDetonationDanger(Encounter state) {
        if (state.center.getWorld() == null || state.center.getWorld().getPlayers().isEmpty()) return;
        for (Map.Entry<Role, Add> entry : state.adds.entrySet()) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!isAlive(entry.getValue())) continue;
            Location center = entry.getValue().anchor;
            particles.spawnForNearbyViewers(detonationSurface(center, 36.0D), entry.getKey().particle(), true);
            particles.spawnForNearbyViewers(detonationFloor(center, 5.0D),
                    SharedParticleDefinitions.SELENEA_SUPPORT_WARNING, true);
        }
    }

    /**
     * 厳密にHP50%未満・10%未満の到達を保持し、成功した波だけを進めます。
     * 前波の戦闘個体が残る間は第二波を保留し、回復後も到達記録を失いません。
     * 部分失敗は今回の四体だけを回収し、永久支援を保持したまま5秒後に再試行します。
     */
    private void summonIfRequired(Encounter state) {
        MobInstance boss = state.boss;
        if (!Double.isFinite(boss.maxHealth()) || boss.maxHealth() < 6.0D
                || !Double.isFinite(boss.currentHealth()) || boss.currentHealth() <= 0.0D) return;
        if (boss.currentHealth() < boss.maxHealth() * 0.5D) state.firstThresholdReached = true;
        if (boss.currentHealth() < boss.maxHealth() * 0.1D) state.secondThresholdReached = true;
        if (state.summoning || state.detonating || state.wavesSummoned >= 2
                || currentTick < state.nextSummonAttemptAt
                || state.adds.values().stream().anyMatch(this::isAlive)
                || !(state.wavesSummoned == 0 ? state.firstThresholdReached : state.secondThresholdReached)) return;
        state.summoning = true;
        state.nextSummonAttemptAt = currentTick + SUMMON_RETRY_DELAY;
        try {
            clearPartialSummon(state);
            if (encounters.get(boss.instanceId()) != state) return;
            int wave = state.wavesSummoned + 1;
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
                state.adds.put(role, new Add(add.instanceId(), anchor, wave == 1 ? 6.0D : 3.0D, role));
                addOwners.put(add.instanceId(), boss.instanceId());
                add.maxHealth(boss.maxHealth() / (wave == 1 ? 6.0D : 3.0D));
                add.currentHealth(add.maxHealth());
                add.rewardEligible(false);
                add.keepWhenUnobserved(true);
                add.scriptedAction(true);
                mobs.stopPathfinding(add);
                Entity entity = mobs.entityController().getEntity(add);
                if (entity == null || !entity.isValid() || entity.isDead()) {
                    clearPartialSummon(state);
                    return;
                }
                entity.setGravity(false);
                mobs.holdPosition(add, anchor);
                if (encounters.get(boss.instanceId()) != state) return;
            }
            if (state.adds.size() != Role.values().length || state.adds.values().stream().anyMatch(add -> !isAlive(add))) {
                clearPartialSummon(state);
                return;
            }
            refreshBuffs(state);
            state.wavesSummoned = wave;
            state.addsSummoned = true;
            state.detonated = false;
            state.returnAnnounced = false;
            state.warnedSeconds.clear();
            state.detonateAt = currentTick + DETONATION_DELAY;
            if (state.nextHealAt == 0L) state.nextHealAt = currentTick + HEAL_INTERVAL;
            List<Player> active = participants.apply(boss);
            announce(state, active, wave == 1 ? PlayerMsgId.P_7680 : PlayerMsgId.P_7716);
            if (encounters.get(boss.instanceId()) != state) return;
            announce(state, active, PlayerMsgId.P_7681);
        } catch (RuntimeException exception) {
            if (encounters.get(boss.instanceId()) == state) {
                try { clearPartialSummon(state); }
                catch (RuntimeException cleanupFailure) {
                    if (cleanupFailure != exception) exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        } finally {
            state.summoning = false;
        }
    }

    /** 部分召喚を先に所有関係から外し、回収例外があっても全個体の破棄を試行します。 */
    private void clearPartialSummon(Encounter state) {
        List<Add> partialAdds = List.copyOf(state.adds.values());
        state.adds.clear();
        state.orbs.removeIf(orb -> state.remnants.stream().noneMatch(add -> add.instanceId.equals(orb.sourceId)));
        for (Add add : partialAdds) addOwners.remove(add.instanceId);
        RuntimeException failure = null;
        try { refreshBuffs(state); }
        catch (RuntimeException cleanupFailure) { failure = cleanupFailure; }
        for (Add add : partialAdds) {
            try { mobs.destroy(add.instanceId); }
            catch (RuntimeException cleanupFailure) {
                if (failure == null) failure = cleanupFailure;
                else if (failure != cleanupFailure) failure.addSuppressed(cleanupFailure);
            }
        }
        if (failure != null) throw failure;
    }

    /** 登録済みMobとBukkit実体の両方で、生存を確認します。 */
    private boolean isAlive(@Nullable Add add) {
        if (add == null || !add.combatActive) return false;
        MobInstance instance = mobs.getInstance(add.instanceId);
        if (instance == null || instance.state() == MobState.DEAD || instance.currentHealth() <= 0.0D) return false;
        Entity entity = mobs.entityController().getEntity(instance);
        return entity != null && entity.isValid() && !entity.isDead();
    }

    /** 参加人数HP倍率へ現在HP割合を保ち、第一波は最大HP/6、第二波は最大HP/3を同期します。 */
    private void syncAddHealth(Encounter state) {
        for (Add add : state.adds.values()) {
            if (!add.combatActive) continue;
            double maximum = state.boss.maxHealth() / add.healthDivisor;
            if (!Double.isFinite(maximum) || maximum < 1.0D) continue;
            MobInstance instance = mobs.getInstance(add.instanceId);
            if (instance == null || instance.state() == MobState.DEAD || instance.currentHealth() <= 0.0D
                    || Double.compare(instance.maxHealth(), maximum) == 0) continue;
            double fraction = Math.clamp(instance.currentHealth() / instance.maxHealth(), 0.0D, 1.0D);
            instance.maxHealth(maximum);
            instance.currentHealth(maximum * fraction);
        }
    }

    /** 生存個体または爆発後の永久支援があれば同じroleを一度だけ適用します。 */
    private void refreshBuffs(Encounter state) {
        if (effects == null) return;
        UUID bossId = state.boss.instanceId();
        if (state.permanentRoles.contains(Role.WARD) || isAlive(state.adds.get(Role.WARD))) effects.applyDefenseMultiplier(bossId, WARD_EFFECT, 40L, 3.0D);
        else effects.clear(bossId, WARD_EFFECT);
        if (state.permanentRoles.contains(Role.WRATH) || isAlive(state.adds.get(Role.WRATH))) effects.apply(bossId, WRATH_EFFECT, 40L, 1.0D, 2.0D, 1.0D);
        else effects.clear(bossId, WRATH_EFFECT);
    }

    /** このコントローラーが付与した二種類の効果だけを解除します。 */
    private void clearBuffs(UUID bossId) {
        if (effects == null) return;
        effects.clear(bossId, WARD_EFFECT);
        effects.clear(bossId, WRATH_EFFECT);
    }

    /** 月癒はボスへ、慈癒は他の生存個体へ発射します。永久慈癒はボスを回復します。 */
    private void launchHealingOrbs(Encounter state, long tick) {
        Add lunar = healingSource(state, Role.LUNAR);
        if (lunar != null) launchOrb(state, lunar, state.boss.instanceId(), tick);
        if (state.permanentRoles.contains(Role.MERCY)) {
            Add permanentMercy = healingSource(state, Role.MERCY);
            if (permanentMercy != null) launchOrb(state, permanentMercy, state.boss.instanceId(), tick);
        }
        Add mercy = state.adds.get(Role.MERCY);
        if (isAlive(mercy)) {
            for (Map.Entry<Role, Add> entry : state.adds.entrySet()) {
                if (entry.getKey() != Role.MERCY && isAlive(entry.getValue())) {
                    launchOrb(state, mercy, entry.getValue().instanceId, tick);
                }
            }
        }
    }

    /** 同roleの残像が複数あっても、永久支援の発射元は一体だけ選びます。 */
    private @Nullable Add healingSource(Encounter state, Role role) {
        if (state.permanentRoles.contains(role)) {
            return state.remnants.stream().filter(add -> add.role == role && canEmitOrb(state, add))
                    .findFirst().orElse(null);
        }
        Add add = state.adds.get(role);
        return isAlive(add) ? add : null;
    }

    /** 戦闘個体の生存、または所有する無敵残像の実体を確認します。 */
    private boolean canEmitOrb(Encounter state, @Nullable Add add) {
        if (add == null) return false;
        if (add.combatActive) return isAlive(add);
        if (!state.remnants.contains(add)) return false;
        MobInstance instance = mobs.getInstance(add.instanceId);
        Entity entity = instance == null ? null : mobs.entityController().getEntity(instance);
        return entity != null && entity.isValid() && !entity.isDead();
    }

    /** 発射時の始点と対象を保持し、永久二役と生存慈癒を合わせ上限五弾の軌道を登録します。 */
    private void launchOrb(Encounter state, Add source, UUID targetId, long tick) {
        MobInstance target = mobs.getInstance(targetId);
        if (target == null || state.orbs.size() >= MAX_ORBS) return;
        state.orbs.add(new Orb(source.instanceId, targetId, source.anchor.clone().add(0, 1.2D, 0), tick));
    }

    /** 着弾時に最大HPの3%を回復し、回復通知が他弾を破棄しても安全に走査します。 */
    private void advanceOrbs(Encounter state, long tick) {
        List<Location> points = new ArrayList<>(16);
        for (Orb orb : List.copyOf(state.orbs)) {
            if (!state.orbs.contains(orb)) continue;
            Add source = state.adds.values().stream().filter(add -> add.instanceId.equals(orb.sourceId))
                    .findFirst().orElse(null);
            if (source == null) source = state.remnants.stream().filter(add -> add.instanceId.equals(orb.sourceId))
                    .findFirst().orElse(null);
            MobInstance target = mobs.getInstance(orb.targetId);
            if (!canEmitOrb(state, source) || target == null || target.state() == MobState.DEAD
                    || target.currentHealth() <= 0.0D || target.currentLocation().getWorld() != state.center.getWorld()) {
                state.orbs.remove(orb);
                continue;
            }
            Location destination = target.currentLocation().add(0, 1.2D, 0);
            double progress = Math.clamp((double) (tick - orb.startedAt) / ORB_DURATION, 0.0D, 1.0D);
            for (int index = 0; index < 3; index++) {
                double tailProgress = Math.max(0.0D, progress - index * 0.025D);
                points.add(arc(orb.origin, destination, tailProgress));
            }
            if (progress >= 1.0D) {
                state.orbs.remove(orb);
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

    /** 生存個体の球形60mを一度だけ判定し、元個体を回収して第一門へ永久残像を置きます。 */
    private void detonate(Encounter state, List<Player> active) {
        List<Add> survivors = state.adds.values().stream().filter(this::isAlive).toList();
        // 致死通知の再入より前に全門・軽減・BossBarを閉じます。元個体の回収所有権は保持します。
        for (Add add : state.adds.values()) add.combatActive = false;
        state.orbs.clear();
        if (state.supportBar != null) {
            state.supportBar.removeAll();
            state.supportBar = null;
        }
        for (Add add : survivors) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            particles.spawnForNearbyViewers(add.anchor, SharedParticleDefinitions.SELENEA_SUPPORT_DETONATION, true);
            particles.spawnForNearbyViewers(detonationSurface(add.anchor, 24.0D),
                    SharedParticleDefinitions.SELENEA_SUPPORT_DETONATION_SURFACE, true);
            particles.spawnForNearbyViewers(detonationFloor(add.anchor, 4.0D),
                    SharedParticleDefinitions.SELENEA_SUPPORT_DETONATION_SURFACE, true);
            for (Player player : active) {
                if (!player.isDead() && player.getWorld() == add.anchor.getWorld()
                        && player.getLocation().distanceSquared(add.anchor) <= DETONATION_RADIUS_SQUARED) {
                    lethalHit.accept(state.boss, player);
                    if (encounters.get(state.boss.instanceId()) != state) return;
                }
            }
            MobInstance original = mobs.getInstance(add.instanceId);
            if (original == null || original.state() == MobState.DEAD || original.currentHealth() <= 0.0D) continue;
            // 元実体は先に破棄します。テンプレートを保持して同形・同装備のnative Mobを再生成します。
            var template = original.template();
            mobs.destroy(add.instanceId);
            if (encounters.get(state.boss.instanceId()) != state) return;
            Location anchor = offset(state.center, add.role, INNER_GATE_RADIUS, 0.0D);
            MobInstance instance = mobs.spawn(template, anchor);
            if (instance == null) throw new IllegalStateException("Support remnant could not be spawned");
            if (encounters.get(state.boss.instanceId()) != state) {
                mobs.destroy(instance.instanceId());
                return;
            }
            Add remnant = new Add(instance.instanceId(), anchor, add.healthDivisor, add.role);
            remnant.combatActive = false;
            state.remnants.add(remnant);
            addOwners.put(instance.instanceId(), state.boss.instanceId());
            instance.rewardEligible(false);
            instance.keepWhenUnobserved(true);
            instance.scriptedAction(true);
            mobs.setDamageImmune(instance, true);
            if (encounters.get(state.boss.instanceId()) != state) return;
            Entity entity = mobs.entityController().getEntity(instance);
            if (entity == null) throw new IllegalStateException("Support remnant entity is unavailable");
            entity.setGravity(false);
            if (entity instanceof LivingEntity living) {
                living.setAI(false);
                living.setCollidable(false);
                living.setCanPickupItems(false);
            }
            if (entity instanceof Mob mob) mob.setTarget(null);
            mobs.holdPosition(instance, anchor);
            if (encounters.get(state.boss.instanceId()) != state) return;
            state.permanentRoles.add(add.role);
        }
        refreshBuffs(state);
    }

    /** 球面積に比例するFibonacci配置を使い、一個体最大1200点の表面を生成します。 */
    private static List<Location> detonationSurface(Location center, double areaPerPoint) {
        int count = Math.min(MAX_SURFACE_POINTS, (int) Math.ceil(4.0D * Math.PI
                * DETONATION_RADIUS * DETONATION_RADIUS / areaPerPoint));
        List<Location> points = new ArrayList<>(count);
        double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int index = 0; index < count; index++) {
            double y = 1.0D - 2.0D * (index + 0.5D) / count;
            double radial = Math.sqrt(1.0D - y * y);
            double angle = goldenAngle * index;
            points.add(center.clone().add(DETONATION_RADIUS * radial * Math.cos(angle),
                    DETONATION_RADIUS * y, DETONATION_RADIUS * radial * Math.sin(angle)));
        }
        return points;
    }

    /** 水平面積に比例する円盤配置を使い、一個体最大900点の範囲内の床面を生成します。 */
    private static List<Location> detonationFloor(Location center, double spacing) {
        int count = Math.min(MAX_FLOOR_POINTS, (int) Math.ceil(Math.PI
                * DETONATION_RADIUS * DETONATION_RADIUS / (spacing * spacing)));
        List<Location> points = new ArrayList<>(count);
        double goldenAngle = Math.PI * (3.0D - Math.sqrt(5.0D));
        for (int index = 0; index < count; index++) {
            double radius = DETONATION_RADIUS * Math.sqrt((index + 0.5D) / count);
            double angle = goldenAngle * index;
            points.add(center.clone().add(radius * Math.cos(angle), 0.25D, radius * Math.sin(angle)));
        }
        return points;
    }

    /** 転送前にロックし、全ての門の判定範囲から一度出るまで再使用を禁止します。 */
    private void teleportParticipants(Encounter state, List<Player> active, long tick) {
        state.blockedPortalWarnings.entrySet().removeIf(entry -> entry.getValue() <= tick);
        state.portalLocks.removeIf(id -> Bukkit.getPlayer(id) == null);
        for (Player player : active) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!player.isOnline() || !player.isValid() || player.isDead()
                    || player.getWorld() != state.center.getWorld()) continue;
            Location location = player.getLocation();
            boolean insideAnyGate = false;
            for (Role role : Role.values()) {
                if (inGate(location, offset(state.center, role, INNER_GATE_RADIUS, 0.0D))
                        || inGate(location, offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT))) {
                    insideAnyGate = true;
                    break;
                }
            }
            if (!insideAnyGate) state.portalLocks.remove(player.getUniqueId());
            if (state.portalLocks.contains(player.getUniqueId())) continue;
            for (Role role : Role.values()) {
                if (!isAlive(state.adds.get(role))) continue;
                Location source = offset(state.center, role, INNER_GATE_RADIUS, 0.0D);
                Location destination = offset(state.center, role, OUTER_GATE_RADIUS, OUTER_HEIGHT);
                Location target = inGate(location, source) ? destination : inGate(location, destination) ? source : null;
                if (target == null) continue;
                if (!SeleneaGatePlatformRegistry.safeLanding(target)) {
                    if (!state.blockedPortalWarnings.containsKey(player.getUniqueId())) {
                        state.blockedPortalWarnings.put(player.getUniqueId(), tick + GATE_WARNING_INTERVAL);
                        announce(state, List.of(player), PlayerMsgId.P_7689);
                    }
                    break;
                }
                if (encounters.get(state.boss.instanceId()) != state) return;
                state.portalLocks.add(player.getUniqueId());
                if (!PlayerTeleportService.teleport(player, target)) state.portalLocks.remove(player.getUniqueId());
                break;
            }
        }
    }

    /** 足元位置を基準に水平半径1.4m・高さ-0.5～3.2mの通過範囲を判定します。 */
    private static boolean inGate(Location location, Location base) {
        if (location.getWorld() != base.getWorld()) return false;
        double y = location.getY() - base.getY();
        double x = location.getX() - base.getX();
        double z = location.getZ() - base.getZ();
        return y >= -0.5D && y <= 3.2D && x * x + z * z <= GATE_INTERACTION_RADIUS * GATE_INTERACTION_RADIUS;
    }

    /** 八個の大型二重リングと回転する放射線を役割色で描き、白い光点を一括描画します。 */
    private void renderGates(Encounter state) {
        if (state.summoning || state.detonating || state.center.getWorld() == null
                || state.center.getWorld().getPlayers().isEmpty()) return;
        List<Location> accents = new ArrayList<>(96);
        double rotation = currentTick * Math.PI / 120.0D;
        for (Role role : Role.values()) {
            if (encounters.get(state.boss.instanceId()) != state) return;
            if (!isAlive(state.adds.get(role))) continue;
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
        private final double healthDivisor;
        private final Role role;
        private boolean combatActive = true;

        /** 管理Mob ID・固定位置・波別HP比率・役割を保存します。 */
        private Add(UUID instanceId, Location anchor, double healthDivisor, Role role) {
            this.instanceId = instanceId;
            this.anchor = anchor.clone();
            this.healthDivisor = healthDivisor;
            this.role = role;
        }
    }

    /** 一波四体・最大八残像・五弾・参加者ごとの転送制限を所有する一遭遇です。 */
    private static final class Encounter {
        private final MobInstance boss;
        private final boolean originalBossRetention;
        private final Location center;
        private final Map<Role, Add> adds = new EnumMap<>(Role.class);
        private final List<Add> remnants = new ArrayList<>(8);
        private final Set<Role> permanentRoles = EnumSet.noneOf(Role.class);
        private final List<Orb> orbs = new ArrayList<>(MAX_ORBS);
        private final Set<UUID> portalLocks = new java.util.HashSet<>();
        private final Map<UUID, Long> blockedPortalWarnings = new HashMap<>();
        private long detonateAt;
        private long nextSummonAttemptAt;
        private long nextHealAt;
        private long nextRenderAt;
        private long nextLeaseCheckAt;
        private long nextDangerRenderAt;
        private boolean addsSummoned;
        private int wavesSummoned;
        private boolean firstThresholdReached;
        private boolean secondThresholdReached;
        private boolean summoning;
        private boolean detonating;
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
