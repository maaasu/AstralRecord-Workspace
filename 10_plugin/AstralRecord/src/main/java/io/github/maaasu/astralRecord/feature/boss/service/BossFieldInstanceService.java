package io.github.maaasu.astralRecord.feature.boss.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.boss.model.BossChallengeInstance;
import io.github.maaasu.astralRecord.feature.boss.model.BossFieldInstance;
import io.github.maaasu.astralRecord.feature.world.model.WorldMasterData;
import io.github.maaasu.astralRecord.feature.world.service.WorldService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.jetbrains.annotations.NotNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

/**
 * Creates and destroys per-challenge boss field worlds.
 */
public final class BossFieldInstanceService {
    private final AstralRecord plugin;
    private final WorldService worldService;

    public BossFieldInstanceService(@NotNull AstralRecord plugin, @NotNull WorldService worldService) {
        this.plugin = plugin;
        this.worldService = worldService;
    }

    /**
     * Creates and loads a field world for the challenge.
     *
     * @param challenge challenge runtime state
     * @param worldData field world master data
     * @return loaded field instance
     * @throws IOException when file preparation fails
     */
    public @NotNull BossFieldInstance createField(
            @NotNull BossChallengeInstance challenge,
            @NotNull WorldMasterData worldData
    ) throws IOException {
        PreparedField prepared = prepareField(challenge, worldData);
        copyDirectory(prepared.source(), prepared.target());
        return loadPreparedField(challenge, prepared.target());
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
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                copyDirectory(prepared.source(), prepared.target());
            } catch (Throwable ex) {
                tryDeletePreparedTarget(prepared.target());
                result.completeExceptionally(ex);
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    result.complete(loadPreparedField(challenge, prepared.target()));
                } catch (Throwable ex) {
                    tryDeletePreparedTarget(prepared.target());
                    result.completeExceptionally(ex);
                }
            });
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
            @NotNull Path target
    ) throws IOException {
        World world = Bukkit.createWorld(new WorldCreator(worldCreatorName(target)));
        if (world == null) {
            throw new IOException("Bukkit could not load boss field world: " + target);
        }
        worldService.applyRpgGameRules(world);
        return new BossFieldInstance(challenge.challengeId(), world.getName(), target, world);
    }

    /**
     * Unloads and removes the generated field world.
     *
     * @param field field instance
     */
    public void destroyField(@NotNull BossFieldInstance field) {
        World loaded = Bukkit.getWorld(field.world().getUID());
        if (loaded != null && !Bukkit.unloadWorld(loaded, false)) {
            Logger.log(LogId.E_6503, field.worldName(), field.worldFolder().toString());
            return;
        }

        try {
            deleteDirectory(field.worldFolder());
        } catch (IOException ex) {
            Logger.log(LogId.E_6502, ex, field.worldFolder().toString());
        }
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

    private void copyDirectory(@NotNull Path source, @NotNull Path target) throws IOException {
        try (Stream<Path> stream = Files.walk(source)) {
            try {
                stream.forEach(current -> copyPath(source, target, current));
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

    private void deleteDirectory(@NotNull Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        if (!Files.exists(normalized)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(normalized)) {
            for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private record PreparedField(@NotNull Path source, @NotNull Path target) {
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
