package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.fishing.service.FishingSession;
import io.github.maaasu.astralRecord.feature.fishing.service.FishingVisuals;
import io.github.maaasu.astralRecord.feature.fishing.service.FishingFeedback;
import io.github.maaasu.astralRecord.feature.fishing.message.FishingMsgId;
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
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import org.bukkit.block.Block;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.MainHand;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 装備マスタの釣り竿による仮想キャストを扱います。
 * <p>
 * 仮想の針と糸をリソースパックのモデルで表示します。
 * 糸は竿先と針を結ぶ浅い曲線として描画し、食いつき後は張った直線へ切り替えます。
 * 固体へ接触した針は固定し、食いつき前に糸が張ったときは竿先へ引き寄せます。
 * 水中に沈んだ針から釣りセッションへ移行します。
 */
public final class FishingRodUseService {
    static final double ROD_TIP_RIGHT_OFFSET = 0.35D;
    static final double ROD_TIP_UP_OFFSET = 0.20D;
    static final double ROD_TIP_FORWARD_OFFSET = 0.35D;
    static final double HOOK_SPEED_PER_TICK = 1.5D;
    static final double HOOK_GRAVITY_PER_TICK = 0.05D;
    static final double WATER_SINK_SPEED_PER_TICK = 0.08D;
    static final int ROPE_SEGMENT_COUNT = 20;
    static final int ROPE_NODE_COUNT = ROPE_SEGMENT_COUNT + 1;
    private static final int VISUAL_UPDATE_TICKS = 2;
    private static final double MAX_LINE_SAG = 0.35D;
    static final double MIN_VECTOR_LENGTH_SQUARED = 1.0E-8D;
    static final Sound CAST_SOUND = Sound.ENTITY_FISHING_BOBBER_THROW;
    static final double RETRIEVE_SPEED_PER_TICK = 2.0D;

    private static final float FISHING_ROD_SOUND_VOLUME = 0.8F;
    private static final float FISHING_ROD_SOUND_PITCH = 1.0F;
    private static final double COLLISION_ADVANCE_EPSILON = 0.001D;
    private static final int MAX_PASSABLE_BLOCK_SKIPS = 32;
    private static final double MAX_RAY_TRACE_DISTANCE = 2.0D;
    private static final double MAX_HOOK_PULL_PER_TICK = 0.65D;
    private static final double DEFAULT_ROD_ATTACK = 10.0D;

    private final AstralRecord plugin;
    private final InventoryService inventoryService;
    private final ItemReferenceResolver itemReferenceResolver;
    private final StatusService statusService;
    private final FishingVisuals visuals;
    private final Map<UUID, ActiveCast> activeCasts = new HashMap<>();

