package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * フックショットの照準、消費、視覚効果、および物理的な牽引を扱います。
 * <p>
 * プレイヤー座標の直接変更やteleportは行わず、短時間だけ現在velocityへアンカー方向の加速度を加えます。
 */
public final class HookshotUseService {
    static final double MAX_RANGE = 24.0D;
    static final int MAX_PULL_TICKS = 36;
    static final double MAX_PULL_SPEED = 1.20D;

    private static final double STOP_DISTANCE = 1.35D;
    private static final double PULL_ACCELERATION = 0.11D;
    private static final double VELOCITY_RETENTION = 0.82D;
    private static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-6D;
    private static final double ANCHOR_SURFACE_OFFSET = 0.03D;
    private static final int TRAIL_INTERVAL_TICKS = 2;
    private static final int MAX_TETHER_PARTICLE_POINTS = 10;
    private static final String ANCHOR_DISPLAY_TAG = "astralrecord_hookshot_anchor";

    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final HookshotCostService costService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, ActiveHook> activeHooks = new HashMap<>();

    /**
     * フックショットの実行サービスを構成します。
     *
     * @param plugin taskを所有するPlugin
     * @param inventoryService 主手参照と表示更新の正本サービス
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

    /**
     * 現在の視線上にフックショットが発射可能な固体アンカーがあるかを副作用なしで判定します。
     *
     * @param player 照準を確認するプレイヤー
     * @return 最大射程内の最初の命中先が固体blockの場合はtrue
     */
    public boolean hasValidAnchor(@NotNull AstPlayer player) {
        return findAnchor(player.getBukkit()) != null;
    }

    /**
     * 現在の主手フックショットを発射します。
     * <p>
     * 固体blockへ命中した場合だけコストを消費し、BlockDisplayとtether particleを生成して牽引を開始します。
     *
     * @param player 発射プレイヤー
     */
    public void fire(@NotNull AstPlayer player) {
        if (!isPlayerMode(player) || activeHooks.containsKey(player.getBukkit().getUniqueId())) {
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
        Location anchor = findAnchor(bukkitPlayer);
        if (anchor == null) {
            playDenied(bukkitPlayer);
            return;
        }

        BlockDisplay anchorDisplay = spawnAnchorDisplay(anchor);
        if (anchorDisplay == null) {
            playDenied(bukkitPlayer);
            return;
        }

        HookshotCostService.Result costResult = costService.consumeForLaunch(
            player,
            current.model(),
            current.reference()
        );
        if (costResult != HookshotCostService.Result.CONSUMED) {
            anchorDisplay.remove();
            playDenied(bukkitPlayer);
            return;
        }

        ActiveHook active = new ActiveHook(
            current.instance().getEquipmentInstanceId(),
            anchor,
            anchorDisplay
        );
        activeHooks.put(bukkitPlayer.getUniqueId(), active);
        active.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tick(bukkitPlayer.getUniqueId()),
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

    /** Plugin停止時にすべての牽引を終了します。 */
    public void shutdown() {
        for (UUID playerId : List.copyOf(activeHooks.keySet())) {
            cancel(playerId);
        }
    }

    private void tick(@NotNull UUID playerId) {
        ActiveHook active = activeHooks.get(playerId);
        if (active == null) {
            return;
        }
        Player player = plugin.getServer().getPlayer(playerId);
        if (!shouldContinue(player, active)) {
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

    static @NotNull Vector calculatePullVelocity(
        @NotNull Vector currentVelocity,
        @NotNull Vector towardAnchor
    ) {
        Vector next = currentVelocity.clone().multiply(VELOCITY_RETENTION);
        if (towardAnchor.lengthSquared() > MIN_VECTOR_LENGTH_SQUARED) {
            next.add(towardAnchor.clone().normalize().multiply(PULL_ACCELERATION));
        }
        if (next.lengthSquared() <= MAX_PULL_SPEED * MAX_PULL_SPEED) {
            return next;
        }
        return next.normalize().multiply(MAX_PULL_SPEED);
    }

    private boolean shouldContinue(@Nullable Player player, @NotNull ActiveHook active) {
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
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || instance == null || !isHookshot(model)) {
            return null;
        }
        return new CurrentHookshot(reference, model, instance);
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

    private void playDenied(@NotNull Player player) {
        player.playSound(
            player.getLocation(),
            Sound.BLOCK_DISPENSER_FAIL,
            SoundCategory.PLAYERS,
            0.6F,
            1.0F
        );
    }

    private static boolean isPlayerMode(@NotNull AstPlayer player) {
        return player.getAccount().getMode() == AccountMode.PLAYER;
    }

    private record CurrentHookshot(
        @NotNull ItemReference reference,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
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
