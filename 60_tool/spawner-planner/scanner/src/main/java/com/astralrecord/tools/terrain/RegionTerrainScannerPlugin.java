package com.astralrecord.tools.terrain;

import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.HeightMap;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.block.Block;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.plugin.java.JavaPlugin;

public final class RegionTerrainScannerPlugin extends JavaPlugin {
    private ScanSettings settings;
    private World world;
    private JsonWriter json;
    private Path output;
    private Path partial;
    private int chunkIndex;
    private int requestId;
    private int okCells;
    private int unknownCells;
    private boolean finished;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            settings = ScanSettings.read(this);
            world = Bukkit.getWorld(settings.worldName());
            if (world == null) {
                Path worldPath = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize()
                    .resolve(settings.worldName());
                if (!Files.isRegularFile(worldPath.resolve("level.dat"))) {
                    throw new IllegalStateException("Existing world level.dat was not found: " + worldPath);
                }
                world = new WorldCreator(settings.worldName()).createWorld();
                if (world == null) {
                    throw new IllegalStateException("Could not load existing world: " + settings.worldName());
                }
            }
            if (settings.autoStart()) {
                startScan();
            } else {
                getLogger().info("autoStart is false; no scan was started.");
            }
        } catch (Exception failure) {
            getLogger().severe("Terrain scanner could not start: " + failure);
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (json != null) {
            try {
                json.close();
            } catch (IOException failure) {
                getLogger().warning("Could not close partial terrain JSON: " + failure);
            }
            json = null;
        }
        if (!finished && partial != null) {
            try {
                Files.deleteIfExists(partial);
            } catch (IOException failure) {
                getLogger().warning("Could not remove partial terrain JSON: " + failure);
            }
        }
    }

    private void startScan() throws IOException {
        Path serverRoot = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        output = serverRoot.resolve(settings.outputFile()).normalize();
        if (!output.startsWith(serverRoot) || output.equals(serverRoot)) {
            throw new IllegalArgumentException("outputFile must stay within the server root");
        }
        Files.createDirectories(output.getParent());
        partial = output.resolveSibling(output.getFileName() + ".part");
        BufferedWriter writer = Files.newBufferedWriter(partial, StandardCharsets.UTF_8);
        json = new JsonWriter(writer);
        json.beginObject();
        json.name("schemaVersion").value(1);
        json.name("world").value(world.getName());
        json.name("minX").value(settings.minX());
        json.name("minZ").value(settings.minZ());
        json.name("width").value(settings.size());
        json.name("height").value(settings.size());
        json.name("cellSize").value(settings.cellSize());
        json.name("capturedAt").value(Instant.now().toString());
        json.name("cells").beginArray();
        getLogger().info("Scanning " + settings.totalChunks() + " existing chunks in "
            + settings.worldName() + "; output=" + output);
        Bukkit.getScheduler().runTask(this, this::nextChunk);
    }

    private void nextChunk() {
        if (!isEnabled() || finished) {
            return;
        }
        if (chunkIndex == settings.totalChunks()) {
            complete();
            return;
        }
        int chunksPerSide = settings.size() / 16;
        int chunkX = Math.floorDiv(settings.minX(), 16) + chunkIndex % chunksPerSide;
        int chunkZ = Math.floorDiv(settings.minZ(), 16) + chunkIndex / chunksPerSide;
        int currentRequest = ++requestId;
        try {
            if (!world.isChunkGenerated(chunkX, chunkZ)) {
                writeUnknownChunk(chunkX, chunkZ, "chunk_not_generated");
                advance();
                return;
            }
            boolean previouslyLoaded = world.isChunkLoaded(chunkX, chunkZ);
            world.getChunkAtAsync(chunkX, chunkZ, false, false, chunk -> {
                Runnable process = () -> {
                    if (!isEnabled() || finished || currentRequest != requestId) {
                        return;
                    }
                    try {
                        if (chunk == null || !chunk.isLoaded()) {
                            writeUnknownChunk(chunkX, chunkZ, "chunk_not_loaded");
                        } else {
                            scanChunk(chunk);
                        }
                    } catch (Exception failure) {
                        getLogger().warning("Chunk " + chunkX + "," + chunkZ + " failed: " + failure);
                        // A partly written chunk cannot be repaired in the stream.
                        failScan(failure);
                        return;
                    } finally {
                        if (!previouslyLoaded && chunk != null && chunk.isLoaded()) {
                            world.unloadChunkRequest(chunkX, chunkZ);
                        }
                    }
                    advance();
                };
                if (Bukkit.isPrimaryThread()) {
                    process.run();
                } else {
                    Bukkit.getScheduler().runTask(this, process);
                }
            });
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (isEnabled() && !finished && currentRequest == requestId) {
                    try {
                        writeUnknownChunk(chunkX, chunkZ, "chunk_load_timeout");
                        advance();
                    } catch (IOException failure) {
                        failScan(failure);
                    }
                }
            }, 200L);
        } catch (Exception failure) {
            getLogger().warning("Chunk " + chunkX + "," + chunkZ + " could not be requested: " + failure);
            try {
                writeUnknownChunk(chunkX, chunkZ, "chunk_request_failed");
                advance();
            } catch (IOException writeFailure) {
                failScan(writeFailure);
            }
        }
    }

    private void scanChunk(Chunk chunk) throws IOException {
        int cellSize = settings.cellSize();
        int baseX = chunk.getX() * 16;
        int baseZ = chunk.getZ() * 16;
        for (int localZ = 0; localZ < 16; localZ += cellSize) {
            for (int localX = 0; localX < 16; localX += cellSize) {
                int x = baseX + localX;
                int z = baseZ + localZ;
                try {
                    scanCell(chunk, localX, localZ, x, z);
                    okCells++;
                } catch (RuntimeException failure) {
                    getLogger().warning("Cell " + x + "," + z + " could not be sampled: " + failure);
                    writeUnknownCell(x, z, "cell_scan_failed");
                }
            }
        }
    }

    private void scanCell(Chunk chunk, int localX, int localZ, int x, int z) throws IOException {
        int cellSize = settings.cellSize();
        Map<String, Integer> surfaces = new LinkedHashMap<>();
        Map<String, Integer> grounds = new LinkedHashMap<>();
        Map<String, Integer> biomes = new LinkedHashMap<>();
        List<Candidate> safe = new ArrayList<>();
        List<Candidate> walkableBelowSurface = new ArrayList<>();
        int waterCount = 0;
        int treeCount = 0;
        for (int dz = 0; dz < cellSize; dz++) {
            for (int dx = 0; dx < cellSize; dx++) {
                int blockX = x + dx;
                int blockZ = z + dz;
                int topY = world.getHighestBlockAt(blockX, blockZ, HeightMap.WORLD_SURFACE).getY();
                Block top = chunk.getBlock(localX + dx, topY, localZ + dz);
                count(surfaces, key(top.getType()));
                count(biomes, top.getBiome().getKey().toString());
                if (top.isLiquid() || top.getType() == Material.WATER) {
                    waterCount++;
                }
                if (isTree(top.getType())) {
                    treeCount++;
                }
                int floor = Math.max(world.getMinHeight(), topY - 32);
                boolean surfaceFound = false;
                for (int y = topY; y >= floor; y--) {
                    Block ground = chunk.getBlock(localX + dx, y, localZ + dz);
                    Material type = ground.getType();
                    if (!type.isSolid() || isTree(type)) {
                        continue;
                    }
                    boolean isSurface = !surfaceFound;
                    if (isSurface) {
                        count(grounds, key(type));
                        surfaceFound = true;
                    }
                    int feetY = y + 1;
                    if (supportsFeet(type) && !isHazard(type) && feetY + 1 < world.getMaxHeight()) {
                        Block feet = chunk.getBlock(localX + dx, feetY, localZ + dz);
                        Block head = chunk.getBlock(localX + dx, feetY + 1, localZ + dz);
                        if (isClear(feet) && isClear(head)) {
                            Candidate candidate = new Candidate(blockX, feetY, blockZ);
                            if (isSurface) {
                                // The first non-tree solid is the original surface candidate.
                                safe.add(candidate);
                            } else {
                                walkableBelowSurface.add(candidate);
                            }
                        }
                    }
                }
            }
        }
        writeCell(x, z, surfaces, grounds, biomes, safe, walkableBelowSurface,
            waterCount, treeCount);
    }

    private void writeCell(int x, int z, Map<String, Integer> surfaces,
                           Map<String, Integer> grounds, Map<String, Integer> biomes,
                           List<Candidate> safe, List<Candidate> walkableBelowSurface,
                           int waterCount, int treeCount) throws IOException {
        Candidate candidate = null;
        Integer minY = null;
        Integer maxY = null;
        if (!safe.isEmpty()) {
            List<Integer> elevations = safe.stream().map(Candidate::y).sorted().toList();
            int median = elevations.get(elevations.size() / 2);
            int centerX = x + settings.cellSize() / 2;
            int centerZ = z + settings.cellSize() / 2;
            candidate = safe.stream().min(Comparator
                .comparingInt((Candidate value) -> Math.abs(value.y() - median))
                .thenComparingInt(value -> Math.abs(value.x() - centerX) + Math.abs(value.z() - centerZ)))
                .orElseThrow();
            minY = elevations.getFirst();
            maxY = elevations.getLast();
        }
        json.beginObject();
        json.name("x").value(x);
        json.name("z").value(z);
        json.name("y"); nullable(candidate == null ? null : candidate.y());
        json.name("surface"); nullable(mostCommon(surfaces));
        json.name("ground"); nullable(mostCommon(grounds));
        json.name("biome"); nullable(mostCommon(biomes));
        json.name("safeCount").value(safe.size());
        json.name("safeColumnY").beginArray();
        for (int dz = 0; dz < settings.cellSize(); dz++) {
            for (int dx = 0; dx < settings.cellSize(); dx++) {
                Integer height = null;
                for (Candidate value : safe) {
                    if (value.x() == x + dx && value.z() == z + dz) {
                        height = value.y();
                        break;
                    }
                }
                nullable(height);
            }
        }
        json.endArray();
        json.name("walkableBelowSurface").beginArray();
        for (Candidate value : walkableBelowSurface) {
            json.beginObject();
            json.name("x").value(value.x());
            json.name("y").value(value.y());
            json.name("z").value(value.z());
            json.endObject();
        }
        json.endArray();
        json.name("waterCount").value(waterCount);
        json.name("treeCount").value(treeCount);
        json.name("minY"); nullable(minY);
        json.name("maxY"); nullable(maxY);
        json.name("candidateX"); nullable(candidate == null ? null : candidate.x());
        json.name("candidateZ"); nullable(candidate == null ? null : candidate.z());
        json.name("candidateY"); nullable(candidate == null ? null : candidate.y());
        json.name("status").value("ok");
        json.endObject();
    }

    private void writeUnknownChunk(int chunkX, int chunkZ, String reason) throws IOException {
        int cellSize = settings.cellSize();
        for (int dz = 0; dz < 16; dz += cellSize) {
            for (int dx = 0; dx < 16; dx += cellSize) {
                writeUnknownCell(chunkX * 16 + dx, chunkZ * 16 + dz, reason);
            }
        }
    }

    private void writeUnknownCell(int x, int z, String reason) throws IOException {
        json.beginObject();
        json.name("x").value(x);
        json.name("z").value(z);
        json.name("y").nullValue();
        json.name("surface").nullValue();
        json.name("ground").nullValue();
        json.name("biome").nullValue();
        json.name("safeCount").nullValue();
        json.name("safeColumnY").nullValue();
        json.name("walkableBelowSurface").nullValue();
        json.name("waterCount").nullValue();
        json.name("treeCount").nullValue();
        json.name("minY").nullValue();
        json.name("maxY").nullValue();
        json.name("candidateX").nullValue();
        json.name("candidateZ").nullValue();
        json.name("candidateY").nullValue();
        json.name("status").value("unknown");
        json.name("reason").value(reason);
        json.endObject();
        unknownCells++;
    }

    private void advance() {
        // Invalidate both the timeout and any late chunk-load callback immediately.
        requestId++;
        chunkIndex++;
        if (chunkIndex % 100 == 0 || chunkIndex == settings.totalChunks()) {
            getLogger().info("Scanned chunks " + chunkIndex + "/" + settings.totalChunks()
                + "; ok cells=" + okCells + "; unknown cells=" + unknownCells);
        }
        Bukkit.getScheduler().runTaskLater(this, this::nextChunk, 1L);
    }

    private void complete() {
        try {
            json.endArray();
            json.endObject();
            json.close();
            json = null;
            try {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                Files.move(partial, output, StandardCopyOption.REPLACE_EXISTING);
            }
            finished = true;
            getLogger().info("Terrain scan complete: " + output + "; ok cells=" + okCells
                + "; unknown cells=" + unknownCells);
            if (settings.shutdownAfterScan()) {
                Bukkit.getScheduler().runTaskLater(this, Bukkit::shutdown, 20L);
            }
        } catch (IOException failure) {
            failScan(failure);
        }
    }

    private void failScan(Exception failure) {
        getLogger().severe("Terrain scan aborted; existing output retained: " + failure);
        Bukkit.getPluginManager().disablePlugin(this);
    }

    private void nullable(Number value) throws IOException {
        if (value == null) json.nullValue(); else json.value(value);
    }

    private void nullable(String value) throws IOException {
        if (value == null) json.nullValue(); else json.value(value);
    }

    private static void count(Map<String, Integer> counts, String key) {
        counts.merge(key, 1, Integer::sum);
    }

    private static String mostCommon(Map<String, Integer> counts) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey).orElse(null);
    }

    private static String key(Material material) {
        return material.getKey().toString();
    }

    private static boolean isTree(Material material) {
        String name = material.name();
        return name.endsWith("_LEAVES") || name.endsWith("_LOG") || name.endsWith("_WOOD")
            || name.equals("MANGROVE_ROOTS") || name.equals("MUDDY_MANGROVE_ROOTS")
            || name.equals("NETHER_WART_BLOCK") || name.equals("WARPED_WART_BLOCK");
    }

    private static boolean isHazard(Material material) {
        return material == Material.MAGMA_BLOCK || material == Material.CACTUS
            || material == Material.CAMPFIRE || material == Material.SOUL_CAMPFIRE
            || material == Material.POWDER_SNOW;
    }

    /**
     * 光を通す道・階段・ハーフブロックも支持面として扱います。
     * 柵・開閉扉・装飾物は支持候補へ広げず、足と頭の空間は別途検査します。
     *
     * @param material 葉・幹を除いた固体ブロックの材質
     * @return 一般的な歩行面として支持を確認できる場合はtrue
     */
    private static boolean supportsFeet(Material material) {
        return material.isOccluding() || material.name().endsWith("_SLAB")
            || material.name().endsWith("_STAIRS") || material == Material.DIRT_PATH
            || material == Material.FARMLAND;
    }

    private static boolean isClear(Block block) {
        Material type = block.getType();
        return block.isPassable() && !block.isLiquid()
            && !(block.getBlockData() instanceof Waterlogged waterlogged && waterlogged.isWaterlogged())
            && type != Material.POWDER_SNOW && type != Material.FIRE && type != Material.SOUL_FIRE
            && type != Material.SWEET_BERRY_BUSH && type != Material.COBWEB
            && type != Material.WITHER_ROSE && type != Material.NETHER_PORTAL
            && type != Material.END_PORTAL && type != Material.END_GATEWAY;
    }

    private record Candidate(int x, int y, int z) {}

    private record ScanSettings(String worldName, int minX, int minZ, int size, int cellSize,
                                String outputFile, boolean autoStart, boolean shutdownAfterScan) {
        static ScanSettings read(RegionTerrainScannerPlugin plugin) {
            String worldName = plugin.getConfig().getString("worldName", "");
            int minX = plugin.getConfig().getInt("minX");
            int minZ = plugin.getConfig().getInt("minZ");
            int size = plugin.getConfig().getInt("size");
            int cellSize = plugin.getConfig().getInt("cellSize");
            String outputFile = plugin.getConfig().getString("outputFile", "");
            int chunksPerTick = plugin.getConfig().getInt("chunksPerTick");
            if (worldName.isBlank() || worldName.contains("/") || worldName.contains("\\")
                || worldName.equals(".") || worldName.equals("..")) {
                throw new IllegalArgumentException("worldName must be a simple folder name");
            }
            if (size <= 0 || size > 1024 || size % 16 != 0 || minX % 16 != 0 || minZ % 16 != 0
                || cellSize <= 0 || 16 % cellSize != 0
                || (long) minX + size > Integer.MAX_VALUE || (long) minZ + size > Integer.MAX_VALUE
                || (long) minX + size < Integer.MIN_VALUE || (long) minZ + size < Integer.MIN_VALUE) {
                throw new IllegalArgumentException("Region must be chunk-aligned and cellSize must divide 16");
            }
            if (chunksPerTick != 1) {
                throw new IllegalArgumentException("chunksPerTick must be 1");
            }
            if (outputFile.isBlank() || Path.of(outputFile).isAbsolute()) {
                throw new IllegalArgumentException("outputFile must be relative to the server root");
            }
            return new ScanSettings(worldName, minX, minZ, size, cellSize, outputFile,
                plugin.getConfig().getBoolean("autoStart"),
                plugin.getConfig().getBoolean("shutdownAfterScan"));
        }

        int totalChunks() {
            int side = size / 16;
            return Math.multiplyExact(side, side);
        }
    }
}
