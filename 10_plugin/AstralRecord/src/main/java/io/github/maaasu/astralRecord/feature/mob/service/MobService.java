package io.github.maaasu.astralRecord.feature.mob.service;

import io.github.maaasu.astralRecord.feature.combat.model.AstEntity;
import io.github.maaasu.astralRecord.feature.condition.service.ConditionService;
import io.github.maaasu.astralRecord.feature.mob.model.MobCategory;
import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.model.MobSkin;
import io.github.maaasu.astralRecord.feature.mob.model.MobTemplate;
import io.github.maaasu.astralRecord.feature.mob.repository.MobRepository;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.interaction.PlayerInteractionRayTrace;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.ComplexEntityPart;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.BoundingBox;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

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

    /**
     * 視線上で命中したNPCとhitbox入口距離です。
     *
     * @param instance 命中したNPCインスタンス
     * @param hitDistance プレイヤー視点からhitbox入口までの有限な非負距離
     */
    public record MobInteractionHit(@NotNull MobInstance instance, double hitDistance) {
        /**
         * 命中結果を生成し、距離契約を検証します。
         *
         * @throws NullPointerException NPCインスタンスがnullの場合
         * @throws IllegalArgumentException 距離が非有限または負数の場合
         */
        public MobInteractionHit {
            Objects.requireNonNull(instance, "instance");
            if (!Double.isFinite(hitDistance) || hitDistance < 0.0D) {
                throw new IllegalArgumentException("hitDistance must be finite and zero or greater");
            }
        }
    }

    private final Plugin plugin;
    private final MobRepository repository;
    private final MobEntityController entityController;
    private final NpcPlayerSkinPacketService playerSkinPacketService;
    private ConditionService conditionService;
    private Consumer<UUID> destroyListener = ignored -> { };
    private BiConsumer<MobInstance, Double> healthRecoveryListener = (instance, amount) -> { };

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
        this.playerSkinPacketService = new NpcPlayerSkinPacketService(plugin, this.entityController);
    }

    /**
     * 状態異常サービスを設定します。
     *
     * @param conditionService 状態異常サービス
     */
    public void setConditionService(@NotNull ConditionService conditionService) {
        this.conditionService = conditionService;
    }

    /**
     * Mob 破棄時の runtime 状態解放先を設定します。
     *
     * @param destroyListener 破棄した Mob インスタンス UUID の通知先
     */
    public void setDestroyListener(@NotNull Consumer<UUID> destroyListener) {
        this.destroyListener = destroyListener;
    }

    /**
     * Mob HP 回復時の実回復量 listener を設定します。
     *
     * @param healthRecoveryListener 回復した Mob と実回復量を受け取る listener。null で無効化
     */
    public void setHealthRecoveryListener(@Nullable BiConsumer<MobInstance, Double> healthRecoveryListener) {
        this.healthRecoveryListener = healthRecoveryListener == null
                ? (instance, amount) -> { }
                : healthRecoveryListener;
    }

    /**
     * Mob の HP を上限まで回復し、実際に増加した量を listener へ通知します。
     *
     * @param instance 回復対象の Mob インスタンス
     * @param amount 回復量
     * @return 実際に増加した HP
     */
    public double recoverHealth(@NotNull MobInstance instance, double amount) {
        return recoverHealth(instance, amount, true);
    }

    /**
     * Mob の HP を上限まで回復し、必要な場合だけ実際の増加量を listener へ通知します。
     *
     * @param instance 回復対象の Mob インスタンス
     * @param amount 回復量
     * @param notify 回復数値を通知する場合は {@code true}。定期回復などは {@code false}
     * @return 実際に増加した HP
     */
    public double recoverHealth(@NotNull MobInstance instance, double amount, boolean notify) {
        double recoveredAmount = instance.recoverHealth(amount);
        if (notify && recoveredAmount > 0.0D) {
            healthRecoveryListener.accept(instance, recoveredAmount);
        }
        return recoveredAmount;
    }

    /**
     * Mob の発光状態を更新します。
     *
     * <p>通常の Mob は実体 Entity の発光状態を更新し、player-skin NPC は隠された
     * 実体に加えて表示中の疑似 Player の metadata へも状態を反映します。</p>
     *
     * @param instance 発光対象の Mob インスタンス
     * @param glowing 発光させる場合は {@code true}
     */
    public void setGlowing(@NotNull MobInstance instance, boolean glowing) {
        instance.glowing(glowing);
        Entity entity = entityController.getEntity(instance);
        if (entity != null) {
            entity.setGlowing(glowing);
        }
        if (instance.template().usesPlayerSkinPacketView()) {
            playerSkinPacketService.setGlowing(instance, glowing);
        }
    }

    /**
     * AstralRecord API から Mob テンプレートを一括ロードし、キャッシュを置換します。
     *
     * @return ロードしたテンプレート数
     */
    public int loadAll() {
        Map<String, MobTemplate> snapshot = loadTemplateSnapshot();
        replaceTemplateSnapshot(snapshot);
        return templates.size();
    }

    /**
     * API から Mob テンプレートを取得し、公開前の immutable スナップショットを作成します。
     *
     * @return Mob テンプレートスナップショット
     */
    public @NotNull Map<String, MobTemplate> loadTemplateSnapshot() {
        Map<String, MobTemplate> snapshot = new LinkedHashMap<>();
        for (MobTemplate template : repository.findAll()) {
            snapshot.put(template.id(), template);
        }
        return Collections.unmodifiableMap(snapshot);
    }

    /**
     * 準備済み Mob テンプレートを実行時キャッシュへ一括反映します。
     *
     * @param snapshot Mob テンプレートスナップショット
     */
    public void replaceTemplateSnapshot(@NotNull Map<String, MobTemplate> snapshot) {
        templates.clear();
        templates.putAll(snapshot);
        Logger.log(LogId.I_5700, templates.size());
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
     * ロード済みの Mob テンプレートだけを取得します。API への遅延取得は行いません。
     *
     * @param mobId Mob テンプレート ID
     * @return ロード済みテンプレート。存在しない場合は null
     */
    @Nullable
    public MobTemplate findLoadedTemplate(@NotNull String mobId) {
        MobTemplate direct = templates.get(mobId);
        if (direct != null) {
            return direct;
        }
        return templates.values().stream()
            .filter(template -> template.id().equalsIgnoreCase(mobId))
            .findFirst()
            .orElse(null);
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
     * 指定カテゴリに一致するロード済みテンプレートの補完候補を返します。
     *
     * <p>候補にはテンプレート ID、表示名、`id（表示名）` 形式の装飾候補を含めます。</p>
     *
     * @param categories 取得対象カテゴリ
     * @return 補完候補一覧
     */
    @NotNull
    public Collection<String> getLoadedMobSelectorsByCategory(@NotNull Collection<MobCategory> categories) {
        Set<MobCategory> allowed = Set.copyOf(categories);
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (MobTemplate template : templates.values()) {
            if (!allowed.contains(template.category())) {
                continue;
            }
            addTemplateSelectors(suggestions, template);
        }
        return List.copyOf(suggestions);
    }

    /**
     * 指定テンプレート ID 群に対応する補完候補を返します。
     *
     * <p>ロード済みテンプレートは表示名付き候補に展開し、未ロード ID はそのまま返します。</p>
     *
     * @param templateIds テンプレート ID 群
     * @return 補完候補一覧
     */
    @NotNull
    public Collection<String> getLoadedMobSelectors(@NotNull Collection<String> templateIds) {
        LinkedHashSet<String> suggestions = new LinkedHashSet<>();
        for (String templateId : templateIds) {
            MobTemplate template = templates.get(templateId);
            if (template == null) {
                suggestions.add(templateId);
                continue;
            }
            addTemplateSelectors(suggestions, template);
        }
        return List.copyOf(suggestions);
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
     * Bukkit Entity から AstralRecord Mob インスタンスを取得します。
     *
     * <p>Ender Dragon などの複合 Entity は、攻撃を受けた部位ではなく親 Entity の UUID で解決します。</p>
     *
     * @param entity Bukkit Entity または複合 Entity の部位
     * @return 対応する Mob インスタンス。未管理の場合は {@code null}
     */
    @Nullable
    public MobInstance getInstanceByEntity(@NotNull Entity entity) {
        Entity resolved = entity instanceof ComplexEntityPart part ? part.getParent() : entity;
        return getInstanceByEntity(resolved.getUniqueId());
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
     * テンプレート ID・表示名・装飾付き補完候補からカテゴリ一致するテンプレート ID を解決します。
     *
     * <p>未ロードテンプレートは ID 完全一致のときだけ遅延ロードで解決し、表示名解決はロード済みテンプレートに対して行います。</p>
     *
     * @param input      解決対象入力
     * @param categories 許可カテゴリ
     * @return 解決できたテンプレート ID。見つからない場合は {@code null}
     */
    @Nullable
    public String resolveTemplateId(@NotNull String input, @NotNull Collection<MobCategory> categories) {
        Set<MobCategory> allowed = Set.copyOf(categories);
        MobTemplate cached = templates.get(input);
        if (cached != null && allowed.contains(cached.category())) {
            return cached.id();
        }

        if (isLikelyTemplateId(input)) {
            MobTemplate exact = findTemplate(input);
            if (exact != null && allowed.contains(exact.category())) {
                return exact.id();
            }
        }

        String normalizedInput = normalizeLookupValue(input);
        if (normalizedInput.isEmpty()) {
            return null;
        }

        for (MobTemplate template : templates.values()) {
            if (!allowed.contains(template.category())) {
                continue;
            }
            if (normalizeLookupValue(template.id()).equals(normalizedInput)
                    || normalizeLookupValue(templateDisplayName(template)).equals(normalizedInput)
                    || normalizeLookupValue(buildTemplateSelector(template)).equals(normalizedInput)
                    || normalizeLookupValue(buildAsciiTemplateSelector(template)).equals(normalizedInput)) {
                return template.id();
            }
        }
        return null;
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
        MobInteractionHit hit = findTargetedNpcHit(player, distance, raySize);
        return hit == null ? null : hit.instance();
    }

    /**
     * プレイヤー視線上で最も入口距離が近いNPCを返します。
     * Bukkit Entityとblock NPCの双方でAABB入口を距離尺度に使用します。
     * 候補解決だけを行い、NPC actionは実行しません。
     *
     * @param player 判定対象プレイヤー
     * @param distance 判定する最大距離。0以上の有限値
     * @param raySize Entity判定に加えるrayの太さ。0以上の有限値
     * @return 命中したNPCと入口距離。見つからない、または引数が不正な場合はnull
     */
    @Nullable
    public MobInteractionHit findTargetedNpcHit(@NotNull Player player, double distance, double raySize) {
        Location eye = player.getEyeLocation();
        PlayerInteractionRayTrace ray = PlayerInteractionRayTrace.create(
                eye.toVector(),
                eye.getDirection(),
                distance
        );
        if (ray == null || !Double.isFinite(raySize) || raySize < 0.0D) {
            return null;
        }

        MobInteractionHit entityHit = findTargetedEntityNpcHit(player, ray, raySize);
        MobInteractionHit blockHit = findTargetedBlockNpcHit(player, ray);
        if (entityHit == null) {
            return blockHit;
        }
        if (blockHit == null
                || entityHit.hitDistance() < blockHit.hitDistance()
                || (Double.compare(entityHit.hitDistance(), blockHit.hitDistance()) == 0
                && entityHit.instance().instanceId().compareTo(blockHit.instance().instanceId()) <= 0)) {
            return entityHit;
        }
        return blockHit;
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
        return spawn(templateId, null, location);
    }

    /**
     * 指定レベルの Mob を生成します。レベル未指定または未登録の場合は最小レベルを使用します。
     *
     * @param templateId テンプレート ID
     * @param level      レベルプロファイル。未指定時は null
     * @param location   スポーン位置
     * @return 生成した Mob インスタンス。テンプレート未取得または実体生成不可なら null
     */
    @Nullable
    public MobInstance spawn(
            @NotNull String templateId,
            @Nullable Integer level,
            @NotNull Location location
    ) {
        MobTemplate template = findTemplate(templateId);
        if (template == null) {
            Logger.log(LogId.W_5701, templateId);
            return null;
        }

        return spawn(template.resolveLevel(level), location);
    }

    /**
     * filebase に登録されていない実行時テンプレートから Mob を生成します。
     *
     * @param template 実行時テンプレート
     * @param location スポーン位置
     * @return 生成した Mob インスタンス。実体生成不可なら null
     */
    @Nullable
    public MobInstance spawn(@NotNull MobTemplate template, @NotNull Location location) {
        UUID instanceId = UUID.randomUUID();
        MobInstance instance = new MobInstance(instanceId, template, location);
        var entity = entityController.spawn(instance, location);
        if (entity == null) {
            Logger.log(LogId.W_5705, template.entityType().name(), template.id());
            return null;
        }

        instances.put(instanceId, instance);
        trackEntity(instance.instanceId(), entity.getUniqueId());
        if (instance.displayEntityId() != null) {
            trackEntity(instance.instanceId(), instance.displayEntityId());
        }
        viewers.put(instanceId, new HashSet<>());
        updateViewers(instance);

        Logger.log(LogId.D_5701, template.id(), instanceId);
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

        if (conditionService != null) {
            conditionService.clearAll(AstEntity.mob(instance));
        }
        playerSkinPacketService.remove(instance);
        viewers.remove(instanceId);
        untrackEntity(instance.bukkitEntityId());
        untrackEntity(instance.displayEntityId());
        entityController.remove(instance);
        destroyListener.accept(instanceId);
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
            if (conditionService != null) {
                conditionService.clearAll(AstEntity.mob(instance));
            }
            playerSkinPacketService.remove(instance);
            entityController.remove(instance);
            destroyListener.accept(instance.instanceId());
        }
        instances.clear();
        instanceByEntity.clear();
        viewers.clear();
        playerSkinPacketService.removeAll();
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
     * 表示中の PLAYER 型 NPC について、位置・体の向き・頭部回転を viewer へ同期します。
     *
     * <p>viewer 集合の探索とは分離し、AI の毎 tick ループから呼び出すことで移動表示を滑らかに保ちます。</p>
     */
    public void syncPlayerSkinPacketViews() {
        for (MobInstance instance : instances.values()) {
            Set<UUID> currentViewers = viewers.get(instance.instanceId());
            if (instance.template().usesPlayerSkinPacketView()
                    && currentViewers != null
                    && !currentViewers.isEmpty()) {
                playerSkinPacketService.syncTransforms(instance);
            }
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
                .filter(instance -> !instance.keepWhenUnobserved())
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
     * 指定プレイヤーの署名付きスキンを、指定位置へ一時表示します。
     *
     * @param viewer       仮想 Player を表示するプレイヤー
     * @param skinSource   スキンを取得するオンラインプレイヤー
     * @param location     仮想 Player の表示位置
     * @param durationTicks 表示時間（tick）。正数で指定してください
     * @return スキン情報の取得と表示パケットの送信を開始できた場合は {@code true}
     */
    public boolean showTemporaryPlayerSkin(
            @NotNull Player viewer,
            @NotNull Player skinSource,
            @NotNull Location location,
            long durationTicks
    ) {
        return playerSkinPacketService.showTemporaryPlayerSkin(viewer, skinSource, location, durationTicks);
    }

    /**
     * 指定された署名付きプレイヤースキンを、指定位置へ一時表示します。
     *
     * @param viewer        仮想 Player を表示するプレイヤー
     * @param skin          Base64 テクスチャ値と署名値を持つスキン
     * @param location      仮想 Player の表示位置
     * @param durationTicks 表示時間（tick）。正数で指定してください
     * @return スキン情報の検証と表示パケットの送信を開始できた場合は {@code true}
     */
    public boolean showTemporaryPlayerSkin(
            @NotNull Player viewer,
            @NotNull MobSkin skin,
            @NotNull Location location,
            long durationTicks
    ) {
        return playerSkinPacketService.showTemporaryPlayerSkin(viewer, skin, location, durationTicks);
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
     * Vex が保持している三次元経路を 1 tick 分追従させます。
     *
     * @param instance 追従対象インスタンス
     */
    public void tickVexNavigation(@NotNull MobInstance instance) {
        entityController.tickVexNavigation(instance);
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

    /**
     * 対象 Mob を配置アンカーへ戻し、移動状態をリセットします。
     *
     * @param instance リセット対象インスタンス
     * @param anchor   戻し先の配置アンカー
     */
    public void resetPosition(@NotNull MobInstance instance, @NotNull Location anchor) {
        entityController.resetPosition(instance, anchor);
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
        playerSkinPacketService.sync(instance, currentViewers);
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

    private void addTemplateSelectors(@NotNull Set<String> suggestions, @NotNull MobTemplate template) {
        suggestions.add(template.id());
        String displayName = templateDisplayName(template);
        if (displayName.isBlank() || displayName.equalsIgnoreCase(template.id())) {
            return;
        }
        suggestions.add(displayName);
        suggestions.add(buildTemplateSelector(template));
    }

    private @NotNull String templateDisplayName(@NotNull MobTemplate template) {
        return ColorCodeUtil.toPlainText(template.displayName(), template.id());
    }

    private @NotNull String buildTemplateSelector(@NotNull MobTemplate template) {
        return template.id() + "（" + templateDisplayName(template) + "）";
    }

    private @NotNull String buildAsciiTemplateSelector(@NotNull MobTemplate template) {
        return template.id() + "(" + templateDisplayName(template) + ")";
    }

    private @NotNull String normalizeLookupValue(@NotNull String value) {
        return ColorCodeUtil.toPlainText(value, value).trim().toLowerCase(Locale.ROOT);
    }

    private boolean isLikelyTemplateId(@NotNull String input) {
        if (input.isBlank()) {
            return false;
        }
        for (int i = 0; i < input.length(); i++) {
            char ch = input.charAt(i);
            if (!isAsciiTemplateIdChar(ch)) {
                return false;
            }
        }
        return true;
    }

    private boolean isAsciiTemplateIdChar(char ch) {
        return (ch >= 'a' && ch <= 'z')
                || (ch >= 'A' && ch <= 'Z')
                || (ch >= '0' && ch <= '9')
                || ch == '_'
                || ch == '-';
    }

    @Nullable
    private MobInteractionHit findTargetedEntityNpcHit(
            @NotNull Player player,
            @NotNull PlayerInteractionRayTrace ray,
            double raySize
    ) {
        MobInteractionHit nearest = null;
        for (MobInstance instance : instances.values()) {
            if (instance.template().category() != MobCategory.NPC || instance.bukkitEntityId() == null) {
                continue;
            }
            org.bukkit.entity.Entity entity = player.getWorld().getEntity(instance.bukkitEntityId());
            if (entity == null || entity == player || entity.getWorld() != player.getWorld()) {
                continue;
            }
            BoundingBox hitBox = entity.getBoundingBox();
            if (raySize > 0.0D) {
                hitBox.expand(raySize);
            }
            Double hitDistance = ray.aabbEntryDistance(hitBox);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && instance.instanceId().compareTo(nearest.instance().instanceId()) >= 0)))) {
                continue;
            }
            nearest = new MobInteractionHit(instance, hitDistance);
        }
        return nearest;
    }

    @Nullable
    private MobInteractionHit findTargetedBlockNpcHit(
            @NotNull Player player,
            @NotNull PlayerInteractionRayTrace ray
    ) {
        MobInteractionHit nearest = null;
        for (MobInstance instance : instances.values()) {
            if (instance.template().category() != MobCategory.NPC || instance.template().blockMaterial() == null) {
                continue;
            }
            Location blockLocation = instance.currentLocation();
            if (blockLocation.getWorld() != player.getWorld()) {
                continue;
            }
            BoundingBox hitBox = new BoundingBox(
                    blockLocation.getX() - 0.5D,
                    blockLocation.getY(),
                    blockLocation.getZ() - 0.5D,
                    blockLocation.getX() + 0.5D,
                    blockLocation.getY() + 1.0D,
                    blockLocation.getZ() + 0.5D
            );
            Double hitDistance = ray.aabbEntryDistance(hitBox);
            if (hitDistance == null || (nearest != null
                    && (hitDistance > nearest.hitDistance()
                    || (Double.compare(hitDistance, nearest.hitDistance()) == 0
                    && instance.instanceId().compareTo(nearest.instance().instanceId()) >= 0)))) {
                continue;
            }
            nearest = new MobInteractionHit(instance, hitDistance);
        }
        return nearest;
    }

    private void trackEntity(@NotNull UUID instanceId, @Nullable UUID entityId) {
        if (entityId != null) {
            instanceByEntity.put(entityId, instanceId);
        }
    }

    private void untrackEntity(@Nullable UUID entityId) {
        if (entityId != null) {
            instanceByEntity.remove(entityId);
        }
    }
}
