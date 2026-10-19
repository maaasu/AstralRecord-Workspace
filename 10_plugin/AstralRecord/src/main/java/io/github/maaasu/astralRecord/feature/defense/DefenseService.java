package io.github.maaasu.astralRecord.feature.defense;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.mob.model.*;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobTauntService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.afk.service.AfkService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeParticipationRegistry;
import io.github.maaasu.astralRecord.shared.effect.InvulnerabilityVisualService;
import java.io.IOException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.*;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.Nullable;

/** チャンネル内の一開催と受付、戦闘、個人死亡、保存待ちを所有します。ゲーム状態はmain thread限定です。 */
public final class DefenseService {
    public static final String ENTITY_TAG = "astralrecord_defense";
    private static final int MAX_PLAYERS = 30;
    private static final int DEATH_LIMIT = 3;
    private static final long REVIVE_MILLIS = 15_000L;
    private final JavaPlugin plugin;
    private final MobService mobs;
    private final WorldService worlds;
    private final PlayerDeathService deaths;
    private final StatusService status;
    private final AfkService afk;
    private final InvulnerabilityVisualService invulnerability;
    private final ChallengeParticipationRegistry reservations;
    private final MobTauntService taunts;
    private final PlayerSessionTransitionGuard transitions;
    private final DefenseRepository repository;
    private final DefenseStateStore store;
    private final String serverId;
    private final String instanceId = UUID.randomUUID().toString();
    private final Map<UUID, UUID> waiting = new LinkedHashMap<>();
    private final Map<UUID, JsonObject> returns = new LinkedHashMap<>();
    private final Set<UUID> returning = new HashSet<>();
    private final Set<UUID> transitionBlocked = ConcurrentHashMap.newKeySet();
    private final Set<String> protectedWorlds = new HashSet<>();
    private final Map<UUID, Set<UUID>> releasing = new HashMap<>();
    private final List<Delivery> outbox = new ArrayList<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private final Set<String> handledRuns = new HashSet<>();
    private DefenseConfiguration configuration;
    private Session session;
    private BukkitTask task;
    private int ticks;
    private boolean ready;
    private boolean healthy = true;
    private boolean stopped;
    private long lastFailureLog;
    private long lastPollNanos;

    /**
     * @param plugin ライフサイクル所有者
     * @param mobs 登録済みMobと個体
     * @param worlds 共通ワールド・転送
     * @param deaths 共通死亡状態
     * @param status リソース復元
     * @param afk 放置判定
     * @param invulnerability 復活保護
     * @param reservations Boss/Dungeonと共有する参加予約
     * @param taunts 既存挑発
     * @param transitions アカウント切替・チャンネル移動の共有ゲート
     */
    public DefenseService(JavaPlugin plugin, MobService mobs, WorldService worlds, PlayerDeathService deaths,
        StatusService status, AfkService afk, InvulnerabilityVisualService invulnerability,
        ChallengeParticipationRegistry reservations, MobTauntService taunts, PlayerSessionTransitionGuard transitions) {
        this.plugin = plugin; this.mobs = mobs; this.worlds = worlds; this.deaths = deaths;
        this.status = status; this.afk = afk; this.invulnerability = invulnerability;
        this.reservations = reservations; this.taunts = taunts; this.transitions = transitions;
        serverId = plugin.getConfig().getString("api.serverId", "");
        repository = new DefenseRepository(serverId, plugin.getConfig().getString("defense.runtimeKey", ""));
        store = new DefenseStateStore(plugin.getDataFolder().toPath());
    }

    /** 保存待ちを復元して監視を開始します。破損したjournalは上書きしません。 */
    public void start() {
        if (task != null || plugin.getConfig().getString("defense.runtimeKey", "").isBlank() || serverId.isBlank()) return;
        try {
            JsonObject saved = store.load();
            if (saved.has("returns")) saved.getAsJsonObject("returns").entrySet()
                .forEach(entry -> returns.put(UUID.fromString(entry.getKey()), entry.getValue().getAsJsonObject()));
            transitionBlocked.addAll(returns.keySet());
            if (saved.has("protectedWorlds")) for (JsonElement name : saved.getAsJsonArray("protectedWorlds"))
                protectedWorlds.add(name.getAsString());
            if (saved.has("outbox")) for (JsonElement element : saved.getAsJsonArray("outbox")) {
                JsonObject item = element.getAsJsonObject();
                outbox.add(new Delivery(text(item, "runId"), text(item, "action"), item.getAsJsonObject("body")));
            }
            if (saved.has("active") && saved.get("active").isJsonObject()) {
                JsonObject interrupted = saved.getAsJsonObject("active");
                JsonObject report = interrupted.getAsJsonObject("report").deepCopy();
                report.addProperty("operationId", UUID.randomUUID().toString());
                report.addProperty("event", "ABORTED");
                report.addProperty("reason", "SERVER_RESTARTED");
                outbox.add(new Delivery(text(interrupted, "runId"), "report", report));
            }
            for (World world : Bukkit.getWorlds()) removeStaleEntities(world);
            persist();
        } catch (Exception failure) { healthy = false; Logger.error(LogId.E_7901, failure); return; }
        stopped = false;
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        Bukkit.getOnlinePlayers().forEach(this::restoreReturn);
    }

    /** 戦闘を中断して帰還・保存を完了します。HTTP待機でmain threadを止めません。 */
    public void stop() {
        try { if (session != null) end("ABORTED", "SERVER_STOPPED"); }
        catch (RuntimeException failure) { Logger.error(LogId.E_7901, failure); cleanup(); }
        finally {
            stopped = true;
            if (task != null) { task.cancel(); task = null; }
            waiting.keySet().forEach(id -> { if (!returns.containsKey(id)) transitionBlocked.remove(id); });
            waiting.clear();
        }
    }

