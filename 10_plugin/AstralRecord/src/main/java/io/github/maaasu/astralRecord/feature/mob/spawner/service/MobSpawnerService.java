package io.github.maaasu.astralRecord.feature.mob.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerEntry;
import io.github.maaasu.astralRecord.feature.mob.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.mob.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.mob.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mob スポナー定義・配置座標・スポーン制御を集約するサービスです。
 */
public class MobSpawnerService {

    private static final long TICK_INTERVAL = 20L;
    private static final long SAVE_INTERVAL = 20L * 60L;
    private static final int MAX_PLAYER_SCALE = 6;

    private final Plugin plugin;
    private final MobService mobService;
    private final MobSpawnerDefinitionRepository definitionRepository;
    private final MobSpawnerLocationRepository locationRepository;
    private final NamespacedKey spawnerIdKey;

    private final Map<String, MobSpawnerDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, MobSpawnerLocation> locations = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> spawnedByLocation = new HashMap<>();

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
     * @param definitionRepository スポナーマスタリポジトリ
     * @param locationRepository   スポナー座標リポジトリ
     */
    public MobSpawnerService(
            @NotNull Plugin plugin,
            @NotNull MobService mobService,
            @NotNull MobSpawnerDefinitionRepository definitionRepository,
            @NotNull MobSpawnerLocationRepository locationRepository
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.definitionRepository = definitionRepository;
        this.locationRepository = locationRepository;
        this.spawnerIdKey = new NamespacedKey(plugin, "mob_spawner_id");
    }

    /**
     * マスタ定義と座標ファイルを一括ロードします。
     *
     * @return ロードしたスポナー定義数
     */
    public int loadAll() {
        definitions.clear();
        for (MobSpawnerDefinition definition : definitionRepository.findAll()) {
            definitions.put(definition.id(), definition);
        }

        locations.clear();
        spawnedByLocation.clear();
        for (MobSpawnerLocation location : locationRepository.loadAll()) {
            locations.put(location.locationKey(), location);
            spawnedByLocation.put(location.locationKey(), new HashSet<>());
        }
        dirty = false;
        return definitions.size();
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
        if (visualizer == null) {
            visualizer = new MobSpawnerVisualizer(plugin, this);
            visualizer.start();
        }
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
     * 管理者用スポナーアイテムを作成します。
     *
     * @param spawnerId スポナー ID
     * @param amount    個数
     * @return ItemStack。定義がない場合は null
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
            meta.displayName(Component.text(ColorCodeUtil.translateAlternateColorCodes("&dSpawner: &f" + spawnerId)));
            meta.lore(List.of(
                    Component.text(ColorCodeUtil.translateAlternateColorCodes("&7Mob spawner placement item")),
                    Component.text(ColorCodeUtil.translateAlternateColorCodes("&7id: &f" + spawnerId))
            ));
            meta.addItemFlags(ItemFlag.values());
            meta.getPersistentDataContainer().set(spawnerIdKey, PersistentDataType.STRING, spawnerId);
            itemStack.setItemMeta(meta);
        }
        return itemStack;
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
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
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

    private void tick() {
        tick += TICK_INTERVAL;
        for (MobSpawnerLocation spawnerLocation : List.copyOf(locations.values())) {
            processSpawner(spawnerLocation);
        }
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
        double angle = random.nextDouble(0.0D, Math.PI * 2.0D);
        double distance = Math.sqrt(random.nextDouble()) * radius;
        Location location = origin.clone().add(Math.cos(angle) * distance, 0.0D, Math.sin(angle) * distance);
        World world = location.getWorld();
        if (world == null) {
            return location;
        }
        int highestY = world.getHighestBlockYAt(location);
        location.setY(Math.max(location.getY(), highestY + 1.0D));
        return location;
    }

    private void saveIfDirty() {
        if (!dirty) {
            return;
        }
        locationRepository.saveAll(new ArrayList<>(locations.values()));
        dirty = false;
    }
}
