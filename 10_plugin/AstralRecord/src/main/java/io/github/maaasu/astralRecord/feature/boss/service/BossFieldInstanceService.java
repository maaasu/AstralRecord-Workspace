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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * Creates and destroys per-challenge boss field worlds.
 */
public final class BossFieldInstanceService {
    private final AstralRecord plugin;
    private final WorldService worldService;
    private final Map<UUID, PendingFieldCreation> pendingCreations = new ConcurrentHashMap<>();

    public BossFieldInstanceService(@NotNull AstralRecord plugin, @NotNull WorldService worldService) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * ボスフィールドのフォルダコピーを非同期で行い、Bukkit ワールドロードだけメインスレッドへ戻します。
     *
     * @param challenge challenge runtime state
     * @param worldData field world master data
     * @return loaded field instance を返す Future
     */
    public @NotNull CompletableFuture<BossFieldInstance> createFieldAsync(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData
    ) {
        PreparedField prepared;
        try {
            prepared = prepareField(challenge, worldData);
        } catch (IOException ex) {
            CompletableFuture<BossFieldInstance> failed = new CompletableFuture<>();
            failed.completeExceptionally(ex);
            return failed;
        }

        CompletableFuture<BossFieldInstance> result = new CompletableFuture<>();
        PendingFieldCreation pending = new PendingFieldCreation(new AtomicBoolean(false));
        pendingCreations.put(challenge.challengeId(), pending);
        result.whenComplete((ignored, throwable) -> pendingCreations.remove(challenge.challengeId(), pending));
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyDirectory(prepared.source(), prepared.target(), pending.cancelled());
            } catch (Throwable ex) {
                tryDeletePreparedTarget(prepared.target());
                result.completeExceptionally(ex);
                return;
            }
            if (pending.cancelled().get() || !plugin.isEnabled()) {
                tryDeletePreparedTarget(prepared.target());
                result.completeExceptionally(new CancellationException("Boss field creation was cancelled"));
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (pending.cancelled().get()) {
                        deletePreparedTargetAsync(prepared.target());
                        result.completeExceptionally(new CancellationException("Boss field creation was cancelled"));
                        return;
                    }
                    try {
                        result.complete(loadPreparedField(challenge, worldData, prepared.target()));
                    } catch (Throwable ex) {
                        deletePreparedTargetAsync(prepared.target());
                        result.completeExceptionally(ex);
                    }
                });
            } catch (RuntimeException ex) {
                tryDeletePreparedTarget(prepared.target());
                result.completeExceptionally(ex);
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

    private @NotNull PreparedField prepareField(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData
    ) throws IOException {
        Path root = resolvePath(worldData.instanceRootPath());
        Files.createDirectories(root);

        String worldName = sanitize(worldData.id()) + "_" + challenge.challengeId().toString().substring(0, 8);
        Path target = root.resolve(worldName).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IOException("Boss field target escaped instance root: " + target);
        }
        if (Files.exists(target)) {
            throw new IOException("Boss field target already exists: " + target);
        }

        Path source = resolvePath(worldData.baseWorldPath());
        if (!Files.isDirectory(source) || !Files.isRegularFile(source.resolve("level.dat"))) {
            Logger.log(LogId.W_6501, worldData.id(), source.toString());
            throw new IOException("Boss field base world folder is missing or invalid: " + source);
        }
        return new PreparedField(source, target);
    }

    private @NotNull BossFieldInstance loadPreparedField(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData,
            @NotNull Path target
    ) throws IOException {
        World world = Bukkit.createWorld(new WorldCreator(worldCreatorName(target)));
        if (world == null) {
            throw new IOException("Bukkit could not load boss field world: " + target);
        }
        worldService.applyRpgGameRules(world);
        worldService.registerRuntimeDisplayName(world, worldData.displayName());
        return new BossFieldInstance(challenge.challengeId(), world.getName(), target, world);
    }

    /**
     * Unloads and removes the generated field world.
     *
     * @param field field instance
     */
    public void destroyField(@NotNull BossFieldInstance field) {
        if (!unloadField(field)) {
            return;
        }

        try {
            deleteDirectory(field.worldFolder());
        } catch (IOException ex) {
            Logger.log(LogId.E_6502, ex, field.worldFolder().toString());
        }
    }

    /**
     * Bukkit ワールドをメインスレッドでアンロードし、フォルダ削除を非同期実行します。
     *
     * @param field 破棄するフィールド
     * @return フォルダ削除まで成功した場合に {@code true} を返す Future
     */
    public @NotNull CompletableFuture<Boolean> destroyFieldAsync(@NotNull BossFieldInstance field) {
        if (!unloadField(field)) {
            return CompletableFuture.completedFuture(false);
        }
        CompletableFuture<Boolean> result = new CompletableFuture<>();
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
        return result;
    }

    private boolean unloadField(@NotNull BossFieldInstance field) {
        World loaded = Bukkit.getWorld(field.world().getUID());
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            Logger.log(LogId.E_6503, field.worldName(), field.worldFolder().toString());
            return false;
        }
        worldService.unregisterRuntimeDisplayName(field.world());
        return true;
    }

    private @NotNull Path resolvePath(@NotNull String rawPath) {
        Path path = Path.of(rawPath);
        if (path.isAbsolute()) {
            return path.normalize();
        }
        return Bukkit.getWorldContainer().toPath().resolve(path).normalize();
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

    private void tryDeletePreparedTarget(@NotNull Path target) {
        try {
            deleteDirectory(target);
        } catch (IOException ex) {
            Logger.log(LogId.E_6502, ex, target.toString());
        }
    }

    private void deletePreparedTargetAsync(@NotNull Path target) {
        try {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> tryDeletePreparedTarget(target));
        } catch (RuntimeException ex) {
            tryDeletePreparedTarget(target);
        }
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

    private static final class FieldCopyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private FieldCopyException(@NotNull IOException cause) {
            super(cause);
        }

        private @NotNull IOException ioCause() {
            return (IOException) super.getCause();
        }
    }
}