    /** @param player 受付する本人。既存の挑戦と競合する参加は拒否します */
    public void join(AstPlayer player) {
        UUID id = player.getBukkit().getUniqueId();
        if (!healthy || !ready || configuration == null || !AccountModeGuard.isGameplayPlayer(player)
            || deaths.isDead(id) || returns.containsKey(id) || transitions.current(id) != null) {
            message(player, PlayerMsgId.P_7904); return;
        }
        if (session != null && (session.running || session.closed)) { message(player, PlayerMsgId.P_7914); return; }
        if (waiting.containsKey(id) || session != null && session.players.containsKey(id)) return;
        if (registeredCount() >= MAX_PLAYERS) {
            message(player, PlayerMsgId.P_7916); return;
        }
        if (session == null || session.preparing) {
            waiting.put(id, player.getAccount().getUuid()); transitionBlocked.add(id);
        } else if (!enroll(session, player)) return;
        message(player, PlayerMsgId.P_7903);
    }

    /** @param player 自己退出する本人。開始後の自主退出は再参加不可の脱落です */
    public void leave(AstPlayer player) {
        UUID id = player.getBukkit().getUniqueId();
        waiting.remove(id);
        if (!returns.containsKey(id)) transitionBlocked.remove(id);
        if (session != null && session.players.containsKey(id)) {
            Participant member = session.players.get(id);
            member.eliminated = true;
            member.pendingDeath = false;
            deaths.recoverNow(id);
            returnPlayer(id);
            if (!session.running) session.players.remove(id);
        }
        message(player, PlayerMsgId.P_7905);
    }

    /** @param player 状態表示を要求した本人 */
    public void showStatus(AstPlayer player) {
        Session current = session;
        message(player, PlayerMsgId.P_7906, current == null ? 0 : current.level,
            current == null ? waiting.size() : current.players.size(), current == null ? 0 : current.initialPlayers);
    }

    private void tick() {
        if (stopped) return;
        if (!healthy) { restorePendingReturns(); return; }
        try {
            ticks++;
            if (ticks % 100 == 1) poll();
            if (ticks % 20 == 0) {
                restorePendingReturns();
            }
            Session current = session;
            if (current == null) return;
            if (lastPollNanos != 0 && System.nanoTime() - lastPollNanos
                > Math.max(60L, current.config.reconnectGraceSeconds()) * 1_000_000_000L) {
                end("ABORTED", "API_UNAVAILABLE"); return;
            }
            if (!current.running) {
                if (current.preparing) return;
                if (current.scheduledAt == null) {
                    current.closed = true;
                    if (current.players.values().stream().noneMatch(member -> member.transferring)) begin(current);
                } else if (!Instant.now().isBefore(current.scheduledAt)) {
                    current.closed = true;
                    if (current.players.values().stream().noneMatch(member -> member.transferring)) begin(current);
                }
                return;
            }
            if (current.core == null || current.core.currentHealth() <= 0 || mobs.getInstance(current.core.instanceId()) == null) {
                end("ENDED", "CORE_DESTROYED"); return;
            }
            spawnPending(current);
            if (ticks % 20 != 0) return;
            long elapsed = Math.max(0L, (System.nanoTime() - current.startedNanos) / 1_000_000_000L);
            current.level = DefenseConfiguration.level(elapsed);
            updateParticipants(current);
            current.retaliation.entrySet().removeIf(entry -> !current.enemies.contains(entry.getKey())
                || entry.getValue().untilNanos() <= System.nanoTime() || mobs.getInstance(entry.getKey()) == null);
            if (elapsed >= current.nextWaveSecond) { prepareWave(current); current.nextWaveSecond = elapsed + current.config.spawnIntervalSeconds(); }
            if (ticks % 100 == 0) { report(current, "HEARTBEAT", null); updateBar(current); }
            int milestone = current.level / current.config.rewardEveryLevels() * current.config.rewardEveryLevels();
            if (milestone > current.rewardedLevel) reward(current, milestone);
        } catch (Exception failure) {
            Logger.error(LogId.E_7902, failure);
            try { if (session != null) end("ABORTED", "RUNTIME_ERROR"); }
            catch (Exception storageFailure) { healthy = false; cleanup(); Logger.error(LogId.E_7901, storageFailure); }
        }
    }

    private void receive(JsonObject snapshot) {
        if (stopped) return;
        lastPollNanos = System.nanoTime();
        JsonObject incoming = object(snapshot, "configuration");
        if (incoming != null && !text(incoming, "worldName").isBlank()) {
            World mapped = resolveWorld(text(incoming, "worldName"));
            Set<String> previous = Set.copyOf(protectedWorlds);
            protectedWorlds.clear();
            if (mapped != null) protectedWorlds.add(mapped.getName());
            if (!previous.equals(protectedWorlds)) persist();
        }
        JsonElement missing = snapshot.get("missingSettings");
        ready = missing != null && missing.isJsonArray() && missing.getAsJsonArray().isEmpty();
        if (snapshot.has("configuration") && snapshot.get("configuration").isJsonObject() && ready)
            configuration = DefenseConfiguration.parse(snapshot.getAsJsonObject("configuration"));
        else configuration = null;
        JsonObject run = object(snapshot, "activeRun");
        if (run == null) run = object(snapshot, "pendingRun");
        if (session != null) {
            if (run == null || !session.runId.equals(text(run, "runId"))
                || List.of("STOP_REQUESTED", "ABORTED", "ENDED").contains(text(run, "status")))
                end("ABORTED", "MANAGEMENT_STOP");
            return;
        }
        if (run == null || !"PENDING".equals(text(run, "status")) || handledRuns.contains(text(run, "runId"))) return;
        String runId = text(run, "runId");
        handledRuns.add(runId);
        try {
            DefenseConfiguration frozen = DefenseConfiguration.parse(run.getAsJsonObject("configuration"));
            World world = resolveWorld(frozen.worldName());
            if (world == null)
                throw new IllegalArgumentException("DEFENSE_WORLD_OR_ENTRY_INVALID");
            List<MobTemplate> candidates = candidates();
            if (candidates.isEmpty()) throw new IllegalArgumentException("NO_ELIGIBLE_MOBS");
            Session next = new Session(runId, frozen, world, candidates,
                run.has("scheduledAtUtc") && !run.get("scheduledAtUtc").isJsonNull() ? Instant.parse(text(run, "scheduledAtUtc")) : null);
            session = next;
            if (next.scheduledAt == null) next.closed = true;
            next.preparing = true;
            retainChunks(next);
            persist();
        } catch (Exception failure) {
            if (session != null) cleanup();
            JsonObject body = new JsonObject();
            body.addProperty("instanceId", instanceId); body.addProperty("event", "ABORTED");
            body.addProperty("reason", "CONFIGURATION_OR_WORLD_INVALID");
            enqueue(runId, "report", body);
            Logger.error(LogId.E_7902, failure);
        }
    }

