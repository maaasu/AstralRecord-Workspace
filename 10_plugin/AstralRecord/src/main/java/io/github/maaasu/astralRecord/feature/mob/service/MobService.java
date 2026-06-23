package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Mob テンプレートのキャッシュ・実体 Mob インスタンス管理を担うサービス。
 *
 * <p>Mob 本体は Bukkit/Paper の実体 Entity として生成し、バニラ goal は
 * {@link MobEntityController} で全削除する。AI の意思決定と HP は AstralRecord 側で管理する。</p>
 */
public class MobService {

    /** 頭上 packet display の視認距離（ブロック単位）。 */
    private static final double DEFAULT_VIEW_DISTANCE = 64.0D;
    private static final double DEFAULT_VIEW_DISTANCE_SQ = DEFAULT_VIEW_DISTANCE * DEFAULT_VIEW_DISTANCE;
    public static final double NPC_INTERACTION_DISTANCE = 3.0D;
    public static final double NPC_INTERACTION_RAY_SIZE = 0.75D;

    private final Plugin plugin;
    private final MobRepository repository;
    private final MobEntityController entityController;

    private final Map<String, MobTemplate> templates = new LinkedHashMap<>();
    private final Map<UUID, MobInstance> instances = new LinkedHashMap<>();
    private final Map<UUID, UUID> instanceByEntity = new LinkedHashMap<>();
    /** インスタンスごとに、頭上 packet display を表示するプレイヤー UUID 集合を保持。 */
    private final Map<UUID, Set<UUID>> viewers = new LinkedHashMap<>();

    /**
     * サービスを初期化します。
     *
     * @param plugin     プラグイン本体
     * @param repository Mob リポジトリ
     */
    public MobService(@NotNull Plugin plugin, @NotNull MobRepository repository) {
        this.plugin = plugin;
        this.repository = repository;
        this.entityController = new MobEntityController(plugin);
    }

    /**
     * AstralRecord API から Mob テンプレートを一括ロードし、キャッシュを置換します。
     *
     * @return ロードしたテンプレート数
     */
    public int loadAll() {
        templates.clear();
        for (MobTemplate template : repository.findAll()) {
            templates.put(template.id(), template);
        }
        Logger.log(LogId.I_5700, templates.size());
        return templates.size();
    }

    /**
     * テンプレートをキャッシュから取得します。未ヒット時は API から遅延ロードします。
     *
     * @param mobId テンプレート ID
     * @return テンプレート。取得できない場合は {@code null}
     */
    @Nullable
    public MobTemplate findTemplate(@NotNull String mobId) {
        MobTemplate cached = templates.get(mobId);
        if (cached != null) return cached;

        MobTemplate loaded = repository.findById(mobId);
        if (loaded != null) {
            templates.put(mobId, loaded);
        }
        return loaded;
    }

    /**
     * ロード済みテンプレートの ID 一覧を返します。
     *
     * @return ID 集合（変更不可）
     */
    @NotNull
    public Collection<String> getLoadedMobIds() {
        return Collections.unmodifiableSet(templates.keySet());
    }

    /**
     * 指定カテゴリに一致するロード済みテンプレート ID 一覧を返します。
     *
     * @param categories 取得対象カテゴリ
     * @return 指定カテゴリのテンプレート ID 一覧
     */
    @NotNull
    public Collection<String> getLoadedMobIdsByCategory(@NotNull Collection<MobCategory> categories) {
        Set<MobCategory> allowed = Set.copyOf(categories);
        return templates.values().stream()
                .filter(template -> allowed.contains(template.category()))
                .map(MobTemplate::id)
                .toList();
    }

    /**
     * 全インスタンスを取得します。
     *
     * @return Mob インスタンスのコレクション（変更不可）
     */
    @NotNull
    public Collection<MobInstance> getInstances() {
        return Collections.unmodifiableCollection(instances.values());
    }

    /**
     * 現在スポーン中の Mob インスタンス ID 一覧を返します。
     *
     * @return インスタンス ID 一覧（変更不可）
     */
    @NotNull
    public Collection<UUID> getInstanceIds() {
        return Collections.unmodifiableSet(instances.keySet());
    }

    /**
     * 指定インスタンスを取得します。
     *
     * @param instanceId インスタンス ID
     * @return Mob インスタンス。未登録なら {@code null}
     */
    @Nullable
    public MobInstance getInstance(@NotNull UUID instanceId) {
        return instances.get(instanceId);
    }

    /**
     * Bukkit Entity UUID から AstralRecord Mob インスタンスを取得します。
     *
     * @param entityId Bukkit Entity UUID
     * @return 対応する Mob インスタンス。未管理の場合は {@code null}
     */
    @Nullable
    public MobInstance getInstanceByEntity(@NotNull UUID entityId) {
        UUID instanceId = instanceByEntity.get(entityId);
        return instanceId == null ? null : instances.get(instanceId);
    }

