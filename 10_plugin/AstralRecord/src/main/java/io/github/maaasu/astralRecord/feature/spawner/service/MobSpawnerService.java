package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerEntry;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerTimeWindow;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
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
import java.util.function.Function;

/**
 * Mob スポナー定義・配置座標・スポーン制御を集約するサービスです。
 */
public class MobSpawnerService {

    private static final long TICK_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 20L * 60L;
    private static final int MAX_PLAYER_SCALE = 6;
    private static final int SPAWN_LOCATION_ATTEMPTS = 24;
    private static final int INTERIOR_Y_OFFSET = -3;
    private static final int REQUIRED_SPAWN_SPACE_BLOCKS = 2;

    private final Plugin plugin;
    private final MobService mobService;
    private final PlayerRegionService playerRegionService;
    private final MobSpawnerDefinitionRepository definitionRepository;
    private final MobSpawnerLocationRepository locationRepository;
    private final NamespacedKey spawnerIdKey;

    private final Map<String, MobSpawnerDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Integer> regionLevelByName = new HashMap<>();
    private final Map<String, MobSpawnerLocation> locations = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> spawnedByLocation = new HashMap<>();

    private ParticleDisplayService particleDisplayService;
    private BukkitTask task;
    private BukkitTask saveTask;
    private MobSpawnerVisualizer visualizer;
    private long tick;
    private boolean dirty;

    /**
     * サービスを構築します。
     *
     * @param plugin               プラグイン本体
     * @param mobService           Mob サービス
     * @param playerRegionService  プレイヤー地域サービス
     * @param definitionRepository スポナーマスタリポジトリ
     * @param locationRepository   スポナー座標リポジトリ
     */
    public MobSpawnerService(
            @NotNull Plugin plugin,
            @NotNull MobService mobService,
            @NotNull PlayerRegionService playerRegionService,
            @NotNull MobSpawnerDefinitionRepository definitionRepository,
            @NotNull MobSpawnerLocationRepository locationRepository
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.playerRegionService = playerRegionService;
        this.definitionRepository = definitionRepository;
        this.locationRepository = locationRepository;
        this.spawnerIdKey = new NamespacedKey(plugin, "mob_spawner_id");
    }

    /**
     * マスタ定義と座標ファイルを一括ロードし、地域ごとの出現 Mob 平均レベルを再計算します。
     *
     * @return ロードしたスポナー定義数
     */
    public int loadAll() {
        MasterDataSnapshot snapshot = loadMasterDataSnapshot();
        replaceMasterDataSnapshot(snapshot);
        return definitions.size();
    }

    /**
     * スポナー定義と配置 YAML を読み込み、公開前のスナップショットを作成します。
     *
     * @return スポナーマスタスナップショット
     */
    public @NotNull MasterDataSnapshot loadMasterDataSnapshot() {
        return new MasterDataSnapshot(
                List.copyOf(definitionRepository.findAll()),
                List.copyOf(locationRepository.loadAll())
        );
    }

    /**
     * 準備済みスポナーマスタを実行時キャッシュへ一括反映します。
     *
     * @param snapshot スポナーマスタスナップショット
     */
    public void replaceMasterDataSnapshot(@NotNull MasterDataSnapshot snapshot) {
        definitions.clear();
        for (MobSpawnerDefinition definition : snapshot.definitions()) {
            definitions.put(definition.id(), definition);
        }
        regionLevelByName.clear();
        regionLevelByName.putAll(calculateRegionLevels(
                definitions.values(),
                mobId -> {
                    var template = mobService.findTemplate(mobId);
                    return template == null ? null : template.level();
                }
        ));

        locations.clear();
        spawnedByLocation.clear();
        for (MobSpawnerLocation location : snapshot.locations()) {
            locations.put(location.locationKey(), location);
            spawnedByLocation.put(location.locationKey(), new HashSet<>());
        }
        dirty = false;
    }

    /** 公開前に準備したスポナー定義と配置の immutable スナップショットです。 */
    public record MasterDataSnapshot(
            @NotNull List<MobSpawnerDefinition> definitions,
            @NotNull List<MobSpawnerLocation> locations
    ) {
    }