    private boolean enroll(Session current, AstPlayer player) {
        UUID id = player.getBukkit().getUniqueId();
        if (current.players.size() >= MAX_PLAYERS || deaths.isDead(id) || returns.containsKey(id)
            || transitions.current(id) != null || !AccountModeGuard.isGameplayPlayer(player)) return false;
        Set<UUID> ids = new LinkedHashSet<>(current.players.keySet()); ids.add(id);
        returns.forEach((owner, saved) -> { if (current.runId.equals(text(saved, "runId"))) ids.add(owner); });
        var result = reservations.reserve(UUID.fromString(current.runId), "defense:" + current.runId, ids,
            PlayerMsgResource.getMessage(PlayerMsgId.P_7918.getId()));
        if (!result.acquired()) { message(player, PlayerMsgId.P_7909, result.conflictingDisplayName()); return false; }
        Participant member = new Participant(id, player.getAccount().getUuid());
        current.players.put(id, member);
        returns.put(id, returnSnapshot(player));
        returns.get(id).addProperty("runId", current.runId);
        transitionBlocked.add(id);
        persist();
        member.transferring = true;
        worlds.teleportPlayerAsync(player.getBukkit(), current.config.entryLocation(current.world), () -> {
            member.transferring = false;
            if (session == current && !current.running && !member.eliminated) member.arrived = true;
            else returnPlayer(id);
        }, () -> session == current && !current.running && !member.eliminated
            && online(member) == player).whenComplete((success, failure) -> runMain(() -> {
                member.transferring = false;
                if (!Boolean.TRUE.equals(success) || failure != null) {
                    member.eliminated = true;
                    if (session == current && !current.running) current.players.remove(id);
                    returnPlayer(id);
                }
            }));
        return true;
    }

    private void begin(Session current) {
        if (session != current || current.running) return;
        for (Participant member : current.players.values()) {
            AstPlayer player = online(member);
            if (!member.arrived || member.transferring || player == null || player.getBukkit().getWorld() != current.world) {
                member.eliminated = true; returnPlayer(member.userId);
            }
        }
        current.players.values().removeIf(member -> member.eliminated);
        if (current.players.isEmpty()) { end("ABORTED", "NO_PARTICIPANTS"); return; }
        current.initialPlayers = current.players.size();
        current.core = mobs.spawn(objective(current.config), current.config.coreLocation(current.world));
        if (current.core == null) throw new IllegalStateException("Cannot create defense objective");
        prepareEntity(current.core);
        current.running = true;
        current.startedNanos = System.nanoTime();
        current.bar = Bukkit.createBossBar("", BarColor.PURPLE, BarStyle.SOLID);
        for (Participant member : current.players.values()) {
            AstPlayer player = online(member);
            if (player != null) { current.bar.addPlayer(player.getBukkit()); message(player, PlayerMsgId.P_7907); }
        }
        report(current, "STARTED", null);
        updateBar(current);
        prepareWave(current);
        current.nextWaveSecond = current.config.spawnIntervalSeconds();
    }

    private List<MobTemplate> candidates() {
        List<MobTemplate> result = new ArrayList<>();
        for (String id : mobs.getLoadedMobIds()) {
            MobTemplate template = mobs.findLoadedTemplate(id);
            if (template == null || !template.defenseEligible() || template.category() != MobCategory.ENEMY) continue;
            Set<Integer> levels = new TreeSet<>();
            template.levelProfiles().forEach(profile -> levels.add(profile.level()));
            if (levels.isEmpty()) levels.add(template.level());
            for (int level : levels) {
                MobTemplate resolved = template.resolveLevel(level);
                if (resolved.combat() != null && resolved.combat().normalAttack() != null && resolved.targeting() != null)
                    result.add(resolved);
            }
        }
        return List.copyOf(result);
    }

    private void prepareWave(Session current) {
        current.enemies.removeIf(id -> mobs.getInstance(id) == null);
        double count = current.config.baseSpawnCount() * (1 + extraPlayers(current) * current.config.playerSpawnCoefficient());
        if (!Double.isFinite(count)) throw new IllegalArgumentException("Defense spawn multiplier overflow");
        current.pendingSpawns = Math.min(current.config.maxSpawnsPerWave(),
            Math.min(current.config.maxAliveEnemies() - current.enemies.size(), (int) Math.min(Integer.MAX_VALUE, Math.ceil(count))));
        current.waveLevel = current.level;
    }

