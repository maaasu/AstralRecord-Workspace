package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringInstance;
import io.github.maaasu.astralRecord.feature.gathering.service.GatheringService;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerEntry;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerLocation;
import io.github.maaasu.astralRecord.feature.gathering.spawner.model.GatheringSpawnerTimeWindow;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.gathering.spawner.repository.GatheringSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class GatheringSpawnerService {
    private static final long TICK_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 20L * 60L;
    private static final int MAX_PLAYER_SCALE = 6;

    private final Plugin plugin;
    private final GatheringService gatheringService;
    private final GatheringSpawnerDefinitionRepository definitionRepository;
    private final GatheringSpawnerLocationRepository locationRepository;
    private final NamespacedKey spawnerIdKey;
    private final Map<String, GatheringSpawnerDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, GatheringSpawnerLocation> locations = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> spawnedByLocation = new HashMap<>();
    private ParticleDisplayService particleDisplayService;
    private GatheringSpawnerVisualizer visualizer;
    private BukkitTask task;
    private BukkitTask saveTask;
    private long tick;
    private boolean dirty;

    public GatheringSpawnerService(
            @NotNull Plugin plugin,
            @NotNull GatheringService gatheringService,
            @NotNull GatheringSpawnerDefinitionRepository definitionRepository,
            @NotNull GatheringSpawnerLocationRepository locationRepository
    ) {
        this.plugin = plugin;
        this.gatheringService = gatheringService;
        this.definitionRepository = definitionRepository;
        this.locationRepository = locationRepository;
        this.spawnerIdKey = new NamespacedKey(plugin, "gathering_spawner_id");
    }

    public int loadAll() {
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        replaceMasterDataSnapshot(snapshot);
        return definitions.size();
    }

    /**
     * 採集スポナー定義と配置 YAML を読み込み、公開前のスナップショットを作成します。
     *
     * @return 採集スポナーマスタスナップショット
     */
    public @NotNull MasterDataSnapshot loadMasterDataSnapshot() {
        return new MasterDataSnapshot(
                List.copyOf(definitionRepository.findAll()),
                List.copyOf(locationRepository.loadAll())
        );
    }

    /**
     * 準備済み採集スポナーマスタを実行時キャッシュへ一括反映します。
     *
     * @param snapshot 採集スポナーマスタスナップショット
     */
    public void replaceMasterDataSnapshot(@NotNull MasterDataSnapshot snapshot) {
        definitions.clear();
        for (GatheringSpawnerDefinition definition : snapshot.definitions()) {
            definitions.put(definition.id(), definition);
        }
        locations.clear();
        spawnedByLocation.clear();
        for (GatheringSpawnerLocation location : snapshot.locations()) {
            locations.put(location.locationKey(), location);
            spawnedByLocation.put(location.locationKey(), new HashSet<>());
        }
        dirty = false;
    }

    /** 公開前に準備した採集スポナー定義と配置の immutable スナップショットです。 */
    public record MasterDataSnapshot(
            @NotNull List<GatheringSpawnerDefinition> definitions,
            @NotNull List<GatheringSpawnerLocation> locations
    ) {
    }

    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        }
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, SAVE_INTERVAL, SAVE_INTERVAL);
        }
        if (visualizer == null && particleDisplayService != null) {
            visualizer = new GatheringSpawnerVisualizer(plugin, this, particleDisplayService);
            visualizer.start();
        }
    }

    public void setParticleDisplayService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (saveTask != null) {
            saveTask.cancel();
            saveTask = null;
        }
        if (visualizer != null) {
            visualizer.stop();
            visualizer = null;
        }
        saveIfDirty();
    }

    /**
     * 管理者用の採集スポナー設置アイテムを作成します。
     * lore には定義 ID、採集対象、時間帯、半径、判定間隔、上限値、足元ブロック条件など、
     * 設置前に確認できるスポナー情報を日本語で表示します。
     *
     * @param spawnerId スポナー ID
     * @param amount    作成個数。1 未満の場合は 1 として扱います。
     * @return スポナー設置用 ItemStack。定義が存在しない場合は null
     */
    public @Nullable ItemStack createSpawnerItem(@NotNull String spawnerId, int amount) {
        GatheringSpawnerDefinition definition = definitions.get(spawnerId);
        if (definition == null) {
            return null;
        }
        ItemStack itemStack = new ItemStack(definition.itemMaterial(), Math.max(1, amount));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(ColorCodeUtil.toComponent("&b採集スポナー: &f" + spawnerId, spawnerId));
            meta.lore(buildSpawnerLore(definition));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(spawnerIdKey, PersistentDataType.STRING, spawnerId);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull List<Component> buildSpawnerLore(@NotNull GatheringSpawnerDefinition definition) {
        List<String> lore = new ArrayList<>();
        lore.add("&7採集スポナー設置アイテム");
        lore.add("");
        lore.add("&e基本情報");
        lore.add("&7ID: &f" + definition.id());
        lore.add("&7種別: &f採集スポナー");
        lore.add("&7表示ブロック: &f" + definition.itemMaterial().name());
        lore.add("");
        lore.add("&eスポーン条件");
        lore.add("&7半径: &f" + formatMeters(definition.radiusMeters()));
        lore.add("&7判定間隔: &f" + formatTicks(definition.spawnIntervalTicks()));
        lore.add("&7時間帯: &f" + formatTimeWindows(definition.timeWindows()));
        lore.add("&7足元ブロック: &f" + formatRequiredBaseBlocks(definition.requiredBaseBlocks()));
        lore.add("");
        lore.add("&e出現対象");
        if (definition.spawnGatherings().isEmpty()) {
            lore.add("&7 - なし");
        } else {
            for (GatheringSpawnerEntry entry : definition.spawnGatherings()) {
                lore.add("&7 - &f" + entry.gatheringId() + " &7(重み " + entry.weight() + ")");
            }
        }
        lore.add("");
        lore.add("&e上限");
        lore.add("&7スポナー単位: &f" + definition.maxAlivePerSpawner() + " 個");
        lore.add("&7周辺採集物: &f" + definition.maxNearbyGatherings() + " 個");
        lore.add("&7プレイヤーあたり: &f" + definition.spawnPerPlayer() + " 個");
        return lore.stream()
                .map(line -> ColorCodeUtil.toComponent(line, ""))
                .toList();
    }

    private @NotNull String formatMeters(double meters) {
        return String.format(Locale.ROOT, "%.1fm", meters);
    }

    private @NotNull String formatTicks(long ticks) {
        double seconds = ticks / 20.0D;
        return String.format(Locale.ROOT, "%d tick / %.1f秒", ticks, seconds);
    }

    private @NotNull String formatTimeWindows(@NotNull List<GatheringSpawnerTimeWindow> windows) {
        List<String> formatted = new ArrayList<>();
        for (GatheringSpawnerTimeWindow window : windows) {
            if (window.startTick() == 0L && window.endTick() == 23999L) {
                formatted.add("終日");
            } else {
                formatted.add(window.startTick() + "-" + window.endTick() + " tick");
            }
        }
        return String.join(", ", formatted);
    }

    private @NotNull String formatRequiredBaseBlocks(@NotNull List<Material> materials) {
        if (materials.isEmpty()) {
            return "指定なし";
        }
        List<String> names = new ArrayList<>();
        for (Material material : materials) {
            names.add(material.name());
        }
        return String.join(", ", names);
    }

    public @Nullable String readSpawnerId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(spawnerIdKey, PersistentDataType.STRING);
    }

    public boolean registerLocation(@NotNull String spawnerId, @NotNull Location location) {
        if (!definitions.containsKey(spawnerId)) {
            return false;
        }
        GatheringSpawnerLocation spawnerLocation = GatheringSpawnerLocation.from(spawnerId, location);
        if (locations.containsKey(spawnerLocation.locationKey())) {
            return false;
        }
        locations.put(spawnerLocation.locationKey(), spawnerLocation);
        spawnedByLocation.put(spawnerLocation.locationKey(), new HashSet<>());
        dirty = true;
        return true;
    }

    public boolean removeLocation(@NotNull Location location) {
        String key = GatheringSpawnerLocation.from("_", location).locationKey();
        boolean removed = locations.remove(key) != null;
        spawnedByLocation.remove(key);
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    public boolean hasLocation(@NotNull Location location) {
        return locations.containsKey(GatheringSpawnerLocation.from("_", location).locationKey());
    }

    public boolean isAdminMode(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.hasAdminPermission();
    }

    public boolean canViewSpawnerVisual(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
    }

    public @NotNull Material getDisplayMaterial(@NotNull String spawnerId) {
        GatheringSpawnerDefinition definition = definitions.get(spawnerId);
        return definition == null ? Material.RESPAWN_ANCHOR : definition.itemMaterial();
    }

    public @NotNull Collection<String> getLoadedSpawnerIds() {
        return List.copyOf(definitions.keySet());
    }

    public @NotNull Collection<GatheringSpawnerLocation> getLocations() {
        return List.copyOf(locations.values());
    }

    private void tick() {
        tick += TICK_INTERVAL;
        for (GatheringSpawnerLocation spawnerLocation : List.copyOf(locations.values())) {
            processSpawner(spawnerLocation);
        }
    }

    private void processSpawner(@NotNull GatheringSpawnerLocation spawnerLocation) {
        GatheringSpawnerDefinition definition = definitions.get(spawnerLocation.spawnerId());
        Location origin = spawnerLocation.toLocation();
        if (definition == null || origin == null || origin.getWorld() == null) {
            return;
        }
        if (tick % definition.spawnIntervalTicks() != 0L) {
            cleanupTracked(spawnerLocation.locationKey());
            return;
        }

        long worldTime = origin.getWorld().getTime();
        NearbyPlayerCounts playerCounts = countNearbyPlayers(origin, definition.radiusMeters());
        if (playerCounts.playersInRange() <= 0) {
            cleanupTracked(spawnerLocation.locationKey());
            return;
        }
        if (!definition.canSpawnAt(worldTime)) {
            cleanupTracked(spawnerLocation.locationKey());
            Logger.debug(LogId.D_9012, definition.id(), worldTime);
            return;
        }

        int nearbyPlayers = playerCounts.gameplayPlayers();
        if (nearbyPlayers <= 0) {
            cleanupTracked(spawnerLocation.locationKey());
            Logger.debug(LogId.D_9013, definition.id(), playerCounts.playersInRange());
            return;
        }

        int desired = definition.desiredAliveCount(Math.min(MAX_PLAYER_SCALE, nearbyPlayers));
        int alive = cleanupTracked(spawnerLocation.locationKey());
        int nearbyGatherings = countNearbyGatherings(origin, definition.radiusMeters());
        if (alive >= desired || nearbyGatherings >= definition.maxNearbyGatherings()) {
            Logger.debug(
                    LogId.D_9014,
                    definition.id(),
                    alive,
                    desired,
                    nearbyGatherings,
                    definition.maxNearbyGatherings()
            );
            return;
        }

        GatheringSpawnerEntry entry = choose(definition.spawnGatherings());
        if (entry == null) {
            Logger.debug(LogId.D_9017, definition.id());
            return;
        }

        SpawnLocationSearchResult search = findHighestSpawnLocationResult(origin, definition);
        Location spawnLocation = search.location();
        if (spawnLocation == null) {
            int horizontalRadius = (int) Math.ceil(definition.radiusMeters());
            Logger.debug(
                    LogId.D_9018,
                    definition.id(),
                    origin.getWorld().getName(),
                    origin.getBlockX(),
                    origin.getBlockY(),
                    origin.getBlockZ(),
                    definition.radiusMeters(),
                    origin.getBlockX() - horizontalRadius,
                    origin.getBlockX() + horizontalRadius,
                    origin.getBlockZ() - horizontalRadius,
                    origin.getBlockZ() + horizontalRadius,
                    search.minCandidateY(),
                    search.maxCandidateY(),
                    search.columnsChecked(),
                    search.baseMatches(),
                    search.passableMatches(),
                    search.highestCandidateY()
            );
            return;
        }

        Block base = origin.getWorld().getBlockAt(
                spawnLocation.getBlockX(),
                spawnLocation.getBlockY() - 1,
                spawnLocation.getBlockZ()
        );
        Block spawnBlock = spawnLocation.getBlock();
        int horizontalRadius = (int) Math.ceil(definition.radiusMeters());
        double horizontalDistance = Math.sqrt(
                Math.pow(spawnLocation.getX() - origin.getX(), 2.0D)
                        + Math.pow(spawnLocation.getZ() - origin.getZ(), 2.0D)
        );
        double verticalDistance = Math.abs(spawnLocation.getY() - origin.getY());
        double threeDimensionalDistance = Math.sqrt(
                horizontalDistance * horizontalDistance + verticalDistance * verticalDistance
        );
        double effectiveHorizontalRadius = Math.sqrt(
                Math.max(0.0D, definition.radiusMeters() * definition.radiusMeters()
                        - verticalDistance * verticalDistance)
        );
        Logger.debug(
                LogId.D_9015,
                definition.id(),
                entry.gatheringId(),
                spawnLocation.getWorld().getName(),
                spawnLocation.getX(),
                spawnLocation.getY(),
                spawnLocation.getZ(),
                definition.radiusMeters(),
                horizontalDistance,
                effectiveHorizontalRadius,
                verticalDistance,
                threeDimensionalDistance,
                origin.getBlockX() - horizontalRadius,
                origin.getBlockX() + horizontalRadius,
                origin.getBlockZ() - horizontalRadius,
                origin.getBlockZ() + horizontalRadius,
                base.getType().name(),
                spawnBlock.getType().name()
        );
        GatheringInstance instance = gatheringService.spawn(entry.gatheringId(), spawnLocation, definition.id());
        if (instance != null) {
            spawnedByLocation.computeIfAbsent(spawnerLocation.locationKey(), key -> new HashSet<>())
                    .add(instance.instanceId());
            Logger.debug(
                    LogId.D_9016,
                    definition.id(),
                    entry.gatheringId(),
                    instance.instanceId(),
                    spawnLocation.getWorld().getName(),
                    spawnLocation.getX(),
                    spawnLocation.getY(),
                    spawnLocation.getZ()
            );
        } else {
            Logger.debug(
                    LogId.D_9019,
                    definition.id(),
                    entry.gatheringId(),
                    spawnLocation.getWorld().getName(),
                    spawnLocation.getX(),
                    spawnLocation.getY(),
                    spawnLocation.getZ()
            );
        }
    }

    private @NotNull NearbyPlayerCounts countNearbyPlayers(@NotNull Location origin, double radius) {
        double radiusSq = radius * radius;
        int playersInRange = 0;
        int gameplayPlayers = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != origin.getWorld() || player.getLocation().distanceSquared(origin) > radiusSq) {
                continue;
            }
            playersInRange++;
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                gameplayPlayers++;
            }
        }
        return new NearbyPlayerCounts(playersInRange, gameplayPlayers);
    }

    private int countNearbyGatherings(@NotNull Location origin, double radius) {
        double radiusSq = radius * radius;
        int count = 0;
        for (GatheringInstance instance : gatheringService.getInstances()) {
            Location current = instance.location();
            if (current.getWorld() == origin.getWorld() && current.distanceSquared(origin) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private int cleanupTracked(@NotNull String locationKey) {
        Set<UUID> ids = spawnedByLocation.computeIfAbsent(locationKey, key -> new HashSet<>());
        ids.removeIf(id -> gatheringService.getInstance(id) == null);
        return ids.size();
    }

    private @Nullable GatheringSpawnerEntry choose(@NotNull List<GatheringSpawnerEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        int total = entries.stream().mapToInt(GatheringSpawnerEntry::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int cursor = 0;
        for (GatheringSpawnerEntry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    /**
     * スポナー座標を中心とした半径内から、洞窟層に限定した最上位のスポーン地点を探します。
     * 水平方向は半径内の X/Z 列をすべて調査し、垂直方向は登録座標の Y 座標から半径分の範囲に限定します。
     * 各列では足元ブロックの条件と、スポーンブロックの passable / non-liquid 条件を満たす最上位候補を選び、
     * その中で最も高い候補を返します。
     *
     * @param origin     スポナーの登録座標
     * @param definition スポナー定義
     * @return 条件を満たす最上位のスポーン地点。候補がない場合は null
     */
    static @Nullable Location findHighestSpawnLocation(
            @NotNull Location origin,
            @NotNull GatheringSpawnerDefinition definition
    ) {
        return findHighestSpawnLocationResult(origin, definition).location();
    }

    /**
     * スポーン地点の検索結果と、候補が見つからない場合の切り分け情報を返します。
     *
     * @param origin     スポナーの登録座標
     * @param definition スポナー定義
     * @return スポーン地点と検索状況
     */
    static @NotNull SpawnLocationSearchResult findHighestSpawnLocationResult(
            @NotNull Location origin,
            @NotNull GatheringSpawnerDefinition definition
    ) {
        World world = origin.getWorld();
        if (world == null) {
            return SpawnLocationSearchResult.empty();
        }

        double radius = definition.radiusMeters();
        double radiusSq = radius * radius;
        int horizontalRadius = (int) Math.ceil(radius);
        int minCandidateY = Math.max(
                world.getMinHeight() + 1,
                (int) Math.ceil(origin.getY() - radius)
        );
        int maxCandidateY = Math.min(
                world.getMaxHeight() - 1,
                (int) Math.floor(origin.getY() + radius)
        );
        if (minCandidateY > maxCandidateY) {
            return new SpawnLocationSearchResult(
                    null,
                    0,
                    0,
                    0,
                    minCandidateY,
                    maxCandidateY,
                    null
            );
        }

        int highestCandidateY = Integer.MIN_VALUE;
        List<Location> highestCandidates = new ArrayList<>();
        int columnsChecked = 0;
        int baseMatches = 0;
        int passableMatches = 0;
        for (int x = origin.getBlockX() - horizontalRadius; x <= origin.getBlockX() + horizontalRadius; x++) {
            double dx = x + 0.5D - origin.getX();
            for (int z = origin.getBlockZ() - horizontalRadius; z <= origin.getBlockZ() + horizontalRadius; z++) {
                double dz = z + 0.5D - origin.getZ();
                double horizontalDistanceSq = dx * dx + dz * dz;
                if (horizontalDistanceSq > radiusSq) {
                    continue;
                }
                columnsChecked++;

                int highestBlockY = world.getHighestBlockYAt(x, z);
                int startCandidateY = Math.min(maxCandidateY, highestBlockY + 1);
                for (int candidateY = startCandidateY; candidateY >= minCandidateY; candidateY--) {
                    double dy = candidateY - origin.getY();
                    if (horizontalDistanceSq + dy * dy > radiusSq) {
                        continue;
                    }

                    Block base = world.getBlockAt(x, candidateY - 1, z);
                    if (!base.getType().isSolid()
                            || (!definition.requiredBaseBlocks().isEmpty()
                            && !definition.requiredBaseBlocks().contains(base.getType()))) {
                        continue;
                    }
                    baseMatches++;
                    Location candidate = new Location(world, x + 0.5D, candidateY, z + 0.5D);
                    Block spawnBlock = world.getBlockAt(x, candidateY, z);
                    if (!spawnBlock.isPassable() || spawnBlock.isLiquid()) {
                        continue;
                    }
                    passableMatches++;
                    if (candidateY > highestCandidateY) {
                        highestCandidateY = candidateY;
                        highestCandidates.clear();
                    }
                    if (candidateY == highestCandidateY) {
                        highestCandidates.add(candidate);
                    }
                    break;
                }
            }
        }
        Location location = highestCandidates.isEmpty()
                ? null
                : highestCandidates.get(ThreadLocalRandom.current().nextInt(highestCandidates.size()));
        return new SpawnLocationSearchResult(
                location,
                columnsChecked,
                baseMatches,
                passableMatches,
                minCandidateY,
                maxCandidateY,
                highestCandidateY == Integer.MIN_VALUE ? null : highestCandidateY
        );
    }

    private record NearbyPlayerCounts(int playersInRange, int gameplayPlayers) {
    }

    static record SpawnLocationSearchResult(
            @Nullable Location location,
            int columnsChecked,
            int baseMatches,
            int passableMatches,
            int minCandidateY,
            int maxCandidateY,
            @Nullable Integer highestCandidateY
    ) {
        private static @NotNull SpawnLocationSearchResult empty() {
            return new SpawnLocationSearchResult(null, 0, 0, 0, 0, 0, null);
        }
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        if (locationRepository.saveAll(new ArrayList<>(locations.values()))) {
            dirty = false;
        }
    }
}
