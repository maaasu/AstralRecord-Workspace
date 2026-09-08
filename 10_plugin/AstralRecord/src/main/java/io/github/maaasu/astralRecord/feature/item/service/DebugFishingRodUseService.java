package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.model.ItemReference;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.shared.effect.ParticleDisplayService;
import io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.block.Block;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
 * デバッグ釣り竿の仮想キャストを扱います。
 * <p>
 * バニラの {@code FishHook} は生成せず、針を {@link BlockDisplay}、糸を曲線上の複数の
 * {@link BlockDisplay} として表示します。終点は active state 内で可変に保持するため、将来の
 * 移動する釣り針にも同じ更新処理を利用できます。
 */
public final class DebugFishingRodUseService {
    static final double ROD_TIP_RIGHT_OFFSET = 0.35D;
    static final double ROD_TIP_UP_OFFSET = 0.20D;
    static final double ROD_TIP_FORWARD_OFFSET = 0.35D;
    static final double HOOK_SPEED_PER_TICK = 1.5D;
    static final double HOOK_GRAVITY_PER_TICK = 0.05D;
    static final double RETRACT_SPEED_PER_TICK = 2.0D;
    static final double WATER_SINK_SPEED_PER_TICK = 0.08D;
    static final int ROPE_SEGMENT_COUNT = 20;
    static final float LINE_THICKNESS = 0.035F;
    static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-8D;

    private static final float DISPLAY_VIEW_RANGE = Float.MAX_VALUE;
    private static final double MIN_SEGMENT_LENGTH = 0.01D;
    private static final double COLLISION_ADVANCE_EPSILON = 0.001D;
    private static final int MAX_PASSABLE_BLOCK_SKIPS = 32;
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    private static final String HOOK_DISPLAY_TAG = "astralrecord_fishing_rod_hook";
    private static final String LINE_DISPLAY_TAG = "astralrecord_fishing_rod_line";

    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final StatusService statusService;
    private final ParticleDisplayService particleDisplayService;
    private final Map<UUID, ActiveCast> activeCasts = new HashMap<>();

    /**
     * デバッグ釣り竿の表示・移動サービスを構成します。
     *
     * @param plugin taskを所有するPlugin
     * @param inventoryService 主手のアイテム参照を解決するサービス
     * @param itemService equipment instanceを解決するサービス
     * @param statusService キャスト距離を取得するステータスサービス
     * @param particleDisplayService viewer設定に従って粒子を送るサービス
     */
    public DebugFishingRodUseService(
        @NotNull AstralRecord plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull StatusService statusService,
        @NotNull ParticleDisplayService particleDisplayService
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
        this.statusService = statusService;
        this.particleDisplayService = particleDisplayService;
    }

    /** 現在の主手が釣り竿なら、そのequipment instance IDを返します。 */
    public @Nullable String findCurrentFishingRodInstanceId(@NotNull AstPlayer player) {
        CurrentFishingRod current = findCurrentFishingRod(player);
        return current == null ? null : current.instance().getEquipmentInstanceId();
    }

    /** 選択時の主手が、指定したequipment instanceの釣り竿かを確認します。 */
    public boolean isCurrentFishingRod(@NotNull AstPlayer player, @NotNull String expectedInstanceId) {
        CurrentFishingRod current = findCurrentFishingRod(player);
        return current != null
            && current.instance().getEquipmentInstanceId().equalsIgnoreCase(expectedInstanceId);
    }

    /** 現在の主手の釣り竿が回収対象のキャストを持つか判定します。 */
    public boolean isCasting(@NotNull AstPlayer player) {
        String instanceId = findCurrentFishingRodInstanceId(player);
        return instanceId != null
            && activeCasts.containsKey(player.getBukkit().getUniqueId())
            && activeCasts.get(player.getBukkit().getUniqueId()).equipmentInstanceId.equalsIgnoreCase(instanceId);
    }

