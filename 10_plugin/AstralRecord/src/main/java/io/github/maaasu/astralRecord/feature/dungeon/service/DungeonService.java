package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonBlockPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonEncounterPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonLayoutPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.repository.DungeonDefinitionRepository;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * ダンジョンマスタ、セッション進行、部屋戦闘、ゲート解放を一元管理します。
 * アクティブセッションは開始時のマスタと Mob テンプレートを保持するため、reload の影響を受けません。
 */
public final class DungeonService {
    private static final String INSTANCE_ROOT_PATH = "plugins/AstralRecord/_world_instances/dungeon";
    private static final long ENTRY_VISUAL_PERIOD_TICKS = 10L;
    private static final int ENTRY_FRAME_POINTS = 20;
    private static final double ENTRY_VIEW_DISTANCE_SQUARED = 48.0D * 48.0D;
    private final AstralRecord plugin;
    private final DungeonDefinitionRepository repository;
    private final DungeonDefinitionValidator validator;
    private final DungeonLayoutPlanner layoutPlanner;
    private final DungeonBlockPlanner blockPlanner;
    private final DungeonEncounterPlanner encounterPlanner;
    private final DungeonInstanceWorldService instanceWorldService;
    private final WorldService worldService;
    private final PartyService partyService;
    private final MobService mobService;
    private final PlayerMessageService messageService;
    private final ParticleDisplayService particleDisplayService;
    private final String hubWorldId;

    private volatile Map<String, LoadedDefinition> loadedDefinitions = Map.of();
    private final Map<UUID, Session> sessionsById = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionIdByParticipant = new HashMap<>();
    private final Map<UUID, UUID> sessionIdByWorld = new HashMap<>();
    private final Map<UUID, MobBinding> mobBindings = new HashMap<>();
    private BukkitTask entryVisualTask;
    private long entryVisualFrame;
    private boolean stopping;

    /**
     * ダンジョン定義、生成処理、待機ハブ転送、受付演出に必要な依存を構成します。
     *
     * @param plugin Plugin 本体
     * @param repository ダンジョン定義リポジトリ
     * @param worldService World 管理サービス
     * @param partyService パーティー管理サービス
     * @param mobService Mob 管理サービス
     * @param messageService プレイヤーメッセージサービス
     * @param particleDisplayService パーティクル表示サービス
     * @param hubWorldId 生成待機中に参加者を退避する HUB World ID
     */
    public DungeonService(
            @NotNull AstralRecord plugin,
            @NotNull DungeonDefinitionRepository repository,
            @NotNull WorldService worldService,
            @NotNull PartyService partyService,
            @NotNull MobService mobService,
            @NotNull PlayerMessageService messageService,
            @NotNull ParticleDisplayService particleDisplayService,
            @NotNull String hubWorldId
    ) {
        this.plugin = plugin;
        this.repository = repository;
        this.validator = new DungeonDefinitionValidator();
        this.layoutPlanner = new DungeonLayoutPlanner();
        this.blockPlanner = new DungeonBlockPlanner();
        this.encounterPlanner = new DungeonEncounterPlanner();
        this.instanceWorldService = new DungeonInstanceWorldService(plugin, worldService);
        this.worldService = worldService;
        this.partyService = partyService;
        this.mobService = mobService;
        this.messageService = messageService;
        this.particleDisplayService = particleDisplayService;
        this.hubWorldId = hubWorldId;
    }

    /** 現在ロード済みの Mob/World を参照して初回ロードします。 */
    public int loadAll() {
        Map<String, MobTemplate> mobs = new LinkedHashMap<>();
        for (String mobId : mobService.getLoadedMobIds()) {
            MobTemplate mob = mobService.findTemplate(mobId);
            if (mob != null) {
                mobs.put(mobId, mob);
            }
        }
        Map<String, WorldMasterData> worlds = new LinkedHashMap<>();
        for (WorldMasterData world : worldService.getAll()) {
            worlds.put(world.id(), world);
        }
        DefinitionSnapshot snapshot = loadDefinitionSnapshot(mobs, worlds);
        replaceDefinitionSnapshot(snapshot);
        return snapshot.loadedById().size();
    }

    /**
     * reload 時に同時公開予定の Mob/World を使って検証済みスナップショットを構築します。
     *
     * @param mobsById Mob スナップショット
     * @param worldsById World スナップショット
     * @return ダンジョンスナップショット
     */
    public @NotNull DefinitionSnapshot loadDefinitionSnapshot(
            @NotNull Map<String, MobTemplate> mobsById,
            @NotNull Map<String, WorldMasterData> worldsById
    ) {
        List<DungeonDefinition> definitions = repository.findAll().stream()
                .sorted(Comparator.comparing(DungeonDefinition::id))
                .toList();
        validator.validateAll(definitions, mobsById, worldsById);

        Map<String, LoadedDefinition> loaded = new LinkedHashMap<>();
        for (DungeonDefinition definition : definitions) {
            List<LoadedMob> normalMobs = definition.encounter().normalMobPool().stream()
                    .map(entry -> new LoadedMob(mobsById.get(entry.mobId()), entry.weight()))
                    .toList();
            loaded.put(definition.id(), new LoadedDefinition(
                    definition,
                    worldsById.get(definition.entry().worldId()),
                    createInstanceWorldData(definition),
                    normalMobs,
                    mobsById.get(definition.encounter().bossMobId())
            ));
        }
        return new DefinitionSnapshot(Collections.unmodifiableMap(loaded));
    }

