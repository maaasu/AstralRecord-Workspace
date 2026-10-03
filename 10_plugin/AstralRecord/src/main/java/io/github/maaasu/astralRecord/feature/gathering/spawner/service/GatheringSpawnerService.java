package io.github.maaasu.astralRecord.feature.gathering.spawner.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.gathering.model.GatheringDefinition;
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
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
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
    private static final int SEARCH_STEPS_PER_TICK = 1024;
    private static final long SEARCH_NANOS_PER_TICK = 1_000_000L;
    private static final long SPAWN_GAP_TICKS = 5L;

    private final Plugin plugin;
    private final GatheringService gatheringService;
    private final GatheringSpawnerDefinitionRepository definitionRepository;
    private final GatheringSpawnerLocationRepository locationRepository;
    private final NamespacedKey spawnerIdKey;
    private final Map<String, GatheringSpawnerDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, GatheringSpawnerLocation> locations = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> spawnedByLocation = new HashMap<>();
    private final Deque<GatheringSpawnerLocation> pendingSpawns = new ArrayDeque<>();
    private final Set<String> queuedLocations = new HashSet<>();
    private PendingSpawn activeSpawn;
    private ParticleDisplayService particleDisplayService;
    private GatheringSpawnerVisualizer visualizer;
    private BukkitTask task;
    private BukkitTask saveTask;
    private long tick;
    private long nextSpawnTick;
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
        clearPendingSpawns();
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

    /** 同期スレッドで探索を毎tick分割実行し、保存・表示taskを開始します。 */
    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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

    /** 同期スレッドから、保存済みの共通表示モードを採集スポナー表示へ即時反映します。 */
    public void refreshVisuals() {
        if (visualizer != null) {
            visualizer.refresh();
        }
    }

    /** 同期スレッドで全taskと未完了の探索を破棄し、dirty配置を保存します。 */
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
        clearPendingSpawns();
        saveIfDirty();
    }

    /**
     * 管理者向けに表示するスポナー名を、出現対象採集物の日本語表示名から生成します。
     *
     * @param spawnerId スポナー ID
     * @return 出現対象の表示名一覧。定義がない、または対象がない場合は汎用表示
     */
    public @NotNull String getSpawnerDisplayName(@NotNull String spawnerId) {
        GatheringSpawnerDefinition definition = definitions.get(spawnerId);
        if (definition == null || definition.spawnGatherings().isEmpty()) {
            return "出現対象なし";
        }

        List<String> names = new ArrayList<>();
        for (GatheringSpawnerEntry entry : definition.spawnGatherings()) {
            String name = resolveGatheringDisplayName(entry.gatheringId());
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "出現対象なし" : String.join("、", names);
    }

    /**
     * 管理者用の採集スポナー設置アイテムを作成します。
     * lore には種別、採集対象の日本語表示名、時間帯、半径、判定間隔、上限値、足元ブロック条件など、
     * 設置前に確認できるスポナー情報を表示します。
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
            meta.displayName(ColorCodeUtil.toComponent(
                    "&b採集スポナー: &f" + getSpawnerDisplayName(spawnerId),
                    "採集スポナー"
            ));
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
                lore.add("&7 - &f" + resolveGatheringDisplayName(entry.gatheringId())
                        + " &7(重み " + entry.weight() + ")");
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

    private @NotNull String resolveGatheringDisplayName(@NotNull String gatheringId) {
        GatheringDefinition definition = gatheringService.findDefinition(gatheringId);
        if (definition == null) {
            return "未登録の採集物";
        }
        String plainName = ColorCodeUtil.toPlainText(definition.name(), "").trim();
        String normalizedGatheringId = gatheringId.indexOf(':') >= 0
                ? gatheringId.substring(gatheringId.indexOf(':') + 1).trim()
                : gatheringId;
        if (plainName.isBlank() || plainName.equalsIgnoreCase(normalizedGatheringId)) {
            return "未登録の採集物";
        }
        return ColorCodeUtil.toLegacyText(definition.name(), "未登録の採集物");
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

    /** 同期スレッドで判定対象を重複なく待機列へ追加し、1件の探索だけを予算内で進めます。 */
    private void tick() {
        tick++;
        if (tick % TICK_INTERVAL == 0L) {
            for (GatheringSpawnerLocation location : locations.values()) {
                GatheringSpawnerDefinition definition = definitions.get(location.spawnerId());
                if (definition != null && tick % definition.spawnIntervalTicks() == 0L
                        && queuedLocations.add(location.locationKey())) {
                    pendingSpawns.addLast(location);
                }
            }
        }
        if (activeSpawn == null) {
            GatheringSpawnerLocation location = pendingSpawns.pollFirst();
            if (location == null) {
                return;
            }
            activeSpawn = beginSpawn(location);
            if (activeSpawn == null) {
                queuedLocations.remove(location.locationKey());
                return;
            }
        }

        PendingSpawn pending = activeSpawn;
        Location origin = pending.location().toLocation();
        if (locations.get(pending.location().locationKey()) != pending.location()
                || definitions.get(pending.definition().id()) != pending.definition()
                || origin == null || origin.getWorld() != pending.world()) {
            finishPendingSpawn();
            return;
        }
        if (!pending.search().advance(SEARCH_STEPS_PER_TICK, System.nanoTime() + SEARCH_NANOS_PER_TICK)
                || tick < nextSpawnTick) {
            return;
        }

        Location spawnLocation = pending.search().result();
        if (spawnLocation != null && canSpawn(pending.location(), pending.definition(), origin)
                && GatheringSpawnSearch.isValidSpawnLocation(spawnLocation, pending.definition())) {
            GatheringInstance instance = gatheringService.spawn(
                    pending.entry().gatheringId(), spawnLocation, pending.definition().id());
            if (instance != null) {
                spawnedByLocation.computeIfAbsent(pending.location().locationKey(), key -> new HashSet<>())
                        .add(instance.instanceId());
                nextSpawnTick = tick + SPAWN_GAP_TICKS;
            }
        }
        finishPendingSpawn();
    }

    /**
     * 現在も有効な配置の条件を確認し、同期探索の継続状態を作成します。
     *
     * @param spawnerLocation 待機列から取り出した配置
     * @return 探索状態。配置または生成条件が無効ならnull
     */
    private @Nullable PendingSpawn beginSpawn(@NotNull GatheringSpawnerLocation spawnerLocation) {
        GatheringSpawnerDefinition definition = definitions.get(spawnerLocation.spawnerId());
        Location origin = spawnerLocation.toLocation();
        if (locations.get(spawnerLocation.locationKey()) != spawnerLocation
                || definition == null || origin == null || origin.getWorld() == null
                || !canSpawn(spawnerLocation, definition, origin)) {
            return null;
        }
        GatheringSpawnerEntry entry = choose(definition.spawnGatherings());
        if (entry == null || !gatheringService.hasDefinition(entry.gatheringId())) {
            return null;
        }
        return new PendingSpawn(spawnerLocation, definition, entry, origin.getWorld(),
                new GatheringSpawnSearch(origin, definition, ThreadLocalRandom.current()));
    }

    /**
     * 探索開始前と生成直前に、時刻・周辺プレイヤー・出現数上限を再検証します。
     *
     * @param spawnerLocation 対象配置
     * @param definition 公開中の定義
     * @param origin ロード済みworldを持つ登録座標
     * @return 生成枠が残り、生成条件を満たす場合はtrue
     */
    private boolean canSpawn(@NotNull GatheringSpawnerLocation spawnerLocation,
                             @NotNull GatheringSpawnerDefinition definition, @NotNull Location origin) {
        int alive = cleanupTracked(spawnerLocation.locationKey());
        long worldTime = origin.getWorld().getTime();
        int nearbyPlayers = countNearbyGameplayPlayers(origin, definition.radiusMeters());
        if (nearbyPlayers <= 0 || !definition.canSpawnAt(worldTime)) {
            return false;
        }
        int desired = definition.desiredAliveCount(Math.min(MAX_PLAYER_SCALE, nearbyPlayers));
        return alive < desired
                && countNearbyGatherings(origin, definition.radiusMeters()) < definition.maxNearbyGatherings();
    }

    /** 完了または失効した探索を解除し、次回の判定で再び待機できる状態へ戻します。 */
    private void finishPendingSpawn() {
        queuedLocations.remove(activeSpawn.location().locationKey());
        activeSpawn = null;
    }

    /** マスタ再読込・停止時に旧配置の待機列と探索を破棄し、直近生成からの間隔は維持します。 */
    private void clearPendingSpawns() {
        pendingSpawns.clear();
        queuedLocations.clear();
        activeSpawn = null;
    }

    private record PendingSpawn(GatheringSpawnerLocation location, GatheringSpawnerDefinition definition,
                                GatheringSpawnerEntry entry, World world, GatheringSpawnSearch search) {
    }

    /**
     * 出現数の計算対象になる、同一world・球形範囲内のgameplayプレイヤーを数えます。
     *
     * @param origin 登録座標
     * @param radius 判定半径
     * @return 対象プレイヤー数
     */
    private int countNearbyGameplayPlayers(@NotNull Location origin, double radius) {
        double radiusSq = radius * radius;
        int gameplayPlayers = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getWorld() != origin.getWorld() || player.getLocation().distanceSquared(origin) > radiusSq) {
                continue;
            }
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null && astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                gameplayPlayers++;
            }
        }
        return gameplayPlayers;
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

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        if (locationRepository.saveAll(new ArrayList<>(locations.values()))) {
            dirty = false;
        }
    }
}
