package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonBlockPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonEncounterPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonLayoutPlanner;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRoomShape;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRewardEntry;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonSidebarInfo;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonCancelGui;
import io.github.maaasu.astralRecord.feature.dungeon.gui.DungeonRewardGui;
import io.github.maaasu.astralRecord.feature.dungeon.repository.DungeonDefinitionRepository;
import io.github.maaasu.astralRecord.feature.dungeon.view.DungeonCancelController;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResult;
import io.github.maaasu.astralRecord.feature.mob.model.MobDropResultItem;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.service.MobDropService;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.death.PlayerDeathService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldSpawnLocation;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeDeathPolicy;
import io.github.maaasu.astralRecord.shared.challenge.ChallengeStartCountdown;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import net.kyori.adventure.title.Title;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
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
import java.util.function.Consumer;
import java.time.Duration;

/**
 * ダンジョンマスタ、セッション進行、部屋戦闘、ゲート解放を一元管理します。
 * アクティブセッションは開始時のマスタと Mob テンプレートを保持するため、reload の影響を受けません。
 */
public final class DungeonService {
    /** 受付ゲート候補と中心までの距離です。 */
    public record DungeonEntryHit(@NotNull String dungeonId, double hitDistance) {
    }
    private static final String INSTANCE_ROOT_PATH = "plugins/AstralRecord/_world_instances/dungeon";
    private static final long ENTRY_VISUAL_PERIOD_TICKS = 10L;
    private static final int ENTRY_FRAME_POINTS = 20;
    private static final double ENTRY_VIEW_DISTANCE_SQUARED = 48.0D * 48.0D;
    private static final long CLEAR_RETURN_DELAY_TICKS = 30L * 20L;
    private static final double RETURN_GATE_RADIUS_SQUARED = 2.25D * 2.25D;
    private static final String REWARD_SOURCE = "dungeon_clear";
    private static final Title.Times COUNTDOWN_TITLE_TIMES = Title.Times.times(
            Duration.ofMillis(100L), Duration.ofMillis(900L), Duration.ofMillis(100L));
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
    private final DungeonGateReleaseService gateReleaseService;
    private final DisplayTextService displayTextService;
    private final PlayerDeathService playerDeathService;
    private final MobDropService mobDropService;
    private final InventoryService inventoryService;
    private final ItemService itemService;
    private final DungeonCancelGui cancelGui;
    private final DungeonRewardGui rewardGui;
    private final String hubWorldId;

    private volatile Map<String, LoadedDefinition> loadedDefinitions = Map.of();
    private final Map<UUID, Session> sessionsById = new LinkedHashMap<>();
    private final Map<UUID, UUID> sessionIdByParticipant = new HashMap<>();
    private final Map<UUID, UUID> sessionIdByBusyParticipant = new HashMap<>();
    private final Map<UUID, UUID> sessionIdByWorld = new HashMap<>();
    private final Map<String, UUID> sessionIdByPartyKey = new HashMap<>();
    private final Map<UUID, UUID> dungeonDeathSessionByParticipant = new HashMap<>();
    private final Map<UUID, MobBinding> mobBindings = new HashMap<>();
    private final Map<UUID, DungeonCancelController> cancelControllers = new HashMap<>();
    private final Map<UUID, UUID> sessionIdByCancelInteraction = new HashMap<>();
    private final Map<String, DisplayTextService.ManagedTextDisplay> entryPromptDisplays = new HashMap<>();
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
     * @param displayTextService TextDisplay 管理サービス
     * @param playerDeathService 死亡・復帰サービス
     * @param mobDropService クリア報酬抽選サービス
     * @param inventoryService 報酬付与先インベントリ
     * @param itemService アイテム定義サービス
     * @param itemStackFactory 報酬 GUI の ItemStack 生成サービス
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
            @NotNull DisplayTextService displayTextService,
            @NotNull PlayerDeathService playerDeathService,
            @NotNull MobDropService mobDropService,
            @NotNull InventoryService inventoryService,
            @NotNull ItemService itemService,
            @NotNull ItemStackFactory itemStackFactory,
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
        this.gateReleaseService = new DungeonGateReleaseService(particleDisplayService);
        this.displayTextService = displayTextService;
        this.playerDeathService = playerDeathService;
        this.mobDropService = mobDropService;
        this.inventoryService = inventoryService;
        this.itemService = itemService;
        this.cancelGui = new DungeonCancelGui();
        this.rewardGui = new DungeonRewardGui(itemService, itemStackFactory);
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
        clearEntryPromptDisplays();
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

    /**
     * プレイヤーを含む最寄り受付ゲートを副作用なしで返します。
     *
     * @param player 判定対象
     * @return 最寄り受付。範囲外なら {@code null}
     */
    public @Nullable DungeonEntryHit findNearestEntry(@NotNull Player player) {
        DungeonEntryHit nearest = null;
        for (LoadedDefinition loaded : loadedDefinitions.values()) {
            if (!isInsideEntry(player, loaded)) continue;
            Location center = entryLocation(loaded.definition().entry(), player.getWorld());
            DungeonEntryHit hit = new DungeonEntryHit(
                    loaded.definition().id(), Math.sqrt(player.getLocation().distanceSquared(center)));
            if (nearest == null || hit.hitDistance() < nearest.hitDistance()
                    || (Double.compare(hit.hitDistance(), nearest.hitDistance()) == 0
                    && hit.dungeonId().compareTo(nearest.dungeonId()) < 0)) nearest = hit;
        }
        return nearest;
    }

    /**
     * 受付ゲートのスニーク操作から開始／同一インスタンス再参加を要求します。
     *
     * @param player 受付操作プレイヤー
     * @param dungeonId 受付対象ダンジョン ID
     * @return 開始または再参加の即時結果
     */
    public @NotNull StartRequestResult requestEntry(@NotNull Player player, @NotNull String dungeonId) {
        requireMainThread();
        Party party = partyService.findParty(player.getUniqueId());
        String partyKey = partyKey(player.getUniqueId(), party);
        UUID activeSessionId = sessionIdByPartyKey.get(partyKey);
        Session active = activeSessionId == null ? null : sessionsById.get(activeSessionId);
        if (active == null) {
            return requestStart(player, dungeonId);
        }
        if (!active.loaded.definition().id().equals(dungeonId)
                || !canRejoinParticipant(
                        active.originalParticipants,
                        active.gateReturnEligible,
                        player.getUniqueId()
                )) {
            return StartRequestResult.of(StartStatus.ALREADY_IN_PROGRESS);
        }
        return requestRejoin(active, player);
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
        String partyKey = partyKey(leader.getUniqueId(), party);
        if (sessionIdByPartyKey.containsKey(partyKey)) {
            return StartRequestResult.of(StartStatus.ALREADY_IN_PROGRESS);
        }
        if (participants.stream().anyMatch(player ->
                !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(player)))) {
            return StartRequestResult.of(StartStatus.NOT_GAMEPLAY);
        }
        int participantCount = participants.size();
        DungeonDefinition.IntRange allowed = loaded.definition().partySize();
        if (participantCount < allowed.min() || participantCount > allowed.max()) {
            return new StartRequestResult(StartStatus.PARTY_SIZE, allowed.min(), allowed.max(), participantCount);
        }
        if (participants.stream().anyMatch(player ->
                sessionIdByBusyParticipant.containsKey(player.getUniqueId()))) {
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
            sessionIdByBusyParticipant.put(participant.getUniqueId(), sessionId);
        }

