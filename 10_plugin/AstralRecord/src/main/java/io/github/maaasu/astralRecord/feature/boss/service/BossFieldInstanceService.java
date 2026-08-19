package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossFieldInstance;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.model.WorldType;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.FileVisitResult;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Creates and destroys per-challenge boss field worlds.
 */
public final class BossFieldInstanceService {
    private static final long FAILED_FIELD_CLEANUP_RETRY_TICKS = 20L;
    private static final long WORLD_LOAD_SLOT_RELEASE_DELAY_TICKS = 1L;

    private final AstralRecord plugin;
    private final WorldService worldService;
    private final Map<UUID, PendingFieldCreation> pendingCreations = new ConcurrentHashMap<>();
    private final Map<UUID, List<Chunk>> startupChunkTicketsByChallengeId = new ConcurrentHashMap<>();
    private final AtomicBoolean worldLoadSlotInUse = new AtomicBoolean(false);

    public BossFieldInstanceService(@NotNull AstralRecord plugin, @NotNull WorldService worldService) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * パス検証・ディレクトリ作成・ボスフィールドコピーを非同期で行い、
     * Bukkit ワールドロードだけメインスレッドへ戻した後、必要チャンクを非同期準備します。
     *
     * @param challenge challenge runtime state
     * @param worldData field world master data
     * @return loaded field instance を返す Future
     */
    public @NotNull CompletableFuture<BossFieldInstance> createFieldAsync(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData
    ) {
        CompletableFuture<BossFieldInstance> result = new CompletableFuture<>();
        PendingFieldCreation pending = new PendingFieldCreation(new AtomicBoolean(false));
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        pendingCreations.put(challenge.challengeId(), pending);
        result.whenComplete((ignored, throwable) -> pendingCreations.remove(challenge.challengeId(), pending));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            PreparedField prepared = null;
            try {
                prepared = prepareField(challenge, worldData, worldContainer);
                copyDirectory(prepared.source(), prepared.target(), pending.cancelled());
            } catch (Throwable ex) {
                if (prepared != null) {
                    completeAfterPreparedTargetCleanup(prepared.target(), ex, result);
                } else {
                    result.completeExceptionally(ex);
                }
                return;
            }
            if (pending.cancelled().get() || !plugin.isEnabled()) {
                completeAfterPreparedTargetCleanup(
                        prepared.target(),
                        new CancellationException("Boss field creation was cancelled"),
                        result
                );
                return;
            }
            try {
                PreparedField completedPreparation = prepared;
                Bukkit.getScheduler().runTask(
                        plugin,
                        () -> loadPreparedFieldWhenSafe(challenge, worldData, completedPreparation, pending, result)
                );
            } catch (RuntimeException ex) {
                completeAfterPreparedTargetCleanup(prepared.target(), ex, result);
            }
        });
        return result;
    }

    /**
     * 準備中のフィールドコピーをキャンセルし、作成途中フォルダの回収を要求します。
     */
    public void cancelPendingCreations() {
        for (PendingFieldCreation pending : pendingCreations.values()) {
            pending.cancelled().set(true);
        }
    }

    /**
     * 指定した挑戦のフィールド準備をキャンセルします。
     * コピー中の場合はファイル単位で停止し、ロード後の場合は準備完了時に回収します。
     *
     * @param challengeId キャンセルする挑戦 ID
     */
    public void cancelPendingCreation(@NotNull UUID challengeId) {
        PendingFieldCreation pending = pendingCreations.get(challengeId);
        if (pending != null) {
            pending.cancelled().set(true);
        }
    }

    /**
     * 起動時に BOSS_FIELD の instanceRootPath 直下へ残ったインスタンスを非同期削除します。
     *
     * @return 削除完了した残存フォルダ数を返す Future
     */
    public @NotNull CompletableFuture<Integer> cleanupStaleFieldsAsync() {
        Set<Path> roots = worldService.getAll().stream()
                .filter(data -> data.worldType() == WorldType.BOSS_FIELD && data.instanceEnabled())
                .map(data -> resolvePath(data.instanceRootPath()).toAbsolutePath().normalize())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Path> candidates = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> children = Files.list(root)) {
                children.filter(Files::isDirectory)
                        .map(path -> path.toAbsolutePath().normalize())
                        .filter(path -> path.startsWith(root))
                        .forEach(candidates::add);
            } catch (IOException ex) {
                Logger.log(LogId.E_6502, ex, root.toString());
            }
        }
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        candidates.removeIf(candidate -> {
            World loaded = Bukkit.getWorlds().stream()
                    .filter(world -> world.getWorldFolder().toPath().toAbsolutePath().normalize().equals(candidate))
                    .findFirst()
                    .orElse(null);
            if (loaded == null) {
                return false;
            }
            if (!loaded.getPlayers().isEmpty() || !Bukkit.unloadWorld(loaded, false)) {
                Logger.log(LogId.E_6503, loaded.getName(), candidate.toString());
                return true;
            }
            return false;
        });
        if (candidates.isEmpty()) {
            return CompletableFuture.completedFuture(0);
        }

        CompletableFuture<Integer> result = new CompletableFuture<>();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            int deleted = 0;
            for (Path candidate : candidates) {
                try {
                    deleteDirectory(candidate);
                    deleted++;
                } catch (IOException ex) {
                    Logger.log(LogId.E_6502, ex, candidate.toString());
                }
            }
            result.complete(deleted);
        });
        return result;
    }

    /**
     * 非同期タスク上でコピー元とコピー先を検証し、コピー先ディレクトリを準備します。
     *
     * @param challenge 対象挑戦
     * @param worldData ボスフィールドのマスタ
     * @param worldContainer 呼び出し元メインスレッドで取得済みのワールドコンテナ
     * @return 検証済みのコピー元・コピー先
     * @throws IOException パス検証またはディレクトリ作成に失敗した場合
     */
    private @NotNull PreparedField prepareField(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData,
            @NotNull Path worldContainer
    ) throws IOException {
        Path root = resolvePath(worldData.instanceRootPath(), worldContainer);
        Files.createDirectories(root);

        String worldName = sanitize(worldData.id()) + "_" + challenge.challengeId();
        Path target = root.resolve(worldName).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IOException("Boss field target escaped instance root: " + target);
        }
        if (Files.exists(target)) {
            throw new IOException("Boss field target already exists: " + target);
        }

        Path source = resolvePath(worldData.baseWorldPath(), worldContainer);
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("level.dat"))) {
            Logger.log(LogId.W_6501, worldData.id(), source.toString());
            throw new IOException("Boss field base world folder is missing or invalid: " + source);
        }
        return new PreparedField(source, target);
    }

    /**
     * ワールド tick 外のメインスレッドで一時ワールドをロードし、必要チャンク準備へ進めます。
     *
     * @param challenge 対象挑戦
     * @param worldData ボスフィールドのマスタ
     * @param prepared コピー済みフィールド情報
     * @param pending キャンセル状態
     * @param result フィールド準備結果
     */
    private void loadPreparedFieldWhenSafe(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData,
            @NotNull PreparedField prepared,
            @NotNull PendingFieldCreation pending,
            @NotNull CompletableFuture<BossFieldInstance> result
    ) {
        if (pending.cancelled().get() || !plugin.isEnabled()) {
            completeAfterPreparedTargetCleanup(
                    prepared.target(),
                    new CancellationException("Boss field creation was cancelled"),
                    result
            );
            return;
        }
        if (Bukkit.isTickingWorlds() || !worldLoadSlotInUse.compareAndSet(false, true)) {
            try {
                Bukkit.getScheduler().runTask(
                        plugin,
                        () -> loadPreparedFieldWhenSafe(challenge, worldData, prepared, pending, result)
                );
            } catch (RuntimeException ex) {
                completeAfterPreparedTargetCleanup(prepared.target(), ex, result);
            }
            return;
        }

        BossFieldInstance field;
        try {
            field = loadPreparedField(challenge, worldData, prepared.target());
        } catch (Throwable ex) {
            if (ex instanceof LoadedWorldRetainedException retainedException) {
                cleanupFailedPreparedFieldUntilDone(retainedException.field(), ex, result);
            } else {
                completeAfterPreparedTargetCleanup(prepared.target(), ex, result);
            }
            return;
        } finally {
            releaseWorldLoadSlotNextTick();
        }

        List<Location> requiredLocations = List.of(
                challenge.config().playerSpawnLocation().toLocation(field.world()),
                challenge.config().bossSpawnLocation().toLocation(field.world())
        );
        prepareRequiredChunksAsync(
                challenge.challengeId(),
                field.world(),
                requiredLocations,
                pending.cancelled()
        ).whenComplete((ignored, throwable) -> {
            Runnable completion = () -> {
                if (throwable == null && !pending.cancelled().get() && plugin.isEnabled()) {
                    result.complete(field);
                    return;
                }

                Throwable failure = throwable == null
                        ? new CancellationException("Boss field creation was cancelled")
                        : throwable;
                releaseStartupChunkTickets(challenge.challengeId());
                cleanupFailedPreparedFieldUntilDone(field, failure, result);
            };
            if (!runOnMainThread(completion)) {
                Throwable failure = throwable == null
                        ? new CancellationException("Boss field creation was cancelled")
                        : throwable;
                releaseStartupChunkTickets(challenge.challengeId());
                cleanupFailedPreparedFieldUntilDone(field, failure, result);
            }
        });
    }

    /**
     * 準備失敗後のロード済みフィールドを、アンロードとフォルダ削除が完了するまで保持して再試行します。
     *
     * @param field 回収対象フィールド
     * @param failure 元の準備失敗
     * @param result フィールド作成結果
     */
    private void cleanupFailedPreparedFieldUntilDone(
            @NotNull BossFieldInstance field,
            @NotNull Throwable failure,
            @NotNull CompletableFuture<BossFieldInstance> result
    ) {
        destroyFieldAsync(field).whenComplete((destroyed, destroyThrowable) -> {
            if (Boolean.TRUE.equals(destroyed)) {
                result.completeExceptionally(failure);
                return;
            }
            if (!plugin.isEnabled()) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> cleanupFailedPreparedFieldUntilDone(field, failure, result),
                        FAILED_FIELD_CLEANUP_RETRY_TICKS
                );
            } catch (RuntimeException ex) {
                result.completeExceptionally(failure);
            }
        });
    }

    /**
     * 一時ワールド名を予約登録したうえで Bukkit ワールドをロードし、UUID 登録と gamerule 適用を行います。
     *
     * @param challenge 対象挑戦
     * @param worldData ボスフィールドのマスタ
     * @param target コピー済みワールドフォルダ
     * @return ロード済みフィールド
     * @throws IOException Bukkit ワールドをロードできない場合
     */
    private @NotNull BossFieldInstance loadPreparedField(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData,
            @NotNull Path target
    ) throws IOException {
        String worldName = worldCreatorName(target);
        boolean pendingRegistered = false;
        World world = null;
        try {
            worldService.prepareWorldLoad(worldName, worldData);
            pendingRegistered = true;
            world = Bukkit.createWorld(new WorldCreator(worldName));
            if (world == null) {
                throw new IOException("Bukkit could not load boss field world: " + target);
            }
            worldService.registerRuntimeWorld(world, worldData);
            worldService.applyRpgGameRules(world);
            return new BossFieldInstance(challenge.challengeId(), world.getName(), target, world);
        } catch (Throwable ex) {
            if (world != null) {
                BossFieldInstance retainedField = new BossFieldInstance(
                        challenge.challengeId(),
                        world.getName(),
                        target,
                        world
                );
                try {
                    if (Bukkit.unloadWorld(world, false)) {
                        worldService.unregisterRuntimeWorld(world);
                    } else {
                        Logger.log(LogId.E_6503, world.getName(), target.toString());
                        throw new LoadedWorldRetainedException(retainedField, ex);
                    }
                } catch (LoadedWorldRetainedException retainedException) {
                    throw retainedException;
                } catch (RuntimeException unloadException) {
                    Logger.log(LogId.E_6503, unloadException, world.getName(), target.toString());
                    if (unloadException != ex) {
                        unloadException.addSuppressed(ex);
                    }
                    throw new LoadedWorldRetainedException(retainedField, unloadException);
                }
            }
            if (ex instanceof IOException ioException) {
                throw ioException;
            }
            if (ex instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (ex instanceof Error error) {
                throw error;
            }
            throw new IOException("Unexpected boss field load failure", ex);
        } finally {
            if (pendingRegistered) {
                worldService.cancelWorldLoad(worldName, worldData);
            }
        }
    }

    /**
     * 指定地点が属する一意なチャンクを非緊急 Future で準備し、開始完了までチケットを保持します。
     * テストおよび同一パッケージ内の準備処理から利用します。
     *
     * @param challengeId 対象挑戦 ID
     * @param world 対象ワールド
     * @param requiredLocations 準備対象地点
     * @return 全チャンクの準備とチケット保持が完了した Future
     */
    @NotNull
    CompletableFuture<Void> prepareRequiredChunksAsync(
            @NotNull UUID challengeId,
            @NotNull World world,
            @NotNull Collection<Location> requiredLocations
    ) {
        return prepareRequiredChunksAsync(challengeId, world, requiredLocations, new AtomicBoolean(false));
    }

    /**
     * キャンセル状態を監視しながら必要チャンクを準備します。
     *
     * @param challengeId 対象挑戦 ID
     * @param world 対象ワールド
     * @param requiredLocations 準備対象地点
     * @param cancelled キャンセル状態
     * @return 全チャンク準備結果
     */
    private @NotNull CompletableFuture<Void> prepareRequiredChunksAsync(
            @NotNull UUID challengeId,
            @NotNull World world,
            @NotNull Collection<Location> requiredLocations,
            @NotNull AtomicBoolean cancelled
    ) {
        Map<ChunkCoordinate, CompletableFuture<Chunk>> futuresByCoordinate = new LinkedHashMap<>();
        try {
            for (Location location : requiredLocations) {
                if (location.getWorld() == null || !location.getWorld().getUID().equals(world.getUID())) {
                    return CompletableFuture.failedFuture(
                            new IllegalArgumentException("Boss field chunk location belongs to another world")
                    );
                }
                int chunkX = location.getBlockX() >> 4;
                int chunkZ = location.getBlockZ() >> 4;
                ChunkCoordinate coordinate = new ChunkCoordinate(world.getUID(), chunkX, chunkZ);
                if (futuresByCoordinate.containsKey(coordinate)) {
                    continue;
                }
                CompletableFuture<Chunk> future = world.isChunkLoaded(chunkX, chunkZ)
                        ? CompletableFuture.completedFuture(world.getChunkAt(chunkX, chunkZ))
                        : world.getChunkAtAsync(chunkX, chunkZ, true, false);
                futuresByCoordinate.put(coordinate, future);
            }
        } catch (RuntimeException ex) {
            return CompletableFuture.failedFuture(ex);
        }

        List<Chunk> retainedChunks = Collections.synchronizedList(new ArrayList<>());
        List<CompletableFuture<Void>> ticketFutures = new ArrayList<>(futuresByCoordinate.size());
        for (CompletableFuture<Chunk> future : futuresByCoordinate.values()) {
            ticketFutures.add(future.thenAccept(chunk -> {
                if (cancelled.get()) {
                    throw new CancellationException("Boss field chunk preparation was cancelled");
                }
                if (chunk == null) {
                    throw new IllegalStateException("Boss field chunk preparation completed without a chunk");
                }
                if (chunk.addPluginChunkTicket(plugin)) {
                    retainedChunks.add(chunk);
                }
            }));
        }

        CompletableFuture<Void> allPreparedAndTicketed = CompletableFuture.allOf(
                ticketFutures.toArray(CompletableFuture[]::new)
        );
        return allPreparedAndTicketed.handle((ignored, throwable) -> {
            List<Chunk> retainedSnapshot = List.copyOf(retainedChunks);
            if (throwable != null) {
                removeStartupChunkTickets(retainedSnapshot);
                Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                        ? throwable.getCause()
                        : throwable;
                throw new CompletionException(cause);
            }

            List<Chunk> existing = startupChunkTicketsByChallengeId.putIfAbsent(
                    challengeId,
                    retainedSnapshot
            );
            if (existing != null) {
                removeStartupChunkTickets(retainedSnapshot);
                throw new IllegalStateException("Boss field startup chunks are already retained: " + challengeId);
            }
            return null;
        });
    }

    /**
     * フィールド開始準備中だけ保持していたチャンクチケットを解除します。
     *
     * @param challengeId 対象の挑戦 ID
     */
    public void releaseStartupChunkTickets(@NotNull UUID challengeId) {
        List<Chunk> retainedChunks = startupChunkTicketsByChallengeId.remove(challengeId);
        if (retainedChunks == null) {
            return;
        }
        removeStartupChunkTickets(retainedChunks);
    }

    private void removeStartupChunkTickets(@NotNull Collection<Chunk> retainedChunks) {
        for (Chunk chunk : retainedChunks) {
            chunk.removePluginChunkTicket(plugin);
        }
    }

    private void releaseWorldLoadSlotNextTick() {
        try {
            Bukkit.getScheduler().runTaskLater(
                    plugin,
                    () -> worldLoadSlotInUse.set(false),
                    WORLD_LOAD_SLOT_RELEASE_DELAY_TICKS
            );
        } catch (RuntimeException ex) {
            worldLoadSlotInUse.set(false);
        }
    }

    /**
     * 生成済みフィールドの安全なアンロードと非同期フォルダ削除を要求します。
     * plugin 停止中に削除を予約できない場合は次回起動時掃除へ残します。
     *
     * @param field 破棄対象フィールド
     */
    public void destroyField(@NotNull BossFieldInstance field) {
        destroyFieldAsync(field);
    }

    /**
     * Bukkit ワールドをメインスレッドでアンロードし、フォルダ削除を非同期実行します。
     *
     * @param field 破棄するフィールド
     * @return フォルダ削除まで成功した場合に {@code true} を返す Future
     */
    public @NotNull CompletableFuture<Boolean> destroyFieldAsync(@NotNull BossFieldInstance field) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        destroyFieldWhenSafe(field, result);
        return result;
    }

    /**
     * world tick 外のメインスレッドへフィールド破棄を接続します。
     *
     * @param field 破棄対象フィールド
     * @param result 破棄結果
     */
    private void destroyFieldWhenSafe(
            @NotNull BossFieldInstance field,
            @NotNull CompletableFuture<Boolean> result
    ) {
        if (Bukkit.isPrimaryThread() && !Bukkit.isTickingWorlds()) {
            unloadAndDeleteFieldAsync(field, result);
            return;
        }
        if (!plugin.isEnabled()) {
            result.complete(false);
            return;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, () -> destroyFieldWhenSafe(field, result));
        } catch (RuntimeException ex) {
            result.complete(false);
        }
    }

    /**
     * world tick 外でフィールドをアンロードし、成功後にフォルダ削除を非同期実行します。
     *
     * @param field 破棄対象フィールド
     * @param result 破棄結果
     */
    private void unloadAndDeleteFieldAsync(
            @NotNull BossFieldInstance field,
            @NotNull CompletableFuture<Boolean> result
    ) {
        try {
            if (!unloadField(field)) {
                result.complete(false);
                return;
            }
        } catch (RuntimeException ex) {
            Logger.log(LogId.E_6503, ex, field.worldName(), field.worldFolder().toString());
            result.complete(false);
            return;
        }

        if (!plugin.isEnabled()) {
            result.complete(false);
            return;
        }
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    deleteDirectory(field.worldFolder());
                    result.complete(true);
                } catch (IOException ex) {
                    Logger.log(LogId.E_6502, ex, field.worldFolder().toString());
                    result.complete(false);
                }
            });
        } catch (RuntimeException ex) {
            Logger.log(LogId.E_6502, ex, field.worldFolder().toString());
            result.complete(false);
        }
    }

    /**
     * 開始準備用チケットを解除してから一時ワールドをアンロードします。
     * アンロード失敗時は runtime 登録を保持します。
     *
     * @param field 破棄対象フィールド
     * @return アンロード済み、または既に未ロードなら {@code true}
     */
    private boolean unloadField(@NotNull BossFieldInstance field) {
        releaseStartupChunkTickets(field.challengeId());
        World loaded = Bukkit.getWorld(field.world().getUID());
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            Logger.log(LogId.E_6503, field.worldName(), field.worldFolder().toString());
            return false;
        }
        worldService.unregisterRuntimeWorld(field.world());
        return true;
    }

    private @NotNull Path resolvePath(@NotNull String rawPath) {
        return resolvePath(rawPath, Bukkit.getWorldContainer().toPath());
    }

    /**
     * 指定済みワールドコンテナを基準に相対パスを解決します。
     *
     * @param rawPath 解決対象パス
     * @param worldContainer ワールドコンテナ
     * @return 正規化済みパス
     */
    private @NotNull Path resolvePath(@NotNull String rawPath, @NotNull Path worldContainer) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return worldContainer.resolve(path).normalize();
    }

    private @NotNull String worldCreatorName(@NotNull Path worldFolder) {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path absolute = worldFolder.toAbsolutePath().normalize();
        if (absolute.startsWith(container)) {
            return container.relativize(absolute).toString().replace('\\', '/');
        }
        return absolute.toString();
    }

    private @NotNull String sanitize(@NotNull String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private void copyDirectory(
            @NotNull Path source,
            @NotNull Path target,
            @NotNull AtomicBoolean cancelled
    ) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            try {
                stream.forEach(current -> {
                    if (cancelled.get()) {
                        throw new CancellationException("Boss field creation was cancelled");
                    }
                    copyPath(source, target, current);
                });
            } catch (FieldCopyException ex) {
                throw ex.ioCause();
            }
        }
    }

    private void copyPath(@NotNull Path source, @NotNull Path target, @NotNull Path current) {
        try {
            Path relative = source.relativize(current);
            Path destination = target.resolve(relative).normalize();
            if (Files.isDirectory(current)) {
                Files.createDirectories(destination);
                return;
            }
            if (isRuntimeWorldFile(current)) {
                return;
            }
            Files.createDirectories(destination.getParent());
            Files.copy(current, destination);
        } catch (IOException ex) {
            throw new FieldCopyException(ex);
        }
    }

    private boolean isRuntimeWorldFile(@NotNull Path path) {
        String name = path.getFileName().toString();
        return "uid.dat".equalsIgnoreCase(name) || "session.lock".equalsIgnoreCase(name);
    }

    /**
     * コピー先の削除が成功するまで作成結果を完了させず、作成枠を保持します。
     *
     * @param target 削除対象のコピー先
     * @param failure 作成処理の失敗理由
     * @param result 作成処理の結果 Future
     */
    private void completeAfterPreparedTargetCleanup(
            @NotNull Path target,
            @NotNull Throwable failure,
            @NotNull CompletableFuture<BossFieldInstance> result
    ) {
        deletePreparedTargetAsync(target).whenComplete((deleted, cleanupThrowable) -> {
            if (Boolean.TRUE.equals(deleted)) {
                result.completeExceptionally(failure);
                return;
            }
            if (!plugin.isEnabled()) {
                result.completeExceptionally(failure);
                return;
            }
            try {
                Bukkit.getScheduler().runTaskLater(
                        plugin,
                        () -> completeAfterPreparedTargetCleanup(target, failure, result),
                        FAILED_FIELD_CLEANUP_RETRY_TICKS
                );
            } catch (RuntimeException ex) {
                result.completeExceptionally(failure);
            }
        });
    }

    private @NotNull CompletableFuture<Boolean> deletePreparedTargetAsync(@NotNull Path target) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try {
                    deleteDirectory(target);
                    result.complete(true);
                } catch (IOException ex) {
                    Logger.log(LogId.E_6502, ex, target.toString());
                    result.complete(false);
                }
            });
        } catch (RuntimeException ex) {
            Logger.log(LogId.E_6502, ex, target.toString());
            result.complete(false);
        }
        return result;
    }

    static void deleteDirectory(@NotNull Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return;
        }
        Files.walkFileTree(normalized, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException ex) throws IOException {
                if (ex != null) {
                    throw ex;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private record PreparedField(@NotNull Path source, @NotNull Path target) {
    }

    private record PendingFieldCreation(@NotNull AtomicBoolean cancelled) {
    }

    private record ChunkCoordinate(@NotNull UUID worldId, int x, int z) {
    }

    /**
     * 処理をメインスレッドで実行します。
     *
     * @param action 実行処理
     * @return 実行または予約に成功した場合は {@code true}
     */
    private boolean runOnMainThread(@NotNull Runnable action) {
        if (Bukkit.isPrimaryThread()) {
            action.run();
            return true;
        }
        if (!plugin.isEnabled()) {
            return false;
        }
        try {
            Bukkit.getScheduler().runTask(plugin, action);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private static final class FieldCopyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private FieldCopyException(@NotNull IOException cause) {
            super(cause);
        }

        private @NotNull IOException ioCause() {
            return (IOException) super.getCause();
        }
    }

    private static final class LoadedWorldRetainedException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient BossFieldInstance field;

        private LoadedWorldRetainedException(@NotNull BossFieldInstance field, @NotNull Throwable cause) {
            super(cause);
            this.field = field;
        }

        private @NotNull BossFieldInstance field() {
            return field;
        }
    }
}
