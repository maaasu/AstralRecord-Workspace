package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.hud.service.PlayerHudService;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.title.Title;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * フックショットの装填状態、照準、視覚効果、および物理的な牽引を扱います。
 * <p>
 * 装填済み状態は equipment inventory entry metadata に保存します。プレイヤー座標の直接変更や
 * teleport は行わず、短時間だけ現在 velocity へアンカー方向の加速度を加えます。
 */
public final class HookshotUseService {
    static final double MAX_RANGE = 24.0D;
    static final int LOAD_DURATION_TICKS = 30;
    static final int MAX_PULL_TICKS = 44;
    static final double MAX_PULL_SPEED = 2.05D;
    static final double MIN_PULL_ACCELERATION = 0.16D;
    static final double MAX_PULL_ACCELERATION = 0.27D;

    private static final double STOP_DISTANCE = 1.15D;
    private static final double FORWARD_VELOCITY_RETENTION = 0.88D;
    private static final double LATERAL_VELOCITY_RETENTION = 0.68D;
    private static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-6D;
    private static final double ANCHOR_SURFACE_OFFSET = 0.03D;
    private static final int TRAIL_INTERVAL_TICKS = 2;
    private static final int MAX_TETHER_PARTICLE_POINTS = 12;
    private static final int LOAD_ACTION_BAR_LENGTH = 16;
    private static final Title.Times FIRE_FEEDBACK_TITLE_TIMES = Title.Times.times(
        Duration.ZERO,
        Duration.ofMillis(1400L),
        Duration.ofMillis(200L)
    );
    private static final String ANCHOR_DISPLAY_TAG = "astralrecord_hookshot_anchor";
    private static final NamespacedKey LOADING_MOVEMENT_SPEED_MODIFIER_KEY =
        new NamespacedKey("astralrecord", "hookshot_loading_slowdown");
    private static final double LOADING_MOVEMENT_SPEED_MODIFIER_AMOUNT = -0.5D;

    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final HookshotCostService costService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, ActiveHook> activeHooks = new HashMap<>();
    private final Map<UUID, LoadingHook> loadingHooks = new HashMap<>();
    private @Nullable PlayerHudService playerHudService;

    /**
     * フックショットの実行サービスを構成します。
     *
     * @param plugin taskを所有するPlugin
     * @param inventoryService 主手参照・装填状態の正本サービス
     * @param itemService equipment instance解決・耐久更新サービス
     * @param particleDisplayService viewer設定に従う粒子表示サービス
     */
    public HookshotUseService(
        @NotNull AstralRecord plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
        this.costService = new HookshotCostService(inventoryService, itemService);
        this.particleDisplayService = particleDisplayService;
    }

    /** 装填中 ActionBar を通常 HUD と協調して表示するサービスを設定します。 */
    public void setPlayerHudService(@NotNull PlayerHudService playerHudService) {
        this.playerHudService = playerHudService;
    }

    /**
     * 現在の主手がフックショットなら、そのequipment instance IDを返します。
     *
     * @param player 対象プレイヤー
     * @return フックショットinstance ID。条件を満たさない場合はnull
     */
    public @Nullable String findCurrentHookshotInstanceId(@NotNull AstPlayer player) {
        CurrentHookshot current = findCurrentHookshot(player);
        return current == null ? null : current.instance().getEquipmentInstanceId();
    }

    /** 指定した主手フックショットが metadata 上も装填済みか判定します。 */
    public boolean isCurrentHookshotLoaded(@NotNull AstPlayer player, @NotNull String expectedInstanceId) {
        CurrentHookshot current = findCurrentHookshot(player);
        return current != null
            && current.instance().getEquipmentInstanceId().equalsIgnoreCase(expectedInstanceId)
            && HookshotLoadState.isLoaded(current.metadataJson());
    }