    /**
     * 釣り竿の表示・移動サービスを構成します。
     *
     * @param plugin taskを所有するPlugin
     * @param inventoryService 主手のアイテム参照を解決するサービス
     * @param itemService equipment instanceを解決するサービス
     * @param statusService キャスト距離を取得するステータスサービス
     * @param resourcePacks Java版と統合版の判定に使用するサービス
     */
    public FishingRodUseService(
        @NotNull AstralRecord plugin,
        @NotNull InventoryService inventoryService,
        @NotNull ItemService itemService,
        @NotNull StatusService statusService,
        @NotNull ResourcePackService resourcePacks
    ) {
        this.plugin = plugin;
        this.inventoryService = inventoryService;
        this.itemReferenceResolver = new ItemReferenceResolver(itemService);
        this.statusService = statusService;
        this.visuals = new FishingVisuals(plugin, resourcePacks);
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

    /**
     * 竿の持ち替え後も継続する、外した魚との戦闘を含めて釣り中かを返します。
     * @param player 判定対象
     * @return 未終了の釣りセッションが存在する場合true
     */
    public boolean isFishing(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        return active != null && active.fishing != null && !active.fishing.isFinished();
    }

    /**
     * 釣り専用の入力として扱う状態かを返します。
     * @param player 操作プレイヤー
     * @return 魚が外れるまで左右クリックを占有する場合true
     */
    public boolean reservesFishingInput(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        return active != null && active.fishing != null && active.fishing.reservesInput();
    }

    /**
     * プレイヤーが釣り上げた魚を吊り下げている間かを確認します。メインスレッドから呼びます。
     * @param player 操作プレイヤー
     * @return プレイヤーモードで、魚をまだ針から外していない場合true
     */
    public boolean isHoldingLandedFish(@NotNull Player player) {
        ActiveCast active = activeCasts.get(player.getUniqueId());
        if (active == null || active.fishing == null || !active.fishing.isHanging()) {
            return false;
        }
        AstPlayer astPlayer = AstPlayerCache.get(player);
        return astPlayer != null && astPlayer.getAccount().getMode() == AccountMode.PLAYER;
    }

    /**
     * リール操作中の左クリックを巻き取りへ接続します。吊り下げ中は巻き取りを行いません。
     * @param player 所有プレイヤー
     */
    public void fishingLeftClick(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        if (active == null || active.fishing == null) {
            return;
        }
        if (isCurrentFishingRod(player, active.equipmentInstanceId)) {
            active.fishing.leftClick();
        }
    }

    /**
     * 右クリックを待機中の回収、左クリックと共通の巻き取り、魚の取り外しへ接続します。
     * @param player 所有プレイヤー
     */
    public void fishingRightClick(@NotNull AstPlayer player) {
        ActiveCast active = activeCasts.get(player.getBukkit().getUniqueId());
        if (active != null && active.fishing != null
            && (active.fishing.isHanging() || isCurrentFishingRod(player, active.equipmentInstanceId))) {
            if (!active.fishing.isTaut() && !active.fishing.isReleased()) {
                active.fishing.close();
                active.fishing = null;
                active.display.clearWaterEffects();
                retract(player);
                return;
            }
            try {
                active.fishing.rightClick();
            } catch (RuntimeException exception) {
                cancel(player.getBukkit().getUniqueId());
                throw exception;
            }
        }
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
     * あることだけを実行条件とします。投擲はその距離まで行い、食いつき前に糸が張ると針を
     * 竿先へ引き寄せます。右クリックで回収を開始します。
     *
     * @param player 釣り竿を使うプレイヤー
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
        Location rodTip = resolveRodTip(eye, eye.getDirection(), bukkitPlayer.getMainHand());
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

        UUID playerId = bukkitPlayer.getUniqueId();
        ActiveCast active = new ActiveCast(
            current.instance().getEquipmentInstanceId(),
            rodTip.clone(),
            rodTip.clone(),
            target,
            castDistance,
            false,
            visuals.createCast(bukkitPlayer)
        );
        double attack = statusService.getEquipmentStatus(player, current.model(), current.instance(), StatusType.ATTACK).rollValue();
        active.rodAttack = Double.isFinite(attack) && attack > 0.0D ? attack : DEFAULT_ROD_ATTACK;
        double fishingLevel = statusService.getEquipmentStatus(
            player, current.model(), current.instance(), StatusType.FISHING_LEVEL).getMaxValue();
        active.fishingLevel = Double.isFinite(fishingLevel)
            ? (int) Math.max(0.0D, Math.min(Integer.MAX_VALUE, Math.floor(fishingLevel))) : 0;
        activeCasts.put(playerId, active);
        active.task = plugin.getServer().getScheduler().runTaskTimer(
            plugin,
            () -> tickSafely(playerId),
            1L,
            1L
        );
        render(bukkitPlayer, active);
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
        if (active == null || active.fishing != null || active.phase == CastPhase.GROUNDED || !isFinite(target) || target.getWorld() == null
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
            visuals.clearWaterEffects(playerId);
            return;
        }
        if (active.task != null) {
            active.task.cancel();
        }
        try {
            if (active.fishing != null) {
                active.fishing.close();
            }
        } finally {
            active.display.close();
        }
    }

    /** Plugin停止時にすべての仮想キャストを終了します。 */
    public void shutdown() {
        for (UUID playerId : List.copyOf(activeCasts.keySet())) {
            cancel(playerId);
        }
        visuals.shutdown();
    }

    /**
     * 主手側の釣り竿先端を目線から求めます。真上・真下ではyawで左右方向を保ちます。
     *
     * @param eye 目の位置と向き
     * @param viewDirection 目線方向
     * @param mainHand プレイヤーの主手側
     * @return 釣り竿先端の位置。目線方向が無効ならnull
     */
    static @Nullable Location resolveRodTip(
        @NotNull Location eye, @NotNull Vector viewDirection, @NotNull MainHand mainHand
    ) {
        Vector forward = normalizedDirection(viewDirection);
        if (forward == null) {
            return null;
        }
        Vector worldUp = new Vector(0.0D, 1.0D, 0.0D);
        Vector right = forward.clone().crossProduct(worldUp);
        if (right.lengthSquared() <= MIN_VECTOR_LENGTH_SQUARED) {
            double yaw = Math.toRadians(eye.getYaw());
            right = new Vector(-Math.cos(yaw), 0.0D, -Math.sin(yaw));
        } else {
            right.normalize();
        }
        Vector up = right.clone().crossProduct(forward).normalize();
        Vector offset = forward.clone().multiply(ROD_TIP_FORWARD_OFFSET)
            .add(right.multiply(mainHand == MainHand.LEFT ? -ROD_TIP_RIGHT_OFFSET : ROD_TIP_RIGHT_OFFSET))
            .add(up.multiply(ROD_TIP_UP_OFFSET));
        return eye.clone().add(offset);
    }

    /** 例外が発生したセッションも魚・表示・ボスバー・taskを回収し、繰り返し実行を停止します。 */
    private void tickSafely(@NotNull UUID playerId) {
        try {
            tick(playerId);
        } catch (RuntimeException exception) {
            cancel(playerId);
            Logger.log(LogId.E_3002, exception, "fishing_tick:" + playerId);
        }
    }

    /**
     * 所有者・竿・worldを検証して、キャストまたは釣りセッションをメインスレッドで進めます。
     * 食いつき前は糸長を超えても終了せず、距離の超過分だけ針を引き寄せます。
     *
     * @param playerId キャストの所有者
     */
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
            || (!(active.fishing != null && active.fishing.isReleased())
                && !isCurrentFishingRod(astPlayer, active.equipmentInstanceId))
            || !player.getWorld().equals(active.currentHook.getWorld())) {
            cancel(playerId);
            return;
        }

