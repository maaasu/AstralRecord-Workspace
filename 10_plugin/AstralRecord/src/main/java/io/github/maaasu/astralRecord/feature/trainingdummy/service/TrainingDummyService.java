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
import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.feature.trainingdummy.repository.TrainingDummyRepository;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.EntityType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** 検証用カカシの配置、非致死制御、定期全回復を担当します。 */
public final class TrainingDummyService {
    private static final long TICK_INTERVAL = 20L;
    private final Plugin plugin;
    private final MobService mobService;
    private final TrainingDummyRepository repository;
    private final Map<String, TrainingDummyDefinition> definitions = new LinkedHashMap<>();
    private final Map<String, UUID> instanceIds = new LinkedHashMap<>();
    private long tick;
    private BukkitTask task;

    public TrainingDummyService(@NotNull Plugin plugin, @NotNull MobService mobService, @NotNull TrainingDummyRepository repository) {
        this.plugin = plugin;
        this.mobService = mobService;
        this.repository = repository;
    }

    /** 設定を再読込し、既存実体を新しい設定で再生成します。 */
    public int loadAll() {
        stopInstances();
        definitions.clear();
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
                100.0D, 0.0D, 0.0D, false, 10.0D, 40L
        );
        definitions.put(normalizedId, definition);
        save();
        respawn(normalizedId);
        return true;
    }

    /** 配置 ID を削除します。 */
    public boolean remove(@NotNull String id) {
        if (definitions.remove(id) == null) return false;
        destroyInstance(id);
        save();
        return true;
    }

    /** カカシ設定を更新し、実体へ直ちに反映します。 */
    public boolean update(@NotNull TrainingDummyDefinition definition) {
        if (!definitions.containsKey(definition.id())) return false;
        definitions.put(definition.id(), definition);
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

    private void tick() {
        tick += TICK_INTERVAL;
        for (TrainingDummyDefinition definition : List.copyOf(definitions.values())) {
            MobInstance instance = instance(definition.id());
            if (instance == null) { spawn(definition); continue; }
            mobService.stopPathfinding(instance);
            Location anchor = definition.toLocation();
            if (anchor != null) mobService.holdPosition(instance, anchor);
            if (tick % definition.recoveryIntervalTicks() == 0L) {
                instance.currentHealth(instance.maxHealth());
                if (definition.shieldEnabled()) instance.currentShield(definition.shieldMax(), System.currentTimeMillis());
            }
        }
    }

    private void respawn(@NotNull String id) { destroyInstance(id); TrainingDummyDefinition definition = definitions.get(id); if (definition != null) spawn(definition); }
    private void spawn(@NotNull TrainingDummyDefinition definition) {
        Location location = definition.toLocation();
        if (location == null) return;
        MobInstance instance = mobService.spawn(template(definition), location);
        if (instance == null) return;
        instance.nonLethal(true);
        instance.keepWhenUnobserved(true);
        instanceIds.put(definition.id(), instance.instanceId());
    }
    private @Nullable MobInstance instance(@NotNull String id) { UUID instanceId = instanceIds.get(id); return instanceId == null ? null : mobService.getInstance(instanceId); }
    private void destroyInstance(@NotNull String id) { UUID instanceId = instanceIds.remove(id); if (instanceId != null) mobService.destroy(instanceId); }
    private void stopInstances() { List.copyOf(instanceIds.keySet()).forEach(this::destroyInstance); }
    private void save() { repository.saveAll(definitions.values()); }
    private @NotNull MobTemplate template(@NotNull TrainingDummyDefinition definition) {
        return new MobTemplate(1, "training_dummy:" + definition.id(), MobCategory.ENEMY, "&e訓練用カカシ " + definition.id(), null,
                1, EntityType.ZOMBIE, true, "ARMOR_STAND", List.of("&7Drop キーで設定を開く"), List.of(), null,
                new MobEquipmentConfig(null, definition.shieldEnabled() ? "SHIELD" : null, "LEATHER_HELMET", "LEATHER_CHESTPLATE", "LEATHER_LEGGINGS", "LEATHER_BOOTS"),
                List.of(new MobBaseStat("MAX_HEALTH", definition.maxHealth()), new MobBaseStat("DEFENSE", definition.defense()), new MobBaseStat("MAGIC_DEFENSE", definition.magicDefense()), new MobBaseStat("MOVEMENT_SPEED", 0.0D)),
                new MobShieldConfig(definition.shieldEnabled(), definition.shieldMax()), new MobIdleConfig(IdleBehavior.STATIONARY, 0.0D, 0.0D), false,
                MobInteractionsConfig.EMPTY, null, null, null);
    }
}
