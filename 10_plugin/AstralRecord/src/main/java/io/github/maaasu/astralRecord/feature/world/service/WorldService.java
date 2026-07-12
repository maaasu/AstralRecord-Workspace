package io.github.maaasu.astralRecord.feature.world.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.repository.WorldRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.display.OverheadDisplayService;
import io.github.maaasu.astralRecord.shared.teleport.PlayerTeleportService;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameRules;
import org.bukkit.Location;
import org.bukkit.WorldCreator;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * WorldMasterData をロードし、Plugin 内で保持するサービスです。
 */
public class WorldService {
    private static final String DEFAULT_BOSS_FIELD_DISPLAY_NAME = "ボスフィールド";

    private static final long TELEPORT_PREPARE_DELAY_TICKS = 2L;
    private static final long TELEPORT_RESUME_DELAY_TICKS = 10L;
    private static final Title.Times WORLD_CHANGE_TITLE_TIMES =
            Title.Times.times(Duration.ofMillis(150L), Duration.ofMillis(1800L), Duration.ofMillis(250L));

    private final WorldRepository repository;
    private final Supplier<File> worldContainerSupplier;
    private final Supplier<Collection<org.bukkit.World>> bukkitWorldsSupplier;
    private final Map<String, WorldMasterData> loadedWorlds = new LinkedHashMap<>();
    private final Map<String, org.bukkit.World> resolvedBukkitWorldsById = new LinkedHashMap<>();
    private final Map<UUID, String> worldIdByBukkitWorldId = new LinkedHashMap<>();
    private final Map<UUID, RuntimeWorldRegistration> runtimeWorldByBukkitWorldId = new LinkedHashMap<>();
    private final Map<String, RuntimeWorldRegistration> pendingWorldByName = new LinkedHashMap<>();

    /**
     * WorldService を初期化します。
     *
     * @param repository WorldMasterData リポジトリ
     */
    public WorldService(@NotNull WorldRepository repository) {
        this(repository, Bukkit::getWorldContainer, Bukkit::getWorlds);
    }

    WorldService(
            @NotNull WorldRepository repository,
            @NotNull Supplier<File> worldContainerSupplier
    ) {
        this(repository, worldContainerSupplier, List::of);
    }

    private WorldService(
            @NotNull WorldRepository repository,
            @NotNull Supplier<File> worldContainerSupplier,
            @NotNull Supplier<Collection<org.bukkit.World>> bukkitWorldsSupplier
    ) {
        this.repository = repository;
        this.worldContainerSupplier = worldContainerSupplier;
        this.bukkitWorldsSupplier = bukkitWorldsSupplier;
    }

    /**
     * WorldMasterData を API から全件ロードし、autoLoad が有効なワールドだけ Bukkit ワールドとして読み込みます。
     *
     * @return WorldMasterData のロード件数
     */
    public synchronized int loadAll() {
        List<WorldMasterData> worlds = repository.findAll().stream()
                .sorted(Comparator.comparing(WorldMasterData::id))
                .toList();
        loadedWorlds.clear();
        clearWorldResolutionCaches();
        for (WorldMasterData world : worlds) {
            loadedWorlds.put(world.id(), world);
        }
        Logger.log(LogId.I_5750, loadedWorlds.size());
        loadRegisteredBukkitWorlds(worlds);
        return loadedWorlds.size();
    }

    /**
     * filebase YAML を MasterDataDB に同期してから WorldMasterData を再ロードします。
     *
     * @return ロード件数
     */
    public synchronized int reloadFromYaml() {
        repository.seedMasterData();
        return loadAll();
    }

    /**
     * ロード済み WorldMasterData 一覧を返します。
     *
     * @return WorldMasterData 一覧
     */
    @NotNull
    public synchronized Collection<WorldMasterData> getAll() {
        return List.copyOf(loadedWorlds.values());
    }

