package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.dungeon.generation.DungeonVoidChunkGenerator;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonBlockPlan;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonDefinition;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.scheduler.BukkitRunnable;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/** void ワールドの作成、ブロック反映、アンロード、削除を担当します。 */
public final class DungeonInstanceWorldService {
    private static final int BLOCKS_PER_TICK = 8_000;
    private static final long CLEANUP_RETRY_TICKS = 20L;
    private static final String INSTANCE_PREFIX = "dungeon-";

    private final AstralRecord plugin;
    private final WorldService worldService;
    private final Map<UUID, InstanceWorld> trackedInstances = new LinkedHashMap<>();

    public DungeonInstanceWorldService(
            @NotNull AstralRecord plugin,
            @NotNull WorldService worldService
    ) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * メインスレッドで void ワールドを作成し、必要チャンクを準備してからブロックを分割反映します。
     *
     * @param sessionId セッション ID
     * @param definition ダンジョン定義
     * @param worldData DUNGEON World マスタ
     * @param blockPlan ブロック計画
     * @return 完全に生成済みのインスタンスワールド
     */
    public @NotNull CompletableFuture<InstanceWorld> create(
            @NotNull UUID sessionId,
            @NotNull DungeonDefinition definition,
            @NotNull WorldMasterData worldData,
            @NotNull DungeonBlockPlan blockPlan
    ) {
        requireMainThread();
        CompletableFuture<InstanceWorld> result = new CompletableFuture<>();
        Path root = resolveRoot(worldData.instanceRootPath());
        String folderName = INSTANCE_PREFIX + sanitize(definition.id()) + "-"
                + sessionId.toString().replace("-", "");
        Path target = root.resolve(folderName).normalize();
        if (!target.startsWith(root) || target.equals(root)) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Unsafe dungeon instance path"));
        }

        World world = null;
        String worldName = worldCreatorName(target);
        boolean pending = false;
        try {
            Files.createDirectories(root);
            worldService.prepareWorldLoad(worldName, worldData);
            pending = true;
            world = Bukkit.createWorld(new WorldCreator(worldName)
                    .generator(new DungeonVoidChunkGenerator())
                    .generateStructures(false));
            if (world == null) {
                throw new IllegalStateException("Bukkit could not create dungeon world: " + worldName);
            }
            worldService.registerRuntimeWorld(world, worldData);
            worldService.applyRpgGameRules(world);
            world.setAutoSave(false);
            world.setSpawnLocation(
                    blockPlan.playerSpawn().x(),
                    blockPlan.playerSpawn().y(),
                    blockPlan.playerSpawn().z()
            );
            trackedInstances.put(world.getUID(), new InstanceWorld(world, target, Set.of()));
        } catch (Throwable failure) {
            if (world != null) {
                worldService.unregisterRuntimeWorld(world);
                Bukkit.unloadWorld(world, false);
            }
            deleteDirectoryQuietly(target);
            result.completeExceptionally(failure);
            return result;
        } finally {
            if (pending) {
                worldService.cancelWorldLoad(worldName, worldData);
            }
        }