    /**
     * スポーン処理と座標オートセーブを開始します。
     */
    public void start() {
        if (task == null) {
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, TICK_INTERVAL, TICK_INTERVAL);
        }
        if (saveTask == null) {
            saveTask = Bukkit.getScheduler().runTaskTimer(plugin, this::saveIfDirty, SAVE_INTERVAL, SAVE_INTERVAL);
        }
        if (visualizer == null && particleDisplayService != null) {
            visualizer = new MobSpawnerVisualizer(plugin, this, particleDisplayService);
            visualizer.start();
        }
    }

    public void setParticleDisplayService(@NotNull ParticleDisplayService particleDisplayService) {
        this.particleDisplayService = particleDisplayService;
    }

    /**
     * スポナー関連タスクを停止し、未保存座標を保存します。
     */
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
     * 管理者用の Mob スポナー設置アイテムを作成します。
     * lore には定義 ID、スポーン対象、時間帯、半径、判定間隔、上限値など、設置前に確認できる
     * スポナー情報を日本語で表示します。
     *
     * @param spawnerId スポナー ID
     * @param amount    作成個数。1 未満の場合は 1 として扱います。
     * @return スポナー設置用 ItemStack。定義が存在しない場合は null
     */
    @Nullable
    public ItemStack createSpawnerItem(@NotNull String spawnerId, int amount) {
        MobSpawnerDefinition definition = definitions.get(spawnerId);
        if (definition == null) {
            return null;
        }
        ItemStack itemStack = new ItemStack(definition.itemMaterial(), Math.max(1, amount));
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(PlayerMsgResource.formatComponent(PlayerMsgId.P_5730.getId(), spawnerId));
            meta.lore(buildSpawnerLore(definition));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(spawnerIdKey, PersistentDataType.STRING, spawnerId);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    @NotNull
    private List<Component> buildSpawnerLore(@NotNull MobSpawnerDefinition definition) {
        List<String> lore = new ArrayList<>();
        lore.add("&7モブスポナー設置アイテム");
        lore.add("");
        lore.add("&e基本情報");
        lore.add("&7ID: &f" + definition.id());
        lore.add("&7種別: &fモブスポナー");
        lore.add("&7地域: &f" + (definition.region() == null ? "未設定" : definition.region()));
        lore.add("&7表示ブロック: &f" + definition.itemMaterial().name());
        lore.add("");
        lore.add("&eスポーン条件");
        lore.add("&7半径: &f" + formatMeters(definition.radiusMeters()));
        lore.add("&7判定間隔: &f" + formatTicks(definition.spawnIntervalTicks()));
        lore.add("&7時間帯: &f" + formatTimeWindows(definition.timeWindows()));
        lore.add("");
        lore.add("&e出現対象");
        if (definition.spawnMobs().isEmpty()) {
            lore.add("&7 - なし");
        } else {
            for (MobSpawnerEntry entry : definition.spawnMobs()) {
                lore.add("&7 - &f" + entry.mobId() + " &7(重み " + entry.weight() + ")");
            }
        }
        lore.add("");
        lore.add("&e上限");
        lore.add("&7スポナー単位: &f" + definition.maxAlivePerSpawner() + " 体");
        lore.add("&7周辺 Mob: &f" + definition.maxNearbyMobs() + " 体");
        lore.add("&7プレイヤーあたり: &f" + definition.spawnPerPlayer() + " 体");
        return lore.stream()
                .<Component>map(line -> Component.text(ColorCodeUtil.translateAlternateColorCodes(line)))
                .toList();
    }

    @NotNull
    private String formatMeters(double meters) {
        return String.format(Locale.ROOT, "%.1fm", meters);
    }

    @NotNull
    private String formatTicks(long ticks) {
        double seconds = ticks / 20.0D;
        return String.format(Locale.ROOT, "%d tick / %.1f秒", ticks, seconds);
    }

    @NotNull
    private String formatTimeWindows(@NotNull List<MobSpawnerTimeWindow> windows) {
        List<String> formatted = new ArrayList<>();
        for (MobSpawnerTimeWindow window : windows) {
            if (window.startTick() == 0L && window.endTick() == 23999L) {
                formatted.add("終日");
            } else {
                formatted.add(window.startTick() + "-" + window.endTick() + " tick");
            }
        }
        return String.join(", ", formatted);
    }

    /**
     * ItemStack に保存されたスポナー ID を読み取ります。
     *
     * @param itemStack 対象 ItemStack
     * @return スポナー ID。該当しない場合は null
     */
    @Nullable
    public String readSpawnerId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(spawnerIdKey, PersistentDataType.STRING);
    }

    /**
     * スポナー座標を登録します。同一座標は ID に関係なく登録できません。
     *
     * @param spawnerId スポナー ID
     * @param location  登録座標
     * @return 登録できた場合は true
     */
    public boolean registerLocation(@NotNull String spawnerId, @NotNull Location location) {
        if (!definitions.containsKey(spawnerId)) {
            return false;
        }
        MobSpawnerLocation spawnerLocation = MobSpawnerLocation.from(spawnerId, location);
        if (locations.containsKey(spawnerLocation.locationKey())) {
            return false;
        }
        locations.put(spawnerLocation.locationKey(), spawnerLocation);
        spawnedByLocation.put(spawnerLocation.locationKey(), new HashSet<>());
        dirty = true;
        return true;
    }

    /**
     * 指定座標に登録されたスポナーを削除します。
     *
     * @param location 対象座標
     * @return 削除した場合は true
     */
    public boolean removeLocation(@NotNull Location location) {
        String key = MobSpawnerLocation.from("_", location).locationKey();
        boolean removed = locations.remove(key) != null;
        spawnedByLocation.remove(key);
        if (removed) {
            dirty = true;
        }
        return removed;
    }

    /**
     * 指定座標にスポナーが登録されているか返します。
     *
     * @param location 対象座標
     * @return 登録済みなら true
     */
    public boolean hasLocation(@NotNull Location location) {
        return locations.containsKey(MobSpawnerLocation.from("_", location).locationKey());
    }

    /**
     * 管理者モードか判定します。
     *
     * @param astPlayer 対象プレイヤー
     * @return ADMIN モードなら true
     */
    public boolean isAdminMode(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.hasAdminPermission();
    }

    /**
     * スポナー表示を見せるアカウントモードか判定します。
     *
     * @param astPlayer 対象プレイヤー
     * @return ADMIN モードなら true
     */
    public boolean canViewSpawnerVisual(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
    }

    /**
     * スポナー定義の表示 Material を返します。
     *
     * @param spawnerId スポナー ID
     * @return 定義済み Material。未ロードなら SPAWNER
     */
    @NotNull
    public Material getDisplayMaterial(@NotNull String spawnerId) {
        MobSpawnerDefinition definition = definitions.get(spawnerId);
        return definition == null ? Material.SPAWNER : definition.itemMaterial();
    }

    /**
     * ロード済みスポナー ID 一覧を返します。
     *
     * @return スポナー ID 一覧
     */
    @NotNull
    public Collection<String> getLoadedSpawnerIds() {
        return List.copyOf(definitions.keySet());
    }

    /**
     * 登録済み座標を返します。
     *
     * @return 座標一覧
     */
    @NotNull
    public Collection<MobSpawnerLocation> getLocations() {
        return List.copyOf(locations.values());
    }

    /**
     * 地域判定と各スポナーのスポーン判定を1秒周期で実行します。
     */
    private void tick() {
        tick += TICK_INTERVAL;
        updateNearbyPlayerRegions();
        for (MobSpawnerLocation spawnerLocation : List.copyOf(locations.values())) {
            processSpawner(spawnerLocation);
        }
    }

    /**
     * 各オーバーワールドプレイヤーについて、範囲内で最も近い地域付きスポナーを地域として反映します。
     * 範囲内に地域付きスポナーがない場合は「オーバーワールド」へ戻します。
     */
    private void updateNearbyPlayerRegions() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null
                    || !astPlayer.getAccount().getMode().shouldProcessGameplay()
                    || !playerRegionService.isSpawnerRegionWorld(player.getWorld())) {
                continue;
            }

            PlayerRegionCandidate nearest = null;
            for (MobSpawnerLocation spawnerLocation : locations.values()) {
                MobSpawnerDefinition definition = definitions.get(spawnerLocation.spawnerId());
                if (definition == null || definition.region() == null) {
                    continue;
                }
                Location origin = spawnerLocation.toLocation();
                if (origin == null || origin.getWorld() != player.getWorld()) {
                    continue;
                }
                double distanceSquared = player.getLocation().distanceSquared(origin);
                double radiusSquared = definition.radiusMeters() * definition.radiusMeters();
                if (distanceSquared > radiusSquared) {
                    continue;
                }
                PlayerRegionCandidate candidate = new PlayerRegionCandidate(
                        definition.region(),
                        regionLevelByName.getOrDefault(definition.region(), 0),
                        distanceSquared,
                        spawnerLocation.locationKey()
                );
                if (nearest == null || candidate.isPreferredTo(nearest)) {
                    nearest = candidate;
                }
            }

            if (nearest == null) {
                playerRegionService.resetOverworldRegion(astPlayer);
            } else {
                playerRegionService.updateRegionFromSpawner(astPlayer, nearest.region(), nearest.regionLevel());
            }
        }
    }

    /**
     * 地域ごとに、所属スポナーの出現 Mob を抽選重みで加重した平均レベルを算出します。
     * 解決できない Mob は集計から除外し、有効な Mob がない地域はレベル 0 とします。
     *
     * @param definitions 集計対象スポナー定義
     * @param levelResolver Mob ID からレベルを返す解決処理。未解決時は {@code null}
     * @return 地域名をキーとする平均レベル
     */
    @NotNull
    static Map<String, Integer> calculateRegionLevels(
            @NotNull Collection<MobSpawnerDefinition> definitions,
            @NotNull Function<String, Integer> levelResolver
    ) {
        Map<String, long[]> totalsByRegion = new HashMap<>();
        for (MobSpawnerDefinition definition : definitions) {
            if (definition.region() == null) {
                continue;
            }
            long[] totals = totalsByRegion.computeIfAbsent(definition.region(), ignored -> new long[2]);
            for (MobSpawnerEntry entry : definition.spawnMobs()) {
                Integer level = levelResolver.apply(entry.mobId());
                if (level == null) {
                    continue;
                }
                int weight = Math.max(1, entry.weight());
                totals[0] += (long) Math.max(0, level) * weight;
                totals[1] += weight;
            }
        }

        Map<String, Integer> levels = new HashMap<>();
        totalsByRegion.forEach((region, totals) -> levels.put(
                region,
                totals[1] == 0L ? 0 : (int) Math.round((double) totals[0] / totals[1])
        ));
        return Map.copyOf(levels);
    }

    private void processSpawner(@NotNull MobSpawnerLocation spawnerLocation) {
        MobSpawnerDefinition definition = definitions.get(spawnerLocation.spawnerId());
        Location origin = spawnerLocation.toLocation();
        if (definition == null || origin == null || origin.getWorld() == null) {
            return;
        }
        if (tick % definition.spawnIntervalTicks() != 0L || !definition.canSpawnAt(origin.getWorld().getTime())) {
            cleanupTracked(spawnerLocation.locationKey());
            return;
        }

        int nearbyPlayers = countNearbyGameplayPlayers(origin, definition.radiusMeters());
        if (nearbyPlayers <= 0) {
            cleanupTracked(spawnerLocation.locationKey());
            return;
        }

        int desired = definition.desiredAliveCount(Math.min(MAX_PLAYER_SCALE, nearbyPlayers));
        int alive = cleanupTracked(spawnerLocation.locationKey());
        if (alive >= desired || countNearbyMobs(origin, definition.radiusMeters()) >= definition.maxNearbyMobs()) {
            return;
        }

        MobSpawnerEntry entry = choose(definition.spawnMobs());
        if (entry == null) {
            return;
        }
        MobInstance instance = mobService.spawn(entry.mobId(), randomSpawnLocation(origin, definition.radiusMeters()));
        if (instance != null) {
            spawnedByLocation.computeIfAbsent(spawnerLocation.locationKey(), key -> new HashSet<>())
                    .add(instance.instanceId());
        }
    }

    private int countNearbyGameplayPlayers(@NotNull Location origin, double radius) {
        double radiusSq = radius * radius;
        int count = 0;
        for (Player player : Bukkit.getOnlinePlayers()) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.getAccount().getMode().shouldProcessGameplay()) {
                continue;
            }
            if (player.getWorld() == origin.getWorld() && player.getLocation().distanceSquared(origin) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private int countNearbyMobs(@NotNull Location origin, double radius) {
        double radiusSq = radius * radius;
        int count = 0;
        for (MobInstance instance : mobService.getInstances()) {
            Location current = instance.currentLocation();
            if (current.getWorld() == origin.getWorld() && current.distanceSquared(origin) <= radiusSq) {
                count++;
            }
        }
        return count;
    }

    private int cleanupTracked(@NotNull String locationKey) {
        Set<UUID> ids = spawnedByLocation.computeIfAbsent(locationKey, key -> new HashSet<>());
        ids.removeIf(id -> mobService.getInstance(id) == null);
        return ids.size();
    }

    @Nullable
    private MobSpawnerEntry choose(@NotNull List<MobSpawnerEntry> entries) {
        if (entries.isEmpty()) {
            return null;
        }
        int total = entries.stream().mapToInt(MobSpawnerEntry::weight).sum();
        int roll = ThreadLocalRandom.current().nextInt(Math.max(1, total));
        int cursor = 0;
        for (MobSpawnerEntry entry : entries) {
            cursor += entry.weight();
            if (roll < cursor) {
                return entry;
            }
        }
        return entries.get(entries.size() - 1);
    }

    @NotNull
    private Location randomSpawnLocation(@NotNull Location origin, double radius) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        World world = origin.getWorld();
        if (world == null) {
            return origin.clone();
        }

        int minInteriorY = Math.max(world.getMinHeight() + 1, origin.getBlockY() + INTERIOR_Y_OFFSET);
        int maxSpawnY = Math.min(world.getMaxHeight() - REQUIRED_SPAWN_SPACE_BLOCKS, (int) Math.floor(origin.getY() + radius));
        for (int attempt = 0; attempt < SPAWN_LOCATION_ATTEMPTS; attempt++) {
            Location horizontalCandidate = randomHorizontalLocation(origin, radius, random);
            Location interiorLocation = randomInteriorSpawnLocation(horizontalCandidate, minInteriorY, maxSpawnY, random);
            if (interiorLocation != null) {
                return interiorLocation;
            }
            Location surfaceLocation = surfaceSpawnLocation(horizontalCandidate, maxSpawnY);
            if (surfaceLocation != null) {
                return surfaceLocation;
            }
        }

        Location surfaceLocation = surfaceSpawnLocation(origin, maxSpawnY);
        if (surfaceLocation != null) {
            return surfaceLocation;
        }
        return origin.clone();
    }

    @NotNull
    private Location randomHorizontalLocation(
            @NotNull Location origin,
            double radius,
            @NotNull ThreadLocalRandom random
    ) {
        double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
        double distance = Math.sqrt(random.nextDouble()) * radius;
        return origin.clone().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
    }

    @Nullable
    private Location randomInteriorSpawnLocation(
            @NotNull Location horizontalCandidate,
            int minInteriorY,
            int maxSpawnY,
            @NotNull ThreadLocalRandom random
    ) {
        if (minInteriorY > maxSpawnY) {
            return null;
        }

        int yCount = maxSpawnY - minInteriorY + 1;
        int startOffset = random.nextInt(yCount);
        for (int offset = 0; offset < yCount; offset++) {
            int y = minInteriorY + ((startOffset + offset) % yCount);
            Location candidate = horizontalCandidate.clone();
            candidate.setY(y);
            if (isSpawnSpace(candidate)) {
                return blockCenter(candidate);
            }
        }
        return null;
    }

    @Nullable
    private Location surfaceSpawnLocation(@NotNull Location horizontalCandidate, int maxSpawnY) {
        World world = horizontalCandidate.getWorld();
        if (world == null) {
            return null;
        }

        int spawnY = world.getHighestBlockYAt(horizontalCandidate) + 1;
        if (spawnY > maxSpawnY) {
            return null;
        }
        Location candidate = horizontalCandidate.clone();
        candidate.setY(spawnY);
        return isSpawnSpace(candidate) ? blockCenter(candidate) : null;
    }

    private boolean isSpawnSpace(@NotNull Location location) {
        World world = location.getWorld();
        if (world == null) {
            return false;
        }

        int x = location.getBlockX();
        int y = location.getBlockY();
        int z = location.getBlockZ();
        if (y <= world.getMinHeight() || y + 1 >= world.getMaxHeight()) {
            return false;
        }
        Block ground = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        return ground.getType().isSolid()
                && feet.isPassable()
                && !feet.isLiquid()
                && head.isPassable()
                && !head.isLiquid();
    }

    @NotNull
    private Location blockCenter(@NotNull Location location) {
        return new Location(
                location.getWorld(),
                location.getBlockX() + 0.5D,
                location.getBlockY(),
                location.getBlockZ() + 0.5D
        );
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        if (locationRepository.saveAll(new ArrayList<>(locations.values()))) {
            dirty = false;
        }
    }

    private record PlayerRegionCandidate(
            @NotNull String region,
            int regionLevel,
            double distanceSquared,
            @NotNull String locationKey
    ) {
        /**
         * 距離を優先し、同距離では配置キー順で決定的に候補を選びます。
         *
         * @param other 比較対象候補
         * @return この候補を優先する場合は {@code true}
         */
        private boolean isPreferredTo(@NotNull PlayerRegionCandidate other) {
            int distanceComparison = Double.compare(distanceSquared, other.distanceSquared);
            return distanceComparison < 0
                    || (distanceComparison == 0 && locationKey.compareTo(other.locationKey) < 0);
        }
    }
}
