package io.github.maaasu.astralRecord.feature.trainingdummy.service;

import io.github.maaasu.astralRecord.feature.mob.model.IdleBehavior;
import io.github.maaasu.astralRecord.feature.mob.model.MobBaseStat;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobEquipmentConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobIdleConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobInteractionsConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobShieldConfig;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.repository.TrainingDummyRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 検証用カカシの配置、非致死制御、定期全回復を担当します。 */
public final class TrainingDummyService {
    private static final long TICK_INTERVAL = 20L;
    private final Plugin plugin;
    private final MobService mobService;
    private final TrainingDummyRepository repository;
    private final ChunkTicketGateway chunkTicketGateway;
    private final Map<String, TrainingDummyDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, UUID> instanceIds = new LinkedHashMap<>();
    private final Set<String> spawnFailures = new HashSet<>();
    private final Map<String, ChunkTicket> chunkTicketById = new LinkedHashMap<>();
    private final Map<ChunkTicket, Integer> chunkTicketRefs = new LinkedHashMap<>();
    private long tick;
    private BukkitTask task;

    public TrainingDummyService(@NotNull Plugin plugin, @NotNull MobService mobService, @NotNull TrainingDummyRepository repository) {
        this(plugin, mobService, repository, new BukkitChunkTicketGateway(plugin));
    }