        World createdWorld = world;
        Set<ChunkCoordinate> chunks = requiredChunks(blockPlan);
        List<CompletableFuture<Chunk>> chunkFutures = chunks.stream()
                .map(chunk -> createdWorld.getChunkAtAsync(chunk.x(), chunk.z(), true))
                .toList();
        CompletableFuture.allOf(chunkFutures.toArray(CompletableFuture[]::new))
                .whenComplete((ignored, chunkFailure) -> runMain(() -> {
                    if (chunkFailure != null) {
                        failCreation(createdWorld, target, chunkFailure, result);
                        return;
                    }
                    for (CompletableFuture<Chunk> future : chunkFutures) {
                        future.join().addPluginChunkTicket(plugin);
                    }
                    applyBlocks(createdWorld, target, chunks, blockPlan, result);
                }));
        return result;
    }

    private void applyBlocks(
            @NotNull World world,
            @NotNull Path target,
            @NotNull Set<ChunkCoordinate> chunks,
            @NotNull DungeonBlockPlan blockPlan,
            @NotNull CompletableFuture<InstanceWorld> result
    ) {
        List<DungeonBlockPlan.Placement> placements = blockPlan.placements();
        new BukkitRunnable() {
            private int index;

            @Override
            public void run() {
                try {
                    int end = Math.min(placements.size(), index + BLOCKS_PER_TICK);
                    while (index < end) {
                        apply(world, placements.get(index++));
                    }
                    if (index >= placements.size()) {
                        cancel();
                        InstanceWorld completed = new InstanceWorld(world, target, chunks);
                        trackedInstances.put(world.getUID(), completed);
                        result.complete(completed);
                    }
                } catch (Throwable failure) {
                    cancel();
                    failCreation(world, target, failure, result);
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);
    }

    private void apply(@NotNull World world, @NotNull DungeonBlockPlan.Placement placement) {
        DungeonBlockPlan.Position position = placement.position();
        Block block = world.getBlockAt(position.x(), position.y(), position.z());
        if (placement.stair() == null) {
            block.setType(placement.material(), false);
            return;
        }
        BlockData blockData = placement.material().createBlockData();
        if (!(blockData instanceof Stairs stairs)) {
            throw new IllegalArgumentException("Configured pillar stair is not stairs: " + placement.material());
        }
        stairs.setFacing(BlockFace.valueOf(placement.stair().facing().name()));
        stairs.setHalf(placement.stair().topHalf() ? Bisected.Half.TOP : Bisected.Half.BOTTOM);
        block.setBlockData(stairs, false);
    }

    private void failCreation(
            @NotNull World world,
            @NotNull Path target,
            @NotNull Throwable failure,
            @NotNull CompletableFuture<InstanceWorld> result
    ) {
        releaseTickets(world);
        if (Bukkit.unloadWorld(world, false)) {
            worldService.unregisterRuntimeWorld(world);
            trackedInstances.remove(world.getUID());
            runAsync(() -> deleteDirectoryQuietly(target));
        } else {
            Logger.log(LogId.E_7003, world.getName(), target.toString());
            scheduleDestroyRetry(new InstanceWorld(world, target, Set.of()));
        }
        result.completeExceptionally(failure);
    }

    /**
     * プレイヤー退避後のワールドをアンロードし、フォルダを非同期削除します。
     *
     * @param instance 破棄対象
     * @return 削除結果
     */
    public @NotNull CompletableFuture<Boolean> destroyAsync(@NotNull InstanceWorld instance) {
        requireMainThread();
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        releaseTickets(instance.world());
        World loaded = Bukkit.getWorld(instance.world().getUID());
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            Logger.log(LogId.E_7003, instance.world().getName(), instance.folder().toString());
            scheduleDestroyRetry(instance);
            result.complete(false);
            return result;
        }
        worldService.unregisterRuntimeWorld(instance.world());
        trackedInstances.remove(instance.world().getUID());
        runAsync(() -> {
            try {
                deleteDirectory(instance.folder());
                result.complete(true);
            } catch (IOException ex) {
                Logger.log(LogId.E_7002, ex, instance.folder().toString());
                result.complete(false);
            }
        });
        return result;
    }

    /** プラグイン停止時にスケジューラを使わずワールドとフォルダを回収します。 */
    public void destroyNow(@NotNull InstanceWorld instance) {
        requireMainThread();
        releaseTickets(instance.world());
        World loaded = Bukkit.getWorld(instance.world().getUID());
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            Logger.log(LogId.E_7003, instance.world().getName(), instance.folder().toString());
            return;
        }
        worldService.unregisterRuntimeWorld(instance.world());
        trackedInstances.remove(instance.world().getUID());
        deleteDirectoryQuietly(instance.folder());
    }

    /** 作成開始済みで、まだ回収されていないインスタンス一覧を返します。 */
    public @NotNull List<InstanceWorld> activeInstances() {
        requireMainThread();
        return List.copyOf(trackedInstances.values());
    }

    /** Plugin 停止時に、セッション状態から切り離された準備中・終了中ワールドも回収します。 */
    public void destroyAllNow() {
        requireMainThread();
        for (InstanceWorld instance : List.copyOf(trackedInstances.values())) {
            destroyNow(instance);
        }
    }

    private void scheduleDestroyRetry(@NotNull InstanceWorld instance) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            if (trackedInstances.containsKey(instance.world().getUID())) {
                destroyAsync(instance);
            }
        }, CLEANUP_RETRY_TICKS);
    }

    /**
     * 起動時に、定義された instanceRootPath 直下の本機能生成フォルダだけを回収します。
     *
     * @param worldDefinitions 使用中の DUNGEON World マスタ
     */
    public void cleanupStaleInstances(@NotNull Collection<WorldMasterData> worldDefinitions) {
        requireMainThread();
        Set<Path> stale = new LinkedHashSet<>();
        for (WorldMasterData worldData : worldDefinitions) {
            Path root = resolveRoot(worldData.instanceRootPath());
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> stream = Files.list(root)) {
                stream.filter(Files::isDirectory)
                        .filter(path -> path.getFileName().toString().startsWith(INSTANCE_PREFIX))
                        .forEach(stale::add);
            } catch (IOException ex) {
                Logger.log(LogId.E_7002, ex, root.toString());
            }
        }
        if (stale.isEmpty()) {
            return;
        }
        runAsync(() -> stale.forEach(this::deleteDirectoryQuietly));
    }

    private @NotNull Set<ChunkCoordinate> requiredChunks(@NotNull DungeonBlockPlan plan) {
        Set<ChunkCoordinate> chunks = new LinkedHashSet<>();
        for (DungeonBlockPlan.Placement placement : plan.placements()) {
            chunks.add(new ChunkCoordinate(
                    placement.position().x() >> 4,
                    placement.position().z() >> 4
            ));
        }
        return Set.copyOf(chunks);
    }

    private void releaseTickets(@NotNull World world) {
        for (Chunk chunk : world.getLoadedChunks()) {
            chunk.removePluginChunkTicket(plugin);
        }
    }

    private @NotNull Path resolveRoot(@NotNull String rawPath) {
        Path configured = Path.of(rawPath);
        Path root = configured.isAbsolute()
                ? configured
                : Bukkit.getWorldContainer().toPath().resolve(configured);
        return root.toAbsolutePath().normalize();
    }

    private @NotNull String worldCreatorName(@NotNull Path folder) {
        Path container = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        Path absolute = folder.toAbsolutePath().normalize();
        return absolute.startsWith(container)
                ? container.relativize(absolute).toString().replace('\\', '/')
                : absolute.toString();
    }

    private @NotNull String sanitize(@NotNull String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private void deleteDirectoryQuietly(@NotNull Path directory) {
        try {
            deleteDirectory(directory);
        } catch (IOException ex) {
            Logger.log(LogId.E_7002, ex, directory.toString());
        }
    }

    private void deleteDirectory(@NotNull Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(directory)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private void runMain(@NotNull Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }
        Bukkit.getScheduler().runTask(plugin, action);
    }

    private void runAsync(@NotNull Runnable action) {
        if (!plugin.isEnabled()) {
            action.run();
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, action);
    }

    private void requireMainThread() {
        if (!Bukkit.isPrimaryThread()) {
            throw new IllegalStateException("Dungeon world mutation must run on the main thread");
        }
    }

    /** ロード済み一時ワールドと、その回収情報です。 */
    public record InstanceWorld(
            @NotNull World world,
            @NotNull Path folder,
            @NotNull Set<ChunkCoordinate> chunks
    ) {
        public InstanceWorld {
            chunks = Set.copyOf(chunks);
        }
    }

    /** チャンク座標です。 */
    public record ChunkCoordinate(int x, int z) {
    }
}