    /**
     * 現在ワールドの表示名をタイトル表示します。
     *
     * @param player タイトル表示対象のプレイヤー
     */
    public void showWorldChangeTitle(@NotNull Player player) {
        player.showTitle(Title.title(
                PlayerMsgResource.formatComponent(PlayerMsgId.P_5767.getId(), resolveDisplayName(player.getWorld())),
                PlayerMsgResource.getComponent(PlayerMsgId.P_5768.getId()),
                WORLD_CHANGE_TITLE_TIMES
        ));
    }

    /**
     * 指定 ID の WorldMasterData を返します。
     *
     * @param worldId WorldMasterData ID
     * @return WorldMasterData。未ロード時は {@code null}
     */
    @Nullable
    public synchronized WorldMasterData getById(@NotNull String worldId) {
        return loadedWorlds.get(worldId);
    }

    /**
     * 定義に対応する Bukkit ロード済みワールドを解決します。
     *
     * @param data WorldMasterData
     * @return ロード済みワールド。未ロード時は {@code null}
     */
    @Nullable
    public synchronized org.bukkit.World resolveLoadedWorld(@NotNull WorldMasterData data) {
        org.bukkit.World cached = resolvedBukkitWorldsById.get(data.id());
        if (cached != null) {
            org.bukkit.World stillLoaded = Bukkit.getWorld(cached.getUID());
            if (stillLoaded != null) {
                cacheResolvedWorld(data, stillLoaded);
                return stillLoaded;
            }
            resolvedBukkitWorldsById.remove(data.id());
            worldIdByBukkitWorldId.remove(cached.getUID());
        }

        for (String candidate : baseWorldNameCandidates(data)) {
            org.bukkit.World world = Bukkit.getWorld(candidate);
            if (world != null) {
                cacheResolvedWorld(data, world);
                return world;
            }
        }

        for (File baseWorldFolder : resolveWorldFolderCandidates(normalizeWorldPath(data.baseWorldPath()))) {
            for (org.bukkit.World world : bukkitWorldsSupplier.get()) {
                if (sameNormalizedPath(world.getWorldFolder(), baseWorldFolder)) {
                    cacheResolvedWorld(data, world);
                    return world;
                }
            }
        }
        org.bukkit.World world = Bukkit.getWorld(data.id());
        if (world != null) {
            cacheResolvedWorld(data, world);
        }
        return world;
    }

    /**
     * 定義に対応する Bukkit ワールドを解決し、未ロードであれば baseWorldPath からロードします。
     *
     * @param data WorldMasterData
     * @return ロード済みまたはロードできたワールド。ワールドフォルダが存在しない場合は {@code null}
     */
    @Nullable
    public synchronized org.bukkit.World resolveOrLoadWorld(@NotNull WorldMasterData data) {
        org.bukkit.World loaded = resolveLoadedWorld(data);
        if (loaded != null) {
            return loaded;
        }
        return loadBukkitWorld(data);
    }

    /**
     * Bukkit ワールドから対応する WorldMasterData を解決します。
     *
     * @param world Bukkit ワールド
     * @return 対応する WorldMasterData。未登録時は {@code null}
     */
    @Nullable
    public synchronized WorldMasterData findByBukkitWorld(@NotNull org.bukkit.World world) {
        RuntimeWorldRegistration runtimeRegistration = runtimeWorldByBukkitWorldId.get(world.getUID());
        String worldId = worldIdByBukkitWorldId.get(world.getUID());
        if (worldId == null && runtimeRegistration != null) {
            worldId = runtimeRegistration.worldId();
        }
        if (worldId == null) {
            RuntimeWorldRegistration pendingRegistration = pendingWorldByName.get(
                    normalizeWorldPath(world.getName())
            );
            worldId = pendingRegistration == null ? null : pendingRegistration.worldId();
        }

        WorldMasterData worldData = worldId == null ? null : loadedWorlds.get(worldId);
        if (worldData != null) {
            return worldData;
        }
        worldIdByBukkitWorldId.remove(world.getUID());
        if (runtimeRegistration == null) {
            runtimeWorldByBukkitWorldId.remove(world.getUID());
        }
        return null;
    }