    /** 右クリック時点で新しいキャストを開始可能か判定します。 */
    public boolean canCast(@NotNull AstPlayer player) {
        if (player.getAccount().getMode() != AccountMode.PLAYER || player.isSkillCasting()) {
            return false;
        }
        Player bukkitPlayer = player.getBukkit();
        if (!bukkitPlayer.isOnline() || bukkitPlayer.isDead()) {
            return false;
        }
        if (activeCasts.containsKey(bukkitPlayer.getUniqueId())) {
            return false;
        }
        CurrentFishingRod current = findCurrentFishingRod(player);
        if (current == null) {
            return false;
        }
        ItemEquipment equipment = current.model().getEquipment();
        return equipment != null && EquipmentRequirementService.check(player, equipment).allowed();
    }

    /**
     * キャスト距離ステータス分の仮想釣り針を発射します。
     * <p>
     * キャスト距離にプラグイン側の最大値は設けません。値はステータスから取得し、有限かつ正の値で
     * あることだけを実行条件とします。右クリックで発射中の場合は別の処理で回収へ切り替えます。
     */
    public void cast(@NotNull AstPlayer player) {
        if (!canCast(player)) {
            return;
        }
        CurrentFishingRod current = findCurrentFishingRod(player);
        if (current == null) {
            return;
        }
        ItemEquipment equipment = current.model().getEquipment();
        if (equipment == null || !EquipmentRequirementService.checkAndNotify(player, equipment)) {
            return;
        }

        Player bukkitPlayer = player.getBukkit();
        Location eye = bukkitPlayer.getEyeLocation();
        Location rodTip = resolveRodTip(eye, eye.getDirection());
        Vector direction = normalizedDirection(eye.getDirection());
        if (rodTip == null || direction == null || rodTip.getWorld() == null) {
            return;
        }

        StatusSnapshot status = statusService.getStatus(player);
        double castDistance = status.rollValue(StatusType.CAST_DISTANCE);
        if (!Double.isFinite(castDistance) || castDistance <= 0.0D) {
            return;
        }

        Location target = rodTip.clone().add(direction.clone().multiply(castDistance));
        if (!isFinite(target)) {
            return;
        }

        BlockDisplay hookDisplay = spawnHookDisplay(rodTip);
        List<BlockDisplay> ropeDisplays = spawnRopeDisplays(rodTip);
        if (hookDisplay == null || ropeDisplays == null) {
            removeDisplay(hookDisplay);
            removeDisplays(ropeDisplays);
            return;
        }

        UUID playerId = bukkitPlayer.getUniqueId();
        ActiveCast active = new ActiveCast(
            current.instance().getEquipmentInstanceId(),
            rodTip.clone(),
            rodTip.clone(),
            target,
            castDistance,
            false,
            hookDisplay,
            ropeDisplays
        );
        activeCasts.put(playerId, active);
        active.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tick(playerId),
            1L,
            1L
        );
        render(bukkitPlayer, active, rodTip);
    }

    /**
     * 現在の釣り針をプレイヤー側へ回収するアニメーションを開始します。
     * 二重クリックや item 切替による誤操作を避けるため、呼び出し元で現在の釣り竿を確認します。
     */
    public void retract(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        if (active == null || !isCurrentFishingRod(player, active.equipmentInstanceId)) {
            return;
        }
        Location eye = player.getBukkit().getEyeLocation();
        Location rodTip = resolveRodTip(eye, eye.getDirection());
        if (rodTip == null) {
            return;
        }
        active.target = rodTip;
        active.phase = CastPhase.RETRACTING;
        active.waterImpact = false;
    }

    /**
     * アクティブな釣り針の終点を差し替えます。将来、移動する釣り針や追尾先を実装するときに利用します。
     *
     * @param playerId キャスト中プレイヤー
     * @param target 新しい終点
     * @return 終点を更新できた場合はtrue
     */
    public boolean moveHook(@NotNull UUID playerId, @NotNull Location target) {
        ActiveCast active = activeCasts.get(playerId);
        if (active == null || !isFinite(target) || target.getWorld() == null
            || !target.getWorld().equals(active.currentHook.getWorld())) {
            return false;
        }
        active.target = target.clone();
        active.velocity = velocityTowards(active.currentHook, active.target);
        active.waterImpact = isWater(target.getBlock().getType());
        active.phase = CastPhase.OUTBOUND;
        return true;
    }

    /** 指定プレイヤーのキャスト表示と task を回収します。 */
    public void cancel(@NotNull UUID playerId) {
        ActiveCast active = activeCasts.remove(playerId);
        if (active == null) {
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        removeDisplay(active.hookDisplay);
        removeDisplays(active.ropeDisplays);
    }

    /** Plugin停止時にすべての仮想キャストを終了します。 */
    public void shutdown() {
        for (UUID playerId : List.copyOf(activeCasts.keySet())) {
            cancel(playerId);
        }
    }

    /** テストと表示計算で共有する、目線から見た釣り竿先端の位置を返します。 */
    static @Nullable Location resolveRodTip(@NotNull Location eye, @NotNull Vector viewDirection) {
        Vector forward = normalizedDirection(viewDirection);
        if (forward == null) {
            return null;
        }
        Vector worldUp = new Vector(0.0D, 1.0D, 0.0D);
        Vector right = forward.clone().crossProduct(worldUp);
        if (right.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            right = new Vector(1.0D, 0.0D, 0.0D);
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();
        Vector offset = forward.clone().multiply(ROD_TIP_FORWARD_OFFSET)
            .add(right.multiply(ROD_TIP_RIGHT_OFFSET))
            .add(up.multiply(ROD_TIP_UP_OFFSET));
        return eye.clone().add(offset);
    }

    /** テストで使用する、表示線の向きと長さを計算します。 */
    static @Nullable Transformation lineTransformation(
        @NotNull Location start,
        @NotNull Location end
    ) {
        Vector delta = end.toVector().subtract(start.toVector());
        double length = delta.length();
        if (!Double.isFinite(length) || length <= 0.0D || length > Float.MAX_VALUE) {
            return null;
        }
        Vector normalized = delta.multiply(1.0D / length);
        Quaternionf rotation = new Quaternionf().rotationTo(
            new Vector3f(1.0F, 0.0F, 0.0F),
            new Vector3f((float) normalized.getX(), (float) normalized.getY(), (float) normalized.getZ())
        );
        return new Transformation(
            new Vector3f(0.0F, -LINE_THICKNESS * 0.5F, -LINE_THICKNESS * 0.5F),
            new Quaternionf(),
            new Vector3f((float) length, LINE_THICKNESS, LINE_THICKNESS),
            rotation
        );
    }

    /** 二次ベジェ曲線上の糸の位置を返します。 */
    static @NotNull Location ropePoint(
        @NotNull Location start,
        @NotNull Location control,
        @NotNull Location end,
        double progress
    ) {
        double t = Math.max(0.0D, Math.min(1.0D, progress));
        double inverse = 1.0D - t;
        double startWeight = inverse * inverse;
        double controlWeight = 2.0D * inverse * t;
        double endWeight = t * t;
        return start.clone().multiply(startWeight)
            .add(control.clone().multiply(controlWeight))
            .add(end.clone().multiply(endWeight));
    }

    private void tick(@NotNull UUID playerId) {
        ActiveCast active = activeCasts.get(playerId);
        Player player = plugin.getServer().getPlayer(playerId);
        if (active == null || player == null || !player.isOnline() || player.isDead()) {
            cancel(playerId);
            return;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null
            || astPlayer.getAccount().getMode() != AccountMode.PLAYER
            || !isCurrentFishingRod(astPlayer, active.equipmentInstanceId)
            || !player.getWorld().equals(active.currentHook.getWorld())) {
            cancel(playerId);
            return;
        }

        Location eye = player.getEyeLocation();
        Location rodTip = resolveRodTip(eye, eye.getDirection());
        if (rodTip == null || rodTip.getWorld() == null) {
            cancel(playerId);
            return;
        }
        active.rodTip = rodTip.clone();

        boolean arrived = advanceHook(active);
        render(player, active, rodTip);
        if (arrived && active.phase == CastPhase.RETRACTING) {
            cancel(playerId);
        }
    }

    /**
     * 釣り針を1 tick分進めます。発射中は初速と重力、水中では沈下速度と最大糸長を適用します。
     *
     * @param active 更新対象のキャスト状態
     * @return 回収先へ到達した場合はtrue
     */
    static boolean advanceHook(@NotNull ActiveCast active) {
        if (active.phase == CastPhase.HOLDING) {
            return false;
        }
        if (active.phase == CastPhase.SINKING) {
            Location next = constrainToMaxLine(
                active,
                active.currentHook.clone().add(0.0D, -WATER_SINK_SPEED_PER_TICK, 0.0D)
            );
            if (active.sinkTicks > 0 && !isWater(next.getBlock().getType())) {
                active.phase = CastPhase.HOLDING;
                return false;
            }
            active.currentHook = next;
            active.sinkTicks++;
            return false;
        }

        if (active.phase == CastPhase.OUTBOUND) {
            return advanceOutbound(active);
        }

        return moveTowards(active, RETRACT_SPEED_PER_TICK);
    }

    /**
     * 発射中の針を弾道に沿って進め、衝突と最大糸長を処理します。
     *
     * @param active 更新対象のキャスト状態
     * @return 発射中は常にfalse
     */
    private static boolean advanceOutbound(@NotNull ActiveCast active) {
        if (active.velocity.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            active.velocity = velocityTowards(active.currentHook, active.target);
        }
        if (active.velocity.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            return false;
        }

        // 糸長の上限を超える区間は移動させず、針が最大長へ達しても空中停止しないようにする。
        Vector movement = active.velocity.clone();
        Location candidate = active.currentHook.clone().add(movement);
        Location constrainedCandidate = constrainToMaxLine(active, candidate);
        Vector constrainedMovement = constrainedCandidate.toVector()
            .subtract(active.currentHook.toVector());
        boolean lineLimited = constrainedMovement.lengthSquared() + MIN_VECTOR_LENGTH_SQUARED
            < movement.lengthSquared();
        BlockImpact impact = rayTraceImpact(active.currentHook, constrainedMovement);
        if (impact != null) {
            active.currentHook = constrainToMaxLine(active, impact.location());
            active.waterImpact = impact.waterImpact();
            active.phase = impact.waterImpact() ? CastPhase.SINKING : CastPhase.HOLDING;
            active.sinkTicks = 0;
            return false;
        }

        active.currentHook = constrainedCandidate;
        active.velocity.setY(active.velocity.getY() - HOOK_GRAVITY_PER_TICK);
        if (lineLimited) {
            removeOutwardVelocity(active);
        }
        return false;
    }

    /**
     * 指定位置へ向かう初速ベクトルを作成します。
     *
     * @param start 始点
     * @param target 目標点
     * @return 針の初速。距離が無効な場合はゼロベクトル
     */
    private static @NotNull Vector velocityTowards(
        @NotNull Location start,
        @NotNull Location target
    ) {
        Vector delta = target.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_SEGMENT_LENGTH) {
            return new Vector();
        }
        return delta.multiply(HOOK_SPEED_PER_TICK / distance);
    }

    /**
     * 針の候補位置を竿先から最大糸長の球面内へ制限します。
     *
     * @param active キャスト状態
     * @param candidate 候補位置
     * @return 最大糸長を超えない位置
     */
    private static @NotNull Location constrainToMaxLine(
        @NotNull ActiveCast active,
        @NotNull Location candidate
    ) {
        if (!Double.isFinite(active.maxLineLength) || active.maxLineLength <= 0.0D) {
            return candidate;
        }
        Vector delta = candidate.toVector().subtract(active.rodTip.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= active.maxLineLength) {
            return candidate;
        }
        return active.rodTip.clone().add(delta.multiply(active.maxLineLength / distance));
    }

    /**
     * 最大糸長へ達した針の、竿先から外向きの速度成分を除去します。
     *
     * @param active キャスト状態
     */
    private static void removeOutwardVelocity(@NotNull ActiveCast active) {
        Vector radial = active.currentHook.toVector().subtract(active.rodTip.toVector());
        double distance = radial.length();
        if (!Double.isFinite(distance) || distance <= MIN_SEGMENT_LENGTH) {
            return;
        }
        radial.multiply(1.0D / distance);
        double outwardSpeed = active.velocity.dot(radial);
        if (outwardSpeed > 0.0D) {
            active.velocity.subtract(radial.multiply(outwardSpeed));
        }
    }

    private static boolean moveTowards(@NotNull ActiveCast active, double speed) {
        Vector delta = active.target.toVector().subtract(active.currentHook.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_SEGMENT_LENGTH) {
            active.currentHook = active.target.clone();
            return true;
        }
        double travel = Math.min(speed, distance);
        active.currentHook.add(delta.multiply(travel / distance));
        return distance - travel <= MIN_SEGMENT_LENGTH;
    }

    private void render(@NotNull Player player, @NotNull ActiveCast active, @NotNull Location rodTip) {
        if (!active.hookDisplay.isValid() || active.ropeDisplays.stream().anyMatch(display -> !display.isValid())) {
            cancel(player.getUniqueId());
            return;
        }
        active.hookDisplay.teleport(active.currentHook);
        renderRope(active, rodTip, active.currentHook);
        if (active.renderTicks++ % PARTICLE_INTERVAL_TICKS == 0) {
            particleDisplayService.spawnForNearbyViewers(rodTip, SharedParticleDefinitions.FISHING_ROD_TRAIL);
            particleDisplayService.spawnForViewer(player, active.currentHook, SharedParticleDefinitions.FISHING_ROD_HOOK);
        }
    }

    private void renderRope(
        @NotNull ActiveCast active,
        @NotNull Location start,
        @NotNull Location end
    ) {
        Location control = ropeControlPoint(start, end, active.phase);
        for (int index = 0; index < active.ropeDisplays.size(); index++) {
            double startProgress = (double) index / ROPE_SEGMENT_COUNT;
            double endProgress = (double) (index + 1) / ROPE_SEGMENT_COUNT;
            Location segmentStart = ropePoint(start, control, end, startProgress);
            Location segmentEnd = ropePoint(start, control, end, endProgress);
            Vector segment = segmentEnd.toVector().subtract(segmentStart.toVector());
            if (segment.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
                segmentEnd = segmentStart.clone().add(new Vector(MIN_SEGMENT_LENGTH, 0.0D, 0.0D));
            }
            BlockDisplay display = active.ropeDisplays.get(index);
            display.teleport(segmentStart);
            Transformation transformation = lineTransformation(segmentStart, segmentEnd);
            if (transformation != null) {
                display.setTransformation(transformation);
            }
        }
    }

    private static @NotNull Location ropeControlPoint(
        @NotNull Location start,
        @NotNull Location end,
        @NotNull CastPhase phase
    ) {
        Vector delta = end.toVector().subtract(start.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= MIN_SEGMENT_LENGTH) {
            return start.clone().add(0.0D, -0.15D, 0.0D);
        }
        Vector direction = delta.clone().multiply(1.0D / distance);
        double sagRatio = phase == CastPhase.RETRACTING ? 0.08D : 0.14D;
        double sag = Math.max(0.30D, Math.min(8.0D, distance * sagRatio));
        double lag = Math.min(3.0D, distance * 0.08D);
        return start.clone()
            .add(delta.multiply(0.5D))
            .add(0.0D, -sag, 0.0D)
            .subtract(direction.multiply(lag));
    }

    /**
     * 1 tick分の移動だけを衝突探索します。キャスト距離全体を同期レイトレースしないため、
     * ステータス値にプラグイン側の距離上限を設けず、距離が長い場合も探索量を1 tick分に抑えます。
     */
    private static @Nullable BlockImpact rayTraceImpact(
        @NotNull Location start,
        @NotNull Vector movement
    ) {
        World world = start.getWorld();
        double remaining = movement.length();
        if (world == null || !Double.isFinite(remaining) || remaining <= MIN_SEGMENT_LENGTH) {
            return null;
        }
        Vector direction = movement.clone().multiply(1.0D / remaining);
        Location cursor = start.clone();
        for (int skip = 0; skip <= MAX_PASSABLE_BLOCK_SKIPS; skip++) {
            RayTraceResult hit = world.rayTraceBlocks(
                cursor,
                direction,
                remaining,
                FluidCollisionMode.ALWAYS,
                false
            );
            if (hit == null || hit.getHitBlock() == null || hit.getHitPosition() == null) {
                return null;
            }
            Block block = hit.getHitBlock();
            Location hitLocation = hit.getHitPosition().toLocation(world);
            if (isWater(block.getType()) || !block.isPassable()) {
                return new BlockImpact(hitLocation, isWater(block.getType()));
            }

            double travelled = cursor.distance(hitLocation);
            double advance = travelled + COLLISION_ADVANCE_EPSILON;
            if (!Double.isFinite(travelled) || advance >= remaining) {
                return null;
            }
            cursor.add(direction.clone().multiply(advance));
            remaining -= advance;
        }
        return null;
    }

    private @Nullable BlockDisplay spawnHookDisplay(@NotNull Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        return location.getWorld().spawn(location, BlockDisplay.class, display -> {
            display.setBlock(Material.TRIPWIRE_HOOK.createBlockData());
            configureDisplay(display, HOOK_DISPLAY_TAG);
            display.setTransformation(new Transformation(
                new Vector3f(-0.20F, -0.20F, -0.20F),
                new Quaternionf(),
                new Vector3f(0.40F, 0.40F, 0.40F),
                new Quaternionf()
            ));
        });
    }

    private @Nullable List<BlockDisplay> spawnRopeDisplays(@NotNull Location location) {
        if (location.getWorld() == null) {
            return null;
        }
        List<BlockDisplay> displays = new ArrayList<>(ROPE_SEGMENT_COUNT);
        try {
            for (int index = 0; index < ROPE_SEGMENT_COUNT; index++) {
                BlockDisplay display = location.getWorld().spawn(location, BlockDisplay.class, entity -> {
                    entity.setBlock(Material.WHITE_WOOL.createBlockData());
                    configureDisplay(entity, LINE_DISPLAY_TAG);
                    entity.setTransformation(new Transformation(
                        new Vector3f(0.0F, -LINE_THICKNESS * 0.5F, -LINE_THICKNESS * 0.5F),
                        new Quaternionf(),
                        new Vector3f((float) MIN_SEGMENT_LENGTH, LINE_THICKNESS, LINE_THICKNESS),
                        new Quaternionf()
                    ));
                });
                displays.add(display);
            }
            return displays;
        } catch (RuntimeException failure) {
            removeDisplays(displays);
            return null;
        }
    }

    /**
     * 針と糸の表示Entityを、手動の物理・描画更新に適した設定へ初期化します。
     *
     * @param display 初期化対象の表示Entity
     * @param tag 表示Entityへ付与する識別タグ
     */
    private static void configureDisplay(@NotNull BlockDisplay display, @NotNull String tag) {
        // 針の重力と弾道はActiveCastで計算し、表示Entityは糸と同じ座標を描画するだけにする。
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);
        display.setSilent(true);
        display.setViewRange(DISPLAY_VIEW_RANGE);
        // 毎tickの移動補間が糸を横方向へ引き伸ばすため、クライアント補間を使わない。
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(0);
        display.setTeleportDuration(0);
        display.addScoreboardTag(tag);
    }

    private @Nullable CurrentFishingRod findCurrentFishingRod(@NotNull AstPlayer player) {
        InventoryEntryModel entry = inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND);
        if (entry == null) {
            return null;
        }
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || instance == null || !isFishingRod(model)) {
            return null;
        }
        return new CurrentFishingRod(reference, model, instance);
    }

    private boolean isFishingRod(@NotNull ItemModel model) {
        ItemEquipment equipment = model.getEquipment();
        return equipment != null
            && equipment.getSlot() == ItemEquipmentSlot.TOOL
            && MasterTagIds.Equipment.FISHING_ROD.equalsIgnoreCase(equipment.getTag());
    }

    private static @Nullable Vector normalizedDirection(@NotNull Vector direction) {
        if (!isFinite(direction) || direction.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            return null;
        }
        return direction.clone().normalize();
    }

    private static boolean isFinite(@NotNull Vector vector) {
        return Double.isFinite(vector.getX())
            && Double.isFinite(vector.getY())
            && Double.isFinite(vector.getZ());
    }

    private static boolean isFinite(@NotNull Location location) {
        return location.getWorld() != null
            && Double.isFinite(location.getX())
            && Double.isFinite(location.getY())
            && Double.isFinite(location.getZ());
    }

    private static boolean isWater(@NotNull Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN;
    }

    private static void removeDisplay(@Nullable BlockDisplay display) {
        if (display != null && display.isValid()) {
            display.remove();
        }
    }

    private static void removeDisplays(@Nullable List<BlockDisplay> displays) {
        if (displays == null) {
            return;
        }
        for (BlockDisplay display : displays) {
            removeDisplay(display);
        }
    }

    private record CurrentFishingRod(
        @NotNull ItemReference reference,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
    }

    private record BlockImpact(@NotNull Location location, boolean waterImpact) {
    }

    enum CastPhase {
        OUTBOUND,
        HOLDING,
        SINKING,
        RETRACTING
    }

    static final class ActiveCast {
        private final String equipmentInstanceId;
        Location rodTip;
        Location currentHook;
        Location target;
        final double maxLineLength;
        Vector velocity;
        boolean waterImpact;
        private final BlockDisplay hookDisplay;
        private final List<BlockDisplay> ropeDisplays;
        CastPhase phase = CastPhase.OUTBOUND;
        int sinkTicks;
        private int renderTicks;
        private @Nullable BukkitTask task;

        ActiveCast(
            @NotNull String equipmentInstanceId,
            @NotNull Location rodTip,
            @NotNull Location currentHook,
            @NotNull Location target,
            boolean waterImpact,
            @NotNull BlockDisplay hookDisplay,
            @NotNull List<BlockDisplay> ropeDisplays
        ) {
            this(
                equipmentInstanceId,
                rodTip,
                currentHook,
                target,
                currentHook.toVector().distance(target.toVector()),
                waterImpact,
                hookDisplay,
                ropeDisplays
            );
        }

        ActiveCast(
            @NotNull String equipmentInstanceId,
            @NotNull Location rodTip,
            @NotNull Location currentHook,
            @NotNull Location target,
            double maxLineLength,
            boolean waterImpact,
            @NotNull BlockDisplay hookDisplay,
            @NotNull List<BlockDisplay> ropeDisplays
        ) {
            this.equipmentInstanceId = equipmentInstanceId;
            this.rodTip = rodTip;
            this.currentHook = currentHook;
            this.target = target;
            this.maxLineLength = maxLineLength;
            this.velocity = velocityTowards(currentHook, target);
            this.waterImpact = waterImpact;
            this.hookDisplay = hookDisplay;
            this.ropeDisplays = ropeDisplays;
        }
    }
}