    private void spawnPending(Session current) {
        for (int index = 0; index < current.config.maxSpawnsPerTick() && current.pendingSpawns > 0; index++) {
            current.pendingSpawns--;
            current.enemies.removeIf(id -> mobs.getInstance(id) == null);
            if (current.enemies.size() >= current.config.maxAliveEnemies()) { current.pendingSpawns = 0; return; }
            int distance = current.candidates.stream().mapToInt(value -> Math.abs(value.level() - current.waveLevel)).min().orElseThrow();
            List<MobTemplate> closest = current.candidates.stream().filter(value -> Math.abs(value.level() - current.waveLevel) == distance).toList();
            MobTemplate template = closest.get(ThreadLocalRandom.current().nextInt(closest.size()));
            Location point = current.config.spawnPoints().get(ThreadLocalRandom.current().nextInt(current.config.spawnPoints().size())).location(current.world);
            if (!safe(point)) throw new IllegalStateException("Defense spawn obstructed");
            MobInstance enemy = mobs.spawn(template, point);
            if (enemy == null) throw new IllegalStateException("Defense mob could not spawn");
            current.enemies.add(enemy.instanceId());
            prepareEntity(enemy);
            int over = Math.max(0, current.waveLevel - current.maximumMobLevel);
            double hp = (1 + extraPlayers(current) * current.config.playerHealthCoefficient())
                * (1 + over * current.config.postMaxLevelHealthCoefficient());
            double damage = (1 + extraPlayers(current) * current.config.playerDamageCoefficient())
                * (1 + over * current.config.postMaxLevelDamageCoefficient());
            if (!Double.isFinite(hp) || !Double.isFinite(damage) || !Double.isFinite(enemy.maxHealth() * hp))
                throw new IllegalStateException("Defense scaling overflow");
            enemy.applyMaxHealthMultiplier(hp); enemy.applyShieldCapacityMultiplier(hp);
            enemy.outgoingDamageMultiplier(damage);
            enemy.state(MobState.AGGRO);
        }
    }

    private int extraPlayers(Session current) { return Math.max(0, current.initialPlayers - 10); }

    private void prepareEntity(MobInstance instance) {
        instance.rewardEligible(false); instance.keepWhenUnobserved(true);
        Entity entity = Bukkit.getEntity(instance.bukkitEntityId());
        if (entity != null) { entity.setPersistent(false); entity.addScoreboardTag(ENTITY_TAG); }
        if (entity instanceof ArmorStand armorStand) { armorStand.setGravity(false); armorStand.setBasePlate(false); }
    }

    private MobTemplate objective(DefenseConfiguration config) {
        return new MobTemplate(1, "defense_objective", MobCategory.ENEMY,
            PlayerMsgResource.getMessage(PlayerMsgId.P_7900.getId()), null, 1, EntityType.ARMOR_STAND, true, null,
            List.of(), List.of(), null, new MobEquipmentConfig(null, null, "BEACON", null, null, null),
            List.of(new MobBaseStat(StatusType.MAX_HEALTH.name(), config.coreMaxHealth()),
                new MobBaseStat(StatusType.DEFENSE.name(), config.coreDefense()),
                new MobBaseStat(StatusType.MAGIC_DEFENSE.name(), config.coreDefense()),
                new MobBaseStat(StatusType.KNOCKBACK_RESISTANCE.name(), 100)),
            new MobShieldConfig(false, 0), new MobIdleConfig(IdleBehavior.STATIONARY, 0, 0), false,
            MobInteractionsConfig.EMPTY, null, null, null);
    }

    /** @param mob 調べる個体 @return 現在の防衛戦の敵ならtrue */
    public boolean isEnemy(MobInstance mob) { return session != null && session.enemies.contains(mob.instanceId()); }

    /** @param world 調べるワールド @return 設定済みまたは開催中の専用ワールドならtrue */
    public boolean isArena(World world) {
        return protectedWorlds.contains(world.getName()) || session != null && session.world == world;
    }

    /** @param playerId 本人UUID @return 参加受付または本人帰還が未完了ならtrue。遷移ゲートから呼べます */
    public boolean isTransitionBlocked(UUID playerId) { return transitionBlocked.contains(playerId); }

    private int registeredCount() {
        return waiting.size() + (session == null ? 0 : (int) session.players.values().stream()
            .filter(member -> !member.eliminated).count());
    }

    /** @param playerId 本人UUID @return 準備・戦闘中の未脱落参加者ならtrue */
    public boolean isParticipant(UUID playerId) {
        return session != null && session.players.containsKey(playerId) && !session.players.get(playerId).eliminated;
    }

    /** @param entityId Bukkit実体UUID @return 現在の防衛対象の実体。他のエンティティは返しません */
    public @Nullable LivingEntity objectiveTarget(UUID entityId) {
        if (session == null || session.core == null || !entityId.equals(session.core.bukkitEntityId())) return null;
        Entity entity = Bukkit.getEntity(entityId);
        return entity instanceof LivingEntity living && living.isValid() ? living : null;
    }

    /**
     * @param enemy 防衛戦の敵個体
     * @return 同じ開催の挑発者・反撃相手、または中央の防衛対象
     */
    public @Nullable LivingEntity selectTarget(MobInstance enemy) {
        Session current = session;
        if (current == null || !current.running || !current.enemies.contains(enemy.instanceId())) return null;
        UUID taunter = taunts == null ? null : taunts.activeTaunter(enemy);
        AstPlayer chosen = taunter == null ? null : activePlayer(current, taunter);
        if (chosen != null) return target(enemy, chosen.getBukkit());
        Retaliation retaliation = current.retaliation.get(enemy.instanceId());
        if (retaliation != null && System.nanoTime() < retaliation.untilNanos()) {
            chosen = activePlayer(current, retaliation.playerId());
            if (chosen != null && chosen.getBukkit().getLocation().distanceSquared(enemy.currentLocation())
                <= current.config.retaliationRange() * current.config.retaliationRange())
                return target(enemy, chosen.getBukkit());
        }
        current.retaliation.remove(enemy.instanceId());
        LivingEntity core = current.core == null ? null : objectiveTarget(current.core.bukkitEntityId());
        return core == null ? null : target(enemy, core);
    }

    private LivingEntity target(MobInstance enemy, LivingEntity target) {
        enemy.targetId(target.getUniqueId()); return target;
    }

