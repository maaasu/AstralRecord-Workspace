package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.combat.model.AttackType;
import io.github.maaasu.astralRecord.feature.combat.model.DamageComponent;
import io.github.maaasu.astralRecord.feature.combat.model.DamageElement;
import io.github.maaasu.astralRecord.feature.combat.model.DamageSource;
import io.github.maaasu.astralRecord.feature.combat.service.DamageService;
import io.github.maaasu.astralRecord.feature.dungeon.service.DungeonService;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinition;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ボス固有のフェーズ、予兆攻撃、召喚、地形破壊を同期 tick で管理します。
 */
public final class BossMechanicService {

    private static final long TICK_PERIOD = 5L;
    private static final double TARGET_RANGE = 32.0D;
    private static final double COLOSSUS_RUNE_LANES_MAX_LENGTH = TARGET_RANGE;
    private static final double COLOSSUS_RUNE_LANES_HALF_WIDTH = 1.0D;
    private static final double LINE_PARTICLE_INTERVAL = 0.5D;
    private static final double SUNBIRD_FLARE_RADIUS = 5.0D;
    private static final double SUNBIRD_SUNSTRIKE_RADIUS = 3.0D;
    private static final double SUNBIRD_BEAM_LENGTH = 24.0D;
    private static final double SUNBIRD_BEAM_HALF_WIDTH = 1.25D;
    private static final double SUNBIRD_NOVA_RADIUS = 12.0D;
    private static final long SUNBIRD_NOVA_TELEGRAPH_TICKS = 60L;
    private static final double SUNBIRD_BIRD_METEOR_SAFE_RADIUS = 3.0D;
    private static final double SUNBIRD_BIRD_METEOR_SAFE_HEIGHT = 2.5D;
    private static final int SUNBIRD_BIRD_METEOR_SAFE_RING_POINT_COUNT = 36;
    private static final int SUNBIRD_BIRD_METEOR_SAFE_LAYER_COUNT = 3;
    private static final int SUNBIRD_BIRD_METEOR_SAFE_VERTICAL_LINE_COUNT = 12;
    private static final double SUNBIRD_BIRD_METEOR_EXPLOSION_GRID_SPACING = 1.5D;
    private static final double SUNBIRD_BIRD_METEOR_DAMAGE_RATIO = 30.0D;
    private static final double SUNBIRD_BIRD_METEOR_ACCURACY_BONUS = 1000.0D;
    private static final long SUNBIRD_BIRD_METEOR_TELEGRAPH_TICKS = 80L;
    private static final int SUNBIRD_NOVA_DISPLAY_COUNT = 12;
    private static final int SUNBIRD_NOVA_INNER_RING_POINT_COUNT = 36;
    private static final int SUNBIRD_NOVA_MIDDLE_RING_POINT_COUNT = 48;
    private static final int SUNBIRD_NOVA_OUTER_RING_POINT_COUNT = 64;
    private static final double SUNBIRD_NOVA_ACCURACY_BONUS = 25.0D;
    private static final long SUNBIRD_TELEPORT_INTERVAL_TICKS = 240L;
    private static final double SUNBIRD_TELEPORT_RADIUS = 7.0D;
    private static final int SUNBIRD_TELEPORT_POINT_COUNT = 6;
    private static final double SUNBIRD_ARENA_RADIUS = 18.0D;
    private static final int SUNBIRD_ARENA_BOUNDARY_POINT_COUNT = 64;
    private static final int SUNBIRD_ARENA_BOUNDARY_UPPER_LAYER_COUNT = 8;
    private static final int SUNBIRD_ARENA_BOUNDARY_LOWER_LAYER_COUNT = 2;
    private static final double SUNBIRD_ARENA_BOUNDARY_LAYER_HEIGHT = 0.5D;
    private static final double SUNBIRD_ARENA_BOUNDARY_HORIZONTAL_JITTER = 0.12D;
    private static final double SUNBIRD_ARENA_BOUNDARY_VERTICAL_JITTER = 0.04D;
    private static final long SUNBIRD_ARENA_PULSE_INTERVAL_TICKS = 20L;
    private static final double SUNBIRD_ARENA_DAMAGE_RATIO = 0.18D;
    private static final double SUNBIRD_TACKLE_DESTINATION_RADIUS = 9.0D;
    private static final double SUNBIRD_TACKLE_HALF_WIDTH = 1.5D;
    private static final long SUNBIRD_TACKLE_TELEGRAPH_TICKS = 10L;

    private final JavaPlugin plugin;
    private final MobService mobService;
    private final DamageService damageService;
    private final DungeonService dungeonService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, BossRuntime> runtimes = new HashMap<>();
    private final List<PendingMechanic> pendingMechanics = new ArrayList<>();
    private final Map<UUID, BirdMeteorState> birdMeteorStates = new HashMap<>();

    private BukkitTask tickTask;
    private long clockTicks;

    public BossMechanicService(
        @NotNull JavaPlugin plugin,
        @NotNull MobService mobService,
        @NotNull DamageService damageService,
        @NotNull DungeonService dungeonService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.damageService = damageService;
        this.dungeonService = dungeonService;
        this.particleDisplayService = particleDisplayService;
    }

