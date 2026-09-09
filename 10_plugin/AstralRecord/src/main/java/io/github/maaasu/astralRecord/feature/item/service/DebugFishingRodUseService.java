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
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
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
 * バニラの {@code FishHook} は生成せず、針を {@link BlockDisplay}、糸を小さい黒色dustとして表示します。
 * 糸は竿先と針を結ぶ浅い曲線として毎tick描画し、針へ物理的な力を加えません。
 * 固体へ接触した針は回収まで固定し、水中ではキャスト距離の範囲内で沈下します。
 */
public final class DebugFishingRodUseService {
    static final double ROD_TIP_RIGHT_OFFSET = 0.35D;
    static final double ROD_TIP_UP_OFFSET = 0.20D;
    static final double ROD_TIP_FORWARD_OFFSET = 0.35D;
    static final double HOOK_SPEED_PER_TICK = 1.5D;
    static final double HOOK_GRAVITY_PER_TICK = 0.05D;
    static final double WATER_SINK_SPEED_PER_TICK = 0.08D;
    static final int ROPE_SEGMENT_COUNT = 20;
    static final int ROPE_NODE_COUNT = ROPE_SEGMENT_COUNT + 1;
    static final int LINE_PARTICLE_INTERVAL_TICKS = 1;
    static final int MAX_LINE_POINTS_PER_SEGMENT = 4;
    private static final double LINE_PARTICLE_SPACING = 0.15D;
    private static final double MAX_LINE_SAG = 0.35D;
    static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-8D;
    static final Sound CAST_SOUND = Sound.ENTITY_FISHING_BOBBER_THROW;
    static final double RETRIEVE_SPEED_PER_TICK = 2.0D;

    private static final float DISPLAY_VIEW_RANGE = Float.MAX_VALUE;
    private static final float FISHING_ROD_SOUND_VOLUME = 0.8F;
    private static final float FISHING_ROD_SOUND_PITCH = 1.0F;
    private static final double COLLISION_ADVANCE_EPSILON = 0.001D;
    private static final int MAX_PASSABLE_BLOCK_SKIPS = 32;
    private static final double MAX_RAY_TRACE_DISTANCE = 2.0D;
    private static final int PARTICLE_INTERVAL_TICKS = 2;
    private static final String HOOK_DISPLAY_TAG = "astralrecord_fishing_rod_hook";

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