    TrainingDummyService(
            @NotNull Plugin plugin,
            @NotNull MobService mobService,
            @NotNull TrainingDummyRepository repository,
            @NotNull ChunkTicketGateway chunkTicketGateway
    ) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.repository = repository;
        this.chunkTicketGateway = chunkTicketGateway;
    }

    /** 設定を再読込し、既存実体を新しい設定で再生成します。 */
    public int loadAll() {
        stopInstances();
        definitions.clear();
        spawnFailures.clear();
        repository.loadAll().forEach(definition -> definitions.put(definition.id(), definition));
        return definitions.size();
    }

    /** カカシ管理タスクを開始します。 */
    public void start() {
        if (task == null) task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, TICK_INTERVAL);
    }

    /** カカシ実体と管理タスクを停止します。 */
    public void stop() {
        if (task != null) { task.cancel(); task = null; }
        stopInstances();
    }

    /** 配置 ID を作成または上書きし、直ちに実体化します。 */
    public boolean place(@NotNull String id, @NotNull Location location) {
        if (id.isBlank() || location.getWorld() == null) return false;
        String normalizedId = id.trim().toLowerCase(Locale.ROOT);
        TrainingDummyDefinition definition = new TrainingDummyDefinition(
                normalizedId, location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), location.getYaw(),
                TrainingDummyDefinition.FIXED_MAX_HEALTH, 0.0D, 0.0D, false, 10.0D, 40L
        );
        definitions.put(normalizedId, definition);
        spawnFailures.remove(normalizedId);
        save();
        respawn(normalizedId);
        return true;
    }

    /** 配置 ID を削除します。 */
    public boolean remove(@NotNull String id) {
        if (definitions.remove(id) == null) return false;
        spawnFailures.remove(id);
        destroyInstance(id);
        save();
        return true;
    }

    /** カカシ設定を更新し、実体へ直ちに反映します。 */
    public boolean update(@NotNull TrainingDummyDefinition definition) {
        if (!definitions.containsKey(definition.id())) return false;
        definitions.put(definition.id(), definition);
        spawnFailures.remove(definition.id());
        save();
        respawn(definition.id());
        return true;
    }

    /** ID からカカシ定義を返します。 */
    public @Nullable TrainingDummyDefinition find(@NotNull String id) { return definitions.get(id); }

    /** Mob インスタンスから紐付くカカシ定義を返します。 */
    public @Nullable TrainingDummyDefinition findByInstance(@NotNull MobInstance instance) {
        for (Map.Entry<String, UUID> entry : instanceIds.entrySet()) {
            if (entry.getValue().equals(instance.instanceId())) return definitions.get(entry.getKey());
        }
        return null;
    }

    /** 全配置 ID を返します。 */
    public @NotNull Collection<String> ids() { return List.copyOf(definitions.keySet()); }

    /** カカシの生成、固定位置維持、定期回復を一周期分処理します。 */
    void tick() {
        tick += TICK_INTERVAL;
        for (TrainingDummyDefinition definition : List.copyOf(definitions.values())) {
            MobInstance instance = instance(definition.id());
            if (instance == null) {
                if (!spawnFailures.contains(definition.id())) spawn(definition);
                continue;
            }
            mobService.stopPathfinding(instance);
            Location anchor = definition.toLocation();
            if (anchor != null) mobService.holdPosition(instance, anchor);
            if (tick % definition.recoveryIntervalTicks() == 0L) {
                mobService.recoverHealth(instance, instance.maxHealth() - instance.currentHealth(), false);
                if (definition.shieldEnabled()) instance.currentShield(definition.shieldMax(), System.currentTimeMillis());
            }
        }
    }

    private void respawn(@NotNull String id) { destroyInstance(id); TrainingDummyDefinition definition = definitions.get(id); if (definition != null) spawn(definition); }
    private void spawn(@NotNull TrainingDummyDefinition definition) {
        Location location = definition.toLocation();
        if (location == null) return;
        if (!retainChunkTicket(definition.id(), location)) {
            spawnFailures.add(definition.id());
            return;
        }
        MobInstance instance = mobService.spawn(template(definition), location);
        if (instance == null) {
            releaseChunkTicket(definition.id());
            spawnFailures.add(definition.id());
            return;
        }
        spawnFailures.remove(definition.id());
        instance.nonLethal(true);
        instance.keepWhenUnobserved(true);
        instanceIds.put(definition.id(), instance.instanceId());
    }
    private @Nullable MobInstance instance(@NotNull String id) { UUID instanceId = instanceIds.get(id); return instanceId == null ? null : mobService.getInstance(instanceId); }
    private void destroyInstance(@NotNull String id) {
        UUID instanceId = instanceIds.remove(id);
        if (instanceId != null) mobService.destroy(instanceId);
        releaseChunkTicket(id);
    }
    private void stopInstances() {
        List.copyOf(instanceIds.keySet()).forEach(this::destroyInstance);
        releaseAllChunkTickets();
    }
    private void save() { repository.saveAll(definitions.values()); }

    boolean retainChunkTicket(@NotNull String id, @NotNull Location location) {
        Chunk chunk = location.getChunk();
        ChunkTicket ticket = new ChunkTicket(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        ChunkTicket currentTicket = chunkTicketById.get(id);
        if (ticket.equals(currentTicket)) return true;
        if (currentTicket != null) releaseChunkTicket(id);

        int refs = chunkTicketRefs.getOrDefault(ticket, 0);
        if (refs == 0) {
            try {
                chunkTicketGateway.retain(chunk);
            } catch (RuntimeException ex) {
                Logger.error(LogId.E_5708, ex, "retain", ticket);
                return false;
            }
        }
        chunkTicketById.put(id, ticket);
        chunkTicketRefs.put(ticket, refs + 1);
        return true;
    }

    private void releaseChunkTicket(@NotNull String id) {
        ChunkTicket ticket = chunkTicketById.remove(id);
        if (ticket == null) return;

        int refs = chunkTicketRefs.getOrDefault(ticket, 0) - 1;
        if (refs > 0) {
            chunkTicketRefs.put(ticket, refs);
            return;
        }
        chunkTicketRefs.remove(ticket);
        World world = Bukkit.getWorld(ticket.worldName());
        if (world != null) {
            try {
                chunkTicketGateway.release(world, ticket.x(), ticket.z());
            } catch (RuntimeException ex) {
                Logger.error(LogId.E_5708, ex, "release", ticket);
            }
        }
    }

    private void releaseAllChunkTickets() {
        for (ChunkTicket ticket : List.copyOf(chunkTicketRefs.keySet())) {
            World world = Bukkit.getWorld(ticket.worldName());
            if (world != null) {
                try {
                    chunkTicketGateway.release(world, ticket.x(), ticket.z());
                } catch (RuntimeException ex) {
                    Logger.error(LogId.E_5708, ex, "release_all", ticket);
                }
            }
        }
        chunkTicketById.clear();
        chunkTicketRefs.clear();
    }

    private record ChunkTicket(@NotNull String worldName, int x, int z) {}

    interface ChunkTicketGateway {
        void retain(@NotNull Chunk chunk);
        void release(@NotNull World world, int chunkX, int chunkZ);
    }

    private record BukkitChunkTicketGateway(@NotNull Plugin plugin) implements ChunkTicketGateway {
        @Override
        public void retain(@NotNull Chunk chunk) {
            chunk.addPluginChunkTicket(plugin);
        }

        @Override
        public void release(@NotNull World world, int chunkX, int chunkZ) {
            world.getChunkAt(chunkX, chunkZ).removePluginChunkTicket(plugin);
        }
    }
    /**
     * カカシ定義から固定 HP・ノックバック無効の ArmorStand テンプレートを生成します。
     *
     * @param definition カカシ定義
     * @return 実行時 Mob テンプレート
     */
    static @NotNull MobTemplate template(@NotNull TrainingDummyDefinition definition) {
        return new MobTemplate(1, "training_dummy:" + definition.id(), MobCategory.ENEMY, "&e訓練用カカシ " + definition.id(), null,
                1, EntityType.ARMOR_STAND, true, "ARMOR_STAND", List.of("&7Drop キーで設定を開く"), List.of(), null,
                new MobEquipmentConfig(null, definition.shieldEnabled() ? "SHIELD" : null, "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"),
                List.of(new MobBaseStat(StatusType.MAX_HEALTH.name(), TrainingDummyDefinition.FIXED_MAX_HEALTH), new MobBaseStat(StatusType.DEFENSE.name(), definition.defense()), new MobBaseStat(StatusType.MAGIC_DEFENSE.name(), definition.magicDefense()), new MobBaseStat(StatusType.MOVEMENT_SPEED.name(), 0.0D), new MobBaseStat(StatusType.KNOCKBACK_RESISTANCE.name(), 100.0D)),
                new MobShieldConfig(definition.shieldEnabled(), definition.shieldMax()), new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 0.0D), false,
                MobInteractionsConfig.EMPTY, null, null, null);
    }
}