    /** 新しい定義を参照交換で公開します。既存セッションは保持中の旧定義を使い続けます。 */
    public void replaceDefinitionSnapshot(@NotNull DefinitionSnapshot snapshot) {
        loadedDefinitions = snapshot.loadedById();
        Logger.log(LogId.I_7000, loadedDefinitions.size());
    }

    /** 起動時の残存インスタンス回収を開始します。 */
    public void start() {
        requireMainThread();
        stopping = false;
        Collection<WorldMasterData> worlds = loadedDefinitions.values().stream()
                .map(LoadedDefinition::instanceWorldData)
                .distinct()
                .toList();
        instanceWorldService.cleanupStaleInstances(worlds);
        if (entryVisualTask != null) {
            entryVisualTask.cancel();
        }
        entryVisualTask = Bukkit.getScheduler().runTaskTimer(
                plugin,
                this::tickEntryVisuals,
                1L,
                ENTRY_VISUAL_PERIOD_TICKS
        );
    }

    /**
     * プレイヤーまたはオンラインパーティーの新しいダンジョンを要求します。
     *
     * @param leader 実行者
     * @param dungeonId ダンジョン ID
     * @return 即時受付結果
     */
    public @NotNull StartRequestResult requestStart(
            @NotNull Player leader,
            @NotNull String dungeonId
    ) {
        return requestStart(leader, dungeonId, OptionalLong.empty());
    }

    /** テスト・再現調査向けに seed を明示できる開始 API です。 */
    public @NotNull StartRequestResult requestStart(
            @NotNull Player leader,
            @NotNull String dungeonId,
            @NotNull OptionalLong requestedSeed
    ) {
        requireMainThread();
        if (stopping) {
            return StartRequestResult.of(StartStatus.UNAVAILABLE);
        }
        LoadedDefinition loaded = loadedDefinitions.get(dungeonId);
        if (loaded == null) {
            return StartRequestResult.of(StartStatus.NOT_FOUND);
        }
        if (!AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(leader))) {
            return StartRequestResult.of(StartStatus.NOT_GAMEPLAY);
        }
        if (!isInsideEntry(leader, loaded)) {
            return StartRequestResult.of(StartStatus.NOT_AT_ENTRY);
        }

        WorldMasterData hubData = worldService.getById(hubWorldId);
        if (hubData == null || worldService.resolveOrLoadWorld(hubData) == null) {
            return StartRequestResult.of(StartStatus.HUB_UNAVAILABLE);
        }

