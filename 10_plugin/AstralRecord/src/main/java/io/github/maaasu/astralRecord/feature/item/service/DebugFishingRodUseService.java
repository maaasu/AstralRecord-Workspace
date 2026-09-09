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
 * バニラの {@code FishHook} は生成せず、針と糸を {@link BlockDisplay} として表示します。
 * 糸は表示Entityへ直接重力を任せず、active state内の物理ノードを毎tick更新します。終点は
 * active state内で可変に保持するため、将来の移動する釣り針にも同じ更新処理を利用できます。
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
    static final float LINE_THICKNESS = 0.015F;
    static final double ROPE_GRAVITY_PER_TICK = HOOK_GRAVITY_PER_TICK * 0.4D;
    static final double ROPE_WATER_SINK_SPEED_PER_TICK = WATER_SINK_SPEED_PER_TICK * 0.5D;
    static final double ROPE_VELOCITY_DAMPING = 0.96D;
    static final int ROPE_CONSTRAINT_ITERATIONS = 2;
    static final double ROPE_CONSTRAINT_VELOCITY_TRANSFER = 0.25D;
    static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-8D;
    static final Sound CAST_SOUND = Sound.ENTITY_FISHING_BOBBER_THROW;

    private static final float DISPLAY_VIEW_RANGE = Float.MAX_VALUE;
    private static final float FISHING_ROD_SOUND_VOLUME = 0.8F;
    private static final float FISHING_ROD_SOUND_PITCH = 1.0F;
    private static final double MIN_SEGMENT_LENGTH = 0.01D;
    private static final double COLLISION_ADVANCE_EPSILON = 0.001D;
    private static final int MAX_PASSABLE_BLOCK_SKIPS = 32;
    private static final double MAX_RAY_TRACE_DISTANCE = 2.0D;
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
     * あることだけを実行条件とします。キャスト中は針と糸を物理更新し、右クリックでの巻き取りは行いません。
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
        bukkitPlayer.playSound(
            bukkitPlayer.getLocation(),
            CAST_SOUND,
            SoundCategory.PLAYERS,
            FISHING_ROD_SOUND_VOLUME,
            FISHING_ROD_SOUND_PITCH
        );
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

    /**
     * 表示blockのローカルX軸を始点から終点へ一致させ、両端が実際に接続する変換を計算します。
     * 回転を左回転、長さをscale Xに置くため、断面中心のoffsetも同じ回転でワールド座標へ変換します。
     */
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
        Vector3f centerOffset = rotation.transform(new Vector3f(
            0.0F,
            -LINE_THICKNESS * 0.5F,
            -LINE_THICKNESS * 0.5F
        ));
        return new Transformation(
            centerOffset,
            rotation,
            new Vector3f((float) length, LINE_THICKNESS, LINE_THICKNESS),
            new Quaternionf()
        );
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

        advanceHook(active);
        advanceRope(active);
        render(player, active, rodTip);
    }

    /**
     * 釣り針を1 tick分進めます。発射中は糸を繰り出し、水中では沈下を継続します。
     *
     * @param active 更新対象のキャスト状態
     * @return 現在は常にfalse。巻き取りを実装しないため到達完了はありません
     */
    static boolean advanceHook(@NotNull ActiveCast active) {
        enforceHookLineLimit(active);
        if (active.phase == CastPhase.SINKING) {
            moveHookWithCollision(active, new Vector(0.0D, -WATER_SINK_SPEED_PER_TICK, 0.0D));
            return false;
        }

        if (active.phase == CastPhase.OUTBOUND) {
            return advanceOutbound(active);
        }

        active.velocity.setY(active.velocity.getY() - HOOK_GRAVITY_PER_TICK);
        moveHookWithCollision(active, active.velocity.clone());
        active.velocity.multiply(ROPE_VELOCITY_DAMPING);
        return false;
    }

    /**
     * 糸の物理ノードを1 tick分進めます。針と竿先を両端として、各ノードへ針より弱い重力を適用し、
     * 隣接ノードの最大距離拘束を反復して糸の張力を表現します。糸の各可動ノードも針と同じblock・水の
     * ray traceを使い、着水後は水中へゆっくり沈めます。
     *
     * @param active 更新対象のキャスト状態
     */
    static void advanceRope(@NotNull ActiveCast active) {
        int lastNodeIndex = active.ropeNodes.size() - 1;
        if (lastNodeIndex <= 0) {
            return;
        }

        active.ropeSegmentLength = calculateRopeSegmentLength(active.deployedLineLength);
        if (polylineLength(active.ropeNodes) <= 1.0E-8D) {
            List<Location> initial = createRopeNodes(active.rodTip, active.currentHook);
            for (int index = 0; index <= lastNodeIndex; index++) {
                active.ropeNodes.set(index, initial.get(index));
            }
        }
        active.ropeNodes.set(0, active.rodTip.clone());
        active.ropeNodes.set(lastNodeIndex, active.currentHook.clone());

        for (int index = 1; index < lastNodeIndex; index++) {
            advanceRopeNode(active, index);
        }

        solveRopeConstraints(active);
        resolveRopeSegmentCollisions(active);
        active.ropeNodes.set(0, active.rodTip.clone());
        active.ropeNodes.set(lastNodeIndex, active.currentHook.clone());
    }

    /**
     * 内部糸ノードへ重力または水中沈下を加え、移動経路のblock衝突を解決します。
     * 固体に当たってもノードを永久固定せず、次tickの張力と重力で再び移動できるようにします。
     */
    private static void advanceRopeNode(@NotNull ActiveCast active, int index) {
        Location current = active.ropeNodes.get(index);
        Vector velocity = active.ropeVelocities.get(index);
        if (isWater(current.getBlock().getType())) {
            velocity.setY(-ROPE_WATER_SINK_SPEED_PER_TICK);
        } else {
            velocity.setY(velocity.getY() - ROPE_GRAVITY_PER_TICK);
        }
        Vector movement = velocity.clone();
        BlockImpact impact = rayTraceImpact(current, movement, false);
        if (impact != null) {
            active.ropeNodes.set(index, impact.location());
            velocity.zero();
            return;
        }

        active.ropeNodes.set(index, current.clone().add(movement));
        velocity.multiply(ROPE_VELOCITY_DAMPING);
    }

    /**
     * 竿先だけを固定し、針を含む自由端へ距離拘束を伝播します。
     * 逆向きの平滑化と順向きの長さ制限を交互に行い、接触した点も次の反復で再び動かします。
     *
     * @param active 主スレッドで更新するキャスト状態
     */
    private static void solveRopeConstraints(@NotNull ActiveCast active) {
        int last = active.ropeNodes.size() - 1;
        for (int iteration = 0; iteration < ROPE_CONSTRAINT_ITERATIONS; iteration++) {
            for (int index = last - 1; index >= 1; index--) {
                Vector correction = ropeLengthCorrection(active.ropeNodes.get(index),
                    active.ropeNodes.get(index + 1), active.ropeSegmentLength);
                if (correction != null) {
                    shiftRopeNode(active, index, correction);
                }
            }
            for (int index = 0; index < last; index++) {
                Vector correction = ropeLengthCorrection(active.ropeNodes.get(index),
                    active.ropeNodes.get(index + 1), active.ropeSegmentLength);
                if (correction != null) {
                    shiftRopeNode(active, index + 1, correction.multiply(-1.0D));
                }
            }
            boolean converged = true;
            for (int index = 0; index < last; index++) {
                if (Math.abs(active.ropeNodes.get(index).distance(active.ropeNodes.get(index + 1))
                    - active.ropeSegmentLength) > 1.0E-6D) {
                    converged = false;
                    break;
                }
            }
            if (converged) {
                break;
            }
        }
        active.currentHook = active.ropeNodes.get(last).clone();
    }

    /** 隣接点の間隔を繰り出した区間長へ合わせ、接近時にも余った糸を折れ曲がりとして残します。 */
    private static @Nullable Vector ropeLengthCorrection(
        @NotNull Location left,
        @NotNull Location right,
        double maxLength
    ) {
        Vector delta = right.toVector().subtract(left.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= 1.0E-8D
            || Math.abs(distance - maxLength) <= 1.0E-9D) {
            return null;
        }
        return delta.multiply((distance - maxLength) / distance);
    }

    /**
     * 拘束による移動にも固体の掃引判定を適用し、移動量を慣性へ返します。
     *
     * @param active 対象キャスト
     * @param index 竿先を除く移動ノード（終端は針）
     * @param movement 拘束補正量
     */
    private static void shiftRopeNode(
        @NotNull ActiveCast active,
        int index,
        @NotNull Vector movement
    ) {
        if (movement.lengthSquared() <= 1.0E-16D) {
            return;
        }
        Location previous = active.ropeNodes.get(index);
        boolean hook = index == active.ropeNodes.size() - 1;
        BlockImpact impact = rayTraceImpact(previous, movement, hook && !active.inWater);
        Location next = impact == null ? previous.clone().add(movement) : impact.location();
        Vector actual = next.toVector().subtract(previous.toVector());
        active.ropeNodes.set(index, next);
        if (hook) {
            updateHookPosition(active, next, impact);
            active.velocity.add(actual.multiply(ROPE_CONSTRAINT_VELOCITY_TRANSFER));
        } else {
            active.ropeVelocities.get(index).add(actual.multiply(ROPE_CONSTRAINT_VELOCITY_TRANSFER));
        }
    }

    /**
     * 制約解決後の隣接線分を検査し、ノードだけでは検出できないblock貫通を近傍ノードの移動で解消します。
     */
    private static void resolveRopeSegmentCollisions(@NotNull ActiveCast active) {
        int lastNodeIndex = active.ropeNodes.size() - 1;
        for (int index = 0; index < lastNodeIndex; index++) {
            Location start = active.ropeNodes.get(index);
            Location end = active.ropeNodes.get(index + 1);
            Vector segment = end.toVector().subtract(start.toVector());
            BlockImpact impact = rayTraceImpact(start, segment, false);
            if (impact == null || impact.waterImpact()) {
                continue;
            }
            setRopeNodeAtImpact(active, index + 1, impact.location());
        }
    }

    /** 固体面の直前へ内部ノードを戻し、その時点の慣性だけを除去します。 */
    private static void setRopeNodeAtImpact(
        @NotNull ActiveCast active,
        int index,
        @NotNull Location location
    ) {
        Vector movement = location.toVector().subtract(active.ropeNodes.get(index).toVector());
        shiftRopeNode(active, index, movement);
        active.ropeVelocities.get(index).zero();
    }

    /**
     * 発射中の針を弾道に沿って進め、到達した長さだけ糸を繰り出します。
     *
     * @param active 更新対象のキャスト状態
     * @return 発射中は常にfalse
     */
    private static boolean advanceOutbound(@NotNull ActiveCast active) {
        Vector movement = active.velocity.clone();
        Location candidate = active.currentHook.clone().add(movement);
        extendDeployedLine(active, candidate);
        moveHookWithCollision(active, movement);
        if (active.phase == CastPhase.OUTBOUND) {
            active.velocity.setY(active.velocity.getY() - HOOK_GRAVITY_PER_TICK);
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
     * キャスト中に針が到達した距離まで糸を繰り出します。繰り出し済みの長さは巻き取りを実装しないため減少しません。
     *
     * @param active キャスト状態
     * @param candidate 針の次の候補位置
     */
    private static void extendDeployedLine(
        @NotNull ActiveCast active,
        @NotNull Location candidate
    ) {
        Vector delta = candidate.toVector().subtract(active.rodTip.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance)) {
            return;
        }
        active.deployedLineLength = Math.min(
            active.maxLineLength,
            Math.max(active.deployedLineLength, distance)
        );
    }

    /**
     * 竿先の移動で糸が張ったとき、針を繰り出し済みのpolyline上限へ戻します。
     *
     * @param active キャスト状態
     */
    static void enforceHookLineLimit(@NotNull ActiveCast active) {
        Location constrained = constrainToDeployedLine(active, active.currentHook);
        Vector movement = constrained.toVector().subtract(active.currentHook.toVector());
        if (movement.lengthSquared() > MIN_VECTOR_LENGTH_SQUARED) {
            moveHookWithCollision(active, movement);
        }
    }

    /** 繰り出し済みのpolyline上限を直線距離で超える候補を、竿先中心の上限球面へ戻します。 */
    private static @NotNull Location constrainToDeployedLine(
        @NotNull ActiveCast active,
        @NotNull Location candidate
    ) {
        double permittedLength = Math.min(active.maxLineLength, active.deployedLineLength);
        Vector delta = candidate.toVector().subtract(active.rodTip.toVector());
        double distance = delta.length();
        if (!Double.isFinite(distance) || distance <= permittedLength) {
            return candidate;
        }
        if (permittedLength <= 0.0D) {
            return active.rodTip.clone();
        }
        return active.rodTip.clone().add(delta.multiply(permittedLength / distance));
    }

    /**
     * 針を糸長の範囲内で移動し、着水または固体衝突の位相と速度を更新します。
     * 着水位置は描画時に一度だけ演出するため、{@link ActiveCast#pendingWaterImpact}へ記録します。
     */
    private static void moveHookWithCollision(@NotNull ActiveCast active, @NotNull Vector movement) {
        Location constrained = constrainToDeployedLine(active, active.currentHook.clone().add(movement));
        Vector constrainedMovement = constrained.toVector().subtract(active.currentHook.toVector());
        BlockImpact impact = rayTraceImpact(active.currentHook, constrainedMovement, !active.inWater);
        updateHookPosition(active, impact == null ? constrained : impact.location(), impact);
        Vector radial = active.currentHook.toVector().subtract(active.rodTip.toVector());
        if (radial.lengthSquared() > MIN_VECTOR_LENGTH_SQUARED
            && radial.length() >= active.deployedLineLength - MIN_SEGMENT_LENGTH) {
            radial.normalize();
            double outward = active.velocity.dot(radial);
            if (outward > 0) {
                active.velocity.subtract(radial.multiply(outward));
            }
        }
    }

    /**
     * 通常移動と糸の終端補正に共通の針位置・接水状態を更新します。
     *
     * @param active 対象キャスト
     * @param next 掃引判定済みの次位置
     * @param impact 移動中の衝突。衝突がなければnull
     */
    private static void updateHookPosition(
        @NotNull ActiveCast active, @NotNull Location next, @Nullable BlockImpact impact
    ) {
        active.currentHook = next.clone();
        if (impact != null) {
            active.velocity.zero();
            if (impact.waterImpact()) {
                if (!active.inWater) {
                    active.pendingWaterImpact = impact.location().clone();
                }
                active.inWater = true;
            }
            active.phase = active.inWater ? CastPhase.SINKING : CastPhase.DRIFTING;
        } else if (active.inWater
            && !isWater(next.clone().add(0, -COLLISION_ADVANCE_EPSILON, 0).getBlock().getType())) {
            active.inWater = false;
            active.phase = CastPhase.DRIFTING;
        }
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
        if (!active.hookDisplay.isValid() || active.ropeDisplays.stream().anyMatch(display -> !display.isValid())) {
            cancel(player.getUniqueId());
            return;
        }
        active.hookDisplay.teleport(active.currentHook);
        renderRope(active);
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
     * 隣接ノードの両端を細い直方体で接続します。長さゼロの区間は表示せず、人工的な糸長を追加しません。
     *
     * @param active 表示と物理ノードを保持するキャスト状態
     */
    void renderRope(@NotNull ActiveCast active) {
        for (int index = 0; index < active.ropeDisplays.size(); index++) {
            Location start = active.ropeNodes.get(index);
            Location end = active.ropeNodes.get(index + 1);
            BlockDisplay display = active.ropeDisplays.get(index);
            display.teleport(start);
            Transformation transformation = lineTransformation(start, end);
            display.setTransformation(transformation == null
                ? new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(), new Quaternionf())
                : transformation);
        }
    }

    /**
     * 移動全区間を最長2 blockずつ調べます。探索予算を使い切った場合は未確認区間の手前で停止します。
     * 固体面からは接触法線方向へ退避し、接線方向の次の移動を妨げないようにします。
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
                if (!water) {
                    Vector normal = hit.getHitBlockFace() == null
                        ? direction.clone().multiply(-1.0D) : hit.getHitBlockFace().getDirection();
                    hitLocation.add(normal.multiply(COLLISION_ADVANCE_EPSILON));
                }
                return new BlockImpact(hitLocation, water);
            }
            double advance = cursor.distance(hitLocation) + COLLISION_ADVANCE_EPSILON;
            if (advance >= remaining) {
                return null;
            }
            cursor.add(direction.clone().multiply(advance));
            remaining -= advance;
        }
        return new BlockImpact(cursor, false);
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

    private static @NotNull List<Vector> createRopeVelocities() {
        List<Vector> velocities = new ArrayList<>(ROPE_NODE_COUNT);
        for (int index = 0; index < ROPE_NODE_COUNT; index++) {
            velocities.add(new Vector());
        }
        return velocities;
    }

    /** 繰り出し済みの長さを等分し、短いキャストにも人工的な最小糸長を追加しません。 */
    private static double calculateRopeSegmentLength(double maxLineLength) {
        return Double.isFinite(maxLineLength) && maxLineLength > 0.0D
            ? maxLineLength / ROPE_SEGMENT_COUNT : 0.0D;
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
        // 針と糸の重力・衝突はActiveCastで計算し、表示Entityは計算結果を描画するだけにする。
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
        DRIFTING,
        SINKING
    }

    static final class ActiveCast {
        private final String equipmentInstanceId;
        Location rodTip;
        Location currentHook;
        Location target;
        final double maxLineLength;
        double deployedLineLength;
        Vector velocity;
        boolean inWater;
        @Nullable Location pendingWaterImpact;
        private final BlockDisplay hookDisplay;
        private final List<BlockDisplay> ropeDisplays;
        double ropeSegmentLength;
        final List<Location> ropeNodes;
        final List<Vector> ropeVelocities;
        CastPhase phase = CastPhase.OUTBOUND;
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
            this.deployedLineLength = Math.min(maxLineLength, rodTip.distance(currentHook));
            this.velocity = velocityTowards(currentHook, target);
            this.inWater = waterImpact;
            if (waterImpact) {
                this.phase = CastPhase.SINKING;
                this.pendingWaterImpact = currentHook.clone();
            }
            this.hookDisplay = hookDisplay;
            this.ropeDisplays = ropeDisplays;
            this.ropeSegmentLength = calculateRopeSegmentLength(deployedLineLength);
            this.ropeNodes = createRopeNodes(rodTip, currentHook);
            this.ropeVelocities = createRopeVelocities();
        }
    }
}