    /**
     * 選択済みの主手フックショットがexecutor実行時にも同じinstanceかを確認します。
     *
     * @param player 対象プレイヤー
     * @param expectedInstanceId 選択時のinstance ID
     * @return 同じ有効なフックショットを主手に持つ場合はtrue
     */
    public boolean isCurrentHookshot(@NotNull AstPlayer player, @NotNull String expectedInstanceId) {
        CurrentHookshot current = findCurrentHookshot(player);
        return current != null
            && current.instance().getEquipmentInstanceId().equalsIgnoreCase(expectedInstanceId);
    }

    /** 現在の主手フックショットで副作用なく装填開始できるか判定します。 */
    public boolean canStartLoading(@NotNull AstPlayer player) {
        if (!isPlayerMode(player) || player.isSkillCasting()) {
            return false;
        }
        UUID playerId = player.getBukkit().getUniqueId();
        if (activeHooks.containsKey(playerId) || loadingHooks.containsKey(playerId)) {
            return false;
        }
        CurrentHookshot current = findCurrentHookshot(player);
        if (current == null || HookshotLoadState.isLoaded(current.metadataJson())) {
            return false;
        }
        ItemEquipment equipment = current.model().getEquipment();
        return equipment != null && EquipmentRequirementService.check(player, equipment).allowed();
    }

    /**
     * 現在の主手フックショットを装填します。素材・耐久は開始時に消費しません。
     * <p>
     * フックが不足している場合は装填taskを開始せず、エラーを表示します。フックがある場合は
     * 30 tick の装填を完了した時点で、hook 1個の消費と metadata の loaded 化を同じ inventory state lock で確定します。
     *
     * @param player 装填プレイヤー
     */
    public void startLoading(@NotNull AstPlayer player) {
        startLoading(player, true);
    }