    /**
     * @param attacker 攻撃者。環境ダメージならnull
     * @param victim 被害対象
     * @return 防衛戦との所属・生存・本人アカウントの境界を満たす場合true
     */
    public boolean canDamage(@Nullable AstEntity attacker, AstEntity victim) {
        Session current = session;
        if (current == null) return true;
        boolean victimCore = victim.isMob() && current.core != null && victim.id().equals(current.core.instanceId());
        boolean victimEnemy = victim.isMob() && current.enemies.contains(victim.id());
        boolean attackerEnemy = attacker != null && attacker.isMob() && current.enemies.contains(attacker.id());
        if (victimCore) return current.running && attackerEnemy;
        if (victimEnemy) return current.running && attacker != null && attacker.combatOwner() != null
            && activePlayer(current, attacker.combatOwner().getBukkit().getUniqueId()) != null;
        if (attackerEnemy) return current.running && victim.combatOwner() != null
            && activePlayer(current, victim.combatOwner().getBukkit().getUniqueId()) != null;
        return victim.location().getWorld() != current.world
            && (attacker == null || attacker.location().getWorld() != current.world);
    }

    /**
     * @param attacker 実際に被害を与えた攻撃者
     * @param victim 被害対象。通常のフィールドMobには何もしません
     */
    public void recordDamage(@Nullable AstEntity attacker, AstEntity victim) {
        Session current = session;
        if (current == null || attacker == null || attacker.combatOwner() == null || !victim.isMob()
            || !current.enemies.contains(victim.id())) return;
        UUID playerId = attacker.combatOwner().getBukkit().getUniqueId();
        if (activePlayer(current, playerId) != null) current.retaliation.put(victim.id(),
            new Retaliation(playerId, System.nanoTime() + current.config.retaliationSeconds() * 1_000_000_000L));
    }

    /**
     * @param player 死亡した本人
     * @param location 死亡地点
     * @return 防衛戦の個人死亡として処理した場合true。共通startDeathの拡張入口から呼びます
     */
    public boolean handleParticipantDeath(AstPlayer player, Location location) {
        Session current = session;
        UUID id = player.getBukkit().getUniqueId();
        if (current == null || !current.running || current.world != location.getWorld()) return false;
        Participant member = current.players.get(id);
        if (member == null || member.eliminated || !sameAccount(member, player)) return false;
        if (member.pendingDeath || deaths.isDead(id)) return true;
        member.pendingDeath = true;
        member.deathCount++;
        boolean started = deaths.startDeath(player, location, REVIVE_MILLIS, false, () -> {
            if (session != current || member.eliminated || !member.pendingDeath) return;
            member.pendingDeath = false;
            AstPlayer recovered = online(member);
            if (recovered == null) { member.eliminated = true; returnPlayer(id); return; }
            if (member.deathCount >= DEATH_LIMIT) {
                member.eliminated = true;
                message(recovered, PlayerMsgId.P_7908);
                returnPlayer(id);
            } else {
                var snapshot = status.getStatus(recovered);
                double shield = "EMPTY".equals(current.config.reviveShieldMode()) ? 0 : snapshot.getCurrentShield();
                recovered.setStatusSnapshot(snapshot.withCurrentValues(snapshot.getMaxValue(StatusType.MAX_HEALTH),
                    snapshot.getMaxValue(StatusType.MAX_MANA), snapshot.getMaxValue(StatusType.MAX_ENERGY), shield));
                worlds.teleportPlayerAsync(recovered.getBukkit(), current.config.entryLocation(current.world), () -> {
                    if (session == current && !member.eliminated && !deaths.isDead(id))
                        invulnerability.grantTemporary(recovered.getBukkit(), current.config.reviveProtectionSeconds() * 20L);
                }, () -> session == current && !member.eliminated && online(member) == recovered);
            }
            persist();
        });
        if (!started) { member.pendingDeath = false; member.deathCount--; return true; }
        message(player, PlayerMsgId.P_7912, member.deathCount, DEATH_LIMIT);
        persist();
        return true;
    }

    /**
     * @param playerId その場蘇生が成立した本人。終了処理による回収はpendingDeathを先に解除します
     */
    public void waivePendingDeathForRevival(UUID playerId) {
        if (session == null || !session.running) return;
        Participant member = session.players.get(playerId);
        if (member == null || member.eliminated || !member.pendingDeath) return;
        member.pendingDeath = false;
        member.deathCount = Math.max(0, member.deathCount - 1);
        persist();
    }

    /** @param player 切断した本人。死亡回数と参加者枠は復帰猶予中維持します */
    public void onQuit(Player player) {
        waiting.remove(player.getUniqueId());
        if (!returns.containsKey(player.getUniqueId())) transitionBlocked.remove(player.getUniqueId());
        if (session != null) {
            Participant member = session.players.get(player.getUniqueId());
            if (member != null) member.disconnectedNanos = System.nanoTime();
        }
    }

    /** @param player 再接続した本人。キャッシュ読込後のtickで本人を再検証して復帰します */
    public void onJoin(Player player) { restoreReturn(player); }

    /** @param player 別ワールドへ移動した本人。準備・帰還転送以外の退出は脱落にします */
    public void onWorldChanged(Player player) {
        if (session == null) return;
        Participant member = session.players.get(player.getUniqueId());
        if (member != null && !member.transferring && !member.eliminated && player.getWorld() != session.world) {
            member.eliminated = true; member.pendingDeath = false;
            deaths.recoverNow(player.getUniqueId()); returnPlayer(player.getUniqueId());
        }
    }

    private void updateParticipants(Session current) {
        long now = System.nanoTime();
        for (Participant member : current.players.values()) {
            if (member.eliminated) continue;
            AstPlayer player = online(member);
            if (player == null) {
                if (member.disconnectedNanos == 0) member.disconnectedNanos = now;
                if (now - member.disconnectedNanos > current.config.reconnectGraceSeconds() * 1_000_000_000L) {
                    member.eliminated = true; member.pendingDeath = false;
                    deaths.recoverNow(member.userId);
                }
                continue;
            }
            if (member.disconnectedNanos != 0) {
                if (now - member.disconnectedNanos > current.config.reconnectGraceSeconds() * 1_000_000_000L) {
                    member.eliminated = true; member.pendingDeath = false; returnPlayer(member.userId); continue;
                }
                member.disconnectedNanos = 0;
                if (player.getBukkit().getWorld() != current.world && !member.transferring) {
                    member.transferring = true;
                    worlds.teleportPlayerAsync(player.getBukkit(), current.config.entryLocation(current.world), () -> {
                        member.transferring = false;
                        if (session == current && current.bar != null && !member.eliminated) current.bar.addPlayer(player.getBukkit());
                    }, () -> session == current && !member.eliminated && online(member) == player)
                        .whenComplete((success, failure) -> runMain(() -> member.transferring = false));
                }
                message(player, PlayerMsgId.P_7915);
            }
            if (player.getBukkit().getWorld() == current.world && !deaths.isDead(member.userId)
                && !afk.isAfk(player) && !member.transferring) member.activeSeconds++;
        }
    }