    /**
     * 管理ワールドのロード予定名を登録します。
     * {@link Bukkit#createWorld(WorldCreator)} 中にワールドイベントが発生しても、
     * ファイル探索を行わず対応するマスタを解決できるようにします。
     *
     * @param worldName Bukkit へ渡す一時ワールド名
     * @param worldData 対応する WorldMasterData
     * @throws IllegalStateException 同名ワールドのロード準備が既に進行中の場合
     */
    public synchronized void prepareWorldLoad(
            @NotNull String worldName,
            @NotNull WorldMasterData worldData
    ) {
        String normalizedName = normalizeWorldPath(worldName);
        RuntimeWorldRegistration registration = RuntimeWorldRegistration.from(worldData);
        RuntimeWorldRegistration existingRegistration = pendingWorldByName.putIfAbsent(
                normalizedName,
                registration
        );
        if (existingRegistration != null) {
            throw new IllegalStateException("World load is already pending: " + normalizedName);
        }
    }

    /**
     * ロードに成功した一時生成ワールドを UUID で登録します。
     *
     * @param world 一時生成された Bukkit ワールド
     * @param worldData 対応する WorldMasterData
     */
    public synchronized void registerRuntimeWorld(
            @NotNull org.bukkit.World world,
            @NotNull WorldMasterData worldData
    ) {
        RuntimeWorldRegistration registration = RuntimeWorldRegistration.from(worldData);
        runtimeWorldByBukkitWorldId.put(world.getUID(), registration);
        removePendingWorldRegistration(world.getName(), registration.worldId());
    }

    /**
     * 一時生成ワールドのロード予定名を解除します。
     *
     * @param worldName Bukkit へ渡した一時ワールド名
     * @param worldData 対応する WorldMasterData
     */
    public synchronized void cancelWorldLoad(
            @NotNull String worldName,
            @NotNull WorldMasterData worldData
    ) {
        removePendingWorldRegistration(worldName, worldData.id());
    }

    /**
     * 一時生成ワールドの UUID 登録と残存するロード予定名を解除します。
     *
     * @param world 破棄する一時生成ワールド
     */
    public synchronized void unregisterRuntimeWorld(@NotNull org.bukkit.World world) {
        RuntimeWorldRegistration registration = runtimeWorldByBukkitWorldId.remove(world.getUID());
        if (registration != null) {
            removePendingWorldRegistration(world.getName(), registration.worldId());
        }
    }

    private void removePendingWorldRegistration(@NotNull String worldName, @NotNull String worldId) {
        String normalizedName = normalizeWorldPath(worldName);
        RuntimeWorldRegistration registration = pendingWorldByName.get(normalizedName);
        if (registration != null && registration.worldId().equals(worldId)) {
            pendingWorldByName.remove(normalizedName);
        }
    }

    private void cacheResolvedWorld(@NotNull WorldMasterData data, @NotNull org.bukkit.World world) {
        resolvedBukkitWorldsById.put(data.id(), world);
        worldIdByBukkitWorldId.put(world.getUID(), data.id());
    }

    private void clearWorldResolutionCaches() {
        resolvedBukkitWorldsById.clear();
        worldIdByBukkitWorldId.clear();
    }