        Party party = partyService.findParty(leader.getUniqueId());
        if (party != null && !party.isLeader(leader.getUniqueId())) {
            return StartRequestResult.of(StartStatus.NOT_PARTY_LEADER);
        }
        List<Player> participants = snapshotParticipants(leader, party);
        if (participants.stream().anyMatch(player ->
                !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(player)))) {
            return StartRequestResult.of(StartStatus.NOT_GAMEPLAY);
        }
        int participantCount = participants.size();
        DungeonDefinition.IntRange allowed = loaded.definition().partySize();
        if (participantCount < allowed.min() || participantCount > allowed.max()) {
            return new StartRequestResult(StartStatus.PARTY_SIZE, allowed.min(), allowed.max(), participantCount);
        }
        if (participants.stream().anyMatch(player -> sessionIdByParticipant.containsKey(player.getUniqueId()))) {
            return StartRequestResult.of(StartStatus.PARTICIPANT_BUSY);
        }

        UUID sessionId = UUID.randomUUID();
        long seed = requestedSeed.isPresent()
                ? requestedSeed.getAsLong()
                : ThreadLocalRandom.current().nextLong();
        Map<UUID, Location> returnLocations = new LinkedHashMap<>();
        LinkedHashSet<UUID> participantIds = new LinkedHashSet<>();
        for (Player participant : participants) {
            participantIds.add(participant.getUniqueId());
            returnLocations.put(participant.getUniqueId(), participant.getLocation().clone());
            sessionIdByParticipant.put(participant.getUniqueId(), sessionId);
        }

        Session session = new Session(sessionId, seed, loaded, participantIds, returnLocations);
        sessionsById.put(sessionId, session);
        Logger.log(LogId.I_7001, sessionId.toString(), dungeonId, seed, participantCount);
        message(participants, PlayerMsgId.P_7008, loaded.definition().displayName());
        transferToHubAndPrepare(session, participants, hubData);
        return StartRequestResult.of(StartStatus.ACCEPTED);
    }

    /**
     * 受付済み参加者を待機ハブへ転送し、最低人数を再確認して生成を開始します。
     *
     * @param session 受付済みセッション
     * @param participants 受付時点のオンライン参加者
     * @param hubData 転送先 HUB World 定義
     */
    private void transferToHubAndPrepare(
            @NotNull Session session,
            @NotNull List<Player> participants,
            @NotNull WorldMasterData hubData
    ) {
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (Player participant : participants) {
            CompletableFuture<Boolean> transfer = worldService.teleportToSpawnAsync(participant, hubData);
            session.entryTransfers.put(participant.getUniqueId(), transfer);
            transfers.add(transfer);
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    if (session.ending || sessionsById.get(session.id) != session) {
                        return;
                    }
                    retainEligiblePreparingParticipants(session, hubData);
                    if (session.participants.size() < session.loaded.definition().partySize().min()) {
                        completeSession(session, EndReason.PARTICIPANT_REQUIREMENT_NOT_MET, false);
                        return;
                    }
                    prepareAsync(session);
                }));
    }

    private @NotNull List<Player> snapshotParticipants(@NotNull Player leader, @Nullable Party party) {
        if (party == null) {
            return List.of(leader);
        }
        List<Player> online = new ArrayList<>();
        for (UUID memberId : party.members()) {
            Player member = Bukkit.getPlayer(memberId);
            if (member != null && member.isOnline()) {
                online.add(member);
            }
        }
        return List.copyOf(online);
    }

    private void prepareAsync(@NotNull Session session) {
        CompletableFuture<PreparedPlan> preparation = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                DungeonLayout layout = layoutPlanner.plan(session.loaded.definition(), session.seed);
                DungeonBlockPlan blocks = blockPlanner.plan(session.loaded.definition(), layout);
                preparation.complete(new PreparedPlan(layout, blocks));
            } catch (Throwable failure) {
                preparation.completeExceptionally(failure);
            }
        });
        preparation.whenComplete((prepared, failure) -> runMain(() -> {
            if (session.ending || stopping || sessionsById.get(session.id) != session) {
                return;
            }
            if (failure != null) {
                failPreparation(session, failure);
                return;
            }
            session.layout = prepared.layout();
            session.blockPlan = prepared.blocks();
            initializeRoomStates(session);
            instanceWorldService.create(
                    session.id,
                    session.loaded.definition(),
                    session.loaded.instanceWorldData(),
                    prepared.blocks()
            ).whenComplete((instance, worldFailure) -> runMain(() -> {
                if (session.ending || stopping || sessionsById.get(session.id) != session) {
                    if (instance != null) {
                        instanceWorldService.destroyAsync(instance);
                    }
                    return;
                }
                if (worldFailure != null) {
                    failPreparation(session, worldFailure);
                    return;
                }
                handleWorldReady(session, instance);
            }));
        }));
    }

    private void initializeRoomStates(@NotNull Session session) {
        for (DungeonLayout.Room room : session.layout.rooms()) {
            session.roomStates.put(room.id(), RoomState.LOCKED);
            session.liveMobsByRoom.put(room.id(), new LinkedHashSet<>());
        }
        session.roomStates.put(session.layout.startRoomId(), RoomState.AVAILABLE);
    }

    private void handleWorldReady(
            @NotNull Session session,
            @NotNull DungeonInstanceWorldService.InstanceWorld instance
    ) {
        session.instanceWorld = instance;
        sessionIdByWorld.put(instance.world().getUID(), session.id);
        WorldMasterData hubData = worldService.getById(hubWorldId);
        if (hubData == null) {
            completeSession(session, EndReason.PARTICIPANT_REQUIREMENT_NOT_MET, false);
            return;
        }
        retainEligiblePreparingParticipants(session, hubData);
        if (session.participants.size() < session.loaded.definition().partySize().min()) {
            completeSession(session, EndReason.PARTICIPANT_REQUIREMENT_NOT_MET, false);
            return;
        }
        DungeonBlockPlan.Position spawn = session.blockPlan.playerSpawn();
        Location target = new Location(instance.world(), spawn.x() + 0.5D, spawn.y(), spawn.z() + 0.5D);
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (UUID playerId : session.participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                CompletableFuture<Boolean> transfer = worldService.teleportPlayerAsync(
                        player,
                        target.clone(),
                        null
                );
                session.entryTransfers.put(playerId, transfer);
                transfers.add(transfer);
            }
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, transferFailure) -> runMain(() -> {
                    if (session.ending || sessionsById.get(session.id) != session) {
                        return;
                    }
                    boolean allTransferred = transferFailure == null
                            && transfers.stream().allMatch(future -> Boolean.TRUE.equals(future.join()));
                    if (!allTransferred) {
                        completeSession(session, EndReason.TRANSFER_FAILED, false);
                        return;
                    }
                    Logger.log(LogId.I_7002, session.id.toString(), instance.world().getName());
                    activateRoom(session, session.layout.startRoomId());
                }));
    }

    /** プレイヤーが解放済みの部屋へ入ったとき、その部屋の戦闘を開始します。 */
    public void handleMove(@NotNull Player player, @NotNull Location destination) {
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending || session.instanceWorld == null
                || destination.getWorld() == null
                || !destination.getWorld().getUID().equals(session.instanceWorld.world().getUID())) {
            return;
        }
        int x = destination.getBlockX();
        int z = destination.getBlockZ();
        for (DungeonLayout.Room room : session.layout.rooms()) {
            if (session.roomStates.get(room.id()) == RoomState.AVAILABLE && contains(room, x, z)) {
                activateRoom(session, room.id());
                return;
            }
        }
    }

    private void activateRoom(@NotNull Session session, int roomId) {
        if (session.roomStates.get(roomId) != RoomState.AVAILABLE || session.ending) {
            return;
        }
        session.roomStates.put(roomId, RoomState.ACTIVE);
        DungeonLayout.Room room = room(session, roomId);
        List<MobTemplate> encounters;
        if (room.role() == DungeonLayout.RoomRole.BOSS) {
            encounters = List.of(session.loaded.bossMob());
        } else {
            List<DungeonEncounterPlanner.WeightedTemplate> pool = session.loaded.normalMobs().stream()
                    .map(entry -> new DungeonEncounterPlanner.WeightedTemplate(entry.template(), entry.weight()))
                    .toList();
            encounters = encounterPlanner.planNormalRoom(
                    session.loaded.definition(),
                    pool,
                    room.role() == DungeonLayout.RoomRole.START,
                    session.seed,
                    roomId
            );
        }

        List<DungeonBlockPlan.Position> spawnPoints = session.blockPlan.spawnPointsByRoom().get(roomId);
        SplittableRandom random = encounterRandom(session, roomId);
        Set<UUID> liveMobs = session.liveMobsByRoom.get(roomId);
        for (int index = 0; index < encounters.size(); index++) {
            MobTemplate selected = encounters.get(index);
            DungeonBlockPlan.Position spawn = spawnPoints.get(index % spawnPoints.size());
            Location location = new Location(
                    session.instanceWorld.world(),
                    spawn.x() + 0.5D,
                    spawn.y(),
                    spawn.z() + 0.5D,
                    (float) random.nextDouble(360.0D),
                    0.0F
            );
            MobInstance mob = mobService.spawn(selected, location);
            if (mob != null) {
                liveMobs.add(mob.instanceId());
                mobBindings.put(mob.instanceId(), new MobBinding(session.id, roomId));
            }
        }
        message(session.participants, PlayerMsgId.P_7010, room.distanceFromStart() + 1);
        if (liveMobs.isEmpty()) {
            if (room.role() == DungeonLayout.RoomRole.BOSS) {
                completeSession(session, EndReason.SPAWN_FAILED, false);
            } else {
                clearRoom(session, roomId);
            }
        }
    }

    /** DamageService の死亡確定後フックから呼ばれ、対象部屋の全滅を判定します。 */
    public void handleMobDefeated(@NotNull UUID mobInstanceId) {
        MobBinding binding = mobBindings.remove(mobInstanceId);
        if (binding == null) {
            return;
        }
        Session session = sessionsById.get(binding.sessionId());
        if (session == null || session.ending) {
            return;
        }
        Set<UUID> live = session.liveMobsByRoom.get(binding.roomId());
        if (live == null) {
            return;
        }
        live.remove(mobInstanceId);
        if (live.isEmpty() && session.roomStates.get(binding.roomId()) == RoomState.ACTIVE) {
            clearRoom(session, binding.roomId());
        }
    }

    private void clearRoom(@NotNull Session session, int roomId) {
        session.roomStates.put(roomId, RoomState.CLEARED);
        DungeonLayout.Room cleared = room(session, roomId);
        Logger.log(LogId.I_7003, session.id.toString(), roomId, cleared.role().name());
        if (cleared.role() == DungeonLayout.RoomRole.BOSS) {
            message(session.participants, PlayerMsgId.P_7012, session.loaded.definition().displayName());
            completeSession(session, EndReason.CLEARED, true);
            return;
        }

        message(session.participants, PlayerMsgId.P_7011, cleared.distanceFromStart() + 1);
        for (DungeonLayout.Connection connection : session.layout.connections()) {
            if (connection.fromRoomId() != roomId) {
                continue;
            }
            openGate(session, connection.id());
            if (session.roomStates.get(connection.toRoomId()) == RoomState.LOCKED) {
                session.roomStates.put(connection.toRoomId(), RoomState.AVAILABLE);
            }
        }
    }

    private void openGate(@NotNull Session session, int connectionId) {
        if (session.instanceWorld == null) {
            return;
        }
        List<DungeonBlockPlan.Position> positions = session.blockPlan.gateBlocksByConnection().get(connectionId);
        if (positions == null) {
            return;
        }
        World world = session.instanceWorld.world();
        for (DungeonBlockPlan.Position position : positions) {
            world.getBlockAt(position.x(), position.y(), position.z()).setType(Material.AIR, false);
        }
    }

    /** コマンドによる自主離脱です。 */
    public @NotNull LeaveResult leave(@NotNull Player player) {
        requireMainThread();
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending) {
            return LeaveResult.NO_SESSION;
        }
        requestParticipantLeave(session, player);
        return LeaveResult.LEFT;
    }

    /** ログアウトした参加者をセッションから外します。 */
    public void handleQuit(@NotNull UUID playerId) {
        UUID sessionId = sessionIdByParticipant.get(playerId);
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session != null && !session.ending) {
            finalizeParticipantRemoval(session, playerId);
        }
    }

    private void requestParticipantLeave(@NotNull Session session, @NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!session.departingParticipants.add(playerId)) {
            return;
        }
        CompletableFuture<Boolean> entryTransfer = session.entryTransfers.get(playerId);
        if (entryTransfer == null) {
            beginParticipantDeparture(session, player);
            return;
        }
        entryTransfer.whenComplete((ignored, failure) -> runMain(() ->
                beginParticipantDeparture(session, player)));
    }

    private void beginParticipantDeparture(@NotNull Session session, @NotNull Player player) {
        UUID playerId = player.getUniqueId();
        if (!isCurrentParticipant(session, playerId)) {
            return;
        }
        World instance = session.instanceWorld == null ? null : session.instanceWorld.world();
        if (!player.isOnline()) {
            finalizeParticipantRemoval(session, playerId);
            return;
        }

        Location target = resolveReturnLocation(session.returnLocations.get(playerId), instance);
        if (target == null) {
            session.departingParticipants.remove(playerId);
            messageService.send(player, PlayerMsgId.P_7017);
            return;
        }

        worldService.teleportPlayerAsync(player, target, null)
                .whenComplete((success, failure) -> runMain(() -> {
                    if (!isCurrentParticipant(session, playerId)) {
                        return;
                    }
                    boolean reachedReturnWorld = player.isOnline()
                            && target.getWorld() != null
                            && player.getWorld().getUID().equals(target.getWorld().getUID());
                    if ((failure == null && Boolean.TRUE.equals(success)) || reachedReturnWorld) {
                        finalizeParticipantRemoval(session, playerId);
                        if (player.isOnline()) {
                            messageService.send(player, PlayerMsgId.P_7015);
                        }
                        return;
                    }
                    session.departingParticipants.remove(playerId);
                    messageService.send(player, PlayerMsgId.P_7017);
                }));
    }

    private boolean isCurrentParticipant(@NotNull Session session, @NotNull UUID playerId) {
        return !session.ending
                && sessionsById.get(session.id) == session
                && session.participants.contains(playerId);
    }

    private void finalizeParticipantRemoval(@NotNull Session session, @NotNull UUID playerId) {
        if (!session.participants.remove(playerId)) {
            return;
        }
        session.departingParticipants.remove(playerId);
        session.entryTransfers.remove(playerId);
        sessionIdByParticipant.remove(playerId);
        session.returnLocations.remove(playerId);
        if (session.participants.isEmpty()) {
            completeSession(session, EndReason.EMPTY, false);
        }
    }

    private void failPreparation(@NotNull Session session, @NotNull Throwable failure) {
        Logger.log(LogId.E_7000, failure, session.id.toString(), session.loaded.definition().id());
        message(session.participants, PlayerMsgId.P_7009, session.loaded.definition().displayName());
        completeSession(session, EndReason.PREPARATION_FAILED, false);
    }

    private void completeSession(@NotNull Session session, @NotNull EndReason reason, boolean success) {
        if (session.ending) {
            return;
        }
        session.ending = true;
        sessionsById.remove(session.id);
        if (session.instanceWorld != null) {
            sessionIdByWorld.remove(session.instanceWorld.world().getUID());
        }
        for (UUID participant : session.participants) {
            sessionIdByParticipant.remove(participant);
        }
        for (Set<UUID> roomMobs : session.liveMobsByRoom.values()) {
            for (UUID mobId : List.copyOf(roomMobs)) {
                mobBindings.remove(mobId);
                mobService.destroy(mobId);
            }
            roomMobs.clear();
        }
        Logger.log(LogId.I_7004, session.id.toString(), session.loaded.definition().id(), reason.name());
        if (!success && reason != EndReason.PREPARATION_FAILED) {
            message(session.participants, PlayerMsgId.P_7013, session.loaded.definition().displayName());
        }

        if (session.instanceWorld == null) {
            returnParticipants(session, null);
            return;
        }
        World instance = session.instanceWorld.world();
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (UUID participantId : session.participants) {
            Player participant = Bukkit.getPlayer(participantId);
            if (participant == null || !participant.isOnline()) {
                continue;
            }
            Location target = resolveReturnLocation(session.returnLocations.get(participantId), instance);
            transfers.add(target == null
                    ? CompletableFuture.completedFuture(false)
                    : worldService.teleportPlayerAsync(participant, target, null));
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    evacuateRemainingPlayers(instance);
                    instanceWorldService.destroyAsync(session.instanceWorld);
                }));
    }

    private @Nullable Location resolveReturnLocation(@Nullable Location requested, @Nullable World instance) {
        if (requested != null && requested.getWorld() != null
                && Bukkit.getWorld(requested.getWorld().getUID()) != null
                && (instance == null || !requested.getWorld().getUID().equals(instance.getUID()))) {
            return requested.clone();
        }
        return Bukkit.getWorlds().stream()
                .filter(world -> instance == null || !world.getUID().equals(instance.getUID()))
                .filter(world -> worldService.resolveWorldType(world) != WorldType.DUNGEON)
                .findFirst()
                .map(World::getSpawnLocation)
                .orElse(null);
    }

    private void evacuateRemainingPlayers(@NotNull World instance) {
        Location fallback = resolveReturnLocation(null, instance);
        if (fallback == null) {
            return;
        }
        for (Player player : List.copyOf(instance.getPlayers())) {
            PlayerTeleportService.teleport(player, fallback);
        }
    }

    /** プラグイン停止時に全セッションを同期回収します。 */
    public void stop() {
        requireMainThread();
        stopping = true;
        if (entryVisualTask != null) {
            entryVisualTask.cancel();
            entryVisualTask = null;
        }
        for (Session session : List.copyOf(sessionsById.values())) {
            session.ending = true;
            for (Set<UUID> roomMobs : session.liveMobsByRoom.values()) {
                for (UUID mobId : roomMobs) {
                    mobBindings.remove(mobId);
                    mobService.destroy(mobId);
                }
            }
            if (session.instanceWorld != null) {
                World instance = session.instanceWorld.world();
                for (UUID participantId : session.participants) {
                    Player player = Bukkit.getPlayer(participantId);
                    if (player != null && player.isOnline()) {
                        Location target = resolveReturnLocation(session.returnLocations.get(participantId), instance);
                        if (target != null) {
                            PlayerTeleportService.teleport(player, target);
                        }
                    }
                }
                evacuateRemainingPlayers(instance);
                instanceWorldService.destroyNow(session.instanceWorld);
            } else {
                for (UUID participantId : session.participants) {
                    Player player = Bukkit.getPlayer(participantId);
                    Location target = resolveReturnLocation(session.returnLocations.get(participantId), null);
                    if (player != null && player.isOnline() && target != null) {
                        PlayerTeleportService.teleport(player, target);
                    }
                }
            }
        }
        for (DungeonInstanceWorldService.InstanceWorld instance : instanceWorldService.activeInstances()) {
            evacuateRemainingPlayers(instance.world());
        }
        instanceWorldService.destroyAllNow();
        sessionsById.clear();
        sessionIdByParticipant.clear();
        sessionIdByWorld.clear();
        mobBindings.clear();
    }

    /** 指定ワールドが稼働中または実行時登録済みの DUNGEON ワールドかを返します。 */
    public boolean isDungeonWorld(@NotNull World world) {
        return sessionIdByWorld.containsKey(world.getUID())
                || worldService.resolveWorldType(world) == WorldType.DUNGEON;
    }

    /** ロード済みダンジョン ID 一覧です。 */
    public @NotNull List<String> getDungeonIds() {
        return loadedDefinitions.keySet().stream().sorted().toList();
    }

    /**
     * 個別 World マスタを要求せず、安全な共通設定から一時 DUNGEON World 定義を作成します。
     *
     * @param definition ダンジョン定義
     * @return セッション生成に使用する実行時 World 定義
     */
    private @NotNull WorldMasterData createInstanceWorldData(@NotNull DungeonDefinition definition) {
        return new WorldMasterData(
                1,
                "dungeon_" + definition.id(),
                definition.displayName(),
                WorldType.DUNGEON,
                "",
                INSTANCE_ROOT_PATH,
                false,
                true,
                definition.partySize().max(),
                false,
                false,
                false,
                false,
                WorldSpawnLocation.defaultLocation(),
                "Procedurally generated dungeon instance",
                null,
                null,
                null
        );
    }

    /**
     * プレイヤーが定義された挑戦受付範囲内にいるか判定します。
     *
     * @param player 判定対象
     * @param loaded 受付 World を含むロード済み定義
     * @return 同一 World かつ受付半径内なら {@code true}
     */
    private boolean isInsideEntry(@NotNull Player player, @NotNull LoadedDefinition loaded) {
        WorldMasterData currentWorld = worldService.findByBukkitWorld(player.getWorld());
        DungeonDefinition.Entry entry = loaded.definition().entry();
        if (currentWorld == null || !currentWorld.id().equals(entry.worldId())) {
            return false;
        }
        Location center = entryLocation(entry, player.getWorld());
        return player.getLocation().distanceSquared(center) <= entry.radius() * entry.radius();
    }

    /**
     * 生成待機中の固定参加者から、オンライン・通常プレイ・ハブ滞在条件を満たさない者を除外します。
     * 除外したオンライン参加者には受付前 Location への非同期転送を試みます。
     *
     * @param session 準備中セッション
     * @param hubData 待機 HUB World 定義
     */
    private void retainEligiblePreparingParticipants(
            @NotNull Session session,
            @NotNull WorldMasterData hubData
    ) {
        World hubWorld = worldService.resolveLoadedWorld(hubData);
        for (UUID participantId : List.copyOf(session.participants)) {
            Player player = Bukkit.getPlayer(participantId);
            boolean eligible = player != null
                    && player.isOnline()
                    && hubWorld != null
                    && player.getWorld().getUID().equals(hubWorld.getUID())
                    && AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(player));
            if (eligible) {
                continue;
            }
            session.participants.remove(participantId);
            sessionIdByParticipant.remove(participantId);
            session.entryTransfers.remove(participantId);
            if (player != null && player.isOnline()) {
                Location target = resolveReturnLocation(session.returnLocations.get(participantId), null);
                if (target != null) {
                    worldService.teleportPlayerAsync(player, target, null);
                }
            }
        }
    }

    /**
     * 残存オンライン参加者を受付前 Location または安全な非 DUNGEON World へ戻します。
     *
     * @param session 終了対象セッション
     * @param instance 除外すべき一時 World。未生成時は {@code null}
     */
    private void returnParticipants(@NotNull Session session, @Nullable World instance) {
        for (UUID participantId : session.participants) {
            Player player = Bukkit.getPlayer(participantId);
            Location target = resolveReturnLocation(session.returnLocations.get(participantId), instance);
            if (player != null && player.isOnline() && target != null) {
                worldService.teleportPlayerAsync(player, target, null);
            }
        }
    }

    /** ロード済み受付地点のうち、近隣プレイヤーがいる地点へダンジョン専用演出を表示します。 */
    private void tickEntryVisuals() {
        double pulse = 0.08D * Math.sin(entryVisualFrame * 0.35D);
        for (LoadedDefinition loaded : loadedDefinitions.values()) {
            World world = worldService.resolveLoadedWorld(loaded.entryWorldData());
            if (world == null) {
                continue;
            }
            DungeonDefinition.Entry entry = loaded.definition().entry();
            Location center = entryLocation(entry, world);
            if (world.getPlayers().stream().noneMatch(player ->
                    player.getLocation().distanceSquared(center) <= ENTRY_VIEW_DISTANCE_SQUARED)) {
                continue;
            }
            List<Location> frame = dungeonGateFrame(center, Math.max(0.9D, entry.radius() * 0.65D), pulse);
            particleDisplayService.spawnForNearbyViewers(
                    center,
                    frame,
                    SharedParticleDefinitions.DUNGEON_ENTRY_FRAME_DUST
            );
            particleDisplayService.spawnForNearbyViewers(
                    center.clone().add(0.0D, 1.25D, 0.0D),
                    SharedParticleDefinitions.DUNGEON_ENTRY_PORTAL
            );
        }
        entryVisualFrame++;
    }

    /**
     * Boss の円環と区別できる門型パーティクル座標を生成します。
     *
     * @param center 受付中心と向き
     * @param halfWidth 門の半幅
     * @param pulse 現在フレームの上下変位
     * @return 左右の柱と上辺を構成する座標
     */
    private @NotNull List<Location> dungeonGateFrame(
            @NotNull Location center,
            double halfWidth,
            double pulse
    ) {
        List<Location> points = new ArrayList<>(ENTRY_FRAME_POINTS);
        double yaw = Math.toRadians(center.getYaw());
        double rightX = Math.cos(yaw);
        double rightZ = Math.sin(yaw);
        for (int side : List.of(-1, 1)) {
            for (int i = 0; i <= 6; i++) {
                points.add(center.clone().add(
                        rightX * halfWidth * side,
                        0.2D + (i * 0.38D) + pulse,
                        rightZ * halfWidth * side
                ));
            }
        }
        for (int i = 0; i <= 6; i++) {
            double offset = -halfWidth + ((halfWidth * 2.0D * i) / 6.0D);
            points.add(center.clone().add(
                    rightX * offset,
                    2.48D + pulse,
                    rightZ * offset
            ));
        }
        return points;
    }

    /**
     * マスタの受付座標を指定 Bukkit World 上の Location へ変換します。
     *
     * @param entry 受付座標定義
     * @param world 解決済み受付 World
     * @return 受付 Location
     */
    private @NotNull Location entryLocation(
            @NotNull DungeonDefinition.Entry entry,
            @NotNull World world
    ) {
        return new Location(world, entry.x(), entry.y(), entry.z(), entry.yaw(), entry.pitch());
    }

    private @NotNull DungeonLayout.Room room(@NotNull Session session, int roomId) {
        return session.layout.rooms().stream()
                .filter(room -> room.id() == roomId)
                .findFirst()
                .orElseThrow();
    }

    private boolean contains(@NotNull DungeonLayout.Room room, int x, int z) {
        DungeonLayout.Rect bounds = room.bounds();
        if (!bounds.contains(x, z)) {
            return false;
        }
        if (room.shape() == DungeonRoomShape.RECTANGLE) {
            return true;
        }
        double radiusX = Math.max(1.0D, (bounds.width() - 1) / 2.0D);
        double radiusZ = Math.max(1.0D, (bounds.depth() - 1) / 2.0D);
        double normalizedX = (x - (bounds.minX() + bounds.maxX()) / 2.0D) / radiusX;
        double normalizedZ = (z - (bounds.minZ() + bounds.maxZ()) / 2.0D) / radiusZ;
        return normalizedX * normalizedX + normalizedZ * normalizedZ <= 1.0D;
    }

    private @NotNull SplittableRandom encounterRandom(@NotNull Session session, int roomId) {
        return new SplittableRandom(session.seed ^ (0xA54FF53A5F1D36F1L * (roomId + 1L)));
    }

    private void message(@NotNull Collection<UUID> recipients, @NotNull PlayerMsgId id, Object... args) {
        for (UUID recipient : recipients) {
            Player player = Bukkit.getPlayer(recipient);
            if (player != null && player.isOnline()) {
                messageService.send(player, id, args);
            }
        }
    }

    private void message(@NotNull List<Player> recipients, @NotNull PlayerMsgId id, Object... args) {
        for (Player player : recipients) {
            messageService.send(player, id, args);
        }
    }

    private void runMain(@NotNull Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Dungeon session mutation must run on the main thread");
        }
    }

    /** reload 単位で原子的に公開する定義です。 */
    public record DefinitionSnapshot(@NotNull Map<String, LoadedDefinition> loadedById) {
        public DefinitionSnapshot {
            loadedById = Map.copyOf(loadedById);
        }
    }

    /** ダンジョン定義と、同時点の参照先マスタを束ねたスナップショットです。 */
    public record LoadedDefinition(
            @NotNull DungeonDefinition definition,
            @NotNull WorldMasterData entryWorldData,
            @NotNull WorldMasterData instanceWorldData,
            @NotNull List<LoadedMob> normalMobs,
            @NotNull MobTemplate bossMob
    ) {
        public LoadedDefinition {
            normalMobs = List.copyOf(normalMobs);
        }
    }

    /** 重み付き Mob テンプレートのスナップショットです。 */
    public record LoadedMob(@NotNull MobTemplate template, int weight) {
    }

    /** 開始受付の状態です。 */
    public enum StartStatus {
        ACCEPTED,
        UNAVAILABLE,
        NOT_FOUND,
        NOT_PARTY_LEADER,
        PARTY_SIZE,
        PARTICIPANT_BUSY,
        NOT_GAMEPLAY,
        NOT_AT_ENTRY,
        HUB_UNAVAILABLE
    }

    /** 開始受付結果です。人数エラー時だけ min/max/current が設定されます。 */
    public record StartRequestResult(StartStatus status, int min, int max, int current) {
        private static @NotNull StartRequestResult of(@NotNull StartStatus status) {
            return new StartRequestResult(status, 0, 0, 0);
        }
    }

    /** 離脱結果です。 */
    public enum LeaveResult {
        LEFT,
        NO_SESSION
    }

    private enum RoomState {
        LOCKED,
        AVAILABLE,
        ACTIVE,
        CLEARED
    }

    private enum EndReason {
        CLEARED,
        EMPTY,
        PREPARATION_FAILED,
        PARTICIPANT_REQUIREMENT_NOT_MET,
        TRANSFER_FAILED,
        SPAWN_FAILED
    }

    private static final class Session {
        private final UUID id;
        private final long seed;
        private final LoadedDefinition loaded;
        private final LinkedHashSet<UUID> participants;
        private final Map<UUID, Location> returnLocations;
        private final Map<UUID, CompletableFuture<Boolean>> entryTransfers = new HashMap<>();
        private final Set<UUID> departingParticipants = new LinkedHashSet<>();
        private final Map<Integer, RoomState> roomStates = new LinkedHashMap<>();
        private final Map<Integer, Set<UUID>> liveMobsByRoom = new LinkedHashMap<>();
        private DungeonLayout layout;
        private DungeonBlockPlan blockPlan;
        private DungeonInstanceWorldService.InstanceWorld instanceWorld;
        private boolean ending;

        private Session(
                @NotNull UUID id,
                long seed,
                @NotNull LoadedDefinition loaded,
                @NotNull LinkedHashSet<UUID> participants,
                @NotNull Map<UUID, Location> returnLocations
        ) {
            this.id = id;
            this.seed = seed;
            this.loaded = loaded;
            this.participants = participants;
            this.returnLocations = returnLocations;
        }
    }

    private record PreparedPlan(@NotNull DungeonLayout layout, @NotNull DungeonBlockPlan blocks) {
    }

    private record MobBinding(@NotNull UUID sessionId, int roomId) {
    }
}