    private @Nullable AstPlayer activePlayer(Session current, UUID id) {
        Participant member = current.players.get(id);
        if (member == null || member.eliminated || member.pendingDeath || member.transferring || deaths.isDead(id)) return null;
        AstPlayer player = online(member);
        return player != null && player.getBukkit().getWorld() == current.world ? player : null;
    }

    private @Nullable AstPlayer online(Participant member) {
        Player player = Bukkit.getPlayer(member.userId);
        AstPlayer ast = player == null || !player.isOnline() ? null : AstPlayerCache.get(player);
        return ast != null && sameAccount(member, ast) && AccountModeGuard.isGameplayPlayer(ast) ? ast : null;
    }

    private boolean sameAccount(Participant member, AstPlayer player) {
        return player.getBukkit().getUniqueId().equals(member.userId) && player.getAccount().getUuid().equals(member.accountId);
    }

    private void reward(Session current, int stage) {
        JsonArray rewards = new JsonArray();
        for (Participant member : current.players.values()) {
            AstPlayer player = activePlayer(current, member.userId);
            if (player == null || afk.isAfk(player) || member.activeSeconds < current.config.minimumActiveSeconds()) continue;
            if (current.config.rewardTiers().stream().noneMatch(tier -> tier.requiredDefenseLevel() <= stage)) continue;
            JsonObject reward = memberJson(member);
            reward.addProperty("requiredDefenseLevel", stage);
            rewards.add(reward);
        }
        if (!rewards.isEmpty()) {
            report(current, "HEARTBEAT", null);
            JsonObject body = new JsonObject(); body.addProperty("instanceId", instanceId); body.add("rewards", rewards);
            enqueue(current.runId, "rewards", body);
        }
        current.rewardedLevel = stage;
    }

    private void report(Session current, String event, @Nullable String reason) {
        if ("HEARTBEAT".equals(event) && outbox.stream().anyMatch(delivery -> delivery.runId().equals(current.runId)
            && "report".equals(delivery.action()) && "HEARTBEAT".equals(text(delivery.body(), "event"))
            && delivery.body().get("defenseLevel").getAsInt() >= current.level)) return;
        JsonObject body = reportBody(current);
        body.addProperty("event", event);
        if (reason != null) body.addProperty("reason", reason);
        enqueue(current.runId, "report", body);
    }

    private JsonObject reportBody(Session current) {
        JsonObject body = new JsonObject(); body.addProperty("instanceId", instanceId);
        body.addProperty("defenseLevel", current.level);
        JsonArray players = new JsonArray(); current.players.values().forEach(member -> players.add(memberJson(member)));
        body.add("participants", players);
        return body;
    }

    private JsonObject memberJson(Participant member) {
        JsonObject value = new JsonObject();
        value.addProperty("userUuid", member.userId.toString()); value.addProperty("accountId", member.accountId.toString());
        value.addProperty("activeSeconds", member.activeSeconds); value.addProperty("eliminated", member.eliminated);
        AstPlayer player = online(member);
        value.addProperty("isAfk", player == null || afk.isAfk(player));
        return value;
    }

    private void enqueue(String runId, String action, JsonObject body) {
        body.addProperty("operationId", UUID.randomUUID().toString());
        outbox.add(new Delivery(runId, action, body.deepCopy()));
        persist();
    }