        Location eye = player.getEyeLocation();
        Location rodTip = resolveRodTip(eye, eye.getDirection(), player.getMainHand());
        if (rodTip == null || rodTip.getWorld() == null) {
            cancel(playerId);
            return;
        }
        active.rodTip = rodTip.clone();

        if (active.fishing != null && !active.fishing.isWaiting()) {
            tickFishing(playerId, player, active, false);
            return;
        }
        if (active.retrieved) {
            cancel(playerId);
            return;
        }
        boolean pulling = active.phase != CastPhase.RETRACTING && lineExcess(active) > 0.0D;
        active.retrieved = advanceHook(active);
        boolean submerged = active.phase != CastPhase.RETRACTING && active.inWater && isWaterAt(active.currentHook);
        if (!submerged) {
            active.attemptedFishingInWater = false;
        }
        if (active.fishing != null) {
            if (submerged) {
                tickFishing(playerId, player, active, pulling);
                return;
            }
            active.fishing.close();
            active.fishing = null;
        }
        if (submerged && !pulling && !active.attemptedFishingInWater) {
            active.attemptedFishingInWater = true;
            var definition = plugin.getFishingMasterService().findDefinition(
                active.currentHook.getWorld(), active.fishingLevel, plugin.getWorldService());
            if (definition == null) {
                FishingFeedback.notify(astPlayer, FishingMsgId.NO_FISHING_AREA);
            } else {
                active.fishing = new FishingSession(plugin, visuals, astPlayer,
                    active.currentHook.clone(), active.maxLineLength, active.rodAttack,
                    plugin.getFishingMasterService(), definition);
                if (active.fishing.isFinished()) {
                    active.fishing = null;
                } else {
                    emitWaterImpact(player, active);
                    tickFishing(playerId, player, active, false);
                    return;
                }
            }
        }
        advanceRope(active);
        render(player, active);
    }

    /**
     * 待機中は現在の針位置を魚へ渡し、食いつき後は選択魚の位置と張力を針・糸の表示へ反映します。
     * 食いつき前に魚がいなくなっても、針と糸は手動回収まで維持します。
     *
     * @param playerId キャストの所有者ID
     * @param player キャストの所有者
     * @param active 釣りセッションを保持するキャスト
     * @param pulling このtickで糸の距離超過を解消している場合true
     */
    private void tickFishing(UUID playerId, Player player, ActiveCast active, boolean pulling) {
        boolean waiting = active.fishing.isWaiting();
        active.fishing.tick(active.rodTip, active.currentHook, pulling);
        if (active.fishing.isFinished()) {
            if (!waiting) {
                cancel(playerId);
                return;
            }
            active.fishing = null;
        } else {
            active.currentHook = active.fishing.hookLocation();
            active.taut = active.fishing.isTaut();
            if (active.fishing.isReleased()) {
                active.display.close();
                return;
            }
        }
        advanceRope(active);
        render(player, active);
    }

    /**
     * 針を1tick進めます。距離を超えた場合は竿先へ引き寄せ、固体との衝突を調べます。
     * 糸に余裕がある場合は飛翔または沈下を進め、待機中と固体への接触後は針を留めます。
     *
     * @param active 更新対象のキャスト状態
     * @return 回収で竿先へ到達した場合はtrue
     */
    static boolean advanceHook(@NotNull ActiveCast active) {
        if (active.phase == CastPhase.RETRACTING) {
            return advanceRetrieval(active);
        }
        double excess = lineExcess(active);
        if (excess > 0.0D) {
            Location start = active.currentHook;
            Vector towardRod = active.rodTip.toVector().subtract(start.toVector());
            Location candidate = start.clone().add(towardRod.multiply(
                Math.min(excess, MAX_HOOK_PULL_PER_TICK) / towardRod.length()));
            moveHookWithCollision(active, candidate, true);
            return false;
        }
        if (active.fishing != null || active.phase == CastPhase.GROUNDED) {
            return false;
        }
        Location start = active.currentHook;
        if (active.phase == CastPhase.SINKING) {
            Location candidate = start.clone().add(0.0D, -WATER_SINK_SPEED_PER_TICK, 0.0D);
            moveHookWithCollision(active, constrainHookMovement(active, start, candidate), false);
            return false;
        }
        Location candidate = start.clone().add(active.velocity);
        Location constrained = constrainAirbornePosition(active, candidate);
        moveHookWithCollision(active, constrained, false);
        if (active.phase == CastPhase.OUTBOUND) {
            if (constrained.distanceSquared(candidate) > 1.0E-16D) {
                Vector radial = active.currentHook.toVector().subtract(active.rodTip.toVector());
                if (radial.lengthSquared() > MIN_VECTOR_LENGTH_SQUARED) {
                    radial.normalize();
                    double outward = active.velocity.dot(radial);
                    if (outward > 0.0D) {
                        active.velocity.subtract(radial.multiply(outward));
                    }
                }
            }
            active.velocity.setY(active.velocity.getY() - HOOK_GRAVITY_PER_TICK);
        }
        return false;
    }

    /**
     * @param active 同じworldの竿先と針を持つキャスト
     * @return 丸め誤差許容を超えた場合は最大糸長との差、それ以外は0
     */
    private static double lineExcess(ActiveCast active) {
        double excess = active.currentHook.distance(active.rodTip) - active.maxLineLength;
        return excess > FishingSession.LINE_LENGTH_EPSILON ? excess : 0.0D;
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
     * 両端だけから浅い放物線を再計算します。たるみの往復分は残りのキャスト距離内に抑えます。
     * 引き寄せ途中や障害物で最大糸長を超える間は、現在の両端を直線で結びます。
     *
     * @param active 同じworldの竿先・針を保持するキャスト状態
     */
    static void advanceRope(@NotNull ActiveCast active) {
        Vector delta = active.currentHook.toVector().subtract(active.rodTip.toVector());
        double distance = delta.length();
        double horizontal = Math.hypot(delta.getX(), delta.getZ());
        double sag = active.taut ? 0.0D : Math.min(MAX_LINE_SAG, Math.min(horizontal * 0.05D,
            Math.max(0.0D, active.maxLineLength - distance) * 0.5D));
        active.sag = sag;
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
     * 針の移動を掃引し、固体接触で固定、着水で沈下へ移行します。
     * 糸で引くときは接触面に沿う残りの移動も掃引し、岸や床に沿って動けるようにします。
     *
     * @param active 更新するキャスト
     * @param destination 移動先の候補。投擲・沈下は呼び出し側で距離を制限します
     * @param pulling 糸で引き寄せる場合true
     */
    private static void moveHookWithCollision(
        @NotNull ActiveCast active, @NotNull Location destination, boolean pulling
    ) {
        Location start = active.currentHook;
        Vector movement = destination.toVector().subtract(start.toVector());
        BlockImpact impact = rayTraceImpact(start, movement, !active.inWater);
        active.currentHook = impact == null ? destination : impact.location();
        if (!pulling && impact != null) {
            active.currentHook = constrainHookMovement(active, start, active.currentHook);
        }
        if (pulling && impact != null && impact.normal() != null) {
            Vector normal = impact.normal();
            Vector slide = destination.toVector().subtract(active.currentHook.toVector());
            slide.subtract(normal.clone().multiply(slide.dot(normal)));
            BlockImpact slideImpact = rayTraceImpact(active.currentHook, slide, !active.inWater);
            if (slideImpact == null) {
                active.currentHook = active.currentHook.clone().add(slide);
            } else {
                active.currentHook = slideImpact.location();
                if (slideImpact.waterImpact() || slideImpact.normal() != null) {
                    impact = slideImpact;
                }
            }
        }
        boolean water = (impact != null && impact.waterImpact())
            || isWaterAt(active.currentHook)
            || isWaterAt(active.currentHook.clone().add(0, -COLLISION_ADVANCE_EPSILON, 0));
        if (water) {
            if (!active.inWater) {
                active.pendingWaterImpact = active.currentHook.clone();
            }
        }
        if (impact != null && impact.normal() != null) {
            active.velocity.zero();
            active.phase = CastPhase.GROUNDED;
        } else if (water) {
            active.velocity.zero();
            active.phase = CastPhase.SINKING;
        } else if (active.inWater || (active.phase == CastPhase.GROUNDED
            && start.distanceSquared(active.currentHook) > 0.0D)) {
            active.velocity.zero();
            active.phase = CastPhase.OUTBOUND;
        }
        active.inWater = water;
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

    /** 針と糸のモデルを更新し、保留中の着水演出を再生します。 */
    private void render(@NotNull Player player, @NotNull ActiveCast active) {
        if (active.renderTicks++ % VISUAL_UPDATE_TICKS == 0) {
            active.display.update(active.ropeNodes, active.sag);
        }
        emitWaterImpact(player, active);
    }

    /**
     * 記録済みの着水演出を一度だけ表示します。
     *
     * @param player キャストしたプレイヤー
     * @param active 着水位置を保持するキャスト状態
     */
    private void emitWaterImpact(@NotNull Player player, @NotNull ActiveCast active) {
        Location impact = active.pendingWaterImpact;
        active.pendingWaterImpact = null;
        if (impact == null || impact.getWorld() == null) {
            return;
        }
        impact.getWorld().playSound(
            impact,
            Sound.ENTITY_FISHING_BOBBER_SPLASH,
            SoundCategory.PLAYERS,
            FISHING_ROD_SOUND_VOLUME,
            FISHING_ROD_SOUND_PITCH
        );
        visuals.splash(player, impact);
    }

    /**
     * 移動全区間を最長2 blockずつ調べます。未読込チャンクまたは探索予算の手前で停止します。
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
            Location end = cursor.clone().add(direction.clone().multiply(span));
            if (!areRayChunksLoaded(world, cursor, end)) {
                return new BlockImpact(cursor, false, null);
            }
            RayTraceResult hit = world.rayTraceBlocks(cursor, direction, span,
                includeWater ? FluidCollisionMode.ALWAYS : FluidCollisionMode.NEVER, false);
            if (hit == null || hit.getHitBlock() == null) {
                cursor = end;
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
                if (water) {
                    double inset = Math.min(COLLISION_ADVANCE_EPSILON, Math.max(0.0D, remaining - cursor.distance(hitLocation)));
                    Location inside = hitLocation.clone().add(direction.clone().multiply(inset));
                    if (areRayChunksLoaded(world, hitLocation, inside)) {
                        hitLocation = inside;
                    }
                } else {
                    normal = hit.getHitBlockFace() == null
                        ? direction.clone().multiply(-1.0D) : hit.getHitBlockFace().getDirection();
                    Location outside = hitLocation.clone().add(normal.clone().multiply(COLLISION_ADVANCE_EPSILON));
                    if (!areRayChunksLoaded(world, hitLocation, outside)) {
                        return new BlockImpact(cursor, false, null);
                    }
                    hitLocation = outside;
                }
                return new BlockImpact(hitLocation, water, normal);
            }
            double advance = cursor.distance(hitLocation) + COLLISION_ADVANCE_EPSILON;
            if (advance >= remaining) {
                return null;
            }
            Location passed = cursor.clone().add(direction.clone().multiply(advance));
            if (!areRayChunksLoaded(world, cursor, passed)) {
                return new BlockImpact(cursor, false, null);
            }
            cursor = passed;
            remaining -= advance;
        }
        return new BlockImpact(cursor, false, null);
    }

    /**
     * @param world 掃引するワールド
     * @param start 短い掃引区間の始点
     * @param end 掃引区間の終点
     * @return 線分が通り得るチャンクがすべて読み込み済みの場合true
     */
    private static boolean areRayChunksLoaded(World world, Location start, Location end) {
        for (int x = Math.min(start.getBlockX(), end.getBlockX()) >> 4;
                x <= (Math.max(start.getBlockX(), end.getBlockX()) >> 4); x++) {
            for (int z = Math.min(start.getBlockZ(), end.getBlockZ()) >> 4;
                    z <= (Math.max(start.getBlockZ(), end.getBlockZ()) >> 4); z++) {
                if (!world.isChunkLoaded(x, z)) return false;
            }
        }
        return true;
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

    private @Nullable CurrentFishingRod findCurrentFishingRod(@NotNull AstPlayer player) {
        InventoryEntryModel entry = inventoryService.getHotbarEntryInHand(player, EquipmentSlot.HAND);
        if (entry == null) {
            return null;
        }
        ItemReference reference = inventoryService.getItemReferenceInHand(player, EquipmentSlot.HAND);
        ItemModel model = itemReferenceResolver.resolveItemModel(reference);
        EquipmentInstance instance = itemReferenceResolver.resolveEquipmentInstance(reference);
        if (model == null || instance == null || !isFishingRod(model)
            || (instance.getDurabilityMax() > 0 && instance.getDurabilityValue() <= 0)
            || !EquipmentRequirementService.check(player, model.getEquipment()).allowed()) {
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

    /**
     * 着水と沈下継続の判定に、水草を含む水域を使います。
     * @param material 針が接触する材質
     * @return 水・泡柱・水草ならtrue
     */
    private static boolean isWater(@NotNull Material material) {
        return material == Material.WATER || material == Material.BUBBLE_COLUMN
            || material == Material.SEAGRASS || material == Material.TALL_SEAGRASS
            || material == Material.KELP || material == Material.KELP_PLANT;
    }

    /**
     * @param point 調べる位置
     * @return ワールドの高さ範囲内で、読み込み済みの水域にある場合true
     */
    private static boolean isWaterAt(Location point) {
        World world = point.getWorld();
        return world != null && point.getBlockY() >= world.getMinHeight() && point.getBlockY() < world.getMaxHeight()
            && world.isChunkLoaded(point.getBlockX() >> 4, point.getBlockZ() >> 4)
            && isWater(point.getBlock().getType());
    }

    private record CurrentFishingRod(
        @NotNull ItemReference reference,
        @NotNull ItemModel model,
        @NotNull EquipmentInstance instance
    ) {
    }

    private record BlockImpact(@NotNull Location location, boolean waterImpact, @Nullable Vector normal) {
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
        private final FishingVisuals.CastDisplay display;
        final List<Location> ropeNodes;
        CastPhase phase = CastPhase.OUTBOUND;
        private int renderTicks;
        private boolean retrieved;
        private boolean attemptedFishingInWater;
        private @Nullable BukkitTask task;
        private @Nullable FishingSession fishing;
        private double rodAttack = DEFAULT_ROD_ATTACK;
        private int fishingLevel;
        private boolean taut;
        private double sag;

        ActiveCast(
            @NotNull String equipmentInstanceId,
            @NotNull Location rodTip,
            @NotNull Location currentHook,
            @NotNull Location target,
            double maxLineLength,
            boolean waterImpact,
            @NotNull FishingVisuals.CastDisplay display
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
            this.display = display;
            this.ropeNodes = createRopeNodes(rodTip, currentHook);
        }
    }
}
