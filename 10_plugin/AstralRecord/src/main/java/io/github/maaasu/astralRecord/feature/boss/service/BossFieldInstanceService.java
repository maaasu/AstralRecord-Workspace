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
        Path root = resolvePath(worldData.instanceRootPath());
        Files.createDirectories(root);

        String worldName = sanitize(worldData.id()) + "_" + challenge.challengeId().toString().substring(0, 8);
        Path target = root.resolve(worldName).normalize();
        if (!target.startsWith(root.normalize())) {
            throw new IOException("Boss field target escaped instance root: " + target);
        }

        Path source = resolvePath(worldData.baseWorldPath());
        if (Files.exists(source)) {
            copyDirectory(source, target);
        } else {
            Logger.log(LogId.W_6501, worldData.id(), source.toString());
            Files.createDirectories(target);
        }

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
        if (loaded != null) {
            Bukkit.unloadWorld(loaded, false);
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
            for (Path current : stream.toList()) {
                Path relative = source.relativize(current);
                Path destination = target.resolve(relative).normalize();
                if (Files.isDirectory(current)) {
                    Files.createDirectories(destination);
                    continue;
                }
                if (isRuntimeWorldFile(current)) {
                    continue;
                }
                Files.createDirectories(destination.getParent());
                Files.copy(current, destination);
            }
        }
    }

    private boolean isRuntimeWorldFile(@NotNull Path path) {
        String name = path.getFileName().toString();
        return "uid.dat".equalsIgnoreCase(name) || "session.lock".equalsIgnoreCase(name);
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
}