    /** ボス固有ギミックの定期処理を開始します。 */
    public void start() {
        if (tickTask != null) {
            return;
        }
        clockTicks = 0L;
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, TICK_PERIOD);
    }

    /** 定期処理、未発動の予兆、召喚個体を回収します。 */
    public void stop() {
        if (tickTask != null) {
            tickTask.cancel();
            tickTask = null;
        }
        for (PendingMechanic pending : pendingMechanics) {
            removePendingVisuals(pending);
            releaseScriptedAction(pending);
        }
        pendingMechanics.clear();
        for (UUID bossInstanceId : List.copyOf(birdMeteorStates.keySet())) {
            finishBirdMeteor(bossInstanceId);
        }
        for (BossRuntime runtime : runtimes.values()) {
            destroySummons(runtime);
        }
        runtimes.clear();
    }

    private void tick() {
        clockTicks += TICK_PERIOD;
        processPendingMechanics();

        Set<UUID> activeBosses = new HashSet<>();
        for (MobInstance boss : List.copyOf(mobService.getInstances())) {
            BossMechanicProfile profile = BossMechanicProfile.find(boss.template().id());
            if (profile == null || boss.currentHealth() <= 0.0D) {
                continue;
            }
            Entity entity = mobService.entityController().getEntity(boss);
            if (entity == null || !entity.isValid() || entity.isDead()) {
                continue;
            }

            activeBosses.add(boss.instanceId());
            BossRuntime runtime = runtimes.computeIfAbsent(
                boss.instanceId(),
                ignored -> new BossRuntime(
                    1,
                    clockTicks + 40L,
                    clockTicks + SUNBIRD_TELEPORT_INTERVAL_TICKS,
                    clockTicks
                )
            );
            int observedPhase = profile.phaseForHealth(boss.currentHealth(), boss.maxHealth());
            if (observedPhase > runtime.phase) {
                runtime.phase = observedPhase;
                handlePhaseTransition(profile, boss, entity, runtime);
            }
            processSunbirdArena(boss, entity, runtime);
            processSunbirdTeleport(boss, entity, runtime);
            if (boss.scriptedAction() || clockTicks < runtime.nextActionTick) {
                continue;
            }

            BossMechanicProfile.Mechanic mechanic = profile.mechanic(runtime.phase, runtime.actionIndex);
            if (queueMechanic(boss, entity, mechanic)) {
                runtime.actionIndex++;
                runtime.nextActionTick = clockTicks + profile.intervalTicks(runtime.phase);
            } else {
                runtime.nextActionTick = clockTicks + 20L;
            }
        }

        Iterator<Map.Entry<UUID, BossRuntime>> iterator = runtimes.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, BossRuntime> entry = iterator.next();
            if (activeBosses.contains(entry.getKey())) {
                continue;
            }
            destroySummons(entry.getValue());
            removePendingForBoss(entry.getKey());
            iterator.remove();
        }
    }

    /**
     * サンバード戦の半径18ブロック境界を表示し、外周へ継続ダメージと帰還タックルを適用します。
     *
     * @param boss 対象ボス
     * @param entity 対象ボスの実体
     * @param runtime ボス固有の実行状態
     */
    private void processSunbirdArena(
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossRuntime runtime
    ) {
        if (!BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD.equals(boss.template().id())) {
            return;
        }

        Location center = boss.spawnLocation();
        boolean birdMeteorActive = isBirdMeteorActive(boss.instanceId());
        if (clockTicks >= runtime.nextArenaPulseTick) {
            renderSunbirdArenaBoundary(center);
            if (!birdMeteorActive) {
                damagePlayersOutsideSunbirdArena(boss, center);
            }
            runtime.nextArenaPulseTick = clockTicks + SUNBIRD_ARENA_PULSE_INTERVAL_TICKS;
        }

        if (birdMeteorActive
            || boss.scriptedAction()
            || horizontalDistanceSquared(entity.getLocation(), center) <= SUNBIRD_ARENA_RADIUS * SUNBIRD_ARENA_RADIUS) {
            return;
        }
        List<Player> safePlayers = nearbyManagedPlayers(center, SUNBIRD_ARENA_RADIUS);
        if (safePlayers.isEmpty()) {
            return;
        }

        Player target = nearestPlayer(entity.getLocation(), safePlayers);
        Location destination = sunbirdTackleDestination(center, target.getLocation());
        Vector direction = horizontalDirection(entity.getLocation(), destination, entity.getFacing().getDirection());
        boss.scriptedAction(true);
        addPending(
            boss,
            BossMechanicProfile.Mechanic.SUNBIRD_RETURN_TACKLE,
            entity.getLocation(),
            direction,
            SUNBIRD_TACKLE_TELEGRAPH_TICKS,
            destination
        );
        runtime.nextActionTick = Math.max(runtime.nextActionTick, clockTicks + 30L);
    }

    /**
     * スポーン地点から半径18ブロックより外側にいる管理対象Playerへ火属性ダメージを与えます。
     *
     * @param boss ダメージ発生元
     * @param center 安全圏の中心
     */
    private void damagePlayersOutsideSunbirdArena(
        @NotNull MobInstance boss,
        @NotNull Location center
    ) {
        double safeRadiusSquared = SUNBIRD_ARENA_RADIUS * SUNBIRD_ARENA_RADIUS;
        for (Player player : managedPlayersInWorld(center)) {
            if (horizontalDistanceSquared(player.getLocation(), center) <= safeRadiusSquared) {
                continue;
            }
            damagePlayer(boss, player, AttackType.MAGIC, DamageElement.FIRE, SUNBIRD_ARENA_DAMAGE_RATIO);
        }
    }

    /**
     * 境界外のサンバードが帰還タックルで到達する、境界内の地点を返します。
     *
     * @param spawnLocation 安全圏中心
     * @param targetLocation タックル対象の現在地点
     * @return 水平距離9ブロック以内へ丸めた到達地点
     */
    static @NotNull Location sunbirdTackleDestination(
        @NotNull Location spawnLocation,
        @NotNull Location targetLocation
    ) {
        Vector offset = targetLocation.toVector().subtract(spawnLocation.toVector()).setY(0.0D);
        if (offset.lengthSquared() > SUNBIRD_TACKLE_DESTINATION_RADIUS * SUNBIRD_TACKLE_DESTINATION_RADIUS) {
            offset.normalize().multiply(SUNBIRD_TACKLE_DESTINATION_RADIUS);
        }
        Location destination = spawnLocation.clone().add(offset);
        destination.setY(targetLocation.getY() + 1.0D);
        return destination;
    }

    /**
     * サンバードを一定間隔でスポーン地点周辺へ転移させます。
     *
     * <p>必殺技などの専用行動中と、周囲に管理対象プレイヤーがいない間は実行せず、
     * 条件が整うまで短い間隔で再判定します。</p>
     *
     * @param boss 転移対象のボス
     * @param entity 転移前のBukkit Entity
     * @param runtime ボス固有の実行状態
     */
    private void processSunbirdTeleport(
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossRuntime runtime
    ) {
        if (!BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD.equals(boss.template().id())
            || clockTicks < runtime.nextTeleportTick) {
            return;
        }
        if (boss.scriptedAction() || nearbyManagedPlayers(entity.getLocation(), TARGET_RANGE).isEmpty()) {
            runtime.nextTeleportTick = clockTicks + 20L;
            return;
        }

        Location origin = entity.getLocation();
        Location destination = sunbirdTeleportDestination(boss.spawnLocation(), runtime.teleportIndex);
        runtime.teleportIndex++;
        renderSunbirdTeleport(origin);
        mobService.resetPosition(boss, destination);
        renderSunbirdTeleport(destination);
        runtime.nextTeleportTick = clockTicks + SUNBIRD_TELEPORT_INTERVAL_TICKS;
        runtime.nextActionTick = Math.max(runtime.nextActionTick, clockTicks + 20L);
    }

    /**
     * スポーン地点を中心とする6地点から、転移回数に対応する移動先を返します。
     *
     * @param spawnLocation スポーン地点
     * @param teleportIndex 0始まりの転移回数
     * @return スポーン地点から水平7ブロック離れた転移先
     */
    static @NotNull Location sunbirdTeleportDestination(
        @NotNull Location spawnLocation,
        int teleportIndex
    ) {
        double angle = Math.PI * 2.0D * Math.floorMod(teleportIndex, SUNBIRD_TELEPORT_POINT_COUNT)
            / SUNBIRD_TELEPORT_POINT_COUNT;
        return spawnLocation.clone().add(
            Math.cos(angle) * SUNBIRD_TELEPORT_RADIUS,
            0.0D,
            Math.sin(angle) * SUNBIRD_TELEPORT_RADIUS
        );
    }

    /**
     * 転移前後の地点へ太陽色の円と閃光を表示します。
     *
     * @param center 演出中心
     */
    private void renderSunbirdTeleport(@NotNull Location center) {
        renderCircle(center, 1.8D, SharedParticleDefinitions.SUNBIRD_SOLAR_FLAME, 20);
        renderRange(
            center,
            List.of(center.clone().add(0.0D, 1.0D, 0.0D)),
            SharedParticleDefinitions.SUNBIRD_SOLAR_FLASH
        );
    }

    private void processPendingMechanics() {
        Iterator<PendingMechanic> iterator = pendingMechanics.iterator();
        while (iterator.hasNext()) {
            PendingMechanic pending = iterator.next();
            MobInstance boss = mobService.getInstance(pending.bossInstanceId());
            Entity entity = boss == null ? null : mobService.entityController().getEntity(boss);
            boolean noManagedTarget = pending.mechanic() != BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR
                && pending.mechanic() != BossMechanicProfile.Mechanic.SUNBIRD_RETURN_TACKLE
                && entity != null
                && nearbyManagedPlayers(entity.getLocation(), TARGET_RANGE).isEmpty();
            if (boss == null || entity == null || !entity.isValid() || entity.isDead() || boss.currentHealth() <= 0.0D
                || entity.getWorld() != pending.anchor().getWorld()
                || noManagedTarget) {
                removePendingVisuals(pending);
                releaseScriptedAction(pending);
                iterator.remove();
                continue;
            }
            if (clockTicks < pending.executeAtTick()) {
                if (pending.mechanic() == BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR) {
                    updateBirdMeteorBossBar(pending);
                }
                renderTelegraph(pending);
                continue;
            }
            executeMechanic(boss, pending);
            removePendingVisuals(pending);
            releaseScriptedAction(pending);
            iterator.remove();
        }
    }

    private void handlePhaseTransition(
        @NotNull BossMechanicProfile profile,
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossRuntime runtime
    ) {
        if (boss.template().shield().active()) {
            boss.currentShield(boss.shieldDisplayCapacity(), System.currentTimeMillis());
        }
        if (BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD.equals(boss.template().id())
            && runtime.phase >= 2
            && !runtime.finalPhaseTriggered) {
            runtime.finalPhaseTriggered = true;
            removePendingForBoss(boss.instanceId());
            boss.scriptedAction(true);
            mobService.resetPosition(boss, boss.spawnLocation());
            Entity resetEntity = mobService.entityController().getEntity(boss);
            Location anchor = boss.spawnLocation();
            Vector direction = resetEntity == null
                ? new Vector(0.0D, 0.0D, 1.0D)
                : resetEntity.getFacing().getDirection();
            startBirdMeteor(boss, anchor);
            addPending(boss, BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR, anchor, direction,
                SUNBIRD_BIRD_METEOR_TELEGRAPH_TICKS);
            runtime.nextActionTick = clockTicks + SUNBIRD_BIRD_METEOR_TELEGRAPH_TICKS + 20L;
            return;
        }

        Location center = entity.getLocation().add(0.0D, 0.5D, 0.0D);
        renderCircle(center, 3.0D + runtime.phase, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 28);
        entity.getWorld().playSound(center, "entity.warden.sonic_boom", 1.2F, runtime.phase == 3 ? 0.65F : 0.85F);
        runtime.nextActionTick = Math.min(runtime.nextActionTick, clockTicks + 25L);

        if (runtime.phase < 2 || runtime.summonsTriggered) {
            return;
        }
        runtime.summonsTriggered = true;
    }

    /**
     * バードメテオの詠唱状態を開始し、ボスを一時的に無敵にします。
     *
     * @param boss 対象ボス
     * @param arenaCenter 境界の中心
     */
    private void startBirdMeteor(
        @NotNull MobInstance boss,
        @NotNull Location arenaCenter
    ) {
        finishBirdMeteor(boss.instanceId());
        boss.damageImmune(true);
        Entity entity = mobService.entityController().getEntity(boss);
        if (entity != null && entity.isValid()) {
            entity.setInvulnerable(true);
        }

        BossBar bossBar = Bukkit.createBossBar("§cバードメテオ", BarColor.RED, BarStyle.SOLID);
        bossBar.setProgress(1.0D);
        bossBar.setVisible(true);
        birdMeteorStates.put(
            boss.instanceId(),
            new BirdMeteorState(randomBirdMeteorSafeZoneCenter(arenaCenter), bossBar)
        );
    }

    /**
     * バードメテオ詠唱中の BossBar を更新し、周囲の管理対象Playerへ同期します。
     *
     * @param pending バードメテオ予兆
     */
    private void updateBirdMeteorBossBar(@NotNull PendingMechanic pending) {
        BirdMeteorState state = birdMeteorStates.get(pending.bossInstanceId());
        if (state == null) {
            return;
        }
        long remainingTicks = Math.max(0L, pending.executeAtTick() - clockTicks);
        double progress = Math.clamp(
            (double) remainingTicks / SUNBIRD_BIRD_METEOR_TELEGRAPH_TICKS,
            0.0D,
            1.0D
        );
        BossBar bossBar = state.bossBar();
        bossBar.setTitle("§cバードメテオ");
        bossBar.setProgress(progress);

        Set<UUID> visiblePlayerIds = nearbyManagedPlayers(pending.anchor(), TARGET_RANGE).stream()
            .map(Player::getUniqueId)
            .collect(java.util.stream.Collectors.toSet());
        for (Player player : List.copyOf(bossBar.getPlayers())) {
            if (!visiblePlayerIds.contains(player.getUniqueId())) {
                bossBar.removePlayer(player);
            }
        }
        for (UUID playerId : visiblePlayerIds) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && !bossBar.getPlayers().contains(player)) {
                bossBar.addPlayer(player);
            }
        }
    }

    /**
     * バードメテオの表示と一時無敵を解除します。
     *
     * @param bossInstanceId 対象ボスのインスタンスID
     */
    private void finishBirdMeteor(@NotNull UUID bossInstanceId) {
        BirdMeteorState state = birdMeteorStates.remove(bossInstanceId);
        if (state != null) {
            BossBar bossBar = state.bossBar();
            bossBar.removeAll();
            bossBar.setProgress(0.0D);
            bossBar.setVisible(false);
        }

        MobInstance boss = mobService.getInstance(bossInstanceId);
        if (boss == null) {
            return;
        }
        boolean templateDamageImmune = boss.template().damageImmune();
        boss.damageImmune(templateDamageImmune);
        Entity entity = mobService.entityController().getEntity(boss);
        if (entity != null && entity.isValid()) {
            entity.setInvulnerable(templateDamageImmune);
        }
    }

    private boolean isBirdMeteorActive(@NotNull UUID bossInstanceId) {
        return birdMeteorStates.containsKey(bossInstanceId);
    }

    /**
     * 境界内に安全円柱全体が収まるよう、中心から半径15ブロック以内の地点を一様に選びます。
     *
     * @param arenaCenter 境界の中心
     * @return 安全円柱の中心
     */
    private static @NotNull Location randomBirdMeteorSafeZoneCenter(@NotNull Location arenaCenter) {
        double maximumOffset = SUNBIRD_ARENA_RADIUS - SUNBIRD_BIRD_METEOR_SAFE_RADIUS;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
        double distance = Math.sqrt(random.nextDouble()) * maximumOffset;
        return arenaCenter.clone().add(
            Math.cos(angle) * distance,
            0.0D,
            Math.sin(angle) * distance
        );
    }

    private void spawnAdds(
        @NotNull Location center,
        @NotNull String templateId,
        int count,
        @NotNull BossRuntime runtime
    ) {
        for (int index = 0; index < count; index++) {
            double angle = (Math.PI * 2.0D * index) / count;
            Location spawn = center.clone().add(Math.cos(angle) * 3.5D, 0.0D, Math.sin(angle) * 3.5D);
            MobInstance summoned = mobService.spawn(templateId, spawn);
            if (summoned == null) {
                continue;
            }
            summoned.keepWhenUnobserved(true);
            runtime.summonedMobIds.add(summoned.instanceId());
        }
    }

    private void destroySummons(@NotNull BossRuntime runtime) {
        for (UUID summonId : runtime.summonedMobIds) {
            mobService.destroy(summonId);
        }
        runtime.summonedMobIds.clear();
    }

    /**
     * フェーズローテーションの次の攻撃を予兆キューへ追加します。
     *
     * <p>天陽崩落は通常ローテーションでも専用行動として扱い、発動時の位置から移動せずに詠唱します。</p>
     *
     * @param boss 発動するボス
     * @param entity 発動時点の実体
     * @param mechanic 発動するギミック
     * @return 対象を解決して予約できた場合は {@code true}
     */
    private boolean queueMechanic(
        @NotNull MobInstance boss,
        @NotNull Entity entity,
        @NotNull BossMechanicProfile.Mechanic mechanic
    ) {
        Location bossLocation = entity.getLocation();
        List<Player> targets = nearbyManagedPlayers(bossLocation, TARGET_RANGE);
        if (targets.isEmpty()) {
            return false;
        }
        Player primaryTarget = nearestPlayer(bossLocation, targets);
        Location targetLocation = primaryTarget.getLocation();
        Vector direction = horizontalDirection(bossLocation, targetLocation, entity.getFacing().getDirection());
        long telegraphTicks = telegraphTicks(mechanic);
        if (mechanic == BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA) {
            boss.scriptedAction(true);
            addPending(boss, mechanic, bossLocation, direction, telegraphTicks);
            return true;
        }
        Location anchor = mechanic == BossMechanicProfile.Mechanic.SUNBIRD_SUNSTRIKE
            ? targetLocation
            : bossLocation;

        addPending(boss, mechanic, anchor, direction, telegraphTicks);
        return true;
    }

    /**
     * 到達地点を持たない通常ギミックを予兆キューへ追加します。
     *
     * @param boss 発動するボス
     * @param mechanic 発動するギミック
     * @param anchor 予兆中心
     * @param direction 攻撃方向
     * @param delayTicks 発動までのtick数
     */
    private void addPending(
        @NotNull MobInstance boss,
        @NotNull BossMechanicProfile.Mechanic mechanic,
        @NotNull Location anchor,
        @NotNull Vector direction,
        long delayTicks
    ) {
        addPending(boss, mechanic, anchor, direction, delayTicks, null);
    }

    /**
     * 帰還タックルを含むギミックを予兆キューへ追加します。
     *
     * @param boss 発動するボス
     * @param mechanic 発動するギミック
     * @param anchor 予兆中心
     * @param direction 攻撃方向
     * @param delayTicks 発動までのtick数
     * @param destination 発動後の到達地点。移動しないギミックは {@code null}
     */
    private void addPending(
        @NotNull MobInstance boss,
        @NotNull BossMechanicProfile.Mechanic mechanic,
        @NotNull Location anchor,
        @NotNull Vector direction,
        long delayTicks,
        @Nullable Location destination
    ) {
        List<UUID> displayEntityIds = switch (mechanic) {
            case SUNBIRD_SOLAR_NOVA -> spawnSunbirdRitualDisplays(anchor, SUNBIRD_NOVA_DISPLAY_COUNT);
            default -> List.of();
        };
        PendingMechanic pending = new PendingMechanic(
            boss.instanceId(),
            mechanic,
            anchor,
            direction,
            clockTicks + delayTicks,
            displayEntityIds,
            destination
        );
        pendingMechanics.add(pending);
        if (mechanic == BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR) {
            updateBirdMeteorBossBar(pending);
        }
        renderTelegraph(pending);
        World world = anchor.getWorld();
        if (world != null) {
            world.playSound(anchor, "block.note_block.bass", 0.9F, 0.65F);
        }
    }

    /**
     * ギミックごとの予兆時間を返します。
     *
     * @param mechanic 対象ギミック
     * @return 予兆tick数
     */
    private long telegraphTicks(@NotNull BossMechanicProfile.Mechanic mechanic) {
        return switch (mechanic) {
            case COLOSSUS_QUAKE -> 25L;
            case COLOSSUS_RUNE_LANES, SUNBIRD_SOLAR_BEAM -> 30L;
            case COLOSSUS_COLLAPSE -> 35L;
            case SUNBIRD_SOLAR_FLARE -> 20L;
            case SUNBIRD_SUNSTRIKE -> 25L;
            case SUNBIRD_SOLAR_NOVA -> SUNBIRD_NOVA_TELEGRAPH_TICKS;
            case SUNBIRD_BIRD_METEOR -> SUNBIRD_BIRD_METEOR_TELEGRAPH_TICKS;
            case SUNBIRD_RETURN_TACKLE -> SUNBIRD_TACKLE_TELEGRAPH_TICKS;
        };
    }

    /**
     * 予兆または発動瞬間の、現在有効な攻撃範囲を表示します。
     *
     * @param pending 表示対象のギミック情報
     */
    private void renderTelegraph(@NotNull PendingMechanic pending) {
        switch (pending.mechanic()) {
            case COLOSSUS_QUAKE -> renderCircle(pending.anchor(), 4.5D, SharedParticleDefinitions.BOSS_MECHANIC_CRIT, 28);
            case COLOSSUS_RUNE_LANES -> renderCross(
                pending.anchor(), pending.direction(), COLOSSUS_RUNE_LANES_MAX_LENGTH,
                COLOSSUS_RUNE_LANES_HALF_WIDTH,
                SharedParticleDefinitions.BOSS_MECHANIC_SPARK
            );
            case COLOSSUS_COLLAPSE -> {
                renderCircle(pending.anchor(), 2.8D, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 20);
                renderCircle(pending.anchor(), 7.0D, SharedParticleDefinitions.BOSS_MECHANIC_SOUL_FIRE, 36);
            }
            case SUNBIRD_SOLAR_FLARE -> renderCircle(
                pending.anchor(), SUNBIRD_FLARE_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_FLAME, 32
            );
            case SUNBIRD_SUNSTRIKE -> renderCircle(
                pending.anchor(), SUNBIRD_SUNSTRIKE_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_DUST, 24
            );
            case SUNBIRD_SOLAR_BEAM -> renderLane(
                pending.anchor(), pending.direction(), SUNBIRD_BEAM_LENGTH, SUNBIRD_BEAM_HALF_WIDTH,
                SharedParticleDefinitions.SUNBIRD_SOLAR_DUST
            );
            case SUNBIRD_SOLAR_NOVA -> {
                renderCircle(
                    pending.anchor(), 4.0D, SharedParticleDefinitions.SUNBIRD_SOLAR_FLAME,
                    SUNBIRD_NOVA_INNER_RING_POINT_COUNT
                );
                renderCircle(
                    pending.anchor(), 8.0D, SharedParticleDefinitions.SUNBIRD_SOLAR_DUST,
                    SUNBIRD_NOVA_MIDDLE_RING_POINT_COUNT
                );
                renderCircle(
                    pending.anchor(), SUNBIRD_NOVA_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_DUST,
                    SUNBIRD_NOVA_OUTER_RING_POINT_COUNT
                );
                animateSunbirdRitualDisplays(pending);
            }
            case SUNBIRD_BIRD_METEOR -> renderBirdMeteorTelegraph(pending);
            case SUNBIRD_RETURN_TACKLE -> renderLane(
                pending.anchor(), pending.direction(), pending.travelDistance(), SUNBIRD_TACKLE_HALF_WIDTH,
                SharedParticleDefinitions.SUNBIRD_SOLAR_DUST
            );
        }
    }

    /**
     * 予兆終了時に範囲演出と対応するギミック効果を適用します。
     *
     * @param boss ギミックを発動するボス
     * @param pending 発動時刻を過ぎた予兆情報
     */
    private void executeMechanic(@NotNull MobInstance boss, @NotNull PendingMechanic pending) {
        World world = pending.anchor().getWorld();
        if (world == null) {
            return;
        }
        renderImpact(pending);
        switch (pending.mechanic()) {
            case COLOSSUS_QUAKE -> {
                damageCircle(boss, pending.anchor(), 0.0D, 4.5D, AttackType.MELEE, DamageElement.NONE, 0.78D, 0.55D);
                breakCrater(boss, pending.anchor(), 3.0D);
            }
            case COLOSSUS_RUNE_LANES -> damageCross(
                boss, pending.anchor(), pending.direction(), COLOSSUS_RUNE_LANES_MAX_LENGTH,
                COLOSSUS_RUNE_LANES_HALF_WIDTH,
                AttackType.MAGIC, DamageElement.LIGHTNING, 0.68D
            );
            case COLOSSUS_COLLAPSE -> {
                damageCircle(boss, pending.anchor(), 2.8D, 7.0D, AttackType.MAGIC, DamageElement.NONE, 0.95D, 0.7D);
                breakRing(boss, pending.anchor(), 3.0D, 6.0D);
            }
            case SUNBIRD_SOLAR_FLARE -> damageCircle(
                boss, pending.anchor(), 0.0D, SUNBIRD_FLARE_RADIUS,
                AttackType.MAGIC, DamageElement.FIRE, 0.65D, 0.75D
            );
            case SUNBIRD_SUNSTRIKE -> damageCircle(
                boss, pending.anchor(), 0.0D, SUNBIRD_SUNSTRIKE_RADIUS,
                AttackType.MAGIC, DamageElement.FIRE, 0.75D, 0.25D
            );
            case SUNBIRD_SOLAR_BEAM -> damageLine(
                boss, pending.anchor(), pending.direction(), SUNBIRD_BEAM_LENGTH, SUNBIRD_BEAM_HALF_WIDTH,
                AttackType.MAGIC, DamageElement.FIRE, 0.85D, 0.35D
            );
            case SUNBIRD_SOLAR_NOVA -> damageCircle(
                boss, pending.anchor(), 0.0D, SUNBIRD_NOVA_RADIUS,
                AttackType.MAGIC, DamageElement.FIRE, 1.45D, 1.1D, SUNBIRD_NOVA_ACCURACY_BONUS
            );
            case SUNBIRD_BIRD_METEOR -> damagePlayersOutsideBirdMeteorSafeZone(
                boss, pending.anchor(), birdMeteorSafeZoneCenter(pending)
            );
            case SUNBIRD_RETURN_TACKLE -> {
                damageLine(
                    boss, pending.anchor(), pending.direction(), pending.travelDistance(), SUNBIRD_TACKLE_HALF_WIDTH,
                    AttackType.MELEE, DamageElement.FIRE, 1.00D, 0.45D
                );
                if (pending.destination() != null) {
                    mobService.resetPosition(boss, pending.destination());
                }
            }
        }
        world.playSound(pending.anchor(), "entity.generic.explode", 1.0F, 0.85F);
    }

    /**
     * 円形範囲内の管理対象Playerへ通常の命中判定でダメージを適用します。
     *
     * @param boss ダメージ発生元
     * @param center 円形範囲の中心
     * @param innerRadius 内側の無効範囲半径
     * @param outerRadius 外側の有効範囲半径
     * @param attackType 攻撃種別
     * @param element ダメージ属性
     * @param ratio 攻撃倍率
     * @param pushStrength ノックバック強度
     */
    private void damageCircle(
        @NotNull MobInstance boss,
        @NotNull Location center,
        double innerRadius,
        double outerRadius,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double pushStrength
    ) {
        damageCircle(
                boss,
                center,
                innerRadius,
                outerRadius,
                attackType,
                element,
                ratio,
                pushStrength,
                0.0D
        );
    }

    /**
     * 円形範囲内の管理対象Playerへ、一撃限定の命中補正を加えてダメージを適用します。
     * 命中補正は%ポイントとして命中率へ加算し、他の攻撃処理へ持ち越しません。
     *
     * @param boss ダメージ発生元
     * @param center 円形範囲の中心
     * @param innerRadius 内側の無効範囲半径
     * @param outerRadius 外側の有効範囲半径
     * @param attackType 攻撃種別
     * @param element ダメージ属性
     * @param ratio 攻撃倍率
     * @param pushStrength ノックバック強度
     * @param accuracyBonus この一撃だけ命中率へ加算する補正値（%ポイント）
     */
    private void damageCircle(
        @NotNull MobInstance boss,
        @NotNull Location center,
        double innerRadius,
        double outerRadius,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double pushStrength,
        double accuracyBonus
    ) {
        double innerSquared = innerRadius * innerRadius;
        double outerSquared = outerRadius * outerRadius;
        for (Player player : nearbyManagedPlayers(center, outerRadius + 1.0D)) {
            double distanceSquared = horizontalDistanceSquared(player.getLocation(), center);
            if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
                continue;
            }
            damagePlayer(boss, player, attackType, element, ratio, accuracyBonus);
            pushAway(player, center, pushStrength);
        }
    }

    /**
     * バードメテオの安全円柱の外側にいる管理対象Playerへ即死級ダメージを与えます。
     *
     * @param boss ダメージ発生元
     * @param arenaCenter 判定する境界の中心
     * @param safeZoneCenter 安全円柱の中心
     */
    private void damagePlayersOutsideBirdMeteorSafeZone(
        @NotNull MobInstance boss,
        @NotNull Location arenaCenter,
        @NotNull Location safeZoneCenter
    ) {
        double safeRadiusSquared = SUNBIRD_BIRD_METEOR_SAFE_RADIUS * SUNBIRD_BIRD_METEOR_SAFE_RADIUS;
        for (Player player : nearbyManagedPlayers(arenaCenter, SUNBIRD_ARENA_RADIUS)) {
            Location playerLocation = player.getLocation();
            double verticalOffset = playerLocation.getY() - safeZoneCenter.getY();
            if (horizontalDistanceSquared(playerLocation, safeZoneCenter) <= safeRadiusSquared
                && verticalOffset >= 0.0D
                && verticalOffset <= SUNBIRD_BIRD_METEOR_SAFE_HEIGHT) {
                continue;
            }
            damagePlayer(
                boss,
                player,
                AttackType.MAGIC,
                DamageElement.FIRE,
                SUNBIRD_BIRD_METEOR_DAMAGE_RATIO,
                SUNBIRD_BIRD_METEOR_ACCURACY_BONUS
            );
        }
    }

    private void damageLine(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double pushStrength
    ) {
        for (Player player : nearbyManagedPlayers(origin, length + width)) {
            if (!insideLine(player.getLocation(), origin, direction, length, width)) {
                continue;
            }
            damagePlayer(boss, player, attackType, element, ratio);
            pushAway(player, origin, pushStrength);
        }
    }

    /**
     * 壁で区切られた交差ルーンの線分内にいるプレイヤーへダメージを適用します。
     *
     * @param boss 攻撃するボス
     * @param origin 交差の中心
     * @param direction 基準となる向き
     * @param length 壁がない場合の各方向の最大長
     * @param width 各線分の半幅
     * @param attackType 攻撃種別
     * @param element ダメージ属性
     * @param ratio 攻撃倍率
     */
    private void damageCross(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        List<LineSegment> segments = resolveCrossSegments(origin, direction, length);
        for (Player player : nearbyManagedPlayers(origin, length + width)) {
            Location point = player.getLocation();
            boolean hit = segments.stream().anyMatch(segment -> insideLine(
                point,
                origin,
                segment.direction(),
                segment.length(),
                width
            ));
            if (hit) {
                damagePlayer(boss, player, attackType, element, ratio);
            }
        }
    }

    private void damageCone(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double angleDegrees,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        double minimumDot = Math.cos(Math.toRadians(angleDegrees / 2.0D));
        for (Player player : nearbyManagedPlayers(origin, length)) {
            Vector offset = player.getLocation().toVector().subtract(origin.toVector()).setY(0.0D);
            double distance = offset.length();
            if (distance <= 0.001D || distance > length) {
                continue;
            }
            if (offset.normalize().dot(direction) >= minimumDot) {
                damagePlayer(boss, player, attackType, element, ratio);
            }
        }
    }

    /**
     * 管理対象Playerへ通常の命中判定でダメージを適用します。
     *
     * @param boss ダメージ発生元
     * @param player 被弾Player
     * @param attackType 攻撃種別
     * @param element ダメージ属性
     * @param ratio 攻撃倍率
     */
    private void damagePlayer(
        @NotNull MobInstance boss,
        @NotNull Player player,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio
    ) {
        damagePlayer(boss, player, attackType, element, ratio, 0.0D);
    }

    /**
     * 管理対象Playerへ、一撃限定の命中補正を加えてダメージを適用します。
     * 命中補正は%ポイントとして命中率へ加算し、後続攻撃へ持ち越しません。
     *
     * @param boss ダメージ発生元
     * @param player 被弾Player
     * @param attackType 攻撃種別
     * @param element ダメージ属性
     * @param ratio 攻撃倍率
     * @param accuracyBonus この一撃だけ命中率へ加算する補正値（%ポイント）
     */
    private void damagePlayer(
        @NotNull MobInstance boss,
        @NotNull Player player,
        @NotNull AttackType attackType,
        @NotNull DamageElement element,
        double ratio,
        double accuracyBonus
    ) {
        AstEntity victim = damageService.resolveEntity(player);
        if (!victim.isPlayer()) {
            return;
        }
        if (accuracyBonus > 0.0D) {
            damageService.attackWithAccuracyBonus(
                AstEntity.mob(boss),
                victim,
                attackType,
                List.of(new DamageComponent(element, ratio)),
                DamageSource.SKILL,
                accuracyBonus
            );
            return;
        }
        damageService.attack(
                AstEntity.mob(boss),
                victim,
                attackType,
                List.of(new DamageComponent(element, ratio)),
                DamageSource.SKILL
        );
    }

    private void pushAway(@NotNull Player player, @NotNull Location center, double strength) {
        if (strength <= 0.0D) {
            return;
        }
        Vector push = player.getLocation().toVector().subtract(center.toVector()).setY(0.0D);
        if (push.lengthSquared() <= 0.001D) {
            push = new Vector(1.0D, 0.0D, 0.0D);
        }
        push.normalize().multiply(strength).setY(Math.min(0.55D, 0.2D + strength * 0.25D));
        player.setVelocity(player.getVelocity().add(push));
    }

    static boolean insideLine(
        @NotNull Location point,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double width
    ) {
        Vector normalized = direction.clone().setY(0.0D);
        if (normalized.lengthSquared() <= 0.001D) {
            return false;
        }
        normalized.normalize();
        Vector offset = point.toVector().subtract(origin.toVector()).setY(0.0D);
        double projection = offset.dot(normalized);
        if (projection < 0.0D || projection > length) {
            return false;
        }
        Vector nearest = normalized.multiply(projection);
        return offset.subtract(nearest).lengthSquared() <= width * width;
    }

    private static double horizontalDistanceSquared(@NotNull Location left, @NotNull Location right) {
        double x = left.getX() - right.getX();
        double z = left.getZ() - right.getZ();
        return x * x + z * z;
    }

    private @NotNull List<Player> nearbyManagedPlayers(@NotNull Location center, double radius) {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        double radiusSquared = radius * radius;
        return world.getPlayers().stream()
            .filter(Player::isValid)
            .filter(player -> horizontalDistanceSquared(player.getLocation(), center) <= radiusSquared)
            .filter(player -> damageService.resolveEntity(player).isPlayer())
            .toList();
    }

    /**
     * 指定worldにいる有効な管理対象Playerを返します。
     *
     * @param center 対象worldを持つ基準地点
     * @return world内の管理対象Player
     */
    private @NotNull List<Player> managedPlayersInWorld(@NotNull Location center) {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        return world.getPlayers().stream()
            .filter(Player::isValid)
            .filter(player -> damageService.resolveEntity(player).isPlayer())
            .toList();
    }

    private @NotNull Player nearestPlayer(@NotNull Location center, @NotNull List<Player> players) {
        Player nearest = players.getFirst();
        double nearestDistance = horizontalDistanceSquared(nearest.getLocation(), center);
        for (Player player : players.subList(1, players.size())) {
            double distance = horizontalDistanceSquared(player.getLocation(), center);
            if (distance < nearestDistance) {
                nearest = player;
                nearestDistance = distance;
            }
        }
        return nearest;
    }

    private @NotNull Vector horizontalDirection(
        @NotNull Location origin,
        @NotNull Location target,
        @NotNull Vector fallback
    ) {
        Vector direction = target.toVector().subtract(origin.toVector()).setY(0.0D);
        if (direction.lengthSquared() <= 0.001D) {
            direction = fallback.clone().setY(0.0D);
        }
        if (direction.lengthSquared() <= 0.001D) {
            direction = new Vector(0.0D, 0.0D, 1.0D);
        }
        return direction.normalize();
    }

    /**
     * サンバードの安全圏境界を、同じ円周の上8層・下2層表示として1回の近傍閲覧者判定で表示します。
     *
     * @param center 安全圏の中心
     */
    private void renderSunbirdArenaBoundary(@NotNull Location center) {
        int layerCount = SUNBIRD_ARENA_BOUNDARY_UPPER_LAYER_COUNT
            + SUNBIRD_ARENA_BOUNDARY_LOWER_LAYER_COUNT;
        List<Location> locations = new ArrayList<>(
            SUNBIRD_ARENA_BOUNDARY_POINT_COUNT * layerCount
        );
        for (int layer = -SUNBIRD_ARENA_BOUNDARY_LOWER_LAYER_COUNT;
             layer < SUNBIRD_ARENA_BOUNDARY_UPPER_LAYER_COUNT;
             layer++) {
            Location layerCenter = center.clone().add(
                0.0D,
                layer * SUNBIRD_ARENA_BOUNDARY_LAYER_HEIGHT,
                0.0D
            );
            locations.addAll(
                randomizedCircleLocations(
                    layerCenter, SUNBIRD_ARENA_RADIUS, SUNBIRD_ARENA_BOUNDARY_POINT_COUNT
                )
            );
        }
        renderRange(center, locations, SharedParticleDefinitions.SUNBIRD_ARENA_BOUNDARY);
    }

    /**
     * バードメテオの安全円柱をEND_RODパーティクルで表示します。
     *
     * @param pending バードメテオ予兆
     */
    private void renderBirdMeteorTelegraph(@NotNull PendingMechanic pending) {
        renderRange(
            pending.anchor(),
            birdMeteorSafeZoneParticleLocations(birdMeteorSafeZoneCenter(pending)),
            SharedParticleDefinitions.SUNBIRD_BIRD_METEOR_SAFE_ZONE
        );
    }

    /**
     * バードメテオの安全円柱中心を返します。
     *
     * @param pending バードメテオ予兆
     * @return 安全円柱の中心
     */
    private @NotNull Location birdMeteorSafeZoneCenter(@NotNull PendingMechanic pending) {
        BirdMeteorState state = birdMeteorStates.get(pending.bossInstanceId());
        return state == null ? pending.anchor().clone() : state.safeZoneCenter().clone();
    }

    /**
     * 安全円柱の上下円と側面の表示地点を作成します。
     *
     * @param center 安全円柱の中心
     * @return END_ROD表示地点
     */
    static @NotNull List<Location> birdMeteorSafeZoneParticleLocations(@NotNull Location center) {
        List<Location> locations = new ArrayList<>(
            SUNBIRD_BIRD_METEOR_SAFE_RING_POINT_COUNT * SUNBIRD_BIRD_METEOR_SAFE_LAYER_COUNT
                + SUNBIRD_BIRD_METEOR_SAFE_VERTICAL_LINE_COUNT * 6
        );
        double ringHeight = Math.max(
            0.0D,
            SUNBIRD_BIRD_METEOR_SAFE_HEIGHT - 0.15D
        );
        for (int layer = 0; layer < SUNBIRD_BIRD_METEOR_SAFE_LAYER_COUNT; layer++) {
            double height = SUNBIRD_BIRD_METEOR_SAFE_LAYER_COUNT <= 1
                ? 0.0D
                : ringHeight * layer / (SUNBIRD_BIRD_METEOR_SAFE_LAYER_COUNT - 1);
            locations.addAll(circleLocations(center.clone().add(0.0D, height, 0.0D),
                SUNBIRD_BIRD_METEOR_SAFE_RADIUS, SUNBIRD_BIRD_METEOR_SAFE_RING_POINT_COUNT));
        }

        for (int index = 0; index < SUNBIRD_BIRD_METEOR_SAFE_VERTICAL_LINE_COUNT; index++) {
            double angle = Math.PI * 2.0D * index / SUNBIRD_BIRD_METEOR_SAFE_VERTICAL_LINE_COUNT;
            double x = Math.cos(angle) * SUNBIRD_BIRD_METEOR_SAFE_RADIUS;
            double z = Math.sin(angle) * SUNBIRD_BIRD_METEOR_SAFE_RADIUS;
            for (double height = 0.15D; height < SUNBIRD_BIRD_METEOR_SAFE_HEIGHT; height += LINE_PARTICLE_INTERVAL) {
                locations.add(center.clone().add(x, height, z));
            }
            locations.add(center.clone().add(x, SUNBIRD_BIRD_METEOR_SAFE_HEIGHT, z));
        }
        return List.copyOf(locations);
    }

    /**
     * 安全円柱を除く境界内へ、発動時の爆発パーティクル地点を作成します。
     *
     * @param arenaCenter 境界の中心
     * @param safeZoneCenter 安全円柱の中心
     * @return 爆発パーティクル表示地点
     */
    static @NotNull List<Location> birdMeteorExplosionParticleLocations(
        @NotNull Location arenaCenter,
        @NotNull Location safeZoneCenter
    ) {
        int gridRadius = (int) Math.ceil(SUNBIRD_ARENA_RADIUS / SUNBIRD_BIRD_METEOR_EXPLOSION_GRID_SPACING);
        double safeRadiusSquared = SUNBIRD_BIRD_METEOR_SAFE_RADIUS * SUNBIRD_BIRD_METEOR_SAFE_RADIUS;
        double arenaRadiusSquared = SUNBIRD_ARENA_RADIUS * SUNBIRD_ARENA_RADIUS;
        List<Location> locations = new ArrayList<>();
        for (int xIndex = -gridRadius; xIndex <= gridRadius; xIndex++) {
            double x = xIndex * SUNBIRD_BIRD_METEOR_EXPLOSION_GRID_SPACING;
            for (int zIndex = -gridRadius; zIndex <= gridRadius; zIndex++) {
                double z = zIndex * SUNBIRD_BIRD_METEOR_EXPLOSION_GRID_SPACING;
                if (x * x + z * z > arenaRadiusSquared) {
                    continue;
                }
                Location point = arenaCenter.clone().add(x, 0.15D, z);
                if (horizontalDistanceSquared(point, safeZoneCenter) <= safeRadiusSquared) {
                    continue;
                }
                locations.add(point);
            }
        }
        return List.copyOf(locations);
    }

    /**
     * 円形の攻撃範囲を1回の近傍閲覧者判定で表示します。
     *
     * @param center 円の中心
     * @param radius 円の半径
     * @param particle 表示する共通パーティクル定義
     * @param points 円周上の表示地点数
     */
    private void renderCircle(
        @NotNull Location center,
        double radius,
        @NotNull SharedParticleDefinition particle,
        int points
    ) {
        renderRange(center, circleLocations(center, radius, points), particle);
    }

    /**
     * 円周へ送信するパーティクル地点を生成します。
     *
     * @param center 円の中心
     * @param radius 円の半径
     * @param points 円周上の地点数
     * @return 中心から指定水平距離にある地点一覧
     */
    static @NotNull List<Location> circleLocations(
        @NotNull Location center,
        double radius,
        int points
    ) {
        List<Location> locations = new ArrayList<>(points);
        for (int index = 0; index < points; index++) {
            double angle = (Math.PI * 2.0D * index) / points;
            locations.add(center.clone().add(Math.cos(angle) * radius, 0.15D, Math.sin(angle) * radius));
        }
        return List.copyOf(locations);
    }

    /**
     * 円周上の各パーティクル地点へ、境界を視認しやすくする小さな位置揺らぎを加えます。
     *
     * @param center 円の中心
     * @param radius 円の半径
     * @param points 円周上の地点数
     * @return 位置揺らぎを加えた地点一覧
     */
    private static @NotNull List<Location> randomizedCircleLocations(
        @NotNull Location center,
        double radius,
        int points
    ) {
        List<Location> locations = new ArrayList<>(circleLocations(center, radius, points));
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Location location : locations) {
            location.add(
                random.nextDouble(-SUNBIRD_ARENA_BOUNDARY_HORIZONTAL_JITTER, SUNBIRD_ARENA_BOUNDARY_HORIZONTAL_JITTER),
                random.nextDouble(-SUNBIRD_ARENA_BOUNDARY_VERTICAL_JITTER, SUNBIRD_ARENA_BOUNDARY_VERTICAL_JITTER),
                random.nextDouble(-SUNBIRD_ARENA_BOUNDARY_HORIZONTAL_JITTER, SUNBIRD_ARENA_BOUNDARY_HORIZONTAL_JITTER)
            );
        }
        return List.copyOf(locations);
    }

    /**
     * 線形レーンの中央と両端を1回の近傍閲覧者判定で表示します。
     *
     * @param origin レーンの始点
     * @param direction レーンの向き
     * @param length レーンの長さ
     * @param halfWidth レーンの半幅
     * @param particle 表示する共通パーティクル定義
     */
    private void renderLane(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        double halfWidth,
        @NotNull SharedParticleDefinition particle
    ) {
        Vector side = new Vector(-direction.getZ(), 0.0D, direction.getX()).normalize().multiply(halfWidth);
        List<Location> locations = new ArrayList<>();
        appendLine(locations, origin, direction, length);
        appendLine(locations, origin.clone().add(side), direction, length);
        appendLine(locations, origin.clone().subtract(side), direction, length);
        renderRange(origin, locations, particle);
    }

    /**
     * 壁で区切られた交差ルーンの4方向を1回の近傍閲覧者判定で表示します。
     *
     * @param origin 交差の中心
     * @param direction 基準となる向き
     * @param maximumLength 壁がない場合の各方向の安全な最大長
     * @param halfWidth 各方向の命中帯の半幅
     * @param particle 表示する共通パーティクル定義
     */
    private void renderCross(
        @NotNull Location origin,
        @NotNull Vector direction,
        double maximumLength,
        double halfWidth,
        @NotNull SharedParticleDefinition particle
    ) {
        renderRange(origin, crossParticleLocations(origin, direction, maximumLength, halfWidth), particle);
    }

    /**
     * 扇形の境界と中心線を1回の近傍閲覧者判定で表示します。
     *
     * @param origin 扇形の起点
     * @param direction 扇形の中心方向
     * @param length 扇形の長さ
     * @param particle 表示する共通パーティクル定義
     */
    private void renderCone(
        @NotNull Location origin,
        @NotNull Vector direction,
        double length,
        @NotNull SharedParticleDefinition particle
    ) {
        List<Location> locations = new ArrayList<>();
        appendLine(locations, origin, direction.clone().rotateAroundY(Math.toRadians(-26.0D)), length);
        appendLine(locations, origin, direction, length);
        appendLine(locations, origin, direction.clone().rotateAroundY(Math.toRadians(26.0D)), length);
        renderRange(origin, locations, particle);
    }

    /**
     * 指定した線分上のパーティクル地点を追加します。
     *
     * @param locations 追加先の地点一覧
     * @param origin 線分の始点
     * @param direction 線分の向き
     * @param length 線分の長さ
     */
    private static void appendLine(
        @NotNull List<Location> locations,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length
    ) {
        Vector step = direction.clone().setY(0.0D).normalize();
        for (double distance = 0.5D; distance <= length; distance += LINE_PARTICLE_INTERVAL) {
            locations.add(origin.clone().add(step.clone().multiply(distance)).add(0.0D, 0.15D, 0.0D));
        }
    }

    /**
     * 交差ルーンの中心線と命中帯境界のパーティクル地点を作成します。
     *
     * @param origin 交差の中心
     * @param direction 基準となる向き
     * @param maximumLength 壁がない場合の各方向の安全な最大長
     * @param halfWidth 各方向の命中帯の半幅
     * @return 中心線と両境界線を含む表示地点
     */
    static @NotNull List<Location> crossParticleLocations(
        @NotNull Location origin,
        @NotNull Vector direction,
        double maximumLength,
        double halfWidth
    ) {
        List<Location> locations = new ArrayList<>();
        for (LineSegment segment : resolveCrossSegments(origin, direction, maximumLength)) {
            Vector side = new Vector(-segment.direction().getZ(), 0.0D, segment.direction().getX())
                .normalize()
                .multiply(halfWidth);
            appendLine(locations, origin, segment.direction(), segment.length());
            appendLine(locations, origin.clone().add(side), segment.direction(), segment.length());
            appendLine(locations, origin.clone().subtract(side), segment.direction(), segment.length());
        }
        return List.copyOf(locations);
    }

    /**
     * 範囲内の閲覧者を一度だけ解決し、全地点へ共通パーティクルを送信します。
     *
     * @param center 閲覧者探索の中心
     * @param locations 表示地点
     * @param particle 表示する共通パーティクル定義
     */
    private void renderRange(
        @NotNull Location center,
        @NotNull List<Location> locations,
        @NotNull SharedParticleDefinition particle
    ) {
        particleDisplayService.spawnForNearbyViewers(center, locations, particle);
    }

    /**
     * 予兆が完了した瞬間に、実際に攻撃する範囲をパーティクルで再表示します。
     *
     * @param pending 発動するギミックの予兆情報
     */
    private void renderImpact(@NotNull PendingMechanic pending) {
        renderTelegraph(pending);
        switch (pending.mechanic()) {
            case COLOSSUS_QUAKE -> renderCircle(
                pending.anchor(), 4.5D, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION, 28
            );
            case COLOSSUS_RUNE_LANES -> renderCross(
                pending.anchor(), pending.direction(), COLOSSUS_RUNE_LANES_MAX_LENGTH,
                COLOSSUS_RUNE_LANES_HALF_WIDTH, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION
            );
            case COLOSSUS_COLLAPSE -> {
                renderCircle(pending.anchor(), 2.8D, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION, 20);
                renderCircle(pending.anchor(), 7.0D, SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION, 36);
            }
            case SUNBIRD_SOLAR_FLARE -> renderCircle(
                pending.anchor(), SUNBIRD_FLARE_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_IMPACT, 32
            );
            case SUNBIRD_SUNSTRIKE -> {
                renderCircle(pending.anchor(), SUNBIRD_SUNSTRIKE_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_IMPACT, 24);
                renderRange(
                    pending.anchor(),
                    List.of(pending.anchor().clone().add(0.0D, 1.0D, 0.0D)),
                    SharedParticleDefinitions.SUNBIRD_SOLAR_FLASH
                );
            }
            case SUNBIRD_SOLAR_BEAM -> renderLane(
                pending.anchor(), pending.direction(), SUNBIRD_BEAM_LENGTH, SUNBIRD_BEAM_HALF_WIDTH,
                SharedParticleDefinitions.SUNBIRD_SOLAR_IMPACT
            );
            case SUNBIRD_SOLAR_NOVA -> {
                renderCircle(
                    pending.anchor(), SUNBIRD_NOVA_RADIUS, SharedParticleDefinitions.SUNBIRD_SOLAR_IMPACT,
                    SUNBIRD_NOVA_OUTER_RING_POINT_COUNT
                );
                renderRange(
                    pending.anchor(),
                    List.of(pending.anchor().clone().add(0.0D, 2.0D, 0.0D)),
                    SharedParticleDefinitions.SUNBIRD_SOLAR_FLASH
                );
            }
            case SUNBIRD_BIRD_METEOR -> {
                renderRange(
                    pending.anchor(),
                    birdMeteorExplosionParticleLocations(pending.anchor(), birdMeteorSafeZoneCenter(pending)),
                    SharedParticleDefinitions.BOSS_MECHANIC_EXPLOSION
                );
                renderRange(
                    pending.anchor(),
                    List.of(birdMeteorSafeZoneCenter(pending).add(0.0D, SUNBIRD_BIRD_METEOR_SAFE_HEIGHT, 0.0D)),
                    SharedParticleDefinitions.SUNBIRD_SOLAR_FLASH
                );
            }
            case SUNBIRD_RETURN_TACKLE -> renderLane(
                pending.anchor(), pending.direction(), pending.travelDistance(), SUNBIRD_TACKLE_HALF_WIDTH,
                SharedParticleDefinitions.SUNBIRD_SOLAR_IMPACT
            );
        }
    }

    /**
     * 必殺技の詠唱中だけ、太陽儀式を表す BlockDisplay を生成します。
     *
     * @param center 儀式中心
     * @param displayCount 生成する表示 Entity 数
     * @return 生成した表示 Entity の UUID
     */
    private @NotNull List<UUID> spawnSunbirdRitualDisplays(@NotNull Location center, int displayCount) {
        World world = center.getWorld();
        if (world == null) {
            return List.of();
        }
        List<UUID> displayIds = new ArrayList<>(displayCount);
        for (int index = 0; index < displayCount; index++) {
            double angle = Math.PI * 2.0D * index / displayCount;
            Location spawn = center.clone().add(Math.cos(angle) * 6.0D, 0.8D, Math.sin(angle) * 6.0D);
            BlockDisplay display = world.spawn(spawn, BlockDisplay.class, entity -> {
                entity.setPersistent(false);
                entity.setInvulnerable(true);
                entity.setGravity(false);
                entity.setBlock(Material.GOLD_BLOCK.createBlockData());
                entity.setBrightness(new Display.Brightness(15, 15));
                entity.setViewRange(48.0F);
                entity.setDisplayWidth(1.0F);
                entity.setDisplayHeight(1.0F);
                entity.setTeleportDuration((int) TICK_PERIOD);
                entity.setTransformation(new Transformation(
                    new Vector3f(-0.4F, -0.4F, -0.4F),
                    new Quaternionf(),
                    new Vector3f(0.8F, 0.8F, 0.8F),
                    new Quaternionf()
                ));
            });
            displayIds.add(display.getUniqueId());
        }
        return List.copyOf(displayIds);
    }

    private void animateSunbirdRitualDisplays(@NotNull PendingMechanic pending) {
        animateSunbirdRitualDisplays(pending, SUNBIRD_NOVA_TELEGRAPH_TICKS);
    }

    /**
     * 指定した詠唱時間に対する進行度で、太陽儀式Displayを回転・収束させます。
     *
     * @param pending 対象予兆
     * @param telegraphTicks 詠唱全体のtick数
     */
    private void animateSunbirdRitualDisplays(
        @NotNull PendingMechanic pending,
        long telegraphTicks
    ) {
        long remainingTicks = Math.max(0L, pending.executeAtTick() - clockTicks);
        double progress = 1.0D - Math.clamp(
            (double) remainingTicks / telegraphTicks,
            0.0D,
            1.0D
        );
        double radius = 6.0D - progress * 2.5D;
        int displayCount = pending.displayEntityIds().size();
        for (int index = 0; index < pending.displayEntityIds().size(); index++) {
            Entity entity = Bukkit.getEntity(pending.displayEntityIds().get(index));
            if (!(entity instanceof BlockDisplay display) || !display.isValid()) {
                continue;
            }
            double angle = Math.PI * 2.0D * index / displayCount + progress * Math.PI * 2.0D;
            Location target = pending.anchor().clone().add(
                Math.cos(angle) * radius,
                0.8D + progress * 2.2D,
                Math.sin(angle) * radius
            );
            display.teleport(target);
        }
    }

    private void removePendingVisuals(@NotNull PendingMechanic pending) {
        for (UUID entityId : pending.displayEntityIds()) {
            Entity entity = Bukkit.getEntity(entityId);
            if (entity != null) {
                entity.remove();
            }
        }
    }

    /**
     * 専用行動として通常AIを停止したギミックの完了・中断時に状態を解放します。
     *
     * @param pending 完了または中断したギミック
     */
    private void releaseScriptedAction(@NotNull PendingMechanic pending) {
        if (pending.mechanic() != BossMechanicProfile.Mechanic.SUNBIRD_SOLAR_NOVA
            && pending.mechanic() != BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR
            && pending.mechanic() != BossMechanicProfile.Mechanic.SUNBIRD_RETURN_TACKLE) {
            return;
        }
        MobInstance boss = mobService.getInstance(pending.bossInstanceId());
        if (pending.mechanic() == BossMechanicProfile.Mechanic.SUNBIRD_BIRD_METEOR) {
            finishBirdMeteor(pending.bossInstanceId());
        }
        if (boss != null) {
            boss.scriptedAction(false);
        }
    }

    private void removePendingForBoss(@NotNull UUID bossInstanceId) {
        Iterator<PendingMechanic> iterator = pendingMechanics.iterator();
        while (iterator.hasNext()) {
            PendingMechanic pending = iterator.next();
            if (!pending.bossInstanceId().equals(bossInstanceId)) {
                continue;
            }
            removePendingVisuals(pending);
            releaseScriptedAction(pending);
            iterator.remove();
        }
        finishBirdMeteor(bossInstanceId);
    }

    /**
     * 交差ルーンの各腕を、壁との衝突または指定上限までの線分へ解決します。
     *
     * @param origin 交差の中心
     * @param direction 基準となる向き
     * @param maximumLength 壁がない場合の各方向の最大長
     * @return 前後左右の攻撃線分
     */
    private static @NotNull List<LineSegment> resolveCrossSegments(
        @NotNull Location origin,
        @NotNull Vector direction,
        double maximumLength
    ) {
        Vector forward = direction.clone().setY(0.0D).normalize();
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX());
        Vector backward = forward.clone().multiply(-1.0D);
        Vector oppositeSide = side.clone().multiply(-1.0D);
        return List.of(
            new LineSegment(forward, wallLimitedLength(origin, forward, maximumLength)),
            new LineSegment(backward, wallLimitedLength(origin, backward, maximumLength)),
            new LineSegment(side, wallLimitedLength(origin, side, maximumLength)),
            new LineSegment(oppositeSide, wallLimitedLength(origin, oppositeSide, maximumLength))
        );
    }

    /**
     * 指定方向の壁までの長さを、必ず指定上限以内で返します。
     *
     * @param origin 探索の始点
     * @param direction 探索方向
     * @param maximumLength 呼び出し側が要求する最大長（交差ルーンの安全上限以内へ丸める）
     * @return 壁がない場合は上限、壁がある場合は衝突地点までの長さ
     */
    static double wallLimitedLength(
        @NotNull Location origin,
        @NotNull Vector direction,
        double maximumLength
    ) {
        double boundedLength = cappedCrossArmLength(maximumLength, maximumLength);
        World world = origin.getWorld();
        Vector normalized = direction.clone().setY(0.0D);
        if (world == null || boundedLength <= 0.0D || normalized.lengthSquared() <= 0.001D) {
            return boundedLength;
        }
        normalized.normalize();
        RayTraceResult hit = world.rayTraceBlocks(
            origin,
            normalized,
            boundedLength,
            FluidCollisionMode.NEVER,
            true
        );
        if (hit == null || hit.getHitPosition() == null) {
            return boundedLength;
        }
        return cappedCrossArmLength(maximumLength, origin.toVector().distance(hit.getHitPosition()));
    }

    /**
     * 交差ルーンの壁までの距離を、安全上限の範囲へ丸めます。
     *
     * @param maximumLength 呼び出し側が要求する最大長
     * @param collisionDistance 壁との衝突地点までの距離
     * @return 0以上かつ交差ルーンの安全上限以下の有効長
     */
    static double cappedCrossArmLength(double maximumLength, double collisionDistance) {
        double boundedLength = Math.clamp(maximumLength, 0.0D, COLOSSUS_RUNE_LANES_MAX_LENGTH);
        return Math.clamp(collisionDistance, 0.0D, boundedLength);
    }

    private void breakCrater(@NotNull MobInstance boss, @NotNull Location center, double radius) {
        if (!mayBreakTerrain(boss, center)) {
            return;
        }
        int removed = 0;
        int blockRadius = (int) Math.ceil(radius);
        int y = Math.max(center.getWorld().getMinHeight(), center.getBlockY() - 1);
        for (int x = -blockRadius; x <= blockRadius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; x++) {
            for (int z = -blockRadius; z <= blockRadius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; z++) {
                if (x * x + z * z > radius * radius) {
                    continue;
                }
                removed += breakBlock(center.getWorld().getBlockAt(center.getBlockX() + x, y, center.getBlockZ() + z));
            }
        }
    }

    private void breakRing(
        @NotNull MobInstance boss,
        @NotNull Location center,
        double innerRadius,
        double outerRadius
    ) {
        if (!mayBreakTerrain(boss, center)) {
            return;
        }
        int removed = 0;
        int radius = (int) Math.ceil(outerRadius);
        int y = Math.max(center.getWorld().getMinHeight(), center.getBlockY() - 1);
        double innerSquared = innerRadius * innerRadius;
        double outerSquared = outerRadius * outerRadius;
        for (int x = -radius; x <= radius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; x++) {
            for (int z = -radius; z <= radius && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; z++) {
                double distanceSquared = x * x + z * z;
                if (distanceSquared < innerSquared || distanceSquared > outerSquared) {
                    continue;
                }
                removed += breakBlock(center.getWorld().getBlockAt(center.getBlockX() + x, y, center.getBlockZ() + z));
            }
        }
    }

    private void breakFissure(
        @NotNull MobInstance boss,
        @NotNull Location origin,
        @NotNull Vector direction,
        double length
    ) {
        if (!mayBreakTerrain(boss, origin)) {
            return;
        }
        Vector forward = direction.clone().setY(0.0D).normalize();
        Vector side = new Vector(-forward.getZ(), 0.0D, forward.getX());
        int removed = 0;
        int y = Math.max(origin.getWorld().getMinHeight(), origin.getBlockY() - 1);
        for (double distance = 1.0D; distance <= length && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; distance += 1.0D) {
            for (int width = -1; width <= 1 && removed < BossTerrainPolicy.MAX_BLOCKS_PER_MECHANIC; width++) {
                Vector offset = forward.clone().multiply(distance).add(side.clone().multiply(width));
                Block block = origin.getWorld().getBlockAt(
                    (int) Math.floor(origin.getX() + offset.getX()),
                    y,
                    (int) Math.floor(origin.getZ() + offset.getZ())
                );
                removed += breakBlock(block);
            }
        }
    }

    private boolean mayBreakTerrain(@NotNull MobInstance boss, @NotNull Location location) {
        World world = location.getWorld();
        return world != null && BossTerrainPolicy.mayBreak(
            boss.template().id(),
            dungeonService.isDungeonWorld(world)
        );
    }

    private int breakBlock(@NotNull Block block) {
        Material material = block.getType();
        if (!BossTerrainPolicy.isBreakable(material)) {
            return 0;
        }
        block.setType(Material.AIR, false);
        return 1;
    }

    /**
     * 方向と壁で制限済みの長さを持つ、交差ルーンの一方向分の線分です。
     */
    private record LineSegment(@NotNull Vector direction, double length) {
        private LineSegment {
            direction = direction.clone().setY(0.0D).normalize();
            length = Math.max(0.0D, length);
        }
    }

    private static final class BossRuntime {
        private int phase;
        private int actionIndex;
        private long nextActionTick;
        private long nextTeleportTick;
        private long nextArenaPulseTick;
        private int teleportIndex;
        private boolean summonsTriggered;
        private boolean finalPhaseTriggered;
        private final Set<UUID> summonedMobIds = new LinkedHashSet<>();

        private BossRuntime(int phase, long nextActionTick, long nextTeleportTick, long nextArenaPulseTick) {
            this.phase = phase;
            this.nextActionTick = nextActionTick;
            this.nextTeleportTick = nextTeleportTick;
            this.nextArenaPulseTick = nextArenaPulseTick;
        }
    }

    private record BirdMeteorState(
        @NotNull Location safeZoneCenter,
        @NotNull BossBar bossBar
    ) {
        private BirdMeteorState {
            safeZoneCenter = safeZoneCenter.clone();
        }
    }

    private record PendingMechanic(
        @NotNull UUID bossInstanceId,
        @NotNull BossMechanicProfile.Mechanic mechanic,
        @NotNull Location anchor,
        @NotNull Vector direction,
        long executeAtTick,
        @NotNull List<UUID> displayEntityIds,
        @Nullable Location destination
    ) {
        private PendingMechanic {
            anchor = anchor.clone();
            direction = direction.clone().setY(0.0D).normalize();
            displayEntityIds = List.copyOf(displayEntityIds);
            destination = destination == null ? null : destination.clone();
        }

        private double travelDistance() {
            if (destination == null) {
                return 0.0D;
            }
            double x = destination.getX() - anchor.getX();
            double z = destination.getZ() - anchor.getZ();
            return Math.sqrt(x * x + z * z);
        }
    }
}
