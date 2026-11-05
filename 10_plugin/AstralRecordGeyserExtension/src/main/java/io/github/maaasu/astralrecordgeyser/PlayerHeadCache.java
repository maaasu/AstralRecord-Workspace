package io.github.maaasu.astralrecordgeyser;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Retains the first successful skin hash for each player across proxy restarts. */
final class PlayerHeadCache {
    private static final int LOOKUP_CONCURRENCY = 8;
    private static final Duration LOOKUP_BUDGET = Duration.ofSeconds(30);

    @FunctionalInterface
    interface Lookup {
        String fetch(UUID uuid) throws IOException, InterruptedException;
    }

    static Map<UUID, String> resolve(Path cacheFile, List<UUID> players, Lookup lookup, Consumer<String> warning) {
        Map<UUID, String> known = new TreeMap<>();
        if (!load(cacheFile, known, warning)) return Map.of();
        var misses = new LinkedHashSet<>(players);
        misses.removeAll(known.keySet());
        boolean interrupted = false;
        if (!misses.isEmpty()) {
            var lookupOrder = new ArrayList<>(misses);
            // A startup budget or rate limit must not repeatedly favor the same UUIDs.
            Collections.shuffle(lookupOrder);
            int knownBefore = known.size();
            var workers = Executors.newFixedThreadPool(Math.min(LOOKUP_CONCURRENCY, misses.size()),
                Thread.ofVirtual().name("astralrecord-head-lookup-", 0).factory());
            var completed = new ExecutorCompletionService<Map.Entry<UUID, String>>(workers);
            int failed = 0;
            int finished = 0;
            String firstFailure = null;
            long deadline = System.nanoTime() + LOOKUP_BUDGET.toNanos();
            try {
                for (UUID uuid : lookupOrder) {
                    completed.submit(() -> {
                        if (deadline - System.nanoTime() <= 0) return null;
                        String hash = lookup.fetch(uuid);
                        if (hash == null) return null;
                        return Map.entry(uuid, HeadCatalog.requireSkinHash(hash));
                    });
                }
                while (finished < misses.size()) {
                    var task = completed.poll(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    if (task == null) break;
                    finished++;
                    try {
                        var result = task.get();
                        if (result != null) known.putIfAbsent(result.getKey(), result.getValue());
                    } catch (ExecutionException exception) {
                        failed++;
                        if (firstFailure == null) {
                            Throwable cause = exception.getCause();
                            firstFailure = cause instanceof IOException ? cause.getMessage() : cause.getClass().getSimpleName();
                        }
                    }
                }
            } catch (InterruptedException exception) {
                interrupted = true;
            } finally {
                workers.shutdownNow();
            }
            try {
                if (known.size() > knownBefore) save(cacheFile, known);
            } catch (IOException | RuntimeException exception) {
                warning.accept("Could not save player head skin cache (" + exception.getClass().getSimpleName() + ").");
            }
            int unresolved = misses.size() - (known.size() - knownBefore);
            if (unresolved > 0) {
                warning.accept("Unresolved player head skins: " + unresolved + ", lookup errors: " + failed
                    + ", unfinished: " + (misses.size() - finished)
                    + (interrupted ? " (interrupted)" : "")
                    + (firstFailure == null ? "" : ", first error: " + firstFailure)
                    + ". Unresolved UUIDs will be retried on next proxy startup.");
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        Map<UUID, String> active = new LinkedHashMap<>();
        for (UUID uuid : players) {
            if (known.containsKey(uuid)) active.put(uuid, known.get(uuid));
        }
        return active;
    }

    private static boolean load(Path path, Map<UUID, String> known, Consumer<String> warning) {
        try (var reader = Files.newBufferedReader(path)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            int invalid = 0;
            for (var entry : root.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(entry.getKey());
                    if (!uuid.toString().equals(entry.getKey())) throw new IllegalArgumentException("Invalid UUID");
                    known.put(uuid, HeadCatalog.requireSkinHash(HeadCatalog.requireString(entry.getValue())));
                } catch (IllegalArgumentException exception) {
                    invalid++;
                }
            }
            if (invalid > 0) warning.accept("Ignored " + invalid + " invalid player head skin cache entries.");
            return true;
        } catch (NoSuchFileException exception) {
            return true;
        } catch (IOException | RuntimeException exception) {
            warning.accept("Could not read player head skin cache (" + exception.getClass().getSimpleName()
                + "); skipping player skin lookup and keeping the existing file intact. Check it before the next proxy startup.");
            return false;
        }
    }

    private static void save(Path path, Map<UUID, String> known) throws IOException {
        JsonObject root = new JsonObject();
        known.forEach((uuid, hash) -> root.addProperty(uuid.toString(), hash));
        Path target = path.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "player-head-skins-", ".tmp");
        try {
            Files.writeString(temporary, new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