    /**
     * Bukkit Entity UUID から NPC インスタンスを取得します。
     *
     * @param entityId Bukkit Entity UUID
     * @return NPC インスタンス。NPC でない、または未管理なら {@code null}
     */
    @Nullable
    public MobInstance getNpcInstanceByEntity(@NotNull UUID entityId) {
        MobInstance instance = getInstanceByEntity(entityId);
        if (instance == null || instance.template().category() != MobCategory.NPC) {
            return null;
        }
        return instance;
    }

    /**
     * 指定テンプレート ID がカテゴリ条件に一致するか判定します。
     *
     * @param templateId 判定するテンプレート ID
     * @param categories 許可カテゴリ
     * @return 条件に一致する場合は {@code true}
     */
    public boolean matchesTemplateCategory(
            @NotNull String templateId,
            @NotNull Collection<MobCategory> categories
    ) {
        MobTemplate template = findTemplate(templateId);
        return template != null && categories.contains(template.category());
    }

    /**
     * プレイヤーの視線先にある NPC インスタンスを返します。
     *
     * @param player   判定対象プレイヤー
     * @param distance 判定距離
     * @param raySize  判定の太さ
     * @return 視線先の NPC。存在しない場合は {@code null}
     */
    @Nullable
    public MobInstance findTargetedNpc(@NotNull Player player, double distance, double raySize) {
        RayTraceResult result = player.getWorld().rayTraceEntities(
                player.getEyeLocation(),
                player.getEyeLocation().getDirection(),
                distance,
                raySize,
                entity -> entity != player && getNpcInstanceByEntity(entity.getUniqueId()) != null
        );
        if (result == null || result.getHitEntity() == null) {
            return null;
        }
        return getNpcInstanceByEntity(Objects.requireNonNull(result.getHitEntity()).getUniqueId());
    }

    /**
     * Mob を実体 Entity としてスポーンします。
     *
     * @param templateId テンプレート ID
     * @param location   スポーン位置
     * @return 生成した Mob インスタンス。テンプレート未取得または実体生成不可なら {@code null}
     */
    @Nullable
    public MobInstance spawn(@NotNull String templateId, @NotNull Location location) {
        MobTemplate template = findTemplate(templateId);
        if (template == null) {
            Logger.log(LogId.W_5701, templateId);
            return null;
        }

        UUID instanceId = UUID.randomUUID();
        MobInstance instance = new MobInstance(instanceId, template, location);
        var mob = entityController.spawn(instance, location);
        if (mob == null) {
            Logger.log(LogId.W_5705, template.entityType().name(), template.id());
            return null;
        }

        instances.put(instanceId, instance);
        instanceByEntity.put(mob.getUniqueId(), instanceId);
        viewers.put(instanceId, new HashSet<>());
        updateViewers(instance);

        Logger.log(LogId.D_5701, templateId, instanceId);
        return instance;
    }

    /**
     * 指定インスタンスを破棄します。
     *
     * @param instanceId インスタンス ID
     * @return 破棄したかどうか
     */
    public boolean destroy(@NotNull UUID instanceId) {
        MobInstance instance = instances.remove(instanceId);
        if (instance == null) return false;

        viewers.remove(instanceId);
        if (instance.bukkitEntityId() != null) {
            instanceByEntity.remove(instance.bukkitEntityId());
        }
        entityController.remove(instance);
        Logger.log(LogId.D_5702, instanceId);
        return true;
    }

    /**
     * コマンド指定 ID に一致する Mob を破棄します。
     *
     * <p>UUID として解釈できる場合はインスタンス ID、それ以外はテンプレート ID として扱います。</p>
     *
     * @param id インスタンス ID またはテンプレート ID
     * @return 破棄した Mob 数
     */
    public int destroyById(@NotNull String id) {
        try {
            return destroy(UUID.fromString(id)) ? 1 : 0;
        } catch (IllegalArgumentException ignored) {
            return destroyByTemplateId(id);
        }
    }