    /** 現在の主手の釣り竿がキャスト中か判定します。 */
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
     * あることだけを実行条件とします。針の移動はその距離内に制限し、右クリックで回収を開始します。
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
        if (hookDisplay == null) {
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
            hookDisplay
        );
        activeCasts.put(playerId, active);
        active.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tick(playerId),
            1L,
            1L
        );
        render(bukkitPlayer, active, rodTip);
        bukkitPlayer.playSound(
            bukkitPlayer.getLocation(),
            CAST_SOUND,
            SoundCategory.PLAYERS,
            FISHING_ROD_SOUND_VOLUME,
            FISHING_ROD_SOUND_PITCH
        );
    }

    /** 同じ釣り竿の針を竿先へ直接回収します。回収中の再入力は開始音を重複させません。 */
    public void retract(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        if (active == null || !isCurrentFishingRod(player, active.equipmentInstanceId)
            || active.phase == CastPhase.RETRACTING) {
            return;
        }
        active.phase = CastPhase.RETRACTING;
        active.velocity.zero();
        player.getBukkit().playSound(player.getBukkit().getLocation(),
            Sound.ENTITY_FISHING_BOBBER_RETRIEVE, SoundCategory.PLAYERS,
            FISHING_ROD_SOUND_VOLUME, FISHING_ROD_SOUND_PITCH);
    }

    /**
     * アクティブな釣り針の終点を差し替えます。将来、移動する釣り針や追尾先を実装するときに利用します。
     *
     * @param playerId キャスト中プレイヤー
     * @param target 新しい終点
     * @return 終点を更新できた場合はtrue。固体に固定済みの針はfalse
     */
    public boolean moveHook(@NotNull UUID playerId, @NotNull Location target) {
        ActiveCast active = activeCasts.get(playerId);
        if (active == null || active.phase == CastPhase.GROUNDED || !isFinite(target) || target.getWorld() == null
            || !target.getWorld().equals(active.currentHook.getWorld())) {
            return false;
        }
        active.target = target.clone();
        active.velocity = velocityTowards(active.currentHook, active.target);
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

        if (active.retrieved || active.currentHook.distance(rodTip) > active.maxLineLength + 1.0E-8D) {
            cancel(playerId);
            return;
        }
        active.retrieved = advanceHook(active);
        advanceRope(active);
        render(player, active, rodTip);
    }

    /**
     * 針を1tick進めます。固体への接触後は固定し、着水後は最大距離まで真下へ沈めます。
     *
     * @param active 更新対象のキャスト状態
     * @return 回収で竿先へ到達した場合はtrue
     */
    static boolean advanceHook(@NotNull ActiveCast active) {
        if (active.phase == CastPhase.RETRACTING) {
            return advanceRetrieval(active);
        }
        if (active.phase == CastPhase.GROUNDED) {
            return false;
        }
        if (active.phase == CastPhase.SINKING) {
            moveHookWithCollision(active, new Vector(0.0D, -WATER_SINK_SPEED_PER_TICK, 0.0D));
            return false;
        }
        moveHookWithCollision(active, active.velocity.clone());
        if (active.phase == CastPhase.OUTBOUND) {
            active.velocity.setY(active.velocity.getY() - HOOK_GRAVITY_PER_TICK);
        }
        return false;
    }

    /** 回収操作では固体への固定を解除し、表示上の針を竿先へ直接戻して終了します。 */
    private static boolean advanceRetrieval(@NotNull ActiveCast active) {
        Vector delta = active.rodTip.toVector().subtract(active.currentHook.toVector());
        double distance = delta.length();
        if (distance <= RETRIEVE_SPEED_PER_TICK) {
            active.currentHook = active.rodTip.clone();
            return true;
        }
        active.currentHook.add(delta.multiply(RETRIEVE_SPEED_PER_TICK / distance));
        return false;
    }

    /**
     * 両端だけから浅い放物線を再計算します。糸の慣性・衝突・張力で針を動かしません。
     * たるみの往復分を残りのキャスト距離内に抑え、描画線の総長も最大距離を超えません。
     *
     * @param active 同じworldの竿先・針を保持するキャスト状態
     */
    static void advanceRope(@NotNull ActiveCast active) {
        Vector delta = active.currentHook.toVector().subtract(active.rodTip.toVector());
        double distance = delta.length();
        double horizontal = Math.hypot(delta.getX(), delta.getZ());
        double sag = Math.min(MAX_LINE_SAG, Math.min(horizontal * 0.05D,
            Math.max(0.0D, active.maxLineLength - distance) * 0.5D));
        for (int index = 0; index < ROPE_NODE_COUNT; index++) {
            double progress = (double) index / ROPE_SEGMENT_COUNT;
            active.ropeNodes.set(index, active.rodTip.clone().add(delta.clone().multiply(progress))
                .add(0.0D, -4.0D * sag * progress * (1.0D - progress), 0.0D));
        }
        active.ropeNodes.set(0, active.rodTip.clone());
        active.ropeNodes.set(ROPE_SEGMENT_COUNT, active.currentHook.clone());
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
        if (!Double.isFinite(distance) || distance <= 0.0D) {
            return new Vector();
        }
        double flightTicks = Math.max(1.0D, Math.ceil(distance / HOOK_SPEED_PER_TICK));
        Vector velocity = delta.multiply(1.0D / flightTicks);
        // 移動後に重力を加える離散積分で、flightTicks後に目標点へ届く初速とする。
        velocity.setY(velocity.getY() + HOOK_GRAVITY_PER_TICK * (flightTicks - 1.0D) * 0.5D);
        return velocity;
    }

    /**
     * 竿先中心の最大距離球を出る移動を、その移動線分と球面の交点で止めます。
     * 水中沈下時も水平方向へ投影し直さず、同じX/Zを保ちます。
     *
     * @param active キャスト距離と現在の竿先
     * @param start 最大距離以内の移動開始位置
     * @param candidate 移動候補
     * @return 候補、または最大距離に達する直前の位置
     */
    private static @NotNull Location constrainHookMovement(
        @NotNull ActiveCast active, @NotNull Location start, @NotNull Location candidate
    ) {
        if (candidate.distance(active.rodTip) <= active.maxLineLength) {
            return candidate;
        }
        // 内点と外点の線分を二分し、丸め誤差でも範囲外へ出ない側を採用する。
        Vector movement = candidate.toVector().subtract(start.toVector());
        double inside = 0.0D;
        double outside = 1.0D;
        for (int step = 0; step < 48; step++) {
            double middle = (inside + outside) * 0.5D;
            Location point = start.clone().add(movement.clone().multiply(middle));
            if (point.distance(active.rodTip) <= active.maxLineLength) {
                inside = middle;
            } else {
                outside = middle;
            }
        }
        return start.clone().add(movement.multiply(inside));
    }

    /**
     * 最大距離以内の移動を掃引し、最初の固体接触で固定、着水で沈下へ移行します。
     * 着水演出は描画時に一度だけ消費します。
     *
     * @param active 更新するキャスト
     * @param movement 空中移動または真下への沈下量
     */
    private static void moveHookWithCollision(@NotNull ActiveCast active, @NotNull Vector movement) {
        Location start = active.currentHook;
        Location candidate = start.clone().add(movement);
        Location constrained = active.phase == CastPhase.SINKING
            ? constrainHookMovement(active, start, candidate) : constrainAirbornePosition(active, candidate);
        Vector actualMovement = constrained.toVector().subtract(start.toVector());
        BlockImpact impact = rayTraceImpact(start, actualMovement, !active.inWater);
        active.currentHook = impact == null ? constrained
            : constrainHookMovement(active, start, impact.location());
        if (impact != null && impact.solidImpact()) {
            active.velocity.zero();
            active.phase = CastPhase.GROUNDED;
            return;
        }
        boolean water = impact != null && impact.waterImpact()
            || isWater(active.currentHook.clone().add(0, -COLLISION_ADVANCE_EPSILON, 0).getBlock().getType());
        if (water) {
            if (!active.inWater) {
                active.pendingWaterImpact = active.currentHook.clone();
            }
            active.inWater = true;
            active.velocity.zero();
            active.phase = CastPhase.SINKING;
        } else if (active.inWater) {
            active.inWater = false;
            active.velocity.zero();
            active.phase = CastPhase.OUTBOUND;
        } else if (impact == null && constrained.distanceSquared(candidate) > 1.0E-16D) {
            Vector radial = active.currentHook.toVector().subtract(active.rodTip.toVector());
            if (radial.lengthSquared() > MIN_VECTOR_LENGTH_SQUARED) {
                radial.normalize();
                double outward = active.velocity.dot(radial);
                if (outward > 0.0D) {
                    active.velocity.subtract(radial.multiply(outward));
                }
            }
        }
    }

    /**
     * 空中の候補を最大距離球内へ戻し、上限でも接線方向への落下を可能にします。
     * 水中沈下はこの投影を使わず、移動線分上で止めます。
     *
     * @param active 竿先と最大距離
     * @param candidate 空中の次位置
     * @return 最大距離以内の位置
     */
    private static @NotNull Location constrainAirbornePosition(
        @NotNull ActiveCast active, @NotNull Location candidate
    ) {
        Vector radial = candidate.toVector().subtract(active.rodTip.toVector());
        double distance = radial.length();
        if (distance <= active.maxLineLength) {
            return candidate;
        }
        return active.rodTip.clone().add(radial.multiply(Math.nextDown(active.maxLineLength) / distance));
    }

    /**
     * 糸ノード列の総延長を返します。各隣接区間を合計するため、直線距離だけでは見落とすたるみも含みます。
     *
     * @param nodes 糸の順序付きノード列
     * @return 有限な総延長。無効な区間がある場合は正の無限大
     */
    static double polylineLength(@NotNull List<Location> nodes) {
        double length = 0.0D;
        for (int index = 0; index + 1 < nodes.size(); index++) {
            double segmentLength = nodes.get(index).distance(nodes.get(index + 1));
            if (!Double.isFinite(segmentLength)) {
                return Double.POSITIVE_INFINITY;
            }
            length += segmentLength;
        }
        return length;
    }

    /** 針・糸表示と、一定間隔の軌跡粒子および保留中の着水演出を描画します。 */
    private void render(@NotNull Player player, @NotNull ActiveCast active, @NotNull Location rodTip) {
        if (!active.hookDisplay.isValid()) {
            cancel(player.getUniqueId());
            return;
        }
        active.hookDisplay.teleport(active.currentHook);
        if (active.renderTicks % LINE_PARTICLE_INTERVAL_TICKS == 0) {
            renderRope(active);
        }
        emitWaterImpact(active);
        if (active.renderTicks++ % PARTICLE_INTERVAL_TICKS == 0) {
            particleDisplayService.spawnForNearbyViewers(rodTip, SharedParticleDefinitions.FISHING_ROD_TRAIL);
            particleDisplayService.spawnForViewer(player, active.currentHook, SharedParticleDefinitions.FISHING_ROD_HOOK);
        }
    }

    /**
     * 記録済みの着水演出を一度だけ表示します。
     *
     * @param active 着水位置を保持するキャスト状態
     * @return 演出を消費して表示した場合はtrue
     */
    boolean emitWaterImpact(@NotNull ActiveCast active) {
        Location impact = active.pendingWaterImpact;
        active.pendingWaterImpact = null;
        if (impact == null || impact.getWorld() == null) {
            return false;
        }
        impact.getWorld().playSound(
            impact,
            Sound.ENTITY_FISHING_BOBBER_SPLASH,
            SoundCategory.PLAYERS,
            FISHING_ROD_SOUND_VOLUME,
            FISHING_ROD_SOUND_PITCH
        );
        particleDisplayService.spawnForNearbyViewers(impact, SharedParticleDefinitions.FISHING_ROD_SPLASH);
        return true;
    }

    /**
     * 表示ノード間に小さい黒色dustを配置します。各区間は最大4点とし、viewer探索をまとめます。
     * 長さゼロの区間やviewerのいないworldでは粒子を生成しません。
     *
     * @param active 表示位置となる曲線ノードを保持するキャスト状態
     */
    void renderRope(@NotNull ActiveCast active) {
        World world = active.rodTip.getWorld();
        if (world == null || world.getPlayers().isEmpty()) {
            return;
        }
        List<Location> points = new ArrayList<>(ROPE_SEGMENT_COUNT * MAX_LINE_POINTS_PER_SEGMENT);
        for (int index = 0; index + 1 < active.ropeNodes.size(); index++) {
            Location start = active.ropeNodes.get(index);
            Location end = active.ropeNodes.get(index + 1);
            Vector delta = end.toVector().subtract(start.toVector());
            double length = delta.length();
            if (!Double.isFinite(length) || length <= 0.0D) {
                continue;
            }
            int count = (int) Math.min(MAX_LINE_POINTS_PER_SEGMENT, Math.ceil(length / LINE_PARTICLE_SPACING));
            for (int point = 0; point < count; point++) {
                points.add(start.clone().add(delta.clone().multiply((double) point / count)));
            }
        }
        particleDisplayService.spawnForNearbyViewers(active.rodTip, points, SharedParticleDefinitions.FISHING_ROD_LINE);
    }

    /**
     * 移動全区間を最長2 blockずつ調べます。探索予算を使い切った場合は未確認区間の手前で停止します。
     * 固体面からは接触法線方向へ退避し、固定する針が固体へ埋まらないようにします。
     *
     * @param start 移動開始位置
     * @param movement 移動ベクトル
     * @param includeWater 水面への最初の進入も停止対象にする場合はtrue
     * @return 衝突位置、または全区間が通過可能ならnull
     */
    private static @Nullable BlockImpact rayTraceImpact(
        @NotNull Location start,
        @NotNull Vector movement,
        boolean includeWater
    ) {
        World world = start.getWorld();
        double remaining = movement.length();
        if (world == null || !Double.isFinite(remaining) || remaining <= 1.0E-8D) {
            return null;
        }
        Vector direction = movement.clone().multiply(1.0D / remaining);
        Location cursor = start.clone();
        for (int step = 0; step <= MAX_PASSABLE_BLOCK_SKIPS; step++) {
            double span = Math.min(remaining, MAX_RAY_TRACE_DISTANCE);
            RayTraceResult hit = world.rayTraceBlocks(cursor, direction, span,
                includeWater ? FluidCollisionMode.ALWAYS : FluidCollisionMode.NEVER, false);
            if (hit == null || hit.getHitBlock() == null) {
                cursor.add(direction.clone().multiply(span));
                remaining -= span;
                if (remaining <= 1.0E-8D) {
                    return null;
                }
                continue;
            }
            Block block = hit.getHitBlock();
            Location hitLocation = hit.getHitPosition().toLocation(world);
            boolean water = isWater(block.getType());
            if ((includeWater && water) || !block.isPassable()) {
                Vector normal = null;
                if (!water) {
                    normal = hit.getHitBlockFace() == null
                        ? direction.clone().multiply(-1.0D) : hit.getHitBlockFace().getDirection();
                    hitLocation.add(normal.clone().multiply(COLLISION_ADVANCE_EPSILON));
                }
                return new BlockImpact(hitLocation, water, !water);
            }
            double advance = cursor.distance(hitLocation) + COLLISION_ADVANCE_EPSILON;
            if (advance >= remaining) {
                return null;
            }
            cursor.add(direction.clone().multiply(advance));
            remaining -= advance;
        }
        return new BlockImpact(cursor, false, false);
    }

    private static @NotNull List<Location> createRopeNodes(
        @NotNull Location rodTip,
        @NotNull Location currentHook
    ) {
        Vector delta = currentHook.toVector().subtract(rodTip.toVector());
        List<Location> nodes = new ArrayList<>(ROPE_NODE_COUNT);
        for (int index = 0; index < ROPE_NODE_COUNT; index++) {
            double progress = (double) index / ROPE_SEGMENT_COUNT;
            nodes.add(rodTip.clone().add(delta.clone().multiply(progress)));
        }
        return nodes;
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

    /**
     * 針の表示Entityを、手動の物理・描画更新に適した設定へ初期化します。
     *
     * @param display 初期化対象の表示Entity
     * @param tag 表示Entityへ付与する識別タグ
     */
    private static void configureDisplay(@NotNull BlockDisplay display, @NotNull String tag) {
        // 針の重力・衝突はActiveCastで計算し、表示Entityは計算結果を描画するだけにする。
        display.setGravity(false);
        display.setInvulnerable(true);
        display.setPersistent(false);
        display.setSilent(true);
        display.setViewRange(DISPLAY_VIEW_RANGE);
        // 位置と形状を同じ1tickで補間し、サーバー更新間の瞬間移動を抑える。
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(1);
        display.setTeleportDuration(1);
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

    private record CurrentFishingRod(
        @NotNull ItemReference reference,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
    }

    private record BlockImpact(@NotNull Location location, boolean waterImpact, boolean solidImpact) {
    }

    enum CastPhase {
        OUTBOUND,
        GROUNDED,
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
        boolean inWater;
        @Nullable Location pendingWaterImpact;
        private final BlockDisplay hookDisplay;
        final List<Location> ropeNodes;
        CastPhase phase = CastPhase.OUTBOUND;
        private int renderTicks;
        private boolean retrieved;
        private @Nullable BukkitTask task;

        ActiveCast(
            @NotNull String equipmentInstanceId,
            @NotNull Location rodTip,
            @NotNull Location currentHook,
            @NotNull Location target,
            boolean waterImpact,
            @NotNull BlockDisplay hookDisplay
        ) {
            this(
                equipmentInstanceId,
                rodTip,
                currentHook,
                target,
                currentHook.toVector().distance(target.toVector()),
                waterImpact,
                hookDisplay
            );
        }

        ActiveCast(
            @NotNull String equipmentInstanceId,
            @NotNull Location rodTip,
            @NotNull Location currentHook,
            @NotNull Location target,
            double maxLineLength,
            boolean waterImpact,
            @NotNull BlockDisplay hookDisplay
        ) {
            this.equipmentInstanceId = equipmentInstanceId;
            this.rodTip = rodTip;
            this.currentHook = currentHook;
            this.target = target;
            this.maxLineLength = maxLineLength;
            this.velocity = velocityTowards(currentHook, target);
            this.inWater = waterImpact;
            if (waterImpact) {
                this.phase = CastPhase.SINKING;
                this.pendingWaterImpact = currentHook.clone();
            }
            this.hookDisplay = hookDisplay;
            this.ropeNodes = createRopeNodes(rodTip, currentHook);
        }
    }
}