    /**
     * WorldMasterData のスポーン地点を Bukkit Location に変換します。
     *
     * @param data WorldMasterData
     * @return スポーン地点。ワールド未ロード時は {@code null}
     */
    @Nullable
    public Location resolveSpawnLocation(@NotNull WorldMasterData data) {
        org.bukkit.World world = resolveLoadedWorld(data);
        if (world == null) {
            return null;
        }

        var spawn = data.spawnLocation();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    /**
     * WorldMasterData のスポーン地点を Bukkit Location に変換します。
     * 対応する Bukkit ワールドが未ロードの場合は、baseWorldPath からオンデマンドでロードします。
     *
     * @param data WorldMasterData
     * @return スポーン地点。ワールドをロードできない場合は {@code null}
     */
    @Nullable
    public Location resolveOrLoadSpawnLocation(@NotNull WorldMasterData data) {
        org.bukkit.World world = resolveOrLoadWorld(data);
        if (world == null) {
            return null;
        }

        var spawn = data.spawnLocation();
        return new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    /**
     * WorldMasterData のスポーン地点へプレイヤーを移動します。
     *
     * @param player 移動対象プレイヤー
     * @param data 移動先 WorldMasterData
     * @return 移動に成功した場合は {@code true}
     */
    public boolean teleportToSpawn(@NotNull org.bukkit.entity.Player player, @NotNull WorldMasterData data) {
        Location spawnLocation = resolveOrLoadSpawnLocation(data);
        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            return false;
        }

        logPlayerTransportState(player);
        OverheadDisplayService overheadDisplayService = suspendOverheadDisplay(player);
        try {
            detachTextDisplayPassengers(player);
            // 異世界移動前に対象チャンクを明示ロードして teleport() の失敗を減らす
            spawnLocation.getChunk().load();
            return PlayerTeleportService.teleport(player, spawnLocation);
        } finally {
            resumeOverheadDisplay(overheadDisplayService, player);
        }
    }

    /**
     * WorldMasterData のスポーン地点へプレイヤーを非同期で移動します。
     *
     * @param player 移動対象プレイヤー
     * @param data 移動先 WorldMasterData
     * @return 移動結果を返す Future
     */
    @NotNull
    public CompletableFuture<Boolean> teleportToSpawnAsync(
            @NotNull org.bukkit.entity.Player player,
            @NotNull WorldMasterData data
    ) {
        return teleportToSpawnAsync(player, data, null);
    }

    @NotNull
    public CompletableFuture<Boolean> teleportToSpawnAsync(
            @NotNull org.bukkit.entity.Player player,
            @NotNull WorldMasterData data,
            @Nullable Runnable onSuccess
    ) {
        Location spawnLocation = resolveOrLoadSpawnLocation(data);
        if (spawnLocation == null || spawnLocation.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        logPlayerTransportState(player);
        org.bukkit.World world = spawnLocation.getWorld();
        int chunkX = spawnLocation.getBlockX() >> 4;
        int chunkZ = spawnLocation.getBlockZ() >> 4;
        Logger.log(LogId.I_5753, data.id(), world.getName(), chunkX, chunkZ);
        return teleportPlayerAsync(player, spawnLocation, onSuccess);
    }

    /**
     * 指定 Location へプレイヤーを非同期転送します。
     * 転送先チャンクが準備済みなら重複先読みを省略し、未準備の場合だけ緊急先読みを行います。
     * Bukkit API のスレッド制約に従い、メインスレッドから呼び出すことを前提とします。
     *
     * @param player 転送対象プレイヤー
     * @param targetLocation 転送先
     * @param onSuccess 転送成功後に実行する処理。不要な場合は {@code null}
     * @return 転送結果を返す Future
     */
    @NotNull
    public CompletableFuture<Boolean> teleportPlayerAsync(
            @NotNull org.bukkit.entity.Player player,
            @NotNull Location targetLocation,
            @Nullable Runnable onSuccess
    ) {
        if (targetLocation.getWorld() == null) {
            return CompletableFuture.completedFuture(false);
        }

        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin == null) {
            return CompletableFuture.completedFuture(false);
        }

        OverheadDisplayService overheadDisplayService = suspendOverheadDisplay(player);
        detachTextDisplayPassengers(player);

        org.bukkit.World targetWorld = targetLocation.getWorld();
        int chunkX = targetLocation.getBlockX() >> 4;
        int chunkZ = targetLocation.getBlockZ() >> 4;
        CompletableFuture<?> chunkPreparation = targetWorld.isChunkLoaded(chunkX, chunkZ)
                ? CompletableFuture.completedFuture(null)
                : targetWorld.getChunkAtAsyncUrgently(targetLocation, true);

        CompletableFuture<Boolean> result = new CompletableFuture<>();
        chunkPreparation.whenComplete((chunk, chunkThrowable) -> {
            if (chunkThrowable != null) {
                scheduleOverheadResume(plugin, overheadDisplayService, player);
                result.complete(false);
                return;
            }

            Bukkit.getScheduler().runTaskLater(plugin, () -> {
                if (!player.isOnline()) {
                    scheduleOverheadResume(plugin, overheadDisplayService, player);
                    result.complete(false);
                    return;
                }

                detachTextDisplayPassengers(player);
                PlayerTeleportService.teleportAsync(player, targetLocation).whenComplete((success, teleportThrowable) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            boolean teleported = teleportThrowable == null && Boolean.TRUE.equals(success);
                            if (teleported && onSuccess != null) {
                                onSuccess.run();
                            }
                            scheduleOverheadResume(plugin, overheadDisplayService, player);
                            result.complete(teleported);
                        })
                );
            }, TELEPORT_PREPARE_DELAY_TICKS);
        });
        return result;
    }

    @Nullable
    private OverheadDisplayService suspendOverheadDisplay(@NotNull org.bukkit.entity.Player player) {
        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin == null) {
            return null;
        }

        OverheadDisplayService overheadDisplayService = plugin.getOverheadDisplayService();
        if (overheadDisplayService != null) {
            overheadDisplayService.suspendPlayerDisplay(player.getUniqueId());
        }
        return overheadDisplayService;
    }

    private void resumeOverheadDisplay(
            @Nullable OverheadDisplayService overheadDisplayService,
            @NotNull org.bukkit.entity.Player player
    ) {
        if (overheadDisplayService != null) {
            overheadDisplayService.resumePlayerDisplay(player.getUniqueId());
        }
    }

    private void scheduleOverheadResume(
            @NotNull AstralRecord plugin,
            @Nullable OverheadDisplayService overheadDisplayService,
            @NotNull org.bukkit.entity.Player player
    ) {
        Bukkit.getScheduler().runTaskLater(
                plugin,
                () -> resumeOverheadDisplay(overheadDisplayService, player),
                TELEPORT_RESUME_DELAY_TICKS
        );
    }

    private void detachTextDisplayPassengers(@NotNull org.bukkit.entity.Player player) {
        for (Entity passenger : new ArrayList<>(player.getPassengers())) {
            if (passenger instanceof TextDisplay) {
                player.removePassenger(passenger);
            }
        }
    }

    private void logPlayerTransportState(@NotNull org.bukkit.entity.Player player) {
        String vehicle = player.getVehicle() == null ? "-" : player.getVehicle().getType().name();
        String passengers = player.getPassengers().isEmpty()
                ? "-"
                : player.getPassengers().stream()
                .map(entity -> entity.getType().name())
                .collect(Collectors.joining(","));
        Logger.log(LogId.I_5754, player.getName(), vehicle, passengers);
    }

    @NotNull
    synchronized String resolveDisplayName(@NotNull org.bukkit.World world) {
        WorldMasterData worldData = findByBukkitWorld(world);
        if (worldData != null && !worldData.displayName().isBlank()) {
            return ColorCodeUtil.toLegacyText(worldData.displayName(), worldData.id());
        }

        RuntimeWorldRegistration registration = runtimeWorldByBukkitWorldId.get(world.getUID());
        if (registration == null) {
            registration = pendingWorldByName.get(normalizeWorldPath(world.getName()));
        }
        if (registration != null) {
            if (!registration.displayName().isBlank()) {
                return ColorCodeUtil.toLegacyText(registration.displayName(), registration.worldId());
            }
            if (registration.worldType() == WorldType.BOSS_FIELD) {
                return DEFAULT_BOSS_FIELD_DISPLAY_NAME;
            }
        }
        return world.getName();
    }

    private void loadRegisteredBukkitWorlds(@NotNull Collection<WorldMasterData> worlds) {
        int loadedCount = 0;
        for (WorldMasterData world : worlds) {
            if (!world.autoLoad()) {
                resolveLoadedWorld(world);
                continue;
            }
            org.bukkit.World loaded = loadBukkitWorld(world);
            if (loaded != null) {
                loadedCount++;
            }
        }
        Logger.log(LogId.I_5751, loadedCount, worlds.size());
    }

    @Nullable
    private org.bukkit.World loadBukkitWorld(@NotNull WorldMasterData data) {
        org.bukkit.World existing = resolveLoadedWorld(data);
        if (existing != null) {
            Logger.log(LogId.D_5751, data.id(), existing.getName());
            applyRpgGameRules(existing);
            return existing;
        }

        String worldName = normalizeWorldPath(data.baseWorldPath());
        if (worldName.isBlank()) {
            Logger.log(LogId.W_5752, data.id(), data.baseWorldPath());
            return null;
        }

        File worldFolder = resolveExistingWorldFolder(worldName);
        if (worldFolder == null) {
            Logger.log(LogId.W_5752, data.id(), worldName);
            return null;
        }

        boolean pendingRegistered = false;
        try {
            prepareWorldLoad(worldName, data);
            pendingRegistered = true;
            org.bukkit.World created = Bukkit.createWorld(new WorldCreator(worldName));
            if (created == null) {
                Logger.log(LogId.W_5752, data.id(), worldName);
                return null;
            }
            cacheResolvedWorld(data, created);
            applyRpgGameRules(created);
            Logger.log(LogId.D_5751, data.id(), created.getName());
            return created;
        } catch (RuntimeException e) {
            Logger.log(LogId.E_5753, e, data.id() + " path=" + worldName);
            return null;
        } finally {
            if (pendingRegistered) {
                cancelWorldLoad(worldName, data);
            }
        }
    }

    @NotNull
    private Set<String> baseWorldNameCandidates(@NotNull WorldMasterData data) {
        Set<String> candidates = new LinkedHashSet<>();
        var normalizedPath = normalizeWorldPath(data.baseWorldPath());
        addCandidate(candidates, normalizedPath);
        addCandidate(candidates, new File(normalizedPath).getName());
        return candidates;
    }

    private static void addCandidate(@NotNull Set<String> candidates, @Nullable String candidate) {
        if (candidate != null && !candidate.isBlank()) {
            candidates.add(candidate);
        }
    }

    @NotNull
    private static String normalizeWorldPath(@NotNull String path) {
        return path.trim()
                .replace('\\', '/')
                .replaceAll("/{2,}", "/")
                .replaceFirst("^\\./", "");
    }

    @NotNull
    private List<File> resolveWorldFolderCandidates(@NotNull String worldName) {
        Set<File> candidates = new LinkedHashSet<>();
        File folder = new File(worldName);
        if (folder.isAbsolute()) {
            candidates.add(folder);
            return List.copyOf(candidates);
        }

        for (File searchRoot : worldSearchRoots()) {
            candidates.add(new File(searchRoot, worldName));
        }

        File worldContainer = worldContainerSupplier.get();
        String normalizedContainer = normalizeWorldPath(worldContainer.getPath());
        String normalizedName = normalizeWorldPath(worldName);
        if (!normalizedContainer.isBlank()
                && normalizedName.startsWith(normalizedContainer + "/")) {
            String relativeName = normalizedName.substring(normalizedContainer.length() + 1);
            candidates.add(new File(worldContainer, relativeName));
        }

        String containerLeaf = worldContainer.getName();
        if (!containerLeaf.isBlank() && normalizedName.startsWith(containerLeaf + "/")) {
            String relativeName = normalizedName.substring(containerLeaf.length() + 1);
            candidates.add(new File(worldContainer, relativeName));
        }

        return List.copyOf(candidates);
    }

    @NotNull
    private List<File> worldSearchRoots() {
        Set<File> roots = new LinkedHashSet<>();
        File worldContainer = worldContainerSupplier.get();
        roots.add(worldContainer);
        roots.add(new File(worldContainer, "worlds"));

        for (org.bukkit.World loadedWorld : bukkitWorldsSupplier.get()) {
            File cursor = loadedWorld.getWorldFolder();
            int depth = 0;
            while (cursor != null && depth < 4) {
                File parent = cursor.getParentFile();
                if (parent == null) {
                    break;
                }
                roots.add(parent);
                cursor = parent;
                depth++;
            }
        }
        return List.copyOf(roots);
    }

    @Nullable
    private File resolveExistingWorldFolder(@NotNull String worldName) {
        for (File candidate : resolveWorldFolderCandidates(worldName)) {
            if (new File(candidate, "level.dat").isFile()) {
                return candidate;
            }
        }
        return null;
    }

    /**
     * ファイルシステムへアクセスせず、絶対正規化パスとして一致するかを返します。
     *
     * @param left 比較元
     * @param right 比較先
     * @return 字句的に同じ絶対パスの場合は {@code true}
     */
    private static boolean sameNormalizedPath(@NotNull File left, @NotNull File right) {
        return left.toPath().toAbsolutePath().normalize()
                .equals(right.toPath().toAbsolutePath().normalize());
    }

    private record RuntimeWorldRegistration(
            @NotNull String worldId,
            @NotNull String displayName,
            @NotNull WorldType worldType
    ) {
        private static @NotNull RuntimeWorldRegistration from(@NotNull WorldMasterData worldData) {
            return new RuntimeWorldRegistration(
                    worldData.id(),
                    worldData.displayName(),
                    worldData.worldType()
            );
        }
    }

    /**
     * RPG マップとして扱う Bukkit ワールドへ標準のゲームルールを適用します。
     *
     * @param world 適用対象の Bukkit ワールド
     */
    public void applyRpgGameRules(@NotNull org.bukkit.World world) {
        world.setGameRule(GameRules.SPAWN_MOBS, false);
        world.setGameRule(GameRules.SPAWN_MONSTERS, false);
        world.setGameRule(GameRules.SPAWN_PATROLS, false);
        world.setGameRule(GameRules.SPAWN_WANDERING_TRADERS, false);
        world.setGameRule(GameRules.SPAWN_WARDENS, false);
        world.setGameRule(GameRules.RAIDS, false);
        world.setGameRule(GameRules.SPAWN_PHANTOMS, false);
        world.setGameRule(GameRules.MOB_GRIEFING, false);
        world.setGameRule(GameRules.MOB_DROPS, false);
        world.setGameRule(GameRules.ENTITY_DROPS, false);
        world.setGameRule(GameRules.FIRE_SPREAD_RADIUS_AROUND_PLAYER, 0);
        world.setGameRule(GameRules.ADVANCE_TIME, false);
        world.setGameRule(GameRules.ADVANCE_WEATHER, false);
        world.setGameRule(GameRules.KEEP_INVENTORY, true);
        world.setGameRule(GameRules.IMMEDIATE_RESPAWN, true);
        world.setGameRule(GameRules.NATURAL_HEALTH_REGENERATION, false);
        world.setGameRule(GameRules.SHOW_ADVANCEMENT_MESSAGES, false);
        world.setGameRule(GameRules.SEND_COMMAND_FEEDBACK, false);
    }
}