        Session session = new Session(
                sessionId,
                seed,
                loaded,
                partyKey,
                leader.getUniqueId(),
                participantIds,
                returnLocations
        );
        sessionsById.put(sessionId, session);
        sessionIdByPartyKey.put(partyKey, sessionId);
        Logger.log(LogId.I_7001, sessionId.toString(), dungeonId, seed, participantCount);
        message(participants, PlayerMsgId.P_7008, loaded.definition().displayName());
        transferToHubAndPrepare(session, participants, hubData);
        return StartRequestResult.of(StartStatus.ACCEPTED);
    }

    private @NotNull String partyKey(@NotNull UUID playerId, @Nullable Party party) {
        return party == null ? "solo:" + playerId : "party:" + party.getPartyId();
    }

    private @NotNull StartRequestResult requestRejoin(@NotNull Session session, @NotNull Player player) {
        if (session.ending || session.instanceWorld == null || !isInsideEntry(player, session.loaded)
                || !AccountModeGuard.isGameplayPlayer(AstPlayerCache.get(player))) {
            return StartRequestResult.of(StartStatus.UNAVAILABLE);
        }
        UUID playerId = player.getUniqueId();
        if (!session.id.equals(sessionIdByBusyParticipant.get(playerId))) {
            return StartRequestResult.of(StartStatus.UNAVAILABLE);
        }
        session.gateReturnEligible.remove(playerId);
        session.participants.add(playerId);
        sessionIdByParticipant.put(playerId, session.id);
        DungeonBlockPlan.Position spawn = session.blockPlan.playerSpawn();
        Location target = new Location(session.instanceWorld.world(), spawn.x() + 0.5D, spawn.y(), spawn.z() + 0.5D);
        long transferGeneration = session.transferGeneration;
        CompletableFuture<Boolean> transfer = trackEntryTransfer(
                session, playerId, worldService.teleportPlayerAsync(player, target, null));
        transfer.whenComplete((success, failure) -> runMain(() -> {
            if (!isActiveTransferCallback(session, transferGeneration)) return;
            if (!session.participants.contains(playerId)
                    || !session.id.equals(sessionIdByParticipant.get(playerId))
                    || !player.isOnline()) {
                session.gateReturnEligible.remove(playerId);
                return;
            }
            if (failure != null || !Boolean.TRUE.equals(success)) {
                finalizeParticipantRemoval(session, playerId, true);
                return;
            }
            messageService.send(player, PlayerMsgId.P_7025);
        }));
        return StartRequestResult.of(StartStatus.REJOINED);
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
        long transferGeneration = session.transferGeneration;
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (Player participant : participants) {
            CompletableFuture<Boolean> transfer = trackEntryTransfer(
                    session,
                    participant.getUniqueId(),
                    worldService.teleportToSpawnAsync(participant, hubData)
            );
            transfers.add(transfer);
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    if (!isActiveTransferCallback(session, transferGeneration)) {
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
        CompletableFuture<Void> preparationLifecycle = new CompletableFuture<>();
        session.preparationLifecycle = preparationLifecycle;
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
                preparationLifecycle.complete(null);
                return;
            }
            if (failure != null) {
                preparationLifecycle.complete(null);
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
                        instanceWorldService.destroyAsync(instance)
                                .whenComplete((destroyed, destroyFailure) -> preparationLifecycle.complete(null));
                    } else {
                        preparationLifecycle.complete(null);
                    }
                    return;
                }
                if (worldFailure != null) {
                    preparationLifecycle.complete(null);
                    failPreparation(session, worldFailure);
                    return;
                }
                handleWorldReady(session, instance);
                preparationLifecycle.complete(null);
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
        long transferGeneration = session.transferGeneration;
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (UUID playerId : session.participants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                CompletableFuture<Boolean> transfer = trackEntryTransfer(
                        session,
                        playerId,
                        worldService.teleportPlayerAsync(player, target.clone(), null)
                );
                transfers.add(transfer);
            }
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, transferFailure) -> runMain(() -> {
                    if (!isActiveTransferCallback(session, transferGeneration)) {
                        return;
                    }
                    boolean allTransferred = transferFailure == null
                            && transfers.stream().allMatch(future -> Boolean.TRUE.equals(future.join()));
                    if (!allTransferred) {
                        completeSession(session, EndReason.TRANSFER_FAILED, false);
                        return;
                    }
                    Logger.log(LogId.I_7002, session.id.toString(), instance.world().getName());
                    try {
                        createSessionControls(session, target);
                        beginStartCountdown(session);
                    } catch (RuntimeException ex) {
                        Logger.log(LogId.E_7000, ex, session.id.toString(), session.loaded.definition().id());
                        completeSession(session, EndReason.PREPARATION_FAILED, false);
                    }
                }));
    }

    /** 開始地点に帰還ゲート、中止装置、案内表示を生成します。 */
    private void createSessionControls(@NotNull Session session, @NotNull Location playerSpawn) {
        session.returnGateLocation = playerSpawn.clone();
        session.returnGateDisplay = displayTextService.create(
                DisplayAnchor.fixed(playerSpawn.clone().add(0.0D, 2.4D, 0.0D)),
                DisplayTextOptions.defaults(PlayerMsgResource.getMessage(PlayerMsgId.P_7034.getId()))
                        .withLineWidth(280).withViewRange(48.0F).withShadowed(true)
        );
        Location controllerLocation = playerSpawn.clone().add(2.5D, 0.0D, 0.0D);
        DungeonCancelController controller = DungeonCancelController.spawn(session.id, controllerLocation, displayTextService);
        cancelControllers.put(session.id, controller);
        sessionIdByCancelInteraction.put(controller.interaction().getUniqueId(), session.id);
    }

    /** 初回転送後の10秒間は Mob を生成せず、参加者へ毎秒開始演出を送ります。 */
    private void beginStartCountdown(@NotNull Session session) {
        ChallengeStartCountdown countdown = new ChallengeStartCountdown();
        BukkitTask[] taskRef = new BukkitTask[1];
        taskRef[0] = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            try {
                if (session.ending || sessionsById.get(session.id) != session || session.instanceWorld == null) {
                    taskRef[0].cancel();
                    session.startCountdownTask = null;
                    return;
                }
                List<Player> inWorld = activePlayersInWorld(session);
                if (inWorld.isEmpty()) {
                    taskRef[0].cancel();
                    session.startCountdownTask = null;
                    completeSession(session, EndReason.PARTICIPANT_REQUIREMENT_NOT_MET, false);
                    return;
                }
                ChallengeStartCountdown.Tick tick = countdown.advance();
                if (tick.phase() == ChallengeStartCountdown.Phase.COUNTDOWN) {
                    showDungeonCountdown(inWorld, session.loaded.definition().displayName(), tick.remainingSeconds());
                    return;
                }
                taskRef[0].cancel();
                session.startCountdownTask = null;
                showDungeonStart(inWorld, session.loaded.definition().displayName());
                session.combatStarted = true;
                activateRoom(session, session.layout.startRoomId());
            } catch (RuntimeException failure) {
                if (taskRef[0] != null) taskRef[0].cancel();
                session.startCountdownTask = null;
                Logger.log(LogId.E_7000, failure, session.id.toString(), session.loaded.definition().id());
                completeSession(session, EndReason.SPAWN_FAILED, false);
            }
        }, 0L, 20L);
        session.startCountdownTask = taskRef[0];
    }

    private void showDungeonCountdown(@NotNull List<Player> players, @NotNull String name, int seconds) {
        for (Player player : players) {
            player.showTitle(Title.title(
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_7020.getId(), seconds),
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_7021.getId(), name),
                    COUNTDOWN_TITLE_TIMES
            ));
            player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_HAT, SoundCategory.PLAYERS, 0.8F, 1.2F);
        }
    }

    private void showDungeonStart(@NotNull List<Player> players, @NotNull String name) {
        for (Player player : players) {
            player.showTitle(Title.title(
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_7022.getId(), name),
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7023.getId()),
                    COUNTDOWN_TITLE_TIMES
            ));
            player.playSound(player.getLocation(), Sound.UI_TOAST_CHALLENGE_COMPLETE, SoundCategory.PLAYERS, 0.9F, 1.0F);
        }
    }

    private @NotNull List<Player> activePlayersInWorld(@NotNull Session session) {
        if (session.instanceWorld == null) return List.of();
        UUID worldId = session.instanceWorld.world().getUID();
        List<Player> result = new ArrayList<>();
        for (UUID participantId : session.participants) {
            Player player = Bukkit.getPlayer(participantId);
            if (player != null && player.isOnline() && player.getWorld().getUID().equals(worldId)) result.add(player);
        }
        return result;
    }

    /** プレイヤーが解放済みの部屋へ入ったとき、その部屋の戦闘を開始します。 */
    public void handleMove(@NotNull Player player, @NotNull Location destination) {
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending || session.instanceWorld == null || !session.combatStarted
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
        try {
            activateRoomContent(session, roomId);
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_7000, failure, session.id.toString(), session.loaded.definition().id());
            completeSession(session, EndReason.SPAWN_FAILED, false);
        }
    }

    private void activateRoomContent(@NotNull Session session, int roomId) {
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

    /**
     * DamageService の死亡確定後フックから呼ばれ、対象部屋の全滅を判定します。
     *
     * @param mobInstanceId 死亡確定した Mob instance UUID
     * @implNote Bukkit のブロック更新とセッション状態を変更するため、メインスレッドから呼び出す必要があります。
     */
    public void handleMobDefeated(@NotNull UUID mobInstanceId) {
        requireMainThread();
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
            beginClearedWait(session, cleared);
            return;
        }

        message(session.participants, PlayerMsgId.P_7011, cleared.distanceFromStart() + 1);
        for (DungeonLayout.Connection connection : DungeonBossGatePolicy.connectionsToUnlockAfterClear(
                session.layout,
                roomId,
                candidateRoomId -> session.roomStates.get(candidateRoomId) == RoomState.CLEARED
        )) {
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
        List<DungeonBlockPlan.Position> visualBlocks = session.blockPlan.gateBlocksByConnection().get(connectionId);
        List<DungeonBlockPlan.Position> barrierBlocks = session.blockPlan.gateBarrierBlocksByConnection().get(connectionId);
        if (visualBlocks == null || barrierBlocks == null) {
            return;
        }
        gateReleaseService.release(session.instanceWorld.world(), visualBlocks, barrierBlocks);
    }

    /** コマンドによる自主離脱です。 */
    public @NotNull LeaveResult leave(@NotNull Player player) {
        requireMainThread();
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending) {
            return LeaveResult.NO_SESSION;
        }
        requestParticipantLeave(session, player, false);
        return LeaveResult.LEFT;
    }

    /** @return 3.5 block以内にある中止装置のセッション ID。対象外なら {@code null} */
    public @Nullable UUID findNearbyCancelController(@NotNull Player player) {
        return cancelControllers.values().stream()
                .filter(controller -> controller.isNear(player))
                .map(DungeonCancelController::sessionId)
                .findFirst()
                .orElse(null);
    }

    /** @return Interaction に対応するセッション ID。対象外なら {@code null} */
    public @Nullable UUID resolveCancelInteraction(@NotNull org.bukkit.entity.Entity entity) {
        return sessionIdByCancelInteraction.get(entity.getUniqueId());
    }

    /**
     * 現在のパーティーリーダー、またはソロ開始者かを判定します。
     *
     * @param playerId 操作プレイヤー UUID
     * @param sessionId セッション ID
     * @return 中止権限があれば {@code true}
     */
    public boolean isSessionLeader(@NotNull UUID playerId, @NotNull UUID sessionId) {
        Session session = sessionsById.get(sessionId);
        if (session == null || session.ending) return false;
        if (session.partyKey.startsWith("solo:")) return session.initiatorId.equals(playerId);
        Party party = partyService.findParty(playerId);
        return party != null && session.partyKey.equals("party:" + party.getPartyId()) && party.isLeader(playerId);
    }

    /** リーダーによる全員強制帰還付き中止を実行します。 */
    public @NotNull CancelResult cancelForLeader(@NotNull UUID playerId, @NotNull UUID sessionId) {
        Session session = sessionsById.get(sessionId);
        if (session == null || session.ending) return CancelResult.NO_SESSION;
        if (!isSessionLeader(playerId, sessionId)) return CancelResult.NOT_LEADER;
        completeSession(session, EndReason.CANCELLED, false);
        return CancelResult.CANCELLED;
    }

    /** ボス部屋クリア時の受取人と報酬を固定し、30秒の受取猶予を開始します。 */
    private void beginClearedWait(@NotNull Session session, @NotNull DungeonLayout.Room bossRoom) {
        if (session.cleared || session.ending || session.instanceWorld == null) return;
        session.cleared = true;
        try {
        for (Player player : activePlayersInWorld(session)) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null) continue;
            MobDropResult result = mobDropService.roll(session.loaded.definition().clearRewards(), astPlayer);
            List<DungeonRewardEntry> rewards = createRewardEntries(result.items());
            session.rewardsByPlayer.put(player.getUniqueId(), new ArrayList<>(rewards));
        }
        Location chestLocation = findRewardChestLocation(session, bossRoom);
        Block chest = chestLocation.getBlock();
        session.rewardChestLocation = chest.getLocation();
        if (!chest.getRelative(BlockFace.DOWN).getType().isSolid()) {
            chest.getRelative(BlockFace.DOWN).setType(
                    session.loaded.definition().theme().floor().getFirst().material(), false);
        }
        chest.getRelative(BlockFace.UP).setType(Material.AIR, false);
        chest.setType(Material.CHEST, false);
        if (chest.getBlockData() instanceof org.bukkit.block.data.type.Chest chestData) {
            chestData.setFacing(BlockFace.SOUTH);
            chest.setBlockData(chestData, false);
        }
        session.rewardDisplay = displayTextService.create(
                DisplayAnchor.fixed(chest.getLocation().clone().add(0.5D, 1.8D, 0.5D)),
                DisplayTextOptions.defaults(PlayerMsgResource.format(
                                PlayerMsgId.P_7033.getId(), session.loaded.definition().displayName()))
                        .withLineWidth(300).withViewRange(48.0F).withShadowed(true)
        );
        session.clearReturnEndsAtMs = System.currentTimeMillis() + CLEAR_RETURN_DELAY_TICKS * 50L;
        session.clearReturnTask = Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> completeSession(session, EndReason.CLEARED, true),
                CLEAR_RETURN_DELAY_TICKS
        );
        } catch (RuntimeException ex) {
            Logger.log(LogId.E_7000, ex, session.id.toString(), session.loaded.definition().id());
            completeSession(session, EndReason.SPAWN_FAILED, false);
        }
    }

    private @NotNull Location findRewardChestLocation(
            @NotNull Session session,
            @NotNull DungeonLayout.Room room
    ) {
        int centerX = (room.bounds().minX() + room.bounds().maxX()) / 2;
        int centerZ = (room.bounds().minZ() + room.bounds().maxZ()) / 2;
        int y = session.loaded.definition().generation().baseY() + 1;
        World world = session.instanceWorld.world();
        int maxRadius = Math.max(room.bounds().width(), room.bounds().depth());
        for (int radius = 0; radius <= maxRadius; radius++) {
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    if (Math.max(Math.abs(x - centerX), Math.abs(z - centerZ)) != radius) continue;
                    if (!contains(room, x, z)) continue;
                    Block candidate = world.getBlockAt(x, y, z);
                    if (candidate.getType().isAir() && candidate.getRelative(BlockFace.UP).getType().isAir()
                            && candidate.getRelative(BlockFace.DOWN).getType().isSolid()) {
                        return candidate.getLocation();
                    }
                }
            }
        }
        return new Location(world, centerX, y, centerZ);
    }

    /** 操作可能な報酬 CHEST と所有セッションです。 */
    public record DungeonRewardChestTarget(@NotNull UUID sessionId, @NotNull Block block) {
    }

    /**
     * 操作プレイヤーが受取対象である、同一ワールド内の報酬 CHEST を返します。
     *
     * @param player 操作プレイヤー
     * @return 操作可能な報酬 CHEST。参加中でない、受取対象でない、または回収終了後なら {@code null}
     */
    public @Nullable DungeonRewardChestTarget findRewardChestTarget(@NotNull Player player) {
        Session session = rewardSession(player);
        if (session == null) {
            return null;
        }
        Block block = session.rewardChestLocation.getBlock();
        return new DungeonRewardChestTarget(session.id, block);
    }

    /**
     * 共通 interaction gateway が選択した実チェストから個人 GUI を開きます。
     *
     * @param player 操作プレイヤー
     * @param block 視線候補として選択された報酬 CHEST
     * @return 報酬チェストとして処理した場合 {@code true}
     */
    public boolean openRewardChest(@NotNull Player player, @NotNull Block block) {
        Session session = rewardSession(player);
        if (session == null || block.getType() != Material.CHEST
                || !sameBlock(session.rewardChestLocation, block.getLocation())) return false;
        openRewardGui(session, player, 0);
        return true;
    }

    /**
     * プレイヤーが現在操作できる報酬セッションを返します。
     *
     * @param player 操作プレイヤー
     * @return 受取対象として固定済みのセッション。対象外なら {@code null}
     */
    private @Nullable Session rewardSession(@NotNull Player player) {
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.rewardChestLocation == null) {
            return null;
        }
        World rewardWorld = session.rewardChestLocation.getWorld();
        if (rewardWorld == null) {
            return null;
        }
        Block rewardBlock = session.rewardChestLocation.getBlock();
        if (!DungeonRewardChestPolicy.canAccess(
                session.cleared,
                session.ending,
                session.participants,
                session.rewardsByPlayer,
                player.getUniqueId(),
                rewardWorld.getUID(),
                player.getWorld().getUID(),
                rewardBlock.getType()
        )) {
            return null;
        }
        return session;
    }

    private boolean sameBlock(@NotNull Location first, @NotNull Location second) {
        return first.getWorld() != null && second.getWorld() != null
                && first.getWorld().getUID().equals(second.getWorld().getUID())
                && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY()
                && first.getBlockZ() == second.getBlockZ();
    }

    private void openRewardGui(@NotNull Session session, @NotNull Player player, int page) {
        List<DungeonRewardEntry> rewards = session.rewardsByPlayer.get(player.getUniqueId());
        if (rewards == null) {
            messageService.send(player, PlayerMsgId.P_7032);
            return;
        }
        rewardGui.open(player, session.id, session.loaded.definition().displayName(), rewards, page);
    }

    /** @return 報酬 GUI view */
    public @NotNull DungeonRewardGui rewardGui() { return rewardGui; }

    /** @return 中止 GUI view */
    public @NotNull DungeonCancelGui cancelGui() { return cancelGui; }

    /**
     * 報酬 GUI のクリックを処理します。
     *
     * @param player 操作プレイヤー
     * @param sessionId セッション ID
     * @param page 0始まりページ
     * @param slot GUI raw slot
     * @param expectedClaimId GUI 描画時に slot へ固定した claim ID
     */
    public void handleRewardClick(
            @NotNull Player player,
            @NotNull UUID sessionId,
            int page,
            int slot,
            @Nullable UUID expectedClaimId
    ) {
        Session session = sessionsById.get(sessionId);
        if (session == null || session.ending || !session.cleared
                || !session.participants.contains(player.getUniqueId())) {
            messageService.send(player, PlayerMsgId.P_7032);
            player.closeInventory();
            return;
        }
        List<DungeonRewardEntry> rewards = session.rewardsByPlayer.get(player.getUniqueId());
        if (rewards == null) {
            messageService.send(player, PlayerMsgId.P_7032);
            player.closeInventory();
            return;
        }
        if (slot == DungeonRewardGui.PREVIOUS_SLOT) {
            openRewardGui(session, player, Math.max(0, page - 1));
            return;
        }
        if (slot == DungeonRewardGui.NEXT_SLOT) {
            openRewardGui(session, player, page + 1);
            return;
        }
        if (slot < 0 || slot >= DungeonRewardGui.CONTENT_SIZE) return;
        if (expectedClaimId == null) return;
        int index = findRewardIndex(rewards, expectedClaimId);
        if (index < 0) return;
        DungeonRewardEntry reward = rewards.get(index);
        ItemModel model = itemService.findLoadedById(reward.itemId());
        if (model == null) model = itemService.loadItem(reward.itemId());
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (model == null || astPlayer == null) {
            messageService.send(player, PlayerMsgId.P_7032);
            return;
        }
        int granted = inventoryService.addItemToNormalInventory(astPlayer, model, reward.amount(), REWARD_SOURCE);
        if (granted <= 0) {
            messageService.send(player, PlayerMsgId.P_7031);
            return;
        }
        if (granted >= reward.amount()) rewards.remove(index);
        else rewards.set(index, reward.withAmount(reward.amount() - granted));
        openRewardGui(session, player, page);
    }

    static boolean canRejoinParticipant(
            @NotNull Collection<UUID> originalParticipants,
            @NotNull Collection<UUID> gateReturnEligible,
            @NotNull UUID playerId
    ) {
        return originalParticipants.contains(playerId) && gateReturnEligible.contains(playerId);
    }

    static @NotNull List<DungeonRewardEntry> createRewardEntries(
            @NotNull Collection<MobDropResultItem> rolledItems
    ) {
        return rolledItems.stream()
                .sorted(Comparator.comparingDouble(MobDropResultItem::dropRate))
                .map(item -> new DungeonRewardEntry(
                        UUID.randomUUID(), item.itemId(), item.amount(), item.dropRate()))
                .toList();
    }

    static int findRewardIndex(
            @NotNull List<DungeonRewardEntry> rewards,
            @NotNull UUID expectedClaimId
    ) {
        for (int index = 0; index < rewards.size(); index++) {
            if (rewards.get(index).claimId().equals(expectedClaimId)) return index;
        }
        return -1;
    }

    /** @return 指定 Mob が進行中ダンジョンに紐付く場合 {@code true} */
    public boolean isDungeonMob(@NotNull UUID mobInstanceId) {
        return mobBindings.containsKey(mobInstanceId);
    }

    /**
     * Mob 死亡確定時に同一インスタンス内へいる現在参加者だけを固定受取人として返します。
     *
     * @param mobInstanceId ダンジョン Mob UUID
     * @return 固定報酬受取人
     */
    public @NotNull List<AstPlayer> resolveMobRewardRecipients(@NotNull UUID mobInstanceId) {
        MobBinding binding = mobBindings.get(mobInstanceId);
        Session session = binding == null ? null : sessionsById.get(binding.sessionId());
        if (session == null || session.ending) return List.of();
        List<AstPlayer> result = new ArrayList<>();
        for (Player player : activePlayersInWorld(session)) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) result.add(astPlayer);
        }
        return List.copyOf(result);
    }

    /**
     * ダンジョン参加者の死亡を共有回数へ記録し、許容回数内なら開始地点へ復帰させます。
     *
     * @param astPlayer 死亡プレイヤー
     * @param deathLocation 死亡地点
     * @return ダンジョン死亡として処理した場合 {@code true}
     */
    public boolean handleParticipantDeath(@NotNull AstPlayer astPlayer, @NotNull Location deathLocation) {
        UUID playerId = astPlayer.getBukkit().getUniqueId();
        UUID sessionId = sessionIdByParticipant.get(playerId);
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending || session.instanceWorld == null
                || deathLocation.getWorld() == null
                || !deathLocation.getWorld().getUID().equals(session.instanceWorld.world().getUID())) return false;
        if (playerDeathService.isDead(playerId)) return true;
        if (session.cleared) {
            boolean started = playerDeathService.startDeath(
                    astPlayer,
                    deathLocation,
                    session.loaded.definition().challenge().reviveDelaySeconds() * 1_000L,
                    false,
                    () -> reviveParticipant(session.id, playerId)
            );
            if (started) registerDungeonDeath(session, playerId);
            return true;
        }
        session.deathCount++;
        session.deathsByPlayer.merge(playerId, 1, Integer::sum);
        boolean started = playerDeathService.startDeath(
                astPlayer,
                deathLocation,
                session.loaded.definition().challenge().reviveDelaySeconds() * 1_000L,
                false,
                () -> reviveParticipant(session.id, playerId)
        );
        if (!started) return true;
        registerDungeonDeath(session, playerId);
        int limit = session.loaded.definition().challenge().deathLimit();
        if (ChallengeDeathPolicy.isExceeded(session.deathCount, limit)) {
            message(session.participants, PlayerMsgId.P_7027, session.deathCount, limit);
            completeSession(session, EndReason.DEATH_LIMIT, false);
        } else {
            messageService.send(astPlayer, PlayerMsgId.P_7026,
                    session.loaded.definition().challenge().reviveDelaySeconds(), session.deathCount, limit);
        }
        return true;
    }

    /**
     * Dungeon死亡復帰callbackを再検証し、同じ稼働セッションの現在参加者をSTART地点へ戻します。
     *
     * @param sessionId 死亡を受理したDungeonセッションID
     * @param playerId 復帰対象プレイヤーUUID
     */
    private void reviveParticipant(@NotNull UUID sessionId, @NotNull UUID playerId) {
        Session session = sessionsById.get(sessionId);
        if (session != null) session.dungeonDeathParticipants.remove(playerId);
        dungeonDeathSessionByParticipant.remove(playerId, sessionId);
        Player player = Bukkit.getPlayer(playerId);
        if (session == null || session.instanceWorld == null
                || !canRunDungeonRecoveryCallback(
                        sessionId,
                        sessionsById.get(sessionId) == session ? session.id : null,
                        session.ending,
                        session.participants,
                        playerId
                )
                || player == null || !player.isOnline()) return;
        DungeonBlockPlan.Position spawn = session.blockPlan.playerSpawn();
        Location target = new Location(session.instanceWorld.world(), spawn.x() + 0.5D, spawn.y(), spawn.z() + 0.5D);
        long transferGeneration = session.transferGeneration;
        CompletableFuture<Boolean> transfer = trackEntryTransfer(
                session, playerId, worldService.teleportPlayerAsync(player, target, null));
        transfer.whenComplete((success, failure) -> runMain(() -> {
            if (isActiveTransferCallback(session, transferGeneration)
                    && (failure != null || !Boolean.TRUE.equals(success))) {
                completeSession(session, EndReason.TRANSFER_FAILED, false);
            }
        }));
    }

    /** @return 現在のダンジョン Sidebar 情報。参加中でなければ {@code null} */
    public @Nullable DungeonSidebarInfo findSidebarInfo(@NotNull UUID playerId) {
        UUID sessionId = sessionIdByParticipant.get(playerId);
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending || session.layout == null) return null;
        int clearedRooms = (int) session.roomStates.values().stream().filter(state -> state == RoomState.CLEARED).count();
        List<String> names = activePlayersInWorld(session).stream().map(Player::getName).toList();
        long remaining = session.cleared
                ? Math.max(0L, (session.clearReturnEndsAtMs - System.currentTimeMillis() + 999L) / 1_000L)
                : -1L;
        return new DungeonSidebarInfo(
                session.loaded.definition().displayName(), session.deathCount,
                session.loaded.definition().challenge().deathLimit(), clearedRooms,
                session.layout.rooms().size(), names, remaining
        );
    }

    /**
     * 帰還ゲート内のスニークを処理し、受付へ戻した上で再参加権を付与します。
     *
     * @param player 操作プレイヤー
     * @return 帰還処理を受理した場合 {@code true}
     */
    public boolean handleReturnGateSneak(@NotNull Player player) {
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session == null || session.ending || session.returnGateLocation == null
                || !player.getWorld().getUID().equals(session.returnGateLocation.getWorld().getUID())
                || player.getLocation().distanceSquared(session.returnGateLocation) > RETURN_GATE_RADIUS_SQUARED) {
            return false;
        }
        requestParticipantLeave(session, player, true);
        return true;
    }

    /** @return 帰還ゲート内にいる参加者のセッション ID。対象外なら {@code null} */
    public @Nullable UUID findReturnGate(@NotNull Player player) {
        UUID sessionId = sessionIdByParticipant.get(player.getUniqueId());
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        return session != null && !session.ending && session.returnGateLocation != null
                && player.getWorld().getUID().equals(session.returnGateLocation.getWorld().getUID())
                && player.getLocation().distanceSquared(session.returnGateLocation) <= RETURN_GATE_RADIUS_SQUARED
                ? session.id : null;
    }

    /** ログアウトした参加者をセッションから外します。 */
    public void handleQuit(@NotNull UUID playerId) {
        UUID sessionId = sessionIdByParticipant.get(playerId);
        Session session = sessionId == null ? null : sessionsById.get(sessionId);
        if (session != null && !session.ending) {
            finalizeParticipantRemoval(session, playerId, false);
        }
        for (Session active : sessionsById.values()) {
            if (active.gateReturnEligible.remove(playerId)) {
                recoverDungeonDeath(active, playerId);
                releaseBusyParticipantWhenTransfersSettle(active, playerId);
            }
        }
    }

    private void requestParticipantLeave(
            @NotNull Session session,
            @NotNull Player player,
            boolean rejoinEligible
    ) {
        UUID playerId = player.getUniqueId();
        if (!session.departingParticipants.add(playerId)) {
            return;
        }
        CompletableFuture<Boolean> entryTransfer = session.entryTransfers.get(playerId);
        if (entryTransfer == null) {
            beginParticipantDeparture(session, player, rejoinEligible);
            return;
        }
        entryTransfer.whenComplete((ignored, failure) -> runMain(() ->
                beginParticipantDeparture(session, player, rejoinEligible)));
    }

    private void beginParticipantDeparture(
            @NotNull Session session,
            @NotNull Player player,
            boolean rejoinEligible
    ) {
        UUID playerId = player.getUniqueId();
        if (!isCurrentParticipant(session, playerId)) {
            return;
        }
        World instance = session.instanceWorld == null ? null : session.instanceWorld.world();
        if (!player.isOnline()) {
            finalizeParticipantRemoval(session, playerId, false);
            return;
        }

        World entryWorld = worldService.resolveLoadedWorld(session.loaded.entryWorldData());
        Location target = entryWorld == null
                ? resolveReturnLocation(session.returnLocations.get(playerId), instance)
                : entryLocation(session.loaded.definition().entry(), entryWorld);
        if (target == null) {
            session.departingParticipants.remove(playerId);
            messageService.send(player, PlayerMsgId.P_7017);
            return;
        }

        long transferGeneration = session.transferGeneration;
        CompletableFuture<Boolean> returnTransfer = trackReturnTransfer(
                session,
                playerId,
                worldService.teleportPlayerAsync(player, target, null)
        );
        returnTransfer
                .whenComplete((success, failure) -> runMain(() -> {
                    if (!isActiveTransferCallback(session, transferGeneration)
                            || !isCurrentParticipant(session, playerId)) {
                        return;
                    }
                    boolean reachedReturnWorld = player.isOnline()
                            && target.getWorld() != null
                            && player.getWorld().getUID().equals(target.getWorld().getUID());
                    if ((failure == null && Boolean.TRUE.equals(success)) || reachedReturnWorld) {
                        finalizeParticipantRemoval(session, playerId, rejoinEligible);
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

    private void finalizeParticipantRemoval(
            @NotNull Session session,
            @NotNull UUID playerId,
            boolean rejoinEligible
    ) {
        if (!session.participants.remove(playerId)) {
            return;
        }
        session.departingParticipants.remove(playerId);
        sessionIdByParticipant.remove(playerId, session.id);
        recoverDungeonDeath(session, playerId);
        if (rejoinEligible && !session.ending) {
            session.gateReturnEligible.add(playerId);
        } else {
            session.gateReturnEligible.remove(playerId);
        }
        if (session.participants.isEmpty()) {
            completeSession(session, EndReason.EMPTY, false);
        } else if (!rejoinEligible) {
            releaseBusyParticipantWhenTransfersSettle(session, playerId);
        }
    }

    private @NotNull CompletableFuture<Boolean> trackEntryTransfer(
            @NotNull Session session,
            @NotNull UUID playerId,
            @NotNull CompletableFuture<Boolean> transfer
    ) {
        session.entryTransfers.put(playerId, transfer);
        transfer.whenComplete((ignored, failure) -> runMain(() ->
                session.entryTransfers.remove(playerId, transfer)));
        return transfer;
    }

    private @NotNull CompletableFuture<Boolean> trackReturnTransfer(
            @NotNull Session session,
            @NotNull UUID playerId,
            @NotNull CompletableFuture<Boolean> transfer
    ) {
        session.returnTransfers.put(playerId, transfer);
        transfer.whenComplete((ignored, failure) -> runMain(() ->
                session.returnTransfers.remove(playerId, transfer)));
        return transfer;
    }

    private boolean isActiveTransferCallback(@NotNull Session session, long transferGeneration) {
        Session indexed = sessionsById.get(session.id);
        return isTransferCallbackCurrent(
                session.id,
                indexed == session ? session.id : null,
                transferGeneration,
                session.transferGeneration,
                session.ending,
                false
        );
    }

    private boolean isEndingTransferCallback(@NotNull Session session, long transferGeneration) {
        Session indexed = sessionsById.get(session.id);
        return isTransferCallbackCurrent(
                session.id,
                indexed == session ? session.id : null,
                transferGeneration,
                session.transferGeneration,
                session.ending,
                true
        );
    }

    static boolean isTransferCallbackCurrent(
            @NotNull UUID expectedSessionId,
            @Nullable UUID indexedSessionId,
            long expectedGeneration,
            long currentGeneration,
            boolean ending,
            boolean endingCallback
    ) {
        return expectedSessionId.equals(indexedSessionId)
                && expectedGeneration == currentGeneration
                && ending == endingCallback;
    }

    private void releaseBusyParticipantWhenTransfersSettle(
            @NotNull Session session,
            @NotNull UUID playerId
    ) {
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>(2);
        CompletableFuture<Boolean> entryTransfer = session.entryTransfers.get(playerId);
        if (entryTransfer != null) transfers.add(entryTransfer);
        CompletableFuture<Boolean> returnTransfer = session.returnTransfers.get(playerId);
        if (returnTransfer != null) transfers.add(returnTransfer);
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    if (!session.ending
                            && !session.participants.contains(playerId)
                            && !session.gateReturnEligible.contains(playerId)) {
                        sessionIdByBusyParticipant.remove(playerId, session.id);
                    }
                }));
    }

    private void registerDungeonDeath(@NotNull Session session, @NotNull UUID playerId) {
        session.dungeonDeathParticipants.add(playerId);
        dungeonDeathSessionByParticipant.put(playerId, session.id);
    }

    private void recoverDungeonDeath(@NotNull Session session, @NotNull UUID playerId) {
        session.dungeonDeathParticipants.remove(playerId);
        recoverOwnedDungeonDeath(
                dungeonDeathSessionByParticipant,
                session.id,
                playerId,
                ignored -> playerDeathService.recoverNow(playerId)
        );
    }

    static boolean recoverOwnedDungeonDeath(
            @NotNull Map<UUID, UUID> ownershipByPlayer,
            @NotNull UUID sessionId,
            @NotNull UUID playerId,
            @NotNull Consumer<UUID> recovery
    ) {
        if (!ownershipByPlayer.remove(playerId, sessionId)) return false;
        recovery.accept(playerId);
        return true;
    }

    /**
     * @return 同じ稼働セッションの現在参加者に対する死亡復帰callbackなら{@code true}
     */
    static boolean canRunDungeonRecoveryCallback(
            @NotNull UUID expectedSessionId,
            @Nullable UUID indexedSessionId,
            boolean ending,
            @NotNull Set<UUID> participants,
            @NotNull UUID playerId
    ) {
        return expectedSessionId.equals(indexedSessionId)
                && !ending
                && participants.contains(playerId);
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
        long endingGeneration = ++session.transferGeneration;
        closeSessionGuis(session);
        cancelSessionTasks(session);
        destroySessionControls(session);
        for (UUID participant : List.copyOf(session.dungeonDeathParticipants)) {
            recoverDungeonDeath(session, participant);
        }
        for (Set<UUID> roomMobs : session.liveMobsByRoom.values()) {
            for (UUID mobId : List.copyOf(roomMobs)) {
                mobBindings.remove(mobId);
                cleanupSafely(session, "mob:" + mobId, () -> mobService.destroy(mobId));
            }
            roomMobs.clear();
        }
        Logger.log(LogId.I_7004, session.id.toString(), session.loaded.definition().id(), reason.name());
        if (!success && reason != EndReason.PREPARATION_FAILED && reason != EndReason.DEATH_LIMIT) {
            message(session.participants, PlayerMsgId.P_7013, session.loaded.definition().displayName());
        }

        List<CompletableFuture<?>> pendingTransfers = new ArrayList<>();
        pendingTransfers.addAll(session.entryTransfers.values());
        pendingTransfers.addAll(session.returnTransfers.values());
        pendingTransfers.add(session.preparationLifecycle);
        CompletableFuture.allOf(pendingTransfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    if (!isEndingTransferCallback(session, endingGeneration)) return;
                    returnParticipantsAndDestroy(session, endingGeneration);
                }));
    }

    /** 保留転送失効後に現在参加者を退避し、一時 World の破棄完了まで索引を保持します。 */
    private void returnParticipantsAndDestroy(@NotNull Session session, long endingGeneration) {
        World instance = session.instanceWorld == null ? null : session.instanceWorld.world();
        List<CompletableFuture<Boolean>> transfers = new ArrayList<>();
        for (UUID participantId : session.participants) {
            Player participant = Bukkit.getPlayer(participantId);
            if (participant == null || !participant.isOnline()) {
                continue;
            }
            Location target = resolveReturnLocation(session.returnLocations.get(participantId), instance);
            CompletableFuture<Boolean> transfer = target == null
                    ? CompletableFuture.completedFuture(false)
                    : worldService.teleportPlayerAsync(participant, target, null);
            transfers.add(trackReturnTransfer(session, participantId, transfer));
        }
        CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, failure) -> runMain(() -> {
                    if (!isEndingTransferCallback(session, endingGeneration)) return;
                    if (instance == null || session.instanceWorld == null) {
                        finishSessionCleanup(session, endingGeneration);
                        return;
                    }
                    evacuateRemainingPlayers(instance);
                    instanceWorldService.destroyAsync(session.instanceWorld)
                            .whenComplete((destroyed, destroyFailure) -> runMain(() -> {
                                if (isEndingTransferCallback(session, endingGeneration)
                                        && destroyFailure == null && Boolean.TRUE.equals(destroyed)) {
                                    finishSessionCleanup(session, endingGeneration);
                                }
                            }));
                }));
    }

    /** 帰還と一時 World 破棄が完了したセッションの reverse index を最後に解放します。 */
    private void finishSessionCleanup(@NotNull Session session, long endingGeneration) {
        if (!isEndingTransferCallback(session, endingGeneration)) return;
        session.transferGeneration++;
        sessionsById.remove(session.id, session);
        sessionIdByPartyKey.remove(session.partyKey, session.id);
        if (session.instanceWorld != null) {
            sessionIdByWorld.remove(session.instanceWorld.world().getUID(), session.id);
        }
        for (UUID participant : session.originalParticipants) {
            sessionIdByParticipant.remove(participant, session.id);
            sessionIdByBusyParticipant.remove(participant, session.id);
            dungeonDeathSessionByParticipant.remove(participant, session.id);
        }
        session.entryTransfers.clear();
        session.returnTransfers.clear();
    }

    private void cancelSessionTasks(@NotNull Session session) {
        if (session.startCountdownTask != null) {
            BukkitTask task = session.startCountdownTask;
            session.startCountdownTask = null;
            cleanupSafely(session, "start_countdown_task", task::cancel);
        }
        if (session.clearReturnTask != null) {
            BukkitTask task = session.clearReturnTask;
            session.clearReturnTask = null;
            cleanupSafely(session, "clear_return_task", task::cancel);
        }
    }

    private void destroySessionControls(@NotNull Session session) {
        DungeonCancelController controller = cancelControllers.remove(session.id);
        if (controller != null) {
            sessionIdByCancelInteraction.remove(controller.interaction().getUniqueId());
            cleanupSafely(session, "cancel_controller", controller::destroy);
        }
        if (session.returnGateDisplay != null) {
            DisplayTextService.ManagedTextDisplay display = session.returnGateDisplay;
            session.returnGateDisplay = null;
            cleanupSafely(session, "return_gate_display", display::destroy);
        }
        if (session.rewardDisplay != null) {
            DisplayTextService.ManagedTextDisplay display = session.rewardDisplay;
            session.rewardDisplay = null;
            cleanupSafely(session, "reward_display", display::destroy);
        }
        if (session.rewardChestLocation != null && session.rewardChestLocation.getWorld() != null) {
            Location chestLocation = session.rewardChestLocation;
            session.rewardChestLocation = null;
            cleanupSafely(session, "reward_chest", () -> chestLocation.getBlock().setType(Material.AIR, false));
        }
        session.rewardsByPlayer.clear();
    }

    private void closeSessionGuis(@NotNull Session session) {
        for (UUID playerId : session.originalParticipants) {
            Player player = Bukkit.getPlayer(playerId);
            if (player == null || !player.isOnline()) continue;
            org.bukkit.inventory.Inventory top = player.getOpenInventory().getTopInventory();
            UUID cancelSessionId = cancelGui.sessionId(top);
            DungeonRewardGui.Holder rewardHolder = rewardGui.holder(top);
            if (session.id.equals(cancelSessionId)
                    || (rewardHolder != null && session.id.equals(rewardHolder.sessionId()))) {
                player.closeInventory();
            }
        }
    }

    private void cleanupSafely(
            @NotNull Session session,
            @NotNull String element,
            @NotNull Runnable cleanup
    ) {
        try {
            cleanup.run();
        } catch (RuntimeException failure) {
            Logger.log(LogId.E_7004, failure, session.id.toString(), element);
        }
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
        clearEntryPromptDisplays();
        for (Session session : List.copyOf(sessionsById.values())) {
            session.ending = true;
            closeSessionGuis(session);
            cancelSessionTasks(session);
            destroySessionControls(session);
            for (Set<UUID> roomMobs : session.liveMobsByRoom.values()) {
                for (UUID mobId : roomMobs) {
                    mobBindings.remove(mobId);
                    cleanupSafely(session, "mob:" + mobId, () -> mobService.destroy(mobId));
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
            for (UUID participantId : List.copyOf(session.dungeonDeathParticipants)) {
                recoverDungeonDeath(session, participantId);
            }
        }
        for (DungeonInstanceWorldService.InstanceWorld instance : instanceWorldService.activeInstances()) {
            evacuateRemainingPlayers(instance.world());
        }
        instanceWorldService.destroyAllNow();
        sessionsById.clear();
        sessionIdByParticipant.clear();
        sessionIdByBusyParticipant.clear();
        sessionIdByWorld.clear();
        sessionIdByPartyKey.clear();
        dungeonDeathSessionByParticipant.clear();
        mobBindings.clear();
        cancelControllers.clear();
        sessionIdByCancelInteraction.clear();
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
            sessionIdByParticipant.remove(participantId, session.id);
            recoverDungeonDeath(session, participantId);
            if (player != null && player.isOnline()) {
                Location target = resolveReturnLocation(session.returnLocations.get(participantId), null);
                if (target != null) {
                    trackReturnTransfer(
                            session,
                            participantId,
                            worldService.teleportPlayerAsync(player, target, null)
                    );
                }
            }
            releaseBusyParticipantWhenTransfersSettle(session, participantId);
        }
    }

    /** ロード済み受付地点のうち、近隣プレイヤーがいる地点へダンジョン専用演出を表示します。 */
    private void tickEntryVisuals() {
        double pulse = 0.08D * Math.sin(entryVisualFrame * 0.35D);
        Set<String> activePromptIds = new HashSet<>();
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
            updateEntryPrompt(loaded, center);
            activePromptIds.add(loaded.definition().id());
        }
        for (String id : List.copyOf(entryPromptDisplays.keySet())) {
            if (!activePromptIds.contains(id)) {
                entryPromptDisplays.remove(id).destroy();
            }
        }
        for (Session session : sessionsById.values()) {
            if (session.ending || session.returnGateLocation == null || session.instanceWorld == null
                    || session.instanceWorld.world().getPlayers().isEmpty()) continue;
            particleDisplayService.spawnForNearbyViewers(
                    session.returnGateLocation.clone().add(0.0D, 1.0D, 0.0D),
                    SharedParticleDefinitions.DUNGEON_ENTRY_PORTAL
            );
        }
        entryVisualFrame++;
    }

    private void updateEntryPrompt(@NotNull LoadedDefinition loaded, @NotNull Location center) {
        String id = loaded.definition().id();
        String text = PlayerMsgResource.format(PlayerMsgId.P_7035.getId(), loaded.definition().displayName());
        if (entryPromptDisplays.containsKey(id)) return;
        entryPromptDisplays.put(id, displayTextService.create(
                DisplayAnchor.fixed(center.clone().add(0.0D, 2.8D, 0.0D)),
                DisplayTextOptions.defaults(text).withLineWidth(300).withViewRange(48.0F).withShadowed(true)
        ));
    }

    private void clearEntryPromptDisplays() {
        for (DisplayTextService.ManagedTextDisplay display : entryPromptDisplays.values()) display.destroy();
        entryPromptDisplays.clear();
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
        REJOINED,
        ALREADY_IN_PROGRESS,
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

    /** 中止確認 GUI の確定結果です。 */
    public enum CancelResult {
        CANCELLED,
        NO_SESSION,
        NOT_LEADER
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
        DEATH_LIMIT,
        CANCELLED,
        PREPARATION_FAILED,
        PARTICIPANT_REQUIREMENT_NOT_MET,
        TRANSFER_FAILED,
        SPAWN_FAILED
    }

    private static final class Session {
        private final UUID id;
        private final long seed;
        private final LoadedDefinition loaded;
        private final String partyKey;
        private final UUID initiatorId;
        private final LinkedHashSet<UUID> originalParticipants;
        private final LinkedHashSet<UUID> participants;
        private final Set<UUID> gateReturnEligible = new LinkedHashSet<>();
        private final Map<UUID, Location> returnLocations;
        private final Map<UUID, CompletableFuture<Boolean>> entryTransfers = new HashMap<>();
        private final Map<UUID, CompletableFuture<Boolean>> returnTransfers = new HashMap<>();
        private CompletableFuture<Void> preparationLifecycle = CompletableFuture.completedFuture(null);
        private final Set<UUID> departingParticipants = new LinkedHashSet<>();
        private final Map<Integer, RoomState> roomStates = new LinkedHashMap<>();
        private final Map<Integer, Set<UUID>> liveMobsByRoom = new LinkedHashMap<>();
        private DungeonLayout layout;
        private DungeonBlockPlan blockPlan;
        private DungeonInstanceWorldService.InstanceWorld instanceWorld;
        private Location returnGateLocation;
        private Location rewardChestLocation;
        private DisplayTextService.ManagedTextDisplay returnGateDisplay;
        private DisplayTextService.ManagedTextDisplay rewardDisplay;
        private BukkitTask startCountdownTask;
        private BukkitTask clearReturnTask;
        private long clearReturnEndsAtMs;
        private final Map<UUID, Integer> deathsByPlayer = new HashMap<>();
        private final Set<UUID> dungeonDeathParticipants = new LinkedHashSet<>();
        private final Map<UUID, List<DungeonRewardEntry>> rewardsByPlayer = new HashMap<>();
        private int deathCount;
        private boolean combatStarted;
        private boolean cleared;
        private boolean ending;
        private long transferGeneration = 1L;

        private Session(
                @NotNull UUID id,
                long seed,
                @NotNull LoadedDefinition loaded,
                @NotNull String partyKey,
                @NotNull UUID initiatorId,
                @NotNull LinkedHashSet<UUID> participants,
                @NotNull Map<UUID, Location> returnLocations
        ) {
            this.id = id;
            this.seed = seed;
            this.loaded = loaded;
            this.partyKey = partyKey;
            this.initiatorId = initiatorId;
            this.originalParticipants = new LinkedHashSet<>(participants);
            this.participants = participants;
            this.returnLocations = returnLocations;
        }
    }

    private record PreparedPlan(@NotNull DungeonLayout layout, @NotNull DungeonBlockPlan blocks) {
    }

    private record MobBinding(@NotNull UUID sessionId, int roomId) {
    }
}