    /**
     * 現在の主手フックショットを装填し、完了時に自動発射するかを指定します。
     * <p>
     * 左クリックは従来どおり {@code true} として扱い、右クリックの装填だけは
     * {@code false} を指定して loaded 状態を保持します。素材・耐久は開始時に消費せず、
     * 30 tick 完了時の hook 消費と metadata 更新を同じ inventory state lock で確定します。
     *
     * @param player 装填プレイヤー
     * @param fireOnCompletion 装填完了時に現在の有効なアンカーへ自動発射する場合は true
     */
    public void startLoading(@NotNull AstPlayer player, boolean fireOnCompletion) {
        if (!canStartLoading(player)) {
            return;
        }
        CurrentHookshot current = findCurrentHookshot(player);
        if (current == null) {
            return;
        }
        ItemEquipment equipment = current.model().getEquipment();
        if (equipment == null || !EquipmentRequirementService.checkAndNotify(player, equipment)) {
            return;
        }
        Player bukkitPlayer = player.getBukkit();
        if (!bukkitPlayer.isOnline() || bukkitPlayer.isDead()) {
            return;
        }
        if (inventoryService.getNormalItemAmount(
            player.getAccount().getUuid(),
            HookshotCostService.HOOK_ITEM_ID
        ) < HookshotCostService.HOOK_AMOUNT_PER_LOAD) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5271);
            playDenied(bukkitPlayer);
            return;
        }

        UUID playerId = bukkitPlayer.getUniqueId();
        LoadingHook loading = new LoadingHook(
            player,
            current.instance().getEquipmentInstanceId(),
            current.metadataJson(),
            fireOnCompletion,
            applyLoadingMovementSpeedModifier(bukkitPlayer)
        );
        loadingHooks.put(playerId, loading);
        startLoadingFeedback(loading);
        loading.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tickLoading(playerId),
            1L,
            1L
        );
        bukkitPlayer.playSound(
            bukkitPlayer.getLocation(),
            Sound.ITEM_CROSSBOW_LOADING_START,
            SoundCategory.PLAYERS,
            0.65F,
            1.0F
        );
    }

    /**
     * 現在の視線上にフックショットが発射可能な固体アンカーがあるかを副作用なく判定します。
     *
     * @param player 照準を確認するプレイヤー
     * @return 最大射程内の最初の命中先が固体blockの場合はtrue
     */
    public boolean hasValidAnchor(@NotNull AstPlayer player) {
        return findAnchor(player.getBukkit()) != null;
    }

    /** 装填済みの主手フックショットで、副作用なく発射試行候補を返せるか判定します。的なしはfire側で通知します。 */
    public boolean canFire(@NotNull AstPlayer player, @NotNull String expectedInstanceId) {
        if (!isPlayerMode(player) || player.isSkillCasting()) {
            return false;
        }
        UUID playerId = player.getBukkit().getUniqueId();
        return !activeHooks.containsKey(playerId)
            && !loadingHooks.containsKey(playerId)
            && isCurrentHookshotLoaded(player, expectedInstanceId);
    }

    /**
     * 装填済みの現在の主手フックショットを発射します。
     * <p>
     * 固体blockへ命中した場合だけ耐久を消費して loaded 状態を外し、BlockDisplay と tether particle を生成して牽引を開始します。
     * 無効照準・耐久不足時には loaded 状態を保持します。無効照準時はタイトルを空欄にした灰色subtitleを表示します。
     *
     * @param player 発射プレイヤー
     */
    public void fire(@NotNull AstPlayer player) {
        if (!isPlayerMode(player) || player.isSkillCasting()) {
            return;
        }
        UUID playerId = player.getBukkit().getUniqueId();
        if (activeHooks.containsKey(playerId) || loadingHooks.containsKey(playerId)) {
            return;
        }

        CurrentHookshot current = findCurrentHookshot(player);
        if (current == null || !HookshotLoadState.isLoaded(current.metadataJson())) {
            return;
        }
        ItemEquipment equipment = current.model().getEquipment();
        if (equipment == null || !EquipmentRequirementService.checkAndNotify(player, equipment)) {
            return;
        }

        Player bukkitPlayer = player.getBukkit();
        if (!bukkitPlayer.isOnline() || bukkitPlayer.isDead()) {
            return;
        }
        Location anchor = findAnchor(bukkitPlayer);
        if (anchor == null) {
            showMissFeedback(bukkitPlayer);
            playDenied(bukkitPlayer);
            return;
        }

        HookshotLoadState.Update unloaded = HookshotLoadState.setLoaded(current.metadataJson(), false);
        if (!unloaded.accepted()) {
            playDenied(bukkitPlayer);
            return;
        }
        BlockDisplay anchorDisplay = spawnAnchorDisplay(anchor);
        if (anchorDisplay == null) {
            playDenied(bukkitPlayer);
            return;
        }

        HookshotCostService.DurabilityConsumption durabilityConsumption = costService.consumeDurabilityForFire(
            player,
            current.model(),
            current.reference()
        );
        if (durabilityConsumption == null) {
            anchorDisplay.remove();
            playDenied(bukkitPlayer);
            return;
        }
        if (!inventoryService.updateHotbarEquipmentMetadata(
            player,
            EquipmentSlot.HAND,
            current.instance().getEquipmentInstanceId(),
            current.metadataJson(),
            unloaded.metadataJson()
        )) {
            costService.rollbackDurability(player, durabilityConsumption);
            anchorDisplay.remove();
            playDenied(bukkitPlayer);
            return;
        }
        inventoryService.refreshManagedInventoryUi(player);

        ActiveHook active = new ActiveHook(
            current.instance().getEquipmentInstanceId(),
            anchor,
            anchorDisplay
        );
        activeHooks.put(playerId, active);
        active.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tickPull(playerId),
            1L,
            1L
        );
        bukkitPlayer.playSound(
            bukkitPlayer.getLocation(),
            Sound.ITEM_CROSSBOW_SHOOT,
            SoundCategory.PLAYERS,
            0.75F,
            1.25F
        );
        particleDisplayService.spawnForNearbyViewers(anchor, SharedParticleDefinitions.HOOKSHOT_ANCHOR);
        renderTether(bukkitPlayer, active);
    }

    /** 指定プレイヤーの牽引を終了し、短期表示とtaskを回収します。 */
    public void cancel(@NotNull UUID playerId) {
        ActiveHook active = activeHooks.remove(playerId);
        if (active == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        if (active.anchorDisplay.isValid()) {
            active.anchorDisplay.remove();
        }
    }

    /** 指定プレイヤーの未完了装填を中断し、移動低下・ActionBar・taskだけを回収します。 */
    public void cancelLoading(@NotNull UUID playerId) {
        LoadingHook loading = loadingHooks.remove(playerId);
        if (loading == null) {
            return;
        }
        if (loading.task != null) {
            loading.task.cancel();
        }
        loading.movementSpeedCleanup.run();
        stopLoadingFeedback(loading);
    }

    /** Plugin停止時に牽引と未完了装填を終了します。完成済みの loaded metadata は変更しません。 */
    public void shutdown() {
        for (UUID playerId : List.copyOf(activeHooks.keySet())) {
            cancel(playerId);
        }
        for (UUID playerId : List.copyOf(loadingHooks.keySet())) {
            cancelLoading(playerId);
        }
    }

    private void tickLoading(@NotNull UUID playerId) {
        LoadingHook loading = loadingHooks.get(playerId);
        if (loading == null) {
            return;
        }
        Player bukkitPlayer = loading.player.getBukkit();
        AstPlayer astPlayer = AstPlayerCache.get(bukkitPlayer);
        if (astPlayer == null || !shouldContinueLoading(astPlayer, loading)) {
            cancelLoading(playerId);
            return;
        }

        loading.elapsedTicks++;
        refreshLoadingFeedback(loading);
        if (loading.elapsedTicks < LOAD_DURATION_TICKS) {
            return;
        }

        CurrentHookshot current = findCurrentHookshot(astPlayer);
        HookshotLoadState.Update loaded = current == null
            ? HookshotLoadState.Update.rejected()
            : HookshotLoadState.setLoaded(current.metadataJson(), true);
        boolean completed = current != null
            && loaded.accepted()
            && inventoryService.consumeNormalItemAndUpdateHotbarEquipmentMetadata(
                astPlayer,
                EquipmentSlot.HAND,
                loading.equipmentInstanceId,
                loading.metadataJson,
                loaded.metadataJson(),
                HookshotCostService.HOOK_ITEM_ID,
                HookshotCostService.HOOK_AMOUNT_PER_LOAD
            );
        cancelLoading(playerId);
        if (!completed) {
            playDenied(bukkitPlayer);
            return;
        }
        inventoryService.refreshManagedInventoryUi(astPlayer);
        bukkitPlayer.playSound(
            bukkitPlayer.getLocation(),
            Sound.ITEM_CROSSBOW_LOADING_END,
            SoundCategory.PLAYERS,
            0.75F,
            1.15F
        );
        if (loading.fireOnCompletion) {
            // 左クリック装填では、完了を起点に現在の照準へ即時発射します。
            // 右クリック装填は loaded 状態を表示したまま保持します。
            fire(astPlayer);
        }
    }

    private boolean shouldContinueLoading(@NotNull AstPlayer player, @NotNull LoadingHook loading) {
        Player bukkitPlayer = player.getBukkit();
        if (!isPlayerMode(player)
            || player.isSkillCasting()
            || !bukkitPlayer.isOnline()
            || bukkitPlayer.isDead()
            || activeHooks.containsKey(bukkitPlayer.getUniqueId())) {
            return false;
        }
        CurrentHookshot current = findCurrentHookshot(player);
        return current != null
            && current.instance().getEquipmentInstanceId().equalsIgnoreCase(loading.equipmentInstanceId)
            && !HookshotLoadState.isLoaded(current.metadataJson())
            && java.util.Objects.equals(current.metadataJson(), loading.metadataJson);
    }

    private void tickPull(@NotNull UUID playerId) {
        ActiveHook active = activeHooks.get(playerId);
        if (active == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (!shouldContinuePull(player, active)) {
            cancel(playerId);
            return;
        }

        Location playerCenter = player.getLocation().add(0.0D, player.getHeight() * 0.55D, 0.0D);
        Vector towardAnchor = active.anchor.toVector().subtract(playerCenter.toVector());
        active.elapsedTicks++;
        if (towardAnchor.lengthSquared() <= STOP_DISTANCE * STOP_DISTANCE || active.elapsedTicks >= MAX_PULL_TICKS) {
            particleDisplayService.spawnForNearbyViewers(
                active.anchor,
                SharedParticleDefinitions.HOOKSHOT_ANCHOR
            );
            cancel(playerId);
            return;
        }

        player.setVelocity(calculatePullVelocity(player.getVelocity(), towardAnchor));
        if (active.elapsedTicks % TRAIL_INTERVAL_TICKS == 0) {
            renderTether(player, active);
        }
    }

    /**
     * 現在速度をアンカー方向へ自然に曲げる、距離比例の牽引 velocity を返します。
     * <p>
     * 前方速度は保持し、横方向の慣性だけを減衰してロープに引かれる軌道を作ります。
     */
    static @NotNull Vector calculatePullVelocity(
        @NotNull Vector currentVelocity,
        @NotNull Vector towardAnchor
    ) {
        double distanceSquared = towardAnchor.lengthSquared();
        if (distanceSquared <= MIN_VECTOR_LENGTH_SQUARED) {
            return currentVelocity.clone();
        }
        double distance = Math.sqrt(distanceSquared);
        Vector direction = towardAnchor.clone().multiply(1.0D / distance);
        double normalizedDistance = Math.clamp(
            (distance - STOP_DISTANCE) / Math.max(1.0D, MAX_RANGE - STOP_DISTANCE),
            0.0D,
            1.0D
        );
        double acceleration = MIN_PULL_ACCELERATION
            + (MAX_PULL_ACCELERATION - MIN_PULL_ACCELERATION) * normalizedDistance;
        double forwardSpeed = currentVelocity.dot(direction);
        Vector forward = direction.clone().multiply(Math.max(0.0D, forwardSpeed) * FORWARD_VELOCITY_RETENTION + acceleration);
        Vector lateral = currentVelocity.clone()
            .subtract(direction.clone().multiply(forwardSpeed))
            .multiply(LATERAL_VELOCITY_RETENTION);
        Vector next = forward.add(lateral);
        if (next.lengthSquared() <= MAX_PULL_SPEED * MAX_PULL_SPEED) {
            return next;
        }
        return next.normalize().multiply(MAX_PULL_SPEED);
    }

    private boolean shouldContinuePull(@Nullable Player player, @NotNull ActiveHook active) {
        if (player == null
            || !player.isOnline()
            || player.isDead()
            || !player.getWorld().equals(active.anchor.getWorld())
            || !active.anchorDisplay.isValid()) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null
            && isPlayerMode(astPlayer)
            && isCurrentHookshot(astPlayer, active.equipmentInstanceId);
    }

    private @Nullable CurrentHookshot findCurrentHookshot(@NotNull AstPlayer player) {
        InventoryEntryModel entry = inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND);
        if (entry == null) {
            return null;
        }
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || instance == null || !isHookshot(model)) {
            return null;
        }
        return new CurrentHookshot(reference, model, instance, entry.getMetadataJson());
    }

    private boolean isHookshot(@NotNull ItemModel model) {
        ItemEquipment equipment = model.getEquipment();
        return equipment != null
            && equipment.getSlot() == ItemEquipmentSlot.TOOL
            && MasterTagIds.Equipment.HOOKSHOT.equalsIgnoreCase(equipment.getTag());
    }

    private @Nullable Location findAnchor(@NotNull Player player) {
        Location eye = player.getEyeLocation();
        Vector direction = eye.getDirection();
        if (direction.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            return null;
        }
        direction.normalize();
        RayTraceResult hit = player.getWorld().rayTraceBlocks(
            eye,
            direction,
            MAX_RANGE,
            FluidCollisionMode.NEVER,
            true
        );
        if (hit == null
            || hit.getHitBlock() == null
            || hit.getHitPosition() == null
            || !hit.getHitBlock().getType().isSolid()) {
            return null;
        }
        return hit.getHitPosition()
            .toLocation(player.getWorld())
            .add(direction.clone().multiply(-ANCHOR_SURFACE_OFFSET));
    }

    private @Nullable BlockDisplay spawnAnchorDisplay(@NotNull Location anchor) {
        if (anchor.getWorld() == null) {
            return null;
        }
        return anchor.getWorld().spawn(anchor, BlockDisplay.class, display -> {
            display.setBlock(Material.IRON_BARS.createBlockData());
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setSilent(true);
            display.setViewRange(48.0F);
            display.setTeleportDuration(1);
            display.addScoreboardTag(ANCHOR_DISPLAY_TAG);
            display.setTransformation(new Transformation(
                new Vector3f(-0.16F, -0.16F, -0.16F),
                new Quaternionf(),
                new Vector3f(0.32F, 0.32F, 0.32F),
                new Quaternionf()
            ));
        });
    }

    private void renderTether(@NotNull Player player, @NotNull ActiveHook active) {
        Location start = player.getEyeLocation();
        List<Location> points = tetherPoints(start, active.anchor);
        particleDisplayService.spawnForNearbyViewers(
            start,
            points,
            SharedParticleDefinitions.HOOKSHOT_TRAIL
        );
    }

    private @NotNull List<Location> tetherPoints(@NotNull Location start, @NotNull Location anchor) {
        Vector delta = anchor.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (distance <= 0.0D) {
            return List.of();
        }
        int pointCount = Math.min(
            MAX_TETHER_PARTICLE_POINTS,
            Math.max(1, (int) Math.ceil(distance / 2.5D))
        );
        List<Location> points = new ArrayList<>(pointCount);
        for (int index = 1; index <= pointCount; index++) {
            double progress = (double) index / (pointCount + 1);
            points.add(start.clone().add(delta.clone().multiply(progress)));
        }
        return points;
    }

    private @NotNull Runnable applyLoadingMovementSpeedModifier(@NotNull Player player) {
        AttributeInstance movementSpeed = player.getAttribute(Attribute.MOVEMENT_SPEED);
        if (movementSpeed == null) {
            return () -> { };
        }
        if (movementSpeed.getModifier(LOADING_MOVEMENT_SPEED_MODIFIER_KEY) != null) {
            movementSpeed.removeModifier(LOADING_MOVEMENT_SPEED_MODIFIER_KEY);
        }
        movementSpeed.addTransientModifier(new AttributeModifier(
            LOADING_MOVEMENT_SPEED_MODIFIER_KEY,
            LOADING_MOVEMENT_SPEED_MODIFIER_AMOUNT,
            AttributeModifier.Operation.MULTIPLY_SCALAR_1
        ));
        return () -> {
            if (movementSpeed.getModifier(LOADING_MOVEMENT_SPEED_MODIFIER_KEY) != null) {
                movementSpeed.removeModifier(LOADING_MOVEMENT_SPEED_MODIFIER_KEY);
            }
        };
    }

    private void startLoadingFeedback(@NotNull LoadingHook loading) {
        PlayerHudService hudService = playerHudService;
        if (hudService == null) {
            loading.player.getBukkit().sendActionBar(createLoadingActionBar(loading.elapsedTicks));
            return;
        }
        hudService.setPrimaryActionBarRenderer(
            loading.player.getBukkit().getUniqueId(),
            ignored -> createLoadingActionBar(loading.elapsedTicks)
        );
        hudService.refreshActionBar(loading.player);
    }

    private void refreshLoadingFeedback(@NotNull LoadingHook loading) {
        PlayerHudService hudService = playerHudService;
        if (hudService == null) {
            loading.player.getBukkit().sendActionBar(createLoadingActionBar(loading.elapsedTicks));
            return;
        }
        hudService.refreshActionBar(loading.player);
    }

    private void stopLoadingFeedback(@NotNull LoadingHook loading) {
        PlayerHudService hudService = playerHudService;
        if (hudService == null || loading.player.isSkillCasting()) {
            return;
        }
        hudService.clearPrimaryActionBarRenderer(loading.player.getBukkit().getUniqueId());
        hudService.refreshActionBar(loading.player);
    }

    private @NotNull Component createLoadingActionBar(int elapsedTicks) {
        int safeElapsed = Math.clamp(elapsedTicks, 0, LOAD_DURATION_TICKS);
        int remainingTicks = LOAD_DURATION_TICKS - safeElapsed;
        int completed = (int) Math.round((double) safeElapsed / LOAD_DURATION_TICKS * LOAD_ACTION_BAR_LENGTH);
        String bar = "■".repeat(completed) + "□".repeat(LOAD_ACTION_BAR_LENGTH - completed);
        return Component.text("フック装填中 ", NamedTextColor.AQUA)
            .append(Component.text(String.format(Locale.ROOT, "%.1fs ", remainingTicks / 20.0D), NamedTextColor.WHITE))
            .append(Component.text(bar, NamedTextColor.GREEN));
    }

    private void playDenied(@NotNull Player player) {
        player.playSound(
            player.getLocation(),
            Sound.BLOCK_DISPENSER_FAIL,
            SoundCategory.PLAYERS,
            0.6F,
            1.0F
        );
    }

    private void showMissFeedback(@NotNull Player player) {
        player.showTitle(Title.title(
            Component.empty(),
            PlayerMsgResource.getComponent(PlayerMsgId.P_5272.getId()),
            FIRE_FEEDBACK_TITLE_TIMES
        ));
    }

    private static boolean isPlayerMode(@NotNull AstPlayer player) {
        return player.getAccount().getMode() == AccountMode.PLAYER;
    }

    private record CurrentHookshot(
        @NotNull ItemReference reference,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance,
        @Nullable String metadataJson
    ) {
    }

    private static final class LoadingHook {
        private final AstPlayer player;
        private final String equipmentInstanceId;
        private final @Nullable String metadataJson;
        private final boolean fireOnCompletion;
        private final Runnable movementSpeedCleanup;
        private int elapsedTicks;
        private @Nullable BukkitTask task;

        private LoadingHook(
            @NotNull AstPlayer player,
            @NotNull String equipmentInstanceId,
            @Nullable String metadataJson,
            boolean fireOnCompletion,
            @NotNull Runnable movementSpeedCleanup
        ) {
            this.player = player;
            this.equipmentInstanceId = equipmentInstanceId;
            this.metadataJson = metadataJson;
            this.fireOnCompletion = fireOnCompletion;
            this.movementSpeedCleanup = movementSpeedCleanup;
        }
    }

    private static final class ActiveHook {
        private final String equipmentInstanceId;
        private final Location anchor;
        private final BlockDisplay anchorDisplay;
        private int elapsedTicks;
        private @Nullable BukkitTask task;

        private ActiveHook(
            @NotNull String equipmentInstanceId,
            @NotNull Location anchor,
            @NotNull BlockDisplay anchorDisplay
        ) {
            this.equipmentInstanceId = equipmentInstanceId;
            this.anchor = anchor.clone();
            this.anchorDisplay = anchorDisplay;
        }
    }
}