    private void poll() {
        if (!polling.compareAndSet(false, true)) return;
        List<Delivery> pending = List.copyOf(outbox.subList(0, Math.min(16, outbox.size())));
        JsonObject registration = new JsonObject();
        registration.addProperty("instanceId", instanceId); registration.addProperty("operationId", UUID.randomUUID().toString());
        registration.addProperty("status", !ready || session != null && session.running ? "CLOSED"
            : session != null && (session.closed || session.preparing) ? "PREPARING" : "OPEN");
        registration.addProperty("registeredCount", registeredCount());
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            JsonObject response = null;
            List<Delivery> delivered = new ArrayList<>();
            Exception failure = null;
            try {
                response = repository.poll(instanceId);
                for (Delivery delivery : pending) {
                    repository.deliver(delivery.runId(), delivery.action(), delivery.body()); delivered.add(delivery);
                }
                repository.registration(registration);
            } catch (Exception ex) { failure = ex; }
            JsonObject result = response;
            Exception error = failure;
            if (!plugin.isEnabled()) { polling.set(false); return; }
            Bukkit.getScheduler().runTask(plugin, () -> {
                polling.set(false);
                if (stopped) return;
                try {
                    if (!delivered.isEmpty()) { outbox.removeAll(delivered); persist(); }
                    if (result != null) receive(result);
                    if (error != null && System.currentTimeMillis() - lastFailureLog > 60_000L) {
                        lastFailureLog = System.currentTimeMillis(); Logger.error(LogId.E_7900, error, serverId);
                    }
                } catch (Exception invalid) { Logger.error(LogId.E_7902, invalid); }
            });
        });
    }

    private void end(String event, String reason) {
        Session current = session;
        if (current == null) return;
        report(current, event, reason);
        for (Participant member : current.players.values()) {
            AstPlayer player = online(member);
            if (player != null) message(player, PlayerMsgId.P_7910, current.level);
        }
        cleanup();
        persist();
    }

    private void cleanup() {
        Session current = session;
        session = null;
        if (current == null) return;
        UUID reservationId = UUID.fromString(current.runId);
        Set<UUID> held = new HashSet<>(current.players.keySet());
        returns.forEach((id, saved) -> { if (current.runId.equals(text(saved, "runId"))) held.add(id); });
        releasing.put(reservationId, held);
        for (Participant member : current.players.values()) {
            member.pendingDeath = false; member.eliminated = true;
            recoverResource(() -> deaths.recoverNow(member.userId));
            recoverResource(() -> returnPlayer(member.userId));
        }
        for (UUID id : List.copyOf(current.enemies)) recoverResource(() -> mobs.destroy(id));
        if (current.core != null) recoverResource(() -> mobs.destroy(current.core.instanceId()));
        if (current.bar != null) recoverResource(current.bar::removeAll);
        for (Chunk chunk : current.chunks) recoverResource(() -> chunk.removePluginChunkTicket(plugin));
        releaseReturnedParticipants();
    }

    private void recoverResource(Runnable release) {
        try { release.run(); }
        catch (RuntimeException failure) { Logger.error(LogId.E_7902, failure); }
    }

    private void updateBar(Session current) {
        if (current.bar == null || current.core == null) return;
        current.bar.setTitle(PlayerMsgResource.format(PlayerMsgId.P_7901.getId(), current.level,
            (long) Math.ceil(current.core.currentHealth()), (long) Math.ceil(current.core.maxHealth())));
        current.bar.setProgress(Math.clamp(current.core.currentHealth() / current.core.maxHealth(), 0, 1));
        for (Participant member : current.players.values()) {
            Player player = Bukkit.getPlayer(member.userId);
            if (player == null || !player.isOnline()) continue;
            if (member.eliminated || player.getWorld() != current.world) current.bar.removePlayer(player);
            else current.bar.addPlayer(player);
        }
    }

    private JsonObject returnSnapshot(AstPlayer player) {
        Location location = player.getBukkit().getLocation();
        JsonObject value = new JsonObject(); value.addProperty("world", location.getWorld().getName());
        value.addProperty("x", location.getX()); value.addProperty("y", location.getY()); value.addProperty("z", location.getZ());
        value.addProperty("accountId", player.getAccount().getUuid().toString());
        return value;
    }

    private void restoreReturn(Player player) {
        AstPlayer ast = AstPlayerCache.get(player);
        JsonObject saved = returns.get(player.getUniqueId());
        if (ast == null || saved == null) return;
        boolean correctAccount = ast.getAccount().getUuid().toString().equals(text(saved, "accountId"));
        if (session != null) {
            Participant member = session.players.get(player.getUniqueId());
            if (member != null && !member.eliminated) {
                if (correctAccount) return;
                member.eliminated = true; member.pendingDeath = false;
                deaths.recoverNow(player.getUniqueId());
            }
        }
        if (!correctAccount) { transitionBlocked.remove(player.getUniqueId()); return; }
        transitionBlocked.add(player.getUniqueId());
        returnPlayer(player.getUniqueId());
    }

    private void returnPlayer(UUID id) {
        JsonObject saved = returns.get(id);
        Player player = Bukkit.getPlayer(id);
        AstPlayer ast = player == null ? null : AstPlayerCache.get(player);
        if (!plugin.isEnabled() || saved == null || ast == null || !player.isOnline()
            || !ast.getAccount().getUuid().toString().equals(text(saved, "accountId")) || !returning.add(id)) return;
        World world = resolveWorld(text(saved, "world"));
        Location target = world == null ? Bukkit.getWorlds().getFirst().getSpawnLocation()
            : new Location(world, saved.get("x").getAsDouble(), saved.get("y").getAsDouble(), saved.get("z").getAsDouble());
        worlds.teleportPlayerAsync(player, target, () -> {
            returns.remove(id); returning.remove(id); transitionBlocked.remove(id);
            if (!text(saved, "runId").isBlank()) reservations.removeParticipant(UUID.fromString(text(saved, "runId")), id);
            releaseReturnedParticipants();
            if (healthy) persist();
        }, () -> AstPlayerCache.get(player) == ast).whenComplete((success, failure) -> {
            runMain(() -> returning.remove(id));
        });
    }

    private void restorePendingReturns() {
        for (UUID id : List.copyOf(returns.keySet())) {
            Player player = Bukkit.getPlayer(id);
            if (player != null) restoreReturn(player);
        }
    }

    private void runMain(Runnable action) {
        if (!plugin.isEnabled() || stopped) return;
        if (Bukkit.isPrimaryThread()) action.run();
        else Bukkit.getScheduler().runTask(plugin, action);
    }

    private void releaseReturnedParticipants() {
        for (UUID owner : List.copyOf(releasing.keySet())) {
            Set<UUID> remaining = releasing.get(owner);
            remaining.removeIf(id -> !returns.containsKey(id));
            if (remaining.isEmpty()) { reservations.release(owner); releasing.remove(owner); }
        }
    }

    private World resolveWorld(String name) {
        var definition = worlds.getByCommandName(name);
        return definition == null ? Bukkit.getWorld(name) : worlds.resolveOrLoadWorld(definition);
    }

    private boolean safe(Location location) {
        return location.getWorld() != null && location.getY() >= location.getWorld().getMinHeight()
            && location.getY() + 2 < location.getWorld().getMaxHeight()
            && location.getBlock().isPassable() && location.clone().add(0, 1, 0).getBlock().isPassable()
            && !location.clone().add(0, -1, 0).getBlock().isPassable();
    }

    private void retainChunks(Session current) {
        List<Location> points = new ArrayList<>(List.of(current.config.coreLocation(current.world), current.config.entryLocation(current.world)));
        current.config.spawnPoints().forEach(point -> points.add(point.location(current.world)));
        int minX = points.stream().mapToInt(point -> point.getBlockX() >> 4).min().orElseThrow();
        int maxX = points.stream().mapToInt(point -> point.getBlockX() >> 4).max().orElseThrow();
        int minZ = points.stream().mapToInt(point -> point.getBlockZ() >> 4).min().orElseThrow();
        int maxZ = points.stream().mapToInt(point -> point.getBlockZ() >> 4).max().orElseThrow();
        if (((long) maxX - minX + 1) * ((long) maxZ - minZ + 1) > 256)
            throw new IllegalArgumentException("Defense map exceeds the chunk preparation limit");
        List<int[]> coordinates = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) coordinates.add(new int[]{x, z});
        prepareChunk(current, coordinates, 0);
    }

    private void prepareChunk(Session current, List<int[]> coordinates, int index) {
        if (session != current || !healthy || stopped) return;
        int[] point = coordinates.get(index);
        current.world.getChunkAtAsync(point[0], point[1], false).whenComplete((chunk, failure) -> runMain(() -> {
            if (session != current || !healthy) return;
            try {
                if (failure != null || chunk == null) throw new IllegalStateException("Defense map chunk is not prepared", failure);
                chunk.addPluginChunkTicket(plugin); current.chunks.add(chunk);
                if (index + 1 < coordinates.size()) Bukkit.getScheduler().runTask(plugin,
                    () -> prepareChunk(current, coordinates, index + 1));
                else completePreparation(current);
            } catch (RuntimeException invalid) {
                Logger.error(LogId.E_7902, invalid);
                end("ABORTED", "CONFIGURATION_OR_WORLD_INVALID");
            }
        }));
    }

    private void completePreparation(Session current) {
        if (!safe(current.config.entryLocation(current.world)) || !safe(current.config.coreLocation(current.world)))
            throw new IllegalArgumentException("Defense core or entry is obstructed");
        for (var point : current.config.spawnPoints()) if (!safe(point.location(current.world)))
            throw new IllegalArgumentException("Defense spawn is obstructed");
        if (!current.world.getPlayers().isEmpty()) throw new IllegalArgumentException("Defense map contains foreign players");
        current.preparing = false;
        for (UUID id : List.copyOf(waiting.keySet())) {
            UUID account = waiting.remove(id);
            Player online = Bukkit.getPlayer(id);
            AstPlayer player = online == null ? null : AstPlayerCache.get(online);
            boolean accepted = player != null && player.getAccount().getUuid().equals(account) && enroll(current, player);
            if (!accepted && !returns.containsKey(id)) transitionBlocked.remove(id);
        }
        if (!current.closed) for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer ast = AstPlayerCache.get(player);
            if (ast != null) message(ast, PlayerMsgId.P_7902);
        }
        persist();
    }

    /** @param world 読込済みワールド。再起動前の防衛戦の非永続実体だけを回収します */
    public void removeStaleEntities(World world) {
        for (Entity entity : world.getEntities()) if (entity.getScoreboardTags().contains(ENTITY_TAG)) entity.remove();
    }

    private void persist() {
        if (!healthy) throw new IllegalStateException("Defense journal is unavailable");
        JsonObject saved = new JsonObject(); JsonObject locations = new JsonObject();
        returns.forEach((id, value) -> locations.add(id.toString(), value)); saved.add("returns", locations);
        JsonArray deliveries = new JsonArray();
        for (Delivery delivery : outbox) {
            JsonObject item = new JsonObject(); item.addProperty("runId", delivery.runId());
            item.addProperty("action", delivery.action()); item.add("body", delivery.body()); deliveries.add(item);
        }
        saved.add("outbox", deliveries);
        JsonArray names = new JsonArray(); protectedWorlds.forEach(names::add); saved.add("protectedWorlds", names);
        if (session != null) {
            JsonObject active = new JsonObject(); active.addProperty("runId", session.runId);
            active.add("report", reportBody(session)); saved.add("active", active);
        }
        try { store.save(saved); }
        catch (IOException failure) {
            healthy = false; ready = false;
            try { cleanup(); }
            catch (RuntimeException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            Logger.error(LogId.E_7901, failure);
            throw new IllegalStateException("Defense journal save failed", failure);
        }
    }

    private static JsonObject object(JsonObject value, String key) {
        return value.has(key) && value.get(key).isJsonObject() ? value.getAsJsonObject(key) : null;
    }

    private static String text(JsonObject value, String key) {
        return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : "";
    }

    private void message(AstPlayer player, PlayerMsgId id, Object... args) {
        PlayerMessageService.getInstance().send(player, id, args);
    }

    private static final class Session {
        final String runId;
        final DefenseConfiguration config;
        final World world;
        final List<MobTemplate> candidates;
        final int maximumMobLevel;
        final Instant scheduledAt;
        final Map<UUID, Participant> players = new LinkedHashMap<>();
        final Set<UUID> enemies = new HashSet<>();
        final Map<UUID, Retaliation> retaliation = new HashMap<>();
        final List<Chunk> chunks = new ArrayList<>();
        MobInstance core;
        BossBar bar;
        boolean closed;
        boolean running;
        boolean preparing;
        int initialPlayers;
        int level = 1;
        int waveLevel = 1;
        int rewardedLevel;
        int pendingSpawns;
        long startedNanos;
        long nextWaveSecond;
        Session(String runId, DefenseConfiguration config, World world, List<MobTemplate> candidates, Instant scheduledAt) {
            this.runId = runId; this.config = config; this.world = world;
            this.candidates = candidates; this.scheduledAt = scheduledAt;
            maximumMobLevel = candidates.stream().mapToInt(MobTemplate::level).max().orElse(1);
        }
    }

    private static final class Participant {
        final UUID userId;
        final UUID accountId;
        boolean transferring;
        boolean arrived;
        boolean eliminated;
        boolean pendingDeath;
        int deathCount;
        int activeSeconds;
        long disconnectedNanos;
        Participant(UUID userId, UUID accountId) { this.userId = userId; this.accountId = accountId; }
    }

    private record Delivery(String runId, String action, JsonObject body) { }
    private record Retaliation(UUID playerId, long untilNanos) { }
}