    /**
     * 指定テンプレート ID から生成された Mob をすべて破棄します。
     *
     * @param templateId テンプレート ID
     * @return 破棄した Mob 数
     */
    public int destroyByTemplateId(@NotNull String templateId) {
        List<UUID> targetIds = instances.values().stream()
                .filter(instance -> instance.template().id().equals(templateId))
                .map(MobInstance::instanceId)
                .toList();

        int count = 0;
        for (UUID instanceId : targetIds) {
            if (destroy(instanceId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * すべての Mob インスタンスを破棄します。
     *
     * @return 破棄した件数
     */
    public int destroyAll() {
        int count = instances.size();
        for (MobInstance instance : instances.values()) {
            entityController.remove(instance);
        }
        instances.clear();
        instanceByEntity.clear();
        viewers.clear();
        Logger.log(LogId.I_5701, count);
        return count;
    }

    /**
     * すべてのインスタンスについて、頭上 packet display の表示対象プレイヤー集合を更新します。
     */
    public void updateViewers() {
        for (MobInstance instance : instances.values()) {
            updateViewers(instance);
        }
    }

    /**
     * 指定プレイヤーが Mob の頭上表示を視認できる距離内か判定します。
     *
     * @param player プレイヤー
     * @param loc    対象座標
     * @return 視認可能なら {@code true}
     */
    public boolean canSee(@NotNull Player player, @NotNull Location loc) {
        if (player.isDead() || !player.isOnline()) return false;
        if (player.getWorld() != loc.getWorld()) return false;
        return player.getLocation().distanceSquared(loc) <= DEFAULT_VIEW_DISTANCE_SQ;
    }

    /**
     * どのオンラインプレイヤーの描画距離にも入っていない enemy Mob を破棄します。
     *
     * <p>プラグイン都合の破棄のため、討伐リザルトやドロップ処理は呼び出しません。</p>
     *
     * @return 破棄した enemy Mob 数
     */
    public int destroyEnemiesOutsideViewDistance() {
        List<UUID> targetIds = instances.values().stream()
                .filter(instance -> instance.template().category() == MobCategory.ENEMY)
                .filter(instance -> {
                    if (!syncLocation(instance)) {
                        return true;
                    }
                    return !hasViewerInRange(instance);
                })
                .map(MobInstance::instanceId)
                .toList();

        int count = 0;
        for (UUID instanceId : targetIds) {
            if (destroy(instanceId)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 指定インスタンスを現在視認しているプレイヤーの UUID 集合を返します。
     * インスタンスが存在しない場合は空集合を返します。
     *
     * @param instanceId インスタンス ID
     * @return 視認中プレイヤーの UUID 集合（変更不可なコピー）
     */
    @NotNull
    public Set<UUID> getViewers(@NotNull UUID instanceId) {
        Set<UUID> v = viewers.get(instanceId);
        return v != null ? new HashSet<>(v) : new HashSet<>();
    }

    /**
     * 実体 Mob の現在位置を {@link MobInstance} へ同期します。
     *
     * @param instance 同期対象インスタンス
     * @return 実体が有効なら {@code true}
     */
    public boolean syncLocation(@NotNull MobInstance instance) {
        return entityController.syncLocation(instance);
    }

    /**
     * Paper Pathfinder へ移動目標を設定します。
     *
     * @param instance        移動対象インスタンス
     * @param target          目標位置
     * @param aiSpeedModifier AI 定義側の速度倍率
     * @param currentTick     Mob AI 内部 tick
     * @return 経路設定を実行した場合は {@code true}
     */
    public boolean moveToward(
            @NotNull MobInstance instance,
            @NotNull Location target,
            double aiSpeedModifier,
            long currentTick) {
        return entityController.moveTo(instance, target, aiSpeedModifier, currentTick);
    }

    /**
     * 対象 Mob の経路探索を停止します。
     *
     * @param instance 対象インスタンス
     */
    public void stopPathfinding(@NotNull MobInstance instance) {
        entityController.stopPathfinding(instance);
    }

    /**
     * Mob の水平移動だけを停止し、視線追従に必要な回転は維持します。
     *
     * @param instance 対象 Mob インスタンス
     */
    /**
     * Mob の水平移動だけを停止し、視線追従に必要な回転は維持します。
     *
     * @param instance 対象 Mob インスタンス
     */
    public void stopHorizontalMovement(@NotNull MobInstance instance) {
        entityController.stopHorizontalMovement(instance);
    }

    /**
     * 対象 Mob の視線を指定位置に向けます。
     *
     * @param instance 対象インスタンス
     * @param target   視線を向ける位置
     */
    public void holdPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        entityController.holdPosition(instance, anchor);
    }

    public void lookAt(@NotNull MobInstance instance, @NotNull Location target) {
        entityController.lookAt(instance, target);
    }

    /**
     * 実体 Mob 制御サービスを返します。
     *
     * @return 実体 Mob 制御サービス
     */
    @NotNull
    public MobEntityController entityController() {
        return entityController;
    }

    /** プラグイン本体を返します（内部基盤用）。 */
    @NotNull
    public Plugin plugin() {
        return plugin;
    }

    private void updateViewers(@NotNull MobInstance instance) {
        entityController.syncLocation(instance);
        Set<UUID> currentViewers = viewers.computeIfAbsent(instance.instanceId(), id -> new HashSet<>());
        Location loc = instance.currentLocation();

        for (Player player : Bukkit.getOnlinePlayers()) {
            UUID playerId = player.getUniqueId();
            if (canSee(player, loc)) {
                currentViewers.add(playerId);
            } else {
                currentViewers.remove(playerId);
            }
        }

        currentViewers.removeIf(id -> Bukkit.getPlayer(id) == null);
    }

    private boolean hasViewerInRange(@NotNull MobInstance instance) {
        Location loc = instance.currentLocation();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (canSee(player, loc)) {
                return true;
            }
        }
        return false;
    }
}
