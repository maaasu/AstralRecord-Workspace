package io.github.maaasu.astralRecord.feature.spawner.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerDefinition;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerEntry;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerLocation;
import io.github.maaasu.astralRecord.feature.spawner.model.MobSpawnerTimeWindow;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerDefinitionRepository;
import io.github.maaasu.astralRecord.feature.spawner.repository.MobSpawnerLocationRepository;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.network.NetworkAuthorityRegistry;
import io.github.maaasu.astralRecord.feature.network.NetworkChannelAccessRegistry;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.player.service.PlayerRegionService;
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
    private static final int SPAWN_SEARCH_CHECKS_PER_TICK = 32;

    private final Plugin plugin;
    private final MobService mobService;
    private final PlayerRegionService playerRegionService;
    private final MobSpawnerDefinitionRepository definitionRepository;
    private final MobSpawnerLocationRepository locationRepository;
    private final NamespacedKey spawnerIdKey;
    private final NamespacedKey visualModeKey;

    private final Map<String, MobSpawnerDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, Integer> regionLevelByName = new HashMap<>();
    private final Map<String, MobSpawnerLocation> locations = new LinkedHashMap<>();
    private final Map<String, Set<UUID>> spawnedByLocation = new HashMap<>();
    private final LinkedHashMap<String, SpawnAttempt> pendingSpawns = new LinkedHashMap<>();

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
        this.visualModeKey = new NamespacedKey(plugin, "mob_spawner_visual_mode");
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
                (mobId, level) -> {
                    var template = mobService.findTemplate(mobId);
                    return template == null ? null : template.resolveLevel(level).level();
                }
        ));

        locations.clear();
        spawnedByLocation.clear();
        pendingSpawns.clear();
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
            task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
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
        pendingSpawns.clear();
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
     * 管理者向けに表示するスポナー名を、出現対象 Mob の日本語表示名から生成します。
     *
     * @param spawnerId スポナー ID
     * @return 出現対象の表示名一覧。定義がない、または対象がない場合は汎用表示
     */
    @NotNull
    public String getSpawnerDisplayName(@NotNull String spawnerId) {
        MobSpawnerDefinition definition = definitions.get(spawnerId);
        if (definition == null || definition.spawnMobs().isEmpty()) {
            return "出現対象なし";
        }

        List<String> names = new ArrayList<>();
        for (MobSpawnerEntry entry : definition.spawnMobs()) {
            String name = resolveMobDisplayName(entry.mobId(), entry.level());
            if (!names.contains(name)) {
                names.add(name);
            }
        }
        return names.isEmpty() ? "出現対象なし" : String.join("、", names);
    }

    /**
     * 管理者用の Mob スポナー設置アイテムを作成します。
     * lore には種別、スポーン対象の日本語表示名、時間帯、半径、判定間隔、上限値など、
     * 設置前に確認できるスポナー情報を表示します。
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
            meta.displayName(PlayerMsgResource.formatComponent(
                    PlayerMsgId.P_5730.getId(),
                    getSpawnerDisplayName(spawnerId)
            ));
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
                String level = entry.level() == null ? "最低レベル" : "Lv." + entry.level();
                lore.add("&7 - &f" + resolveMobDisplayName(entry.mobId(), entry.level())
                        + " &7(" + level + ", 重み " + entry.weight() + ")");
            }
        }
        lore.add("");
        lore.add("&e上限");
        lore.add("&7スポナー単位: &f" + definition.maxAlivePerSpawner() + " 体");
        lore.add("&7周辺 Mob: &f" + definition.maxNearbyMobs() + " 体");
        lore.add("&7プレイヤーあたり: &f" + definition.spawnPerPlayer() + " 体");
        return lore.stream()
                .map(line -> ColorCodeUtil.toComponent(line, ""))
                .toList();
    }

    @NotNull
    private String resolveMobDisplayName(@NotNull String mobId, @Nullable Integer level) {
        MobTemplate template = mobService.findTemplate(mobId);
        if (template == null && mobId.indexOf(':') >= 0) {
            template = mobService.findTemplate(mobId.substring(mobId.indexOf(':') + 1).trim());
        }
        if (template == null) {
            return "未登録のモブ";
        }

        MobTemplate displayTemplate = template.resolveLevel(level);
        String plainName = ColorCodeUtil.toPlainText(displayTemplate.displayName(), "").trim();
        String normalizedMobId = mobId.indexOf(':') >= 0
                ? mobId.substring(mobId.indexOf(':') + 1).trim()
                : mobId;
        if (plainName.isBlank() || plainName.equalsIgnoreCase(normalizedMobId)) {
            return "未登録のモブ";
        }
        return ColorCodeUtil.toLegacyText(displayTemplate.displayName(), "未登録のモブ");
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
        pendingSpawns.remove(key);
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
     * Mob スポナーの既存の管理者権限を判定します。
     *
     * @param astPlayer 対象プレイヤー
     * @return user.permission が管理者なら true
     */
    public boolean isAdminMode(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.hasAdminPermission();
    }

    /**
     * Mob スポナー削除を許可する管理状態か判定します。
     * <p>
     * 表示・設置の user permission 判定とは分離し、削除だけは user.permission が管理者かつ
     * account mode が {@link AccountMode#ADMIN} の場合に限定します。
     *
     * @param astPlayer 対象プレイヤー
     * @return 削除が許可される場合は true
     */
    public boolean canRemoveSpawner(@Nullable AstPlayer astPlayer) {
        return hasSpawnerAdminPermission(astPlayer)
            && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
    }

    /**
     * スポナー表示を見せる account mode か判定します。
     *
     * @param astPlayer 対象プレイヤー
     * @return account mode が {@link AccountMode#ADMIN} の場合は true
     */
    public boolean canViewSpawnerVisual(@Nullable AstPlayer astPlayer) {
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.ADMIN;
    }

    /**
     * 管理者本人のモブスポナー表示モードを取得します。
     *
     * @param viewer 表示対象のプレイヤー
     * @return 保存済みモード。未設定の場合は通常表示
     */
    public @NotNull MobSpawnerVisualMode getVisualMode(@NotNull Player viewer) {
        Byte value = viewer.getPersistentDataContainer().get(visualModeKey, PersistentDataType.BYTE);
        return MobSpawnerVisualMode.fromStoredValue(value);
    }

    /**
     * 管理者本人のモブスポナー表示モードを保存し、現在の表示へ直ちに反映します。
     * Bukkit メインスレッドから呼び出してください。
     *
     * @param viewer 表示設定を変更するプレイヤー
     * @param mode 新しい表示モード
     */
    public void setVisualMode(@NotNull Player viewer, @NotNull MobSpawnerVisualMode mode) {
        if (mode == MobSpawnerVisualMode.NORMAL) {
            viewer.getPersistentDataContainer().remove(visualModeKey);
        } else {
            viewer.getPersistentDataContainer().set(visualModeKey, PersistentDataType.BYTE, mode.storedValue());
        }
        if (visualizer != null) {
            visualizer.refresh();
        }
    }

    private boolean hasSpawnerAdminPermission(@Nullable AstPlayer astPlayer) {
        return astPlayer != null
            && (astPlayer.getUser().getPermission() == UserPermission.ADMIN.getValue()
                || astPlayer.getBukkit() != null
                    && (NetworkAuthorityRegistry.isAuthority(astPlayer.getBukkit().getUniqueId())
                        || NetworkChannelAccessRegistry.isAuthority(astPlayer.getBukkit().getUniqueId())));
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
     * 地域・出現周期は1秒ごとに判定し、待機中の探索を毎 tick 1配置ずつ進めます。
     * 1 tick の空間判定を32回、Mob生成を最大1体に制限します。
     */
    private void tick() {
        tick++;
        if (tick % TICK_INTERVAL == 0L) {
            updateNearbyPlayerRegions();
            for (MobSpawnerLocation location : locations.values()) {
                MobSpawnerDefinition definition = definitions.get(location.spawnerId());
                if (definition != null && tick % definition.spawnIntervalTicks() == 0L) {
                    Location origin = location.toLocation();
                    if (origin != null && origin.getWorld() != null
                            && definition.canSpawnAt(origin.getWorld().getTime())
                            && countNearbyGameplayPlayers(origin, definition.radiusMeters()) > 0) {
                        pendingSpawns.computeIfAbsent(location.locationKey(), ignored -> new SpawnAttempt(location));
                    }
                }
            }
        }
        var pending = pendingSpawns.pollFirstEntry();
        if (pending != null && processSpawner(pending.getValue())) {
            pendingSpawns.put(pending.getKey(), pending.getValue());
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
        return calculateRegionLevelsInternal(definitions, entry -> levelResolver.apply(entry.mobId()));
    }

    /** レベル指定付きスポナーを含む地域レベルを算出します。 */
    static Map<String, Integer> calculateRegionLevels(
            @NotNull Collection<MobSpawnerDefinition> definitions,
            @NotNull java.util.function.BiFunction<String, Integer, Integer> levelResolver
    ) {
        return calculateRegionLevelsInternal(
                definitions,
                entry -> levelResolver.apply(entry.mobId(), entry.level())
        );
    }

    private static Map<String, Integer> calculateRegionLevelsInternal(
            @NotNull Collection<MobSpawnerDefinition> definitions,
            @NotNull Function<MobSpawnerEntry, Integer> levelResolver
    ) {
        Map<String, long[]> totalsByRegion = new HashMap<>();
        for (MobSpawnerDefinition definition : definitions) {
            if (definition.region() == null) {
                continue;
            }
            long[] totals = totalsByRegion.computeIfAbsent(definition.region(), ignored -> new long[2]);
            for (MobSpawnerEntry entry : definition.spawnMobs()) {
                Integer level = levelResolver.apply(entry);
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

    /**
     * 待機中の配置の条件を再検証し、探索予算分だけ処理します。
     *
     * @param attempt 処理する配置と継続中の探索
     * @return 次の tick 以降も探索を継続する場合は true
     */
    private boolean processSpawner(@NotNull SpawnAttempt attempt) {
        MobSpawnerLocation spawnerLocation = attempt.location;
        MobSpawnerDefinition definition = definitions.get(spawnerLocation.spawnerId());
        Location origin = spawnerLocation.toLocation();
        if (locations.get(spawnerLocation.locationKey()) != spawnerLocation
                || definition == null || origin == null || origin.getWorld() == null) {
            return false;
        }
        if (!definition.canSpawnAt(origin.getWorld().getTime())) {
            cleanupTracked(spawnerLocation.locationKey());
            return false;
        }

        int nearbyPlayers = countNearbyGameplayPlayers(origin, definition.radiusMeters());
        if (nearbyPlayers <= 0) {
            cleanupTracked(spawnerLocation.locationKey());
            return false;
        }

        int desired = definition.desiredAliveCount(Math.min(MAX_PLAYER_SCALE, nearbyPlayers));
        int alive = cleanupTracked(spawnerLocation.locationKey());
        if (alive >= desired || countNearbyMobs(origin, definition.radiusMeters()) >= definition.maxNearbyMobs()) {
            return false;
        }
        if (!attempt.chanceChecked) {
            attempt.chanceChecked = true;
            if (ThreadLocalRandom.current().nextDouble(100.0D) >= definition.spawnChancePercent()) {
                return false;
            }
        }

        if (attempt.search == null || attempt.world != origin.getWorld()) {
            attempt.world = origin.getWorld();
            attempt.search = new MobSpawnLocationSearch(origin, definition.radiusMeters(), ThreadLocalRandom.current());
        }
        Location spawnLocation = attempt.search.advance(SPAWN_SEARCH_CHECKS_PER_TICK);
        if (spawnLocation == null) {
            return !attempt.search.isFinished();
        }
        MobSpawnerEntry entry = choose(definition.spawnMobs());
        if (entry == null) {
            return false;
        }
        if (definition.maxAlivePerMob() > 0 && mobService.getInstances().stream()
                .filter(instance -> instance.template().id().equals(entry.mobId()))
                .count() >= definition.maxAlivePerMob()) {
            return false;
        }
        MobInstance instance = mobService.spawn(entry.mobId(), entry.level(), spawnLocation);
        if (instance != null) {
            spawnedByLocation.computeIfAbsent(spawnerLocation.locationKey(), key -> new HashSet<>())
                    .add(instance.instanceId());
        }
        return false;
    }

    /** 1配置につき1件だけ保持し、削除・再読込・停止時に破棄するスポーン待機状態です。 */
    private static final class SpawnAttempt {
        private final MobSpawnerLocation location;
        private boolean chanceChecked;
        private World world;
        private MobSpawnLocationSearch search;

        /** 対象配置を固定し、実際の探索は実行順が来てから作成します。 */
        private SpawnAttempt(MobSpawnerLocation location) {
            this.location = location;
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
