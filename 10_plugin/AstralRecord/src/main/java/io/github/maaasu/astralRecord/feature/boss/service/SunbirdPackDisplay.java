package io.github.maaasu.astralRecord.feature.boss.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** サンバードの実体を維持し、閲覧者のエディションに合わせて外観とギミックを描画します。 */
final class SunbirdPackDisplay implements Listener {
    // Geyser 拡張と共有する UUID マーカー。実体の entity ID は維持する。
    private static final long BEDROCK_BODY_MARKER = 0x41535453554e4231L;
    private static final long BEDROCK_EFFECT_MARKER = 0x41535453554e4631L;
    private static final long BEDROCK_MOTION_MARKER = 0x41535453554e4d31L;
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    private static final int STRIKE_TICKS = 8;
    private static final int FX_FRAMES = 4;
    private static final double VIEW_DISTANCE = 64.0D;
    private static final int POSE_TICKS = 5;
    private static final int RIG_STEP_TICKS = 2;
    private static final int HURT_TINT_TICKS = 4;
    private static final int HURT_COOLDOWN_TICKS = 10;
    private static final String BODY_MODEL = "sunbird";
    private static final Color HURT_TINT = Color.fromRGB(0xFF6666);

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final NamespacedKey templateKey;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final Map<Integer, UUID> trackedBosses = new ConcurrentHashMap<>();
    private final Map<Integer, Set<UUID>> maskedViewers = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, Long> nextHurtTick = new HashMap<>();
    private final Map<UUID, MotionState> motionStates = new HashMap<>();
    private final Map<UUID, Long> strikeUntil = new HashMap<>();
    private final Map<BodyPose, BodyItems> bodyItems = new EnumMap<>(BodyPose.class);
    private final Map<SunbirdDisplayRig.Part, BodyItems> rigItems = new EnumMap<>(SunbirdDisplayRig.Part.class);
    private final Map<Style, ItemStack> smoothEffects = new EnumMap<>(Style.class);
    private final Map<Style, List<ItemStack>> effectFrames = new EnumMap<>(Style.class);
    private final Set<Effect> animatedEffects = new HashSet<>();
    private long animationTick;
    private final Map<UUID, List<TimedEffect>> transients = new HashMap<>();
    private final PacketAdapter packets;

    /** ボスの描画に使うパック判定とパケット監視を準備します。 */
    SunbirdPackDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        this.templateKey = new NamespacedKey(plugin, "mob_template_id");
        for (BodyPose pose : BodyPose.values()) {
            bodyItems.put(pose, new BodyItems(createBodyItem(pose, false), createBodyItem(pose, true)));
        }
        for (SunbirdDisplayRig.Part part : SunbirdDisplayRig.Part.values()) {
            rigItems.put(part, new BodyItems(modelItem(part.model()), withHurtTint(modelItem(part.model()))));
        }
        this.packets = new PacketAdapter(plugin, PacketType.Play.Server.BUNDLE,
                PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (trackedBosses.isEmpty() && maskedViewers.isEmpty()) return;
                try {
                    PacketContainer original = event.getPacket();
                    PacketContainer updated = rewrite(event.getPlayer(), original);
                    if (updated != original) event.setPacket(updated);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6513, exception);
                }
            }
        };
    }

    /** 初回のエンティティ追跡から専用外観を選べるようにします。 */
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        protocol.addPacketListener(packets);
    }

    /** 表示とパケット監視を解除します。 */
    void stop() {
        for (UUID bossId : List.copyOf(bodies.keySet())) clear(bossId);
        for (UUID bossId : List.copyOf(transients.keySet())) clear(bossId);
        protocol.removePacketListener(packets);
        HandlerList.unregisterAll(this);
        trackedBosses.clear();
        nextHurtTick.clear();
        motionStates.clear();
        strikeUntil.clear();
        animatedEffects.clear();
    }

    /** パケット送信前に、このボスの追跡対象を記録します。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() == EntityType.PARROT && BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD.equals(
                entity.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING))) {
            trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
            for (MotionState state : motionStates.values()) {
                if (state.source == entity) {
                    state.viewers.remove(event.getPlayer());
                    break;
                }
            }
            Player viewer = event.getPlayer();
            for (Body body : bodies.values()) {
                if (body.source != entity) continue;
                UUID viewerId = viewer.getUniqueId();
                if (body.tracks(viewer, javaPresentation(viewer))) {
                    body.masked.add(viewerId);
                } else {
                    body.masked.remove(viewerId);
                }
                break;
            }
        }
    }

    /** Java モデルの姿勢・移動を更新し、対応パックの閲覧者だけ実体の絵柄を置き換えます。 */
    void body(UUID bossId, Entity entity, long tick, boolean casting) {
        if (entity.getType() != EntityType.PARROT) return;
        trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
        Body body = bodies.get(bossId);
        if (body != null && (body.source != entity || body.world != entity.getWorld())) {
            clearBody(bossId);
            trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
            body = null;
        }
        Location target = entity.getLocation();
        target.setPitch(0.0F);
        Set<Player> tracked = entity.getTrackedBy();
        MotionState state = motionStates.get(bossId);
        if (state == null || state.source != entity) {
            state = new MotionState(entity, target);
            motionStates.put(bossId, state);
        }
        boolean moving = entity.getVelocity().lengthSquared() > 0.01D
            || (state.position.getWorld() == target.getWorld() && state.position.distanceSquared(target) > 0.04D);
        state.position = target;
        Motion motion = tick < strikeUntil.getOrDefault(bossId, 0L) ? Motion.STRIKE
            : casting ? Motion.CAST : moving ? Motion.MOVE : Motion.IDLE;
        boolean invulnerable = entity.isInvulnerable();
        syncBedrockMotion(state, tracked, target, motion, invulnerable);

        Set<Player> legacyViewers = new HashSet<>();
        Set<Player> rigViewers = new HashSet<>();
        for (Player viewer : tracked) {
            switch (javaPresentation(viewer)) {
                case LEGACY -> legacyViewers.add(viewer);
                case SMOOTH -> rigViewers.add(viewer);
                default -> { }
            }
        }
        if (body == null && legacyViewers.isEmpty() && rigViewers.isEmpty()) return;
        if (body == null) {
            body = new Body(entity);
            bodies.put(bossId, body);
            maskedViewers.put(entity.getEntityId(), body.masked);
        }
        body.motion = motion;
        body.resumeMotion = casting ? Motion.CAST : moving ? Motion.MOVE : Motion.IDLE;
        if (body.legacy != null && !body.legacy.isValid()) {
            body.legacy = null;
            body.javaLegacyViewers.clear();
        }
        if (!body.rig.isEmpty() && body.rig.values().stream().anyMatch(part -> !part.isValid())) {
            body.removeRig();
        }
        if (!legacyViewers.isEmpty()) updateLegacy(body, target, motion, tick);
        if (!rigViewers.isEmpty() && body.rig.isEmpty()) createRig(body, target, tick);
        if (body.invulnerable != invulnerable) {
            body.invulnerable = invulnerable;
            body.displays().forEach(display -> display.setGlowColorOverride(invulnerable ? Color.YELLOW : null));
        }
        boolean glowing = invulnerable || entity.isGlowing();
        for (ItemDisplay display : body.displays()) {
            if (display.isGlowing() != glowing) display.setGlowing(glowing);
        }

        syncViewers(body.javaLegacyViewers, legacyViewers, body.legacy == null ? List.of() : List.of(body.legacy));
        syncViewers(body.javaRigViewers, rigViewers, body.rig.values());
        // 部品が全部追跡されるまで実体を残す。V1/V2 の移行や部品の再生成時も空白にしない。
        Set<UUID> trackedIds = new HashSet<>();
        for (Player viewer : tracked) {
            UUID viewerId = viewer.getUniqueId();
            trackedIds.add(viewerId);
            boolean shouldHide = body.tracks(viewer, javaPresentation(viewer));
            if (!shouldHide) {
                if (body.masked.contains(viewerId) && sendFlags(viewer, entity, false)) body.masked.remove(viewerId);
            } else if (body.masked.add(viewerId) && !sendFlags(viewer, entity, true)) {
                body.masked.remove(viewerId);
            }
        }
        body.masked.retainAll(trackedIds);
        if (legacyViewers.isEmpty() && body.legacy != null) {
            body.legacy.remove();
            body.legacy = null;
        }
        if (rigViewers.isEmpty()) body.removeRig();
    }

    private void updateLegacy(Body body, Location target, Motion motion, long tick) {
        BodyPose pose = motion.poseAt(tick);
        BodyItems items = bodyItems.get(pose);
        if (body.legacy == null) {
            body.legacy = spawn(target, items.get(body.restoreTint != null), false);
            body.legacy.setGlowColorOverride(body.invulnerable ? Color.YELLOW : null);
        } else if (body.pose != pose) {
            body.legacy.setItemStack(items.get(body.restoreTint != null));
        }
        body.pose = pose;
        move(body.legacy, target, POSE_TICKS);
        float wave = (float) Math.sin(tick * (motion == Motion.MOVE ? 0.30D : 0.15D));
        float lean = motion == Motion.MOVE ? 0.16F : 0.0F;
        interpolate(body.legacy, POSE_TICKS, new Transformation(
            new Vector3f(0.0F, 0.16F + wave * 0.13F, 0.0F), new Quaternionf().rotateX(lean + wave * 0.06F),
            new Vector3f(1.0F), new Quaternionf()));
    }

    private void createRig(Body body, Location target, long tick) {
        try {
            for (SunbirdDisplayRig.Part part : SunbirdDisplayRig.Part.values()) {
                ItemDisplay display = spawn(target, rigItems.get(part).get(body.restoreTint != null), false);
                body.rig.put(part, display);
                display.setGlowColorOverride(body.invulnerable ? Color.YELLOW : null);
            }
            animateRig(body, tick, 0);
        } catch (RuntimeException exception) {
            body.removeRig();
            Logger.log(LogId.E_6513, exception);
        }
    }

    private void animateRig(Body body, long tick, int duration) {
        if (body.rig.isEmpty() || !body.source.isValid() || body.world != body.source.getWorld()
                || body.rig.values().stream().anyMatch(part -> !part.isValid())) return;
        SunbirdDisplayRig.Frame frame = body.rigMotion.sample(body.motion.id, tick,
            body.motion == Motion.STRIKE ? duration : 0);
        Location position = body.source.getLocation();
        position.setPitch(0.0F);
        for (Map.Entry<SunbirdDisplayRig.Part, ItemDisplay> part : body.rig.entrySet()) {
            move(part.getValue(), position, RIG_STEP_TICKS);
            interpolate(part.getValue(), duration, frame.transform(part.getKey()));
        }
    }

    private void syncViewers(Set<Player> current, Set<Player> eligible,
                             Collection<ItemDisplay> displays) {
        current.removeIf(viewer -> {
            if (eligible.contains(viewer) && !displays.isEmpty()) return false;
            for (ItemDisplay display : displays) viewer.hideEntity(plugin, display);
            return true;
        });
        if (displays.isEmpty()) return;
        for (Player viewer : eligible) {
            if (current.add(viewer)) {
                for (ItemDisplay display : displays) viewer.showEntity(plugin, display);
            }
        }
    }

    private static void move(ItemDisplay display, Location target, int ticks) {
        Location previous = display.getLocation();
        if (previous.equals(target)) return;
        display.setTeleportDuration(previous.distanceSquared(target) > 9.0D ? 0 : ticks);
        display.teleport(target);
    }

    private static void interpolate(ItemDisplay display, int ticks, Transformation transform) {
        // Paper の補間開始を同じ 0 に再設定すると再生時刻が更新される。
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(ticks);
        display.setTransformation(transform);
    }

    private Presentation javaPresentation(Player viewer) {
        UUID id = viewer.getUniqueId();
        if (isBedrock(viewer) || !packs.hasSunbirdPresentation(id)) return Presentation.NONE;
        return packs.hasSunbirdPresentationV2(id) ? Presentation.SMOOTH : Presentation.LEGACY;
    }

    /**
     * サンバードの被弾表示を開始します。短い間隔の追撃は個体単位でまとめます。
     * @param bossId ボスのインスタンス ID
     * @param source ダメージを受けた実体
     * @param tick ギミック処理と共通のサーバー tick
     */
    void hurt(UUID bossId, Entity source, long tick) {
        if (source.getType() != EntityType.PARROT || !source.isValid()
                || tick < nextHurtTick.getOrDefault(bossId, 0L)) return;
        nextHurtTick.put(bossId, tick + HURT_COOLDOWN_TICKS);
        Body body = bodies.get(bossId);
        try {
            if (body != null && body.source == source
                    && body.displays().stream().anyMatch(display -> !display.getTrackedBy().isEmpty())) {
                body.restoreTint = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    body.restoreTint = null;
                    tintBody(body, false);
                }, HURT_TINT_TICKS);
                tintBody(body, true);
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
        try {
            sendBedrockHurt(source);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
    }

    private void tintBody(Body body, boolean hurt) {
        if (body.legacy != null && body.legacy.isValid()) body.legacy.setItemStack(bodyItems.get(body.pose).get(hurt));
        body.rig.forEach((part, display) -> {
            if (display.isValid()) display.setItemStack(rigItems.get(part).get(hurt));
        });
    }

    /** 統合版で実体を追跡している閲覧者へ、同じ被弾パケットを使って通知します。 */
    private void sendBedrockHurt(Entity source) {
        PacketContainer packet = null;
        for (Player viewer : source.getTrackedBy()) {
            if (!isBedrock(viewer)) continue;
            if (packet == null) {
                packet = BossBedrockHurt.create(source);
            }
            send(viewer, packet);
        }
    }

    /** 発動時点から短時間だけ専用の攻撃姿勢を選びます。 */
    void strike(UUID bossId, long tick) {
        strikeUntil.put(bossId, tick + STRIKE_TICKS);
        Body body = bodies.get(bossId);
        if (body == null) return;
        body.rigWasStriking = true;
        body.motion = Motion.STRIKE;
        try {
            // 発動フックで最初の補間を開始し、次の 2 tick を目標にする。
            animateRig(body, tick, RIG_STEP_TICKS);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
    }

    /** 表示中の演出だけを進め、フレームが変わるときにモデルを更新します。 */
    void animate(long tick) {
        animationTick = tick;
        for (Map.Entry<UUID, Body> entry : bodies.entrySet()) {
            Body body = entry.getValue();
            if (tick < strikeUntil.getOrDefault(entry.getKey(), 0L)) {
                body.rigWasStriking = true;
                body.motion = Motion.STRIKE;
            } else if (body.rigWasStriking) {
                body.rigWasStriking = false;
                body.motion = body.resumeMotion;
            }
            if (tick % RIG_STEP_TICKS == 0L) {
                try {
                    animateRig(body, tick, RIG_STEP_TICKS);
                } catch (RuntimeException exception) {
                    // 一個体の表示障害でボス処理全体を止めない。
                    Logger.log(LogId.E_6513, exception);
                }
            }
        }
        Iterator<Effect> iterator = animatedEffects.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            try {
                effect.advance();
            } catch (RuntimeException exception) {
                iterator.remove();
                Logger.log(LogId.E_6513, exception);
            }
        }
    }

    /** 追跡開始時と状態が変化した時だけ、統合版へ現在の姿勢と無敵状態を渡します。 */
    private void syncBedrockMotion(MotionState state, Set<Player> tracked, Location location, Motion motion,
                                   boolean invulnerable) {
        long mode = BossBedrockMotion.state(motion.id, invulnerable, location.getYaw());
        boolean changed = state.mode != mode;
        state.viewers.retainAll(tracked);
        PacketContainer spawn = null;
        PacketContainer remove = null;
        for (Player viewer : tracked) {
            if (!isBedrock(viewer) || !changed && state.viewers.contains(viewer)) continue;
            if (spawn == null) {
                int id = PacketEntityIdAllocator.nextEntityId();
                long data = BossBedrockMotion.payload(state.source.getEntityId(), mode);
                spawn = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
                spawn.getIntegers().write(0, id);
                spawn.getUUIDs().write(0, new UUID(BEDROCK_MOTION_MARKER, data));
                spawn.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
                spawn.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
                remove = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                remove.getIntLists().writeSafely(0, List.of(id));
                remove.getIntegerArrays().writeSafely(0, new int[]{id});
            }
            send(viewer, spawn);
            // 旧拡張で通常の空 TextDisplay と解釈された場合も直後に回収する。
            send(viewer, remove);
            state.viewers.add(viewer);
        }
        state.mode = mode;
    }

    /** 停止したボスの本体・短時間演出を回収します。 */
    void clear(UUID bossId) {
        nextHurtTick.remove(bossId);
        motionStates.remove(bossId);
        strikeUntil.remove(bossId);
        clearBody(bossId);
        List<TimedEffect> effects = transients.remove(bossId);
        if (effects != null) {
            for (TimedEffect effect : effects) effect.effect().remove();
        }
    }

    /** 今回確認できなかったボスと、表示期限を過ぎたエフェクトを取り除きます。 */
    void retain(Set<UUID> activeBosses, long tick) {
        nextHurtTick.keySet().retainAll(activeBosses);
        motionStates.keySet().retainAll(activeBosses);
        strikeUntil.keySet().retainAll(activeBosses);
        for (UUID id : List.copyOf(bodies.keySet())) {
            if (!activeBosses.contains(id)) clearBody(id);
        }
        trackedBosses.entrySet().removeIf(entry -> plugin.getServer().getEntity(entry.getValue()) == null);
        transients.entrySet().removeIf(entry -> {
            entry.getValue().removeIf(effect -> {
                if (activeBosses.contains(entry.getKey()) && effect.endTick() > tick) return false;
                effect.effect().remove();
                return true;
            });
            return entry.getValue().isEmpty();
        });
    }

    /** 指定の円・帯を期限付きで表示します。 */
    void burst(UUID bossId, Shape shape, long endTick) {
        Effect effect = new Effect(shape, true);
        try {
            effect.sync();
            transients.computeIfAbsent(bossId, ignored -> new ArrayList<>()).add(new TimedEffect(effect, endTick));
        } catch (RuntimeException exception) {
            effect.remove();
            throw exception;
        }
    }

    /** 警告を構成するモデル群を生成し、更新・回収は予兆と同じ寿命に従わせます。 */
    List<Effect> warnings(List<Shape> shapes) {
        return shapes.stream().map(shape -> new Effect(shape, false)).toList();
    }

    private void clearBody(UUID bossId) {
        Body body = bodies.remove(bossId);
        if (body == null) return;
        if (body.restoreTint != null) body.restoreTint.cancel();
        int entityId = body.source.getEntityId();
        trackedBosses.remove(entityId);
        maskedViewers.remove(entityId);
        body.displays().forEach(ItemDisplay::remove);
        if (body.source.isValid()) {
            for (Player viewer : body.source.getTrackedBy()) {
                if (body.masked.contains(viewer.getUniqueId())) sendFlags(viewer, body.source, false);
            }
        }
    }

    private PacketContainer rewrite(Player viewer, PacketContainer packet) {
        if (packet.getType() == PacketType.Play.Server.BUNDLE) {
            Iterable<PacketContainer> children = packet.getPacketBundles().readSafely(0);
            if (children == null) return packet;
            List<PacketContainer> changes = new ArrayList<>();
            boolean changed = false;
            for (PacketContainer child : children) {
                PacketContainer rewritten = rewrite(viewer, child);
                changed |= rewritten != child;
                changes.add(rewritten);
                Integer hiddenSource = maskedSpawnId(viewer, child);
                if (hiddenSource != null) {
                    // 既定値のフラグが省略される再追跡にも、同じバンドル内で不可視を適用する。
                    var serializer = WrappedDataWatcher.Registry.get((Type) Byte.class);
                    changes.add(flagsPacket(hiddenSource, new WrappedDataValue(0, serializer, (byte) INVISIBLE)));
                    changed = true;
                }
            }
            if (!changed) return packet;
            PacketContainer result = packet.shallowClone();
            result.getPacketBundles().write(0, changes);
            return result;
        }
        Integer entityId = packet.getIntegers().readSafely(0);
        if (entityId == null) return packet;
        if (packet.getType() == PacketType.Play.Server.SPAWN_ENTITY) {
            // 追跡済みのオウムだけを、entity ID と元の UUID の組で識別する。
            UUID bossUuid = trackedBosses.get(entityId);
            if (bossUuid != null && bossUuid.equals(packet.getUUIDs().readSafely(0)) && isBedrock(viewer)) {
                PacketContainer result = packet.shallowClone();
                result.getUUIDs().write(0, new UUID(BEDROCK_BODY_MARKER, bossUuid.getLeastSignificantBits()));
                return result;
            }
        }
        if (packet.getType() == PacketType.Play.Server.ENTITY_METADATA) {
            Set<UUID> viewers = maskedViewers.get(entityId);
            if (viewers != null && viewers.contains(viewer.getUniqueId())) {
                List<WrappedDataValue> values = packet.getDataValueCollectionModifier().readSafely(0);
                if (values == null) return packet;
                List<WrappedDataValue> changes = new ArrayList<>(values);
                for (int index = 0; index < values.size(); index++) {
                    WrappedDataValue value = values.get(index);
                    if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                        changes.set(index, flagValue(value, flags, true));
                        PacketContainer result = packet.shallowClone();
                        result.getDataValueCollectionModifier().write(0, changes);
                        return result;
                    }
                }
            }
        }
        return packet;
    }

    /** 代替モデルが追跡済みの閲覧者に送る、実体のスポーンだけを識別します。 */
    private Integer maskedSpawnId(Player viewer, PacketContainer packet) {
        if (packet.getType() != PacketType.Play.Server.SPAWN_ENTITY) return null;
        Integer entityId = packet.getIntegers().readSafely(0);
        if (entityId == null) return null;
        Set<UUID> viewers = maskedViewers.get(entityId);
        UUID sourceId = trackedBosses.get(entityId);
        return viewers != null && viewers.contains(viewer.getUniqueId()) && sourceId != null
            && sourceId.equals(packet.getUUIDs().readSafely(0)) ? entityId : null;
    }

    /** 専用モデルの閲覧者に対して実体の不可視と発光のビットを調整します。 */
    private boolean sendFlags(Player viewer, Entity source, boolean hide) {
        try {
            for (WrappedDataValue value : WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()) {
                if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                    protocol.sendServerPacket(viewer, flagsPacket(source.getEntityId(), flagValue(value, flags, hide)),
                        false);
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
        return false;
    }

    /** 実体の共有フラグを更新するメタデータパケットを作ります。 */
    private PacketContainer flagsPacket(int entityId, WrappedDataValue flags) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, entityId);
        packet.getDataValueCollectionModifier().write(0, List.of(flags));
        return packet;
    }

    private static WrappedDataValue flagValue(WrappedDataValue source, byte flags, boolean hide) {
        return new WrappedDataValue(source.getIndex(), source.getSerializer(),
            (byte) (hide ? (flags | INVISIBLE) & ~GLOWING : flags));
    }

    private boolean isBedrock(Player viewer) {
        // Floodgate の識別はログイン時の命名規則と、取得済みの接続情報を共用する。
        var astPlayer = AstPlayerCache.get(viewer);
        return astPlayer == null ? packs.isBedrock(viewer.getName()) : astPlayer.isBedrock();
    }

    /** パックのモデル ID を表示用アイテムに設定します。 */
    private static ItemStack modelItem(String model) {
        ItemStack item = ItemStack.of(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("astralrecord", model));
        item.setItemMeta(meta);
        return item;
    }

    /** 同じモデル ID に姿勢と必要な着色値を設定します。 */
    private static ItemStack createBodyItem(BodyPose pose, boolean hurt) {
        ItemStack item = modelItem(BODY_MODEL);
        if (pose == BodyPose.REST && !hurt) return item;
        var meta = item.getItemMeta();
        var modelData = meta.getCustomModelDataComponent();
        if (pose != BodyPose.REST) modelData.setStrings(List.of(pose.key));
        if (hurt) modelData.setColors(List.of(HURT_TINT));
        meta.setCustomModelDataComponent(modelData);
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack withHurtTint(ItemStack item) {
        var meta = item.getItemMeta();
        var data = meta.getCustomModelDataComponent();
        data.setColors(List.of(HURT_TINT));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }

    /** 演出ごとのフレームを最初の使用時に構築して使い回します。 */
    private static List<ItemStack> createEffectFrames(Style style) {
        List<ItemStack> frames = new ArrayList<>(FX_FRAMES);
        for (int frame = 0; frame < FX_FRAMES; frame++) {
            ItemStack item = modelItem(style.model);
            var meta = item.getItemMeta();
            var data = meta.getCustomModelDataComponent();
            data.setFloats(List.of((float) frame));
            meta.setCustomModelDataComponent(data);
            item.setItemMeta(meta);
            frames.add(item);
        }
        return List.copyOf(frames);
    }

    /** 指定のアイテムを追跡者限定の表示として生成します。 */
    private ItemDisplay spawn(Location location, ItemStack item, boolean fullBright) {
        return location.getWorld().spawn(location, ItemDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setItemStack(item);
            display.setViewRange(2.0F);
            if (fullBright) display.setBrightness(new Display.Brightness(15, 15));
        });
    }

    private void send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet, false);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
    }

    /** 正規化済みの地面モデルへ、ゲーム内の幅・高さ・長さを渡します。 */
    record Shape(Style style, Location location, float width, float height, float length) {
        /** 円の外径をリソースモデルの幅・奥行きに適用します。 */
        static Shape circle(Style style, Location center, double radius, double height) {
            Location location = center.clone().add(0.0D, 0.07D, 0.0D);
            location.setYaw(0.0F);
            location.setPitch(0.0F);
            float diameter = (float) (radius * 2.0D);
            return new Shape(style, location, diameter, (float) height, diameter);
        }

        /** ダメージ判定の始点・水平ベクトルから、帯の中心と姿勢を求めます。 */
        static Shape lane(Style style, Location origin, Vector direction, double length, double halfWidth, double height) {
            Location center = origin.clone().add(direction.clone().multiply(length * 0.5D)).add(0, 0.07D, 0);
            center.setDirection(direction);
            return new Shape(style, center, (float) (2 * halfWidth), (float) height, (float) length);
        }
    }

    /** ID は統合版クライアントの style プロパティと同期します。 */
    enum Style {
        CIRCLE(0, "sunbird_warning_circle"),
        LINE(1, "sunbird_warning_line"),
        BOUNDARY(2, "sunbird_warning_boundary"),
        IMPACT(3, "sunbird_impact"),
        SAFE(4, "sunbird_warning_safe"),
        IMPACT_LINE(5, "sunbird_impact_line");

        private final int bedrock;
        private final String model;

        Style(int bedrock, String model) {
            this.bedrock = bedrock;
            this.model = model;
        }
    }

    /** 1枚の表示モデルの閲覧者と寿命を管理します。 */
    final class Effect {
        private final Shape shape;
        private final boolean burst;
        private final long startedAt = animationTick;
        private int frame;
        private final Map<UUID, Player> bedrockViewers = new HashMap<>();
        private final Set<Player> javaViewers = new HashSet<>();
        private final Set<Player> smoothViewers = new HashSet<>();
        private ItemDisplay model;
        private ItemDisplay smoothModel;
        private PacketContainer bedrockSpawn;
        private PacketContainer bedrockRemove;

        private Effect(Shape shape, boolean burst) {
            this.shape = shape;
            this.burst = burst;
        }

        /** 予兆の途中で追跡を開始した閲覧者にも現在の形を提示します。 */
        void sync() {
            World world = shape.location().getWorld();
            if (world == null || !world.isChunkLoaded(shape.location().getBlockX() >> 4,
                    shape.location().getBlockZ() >> 4)) return;
            Set<Player> viewers = new HashSet<>(world.getNearbyPlayers(shape.location(), VIEW_DISTANCE));
            Set<Player> legacy = new HashSet<>();
            Set<Player> smooth = new HashSet<>();
            bedrockViewers.values().removeIf(viewer -> {
                if (viewers.contains(viewer)) return false;
                if (viewer.isOnline()) send(viewer, bedrockRemove);
                return true;
            });
            for (Player viewer : viewers) {
                switch (javaPresentation(viewer)) {
                    case LEGACY -> legacy.add(viewer);
                    case SMOOTH -> smooth.add(viewer);
                    default -> {
                        if (isBedrock(viewer) && bedrockViewers.putIfAbsent(viewer.getUniqueId(), viewer) == null) {
                            if (bedrockSpawn == null) prepareBedrock();
                            send(viewer, bedrockSpawn);
                        }
                    }
                }
            }
            if (!legacy.isEmpty() && (model == null || !model.isValid())) {
                javaViewers.clear();
                spawnJava();
            }
            if (!smooth.isEmpty() && (smoothModel == null || !smoothModel.isValid())) {
                smoothViewers.clear();
                spawnSmooth();
            }
            syncViewers(javaViewers, legacy, model == null ? List.of() : List.of(model));
            syncViewers(smoothViewers, smooth, smoothModel == null ? List.of() : List.of(smoothModel));
            if (legacy.isEmpty() && model != null) {
                model.remove();
                model = null;
            }
            if (smooth.isEmpty() && smoothModel != null) {
                smoothModel.remove();
                smoothModel = null;
            }
        }

        /** モデルの追跡が始まった Java 閲覧者だけ粒子の重複送信を省きます。 */
        boolean visibleTo(Player viewer) {
            ItemDisplay visible = switch (javaPresentation(viewer)) {
                case LEGACY -> model;
                case SMOOTH -> smoothModel;
                default -> null;
            };
            return visible != null && visible.isValid() && visible.getTrackedBy().contains(viewer);
        }

        /** Java と統合版の表示を両方回収します。 */
        void remove() {
            animatedEffects.remove(this);
            if (model != null) model.remove();
            if (smoothModel != null) smoothModel.remove();
            for (Player viewer : bedrockViewers.values()) {
                if (viewer.isOnline() && bedrockRemove != null) send(viewer, bedrockRemove);
            }
            bedrockViewers.clear();
            javaViewers.clear();
            smoothViewers.clear();
        }

        private void spawnJava() {
            frame = frameAt(animationTick);
            model = spawn(shape.location(), effectFrames.computeIfAbsent(shape.style(),
                SunbirdPackDisplay::createEffectFrames).get(frame), true);
            model.setTransformation(effectTransform(shape, false, 0));
            animatedEffects.add(this);
        }

        /** V2 の予兆境界は固定し、着弾だけをネイティブ補間で描きます。 */
        private void spawnSmooth() {
            smoothModel = spawn(shape.location(), smoothEffects.computeIfAbsent(shape.style(),
                style -> modelItem(style.model + "_v2")), true);
            smoothModel.setTransformation(effectTransform(shape, burst, animationTick - startedAt));
            if (burst) animatedEffects.add(this);
        }

        private void advance() {
            int next = frameAt(animationTick);
            if (model != null && model.isValid() && !javaViewers.isEmpty() && next != frame) {
                model.setItemStack(effectFrames.get(shape.style()).get(next));
                frame = next;
            }
            long age = animationTick - startedAt;
            if (burst && age > 0 && age <= 8 && age % RIG_STEP_TICKS == 0 && smoothModel != null
                    && smoothModel.isValid() && !smoothViewers.isEmpty()) {
                interpolate(smoothModel, RIG_STEP_TICKS, effectTransform(shape, true, age));
            }
        }

        private int frameAt(long tick) {
            long age = Math.max(0L, tick - startedAt);
            return burst ? (int) Math.min(FX_FRAMES - 1L, age / 2L) : (int) (age / 3L % FX_FRAMES);
        }

        private void prepareBedrock() {
            int entityId = PacketEntityIdAllocator.nextEntityId();
            Location location = shape.location();
            long data = (ThreadLocalRandom.current().nextLong() & ~0xfffffffffffL) | shape.style().bedrock
                | (encode(shape.width()) << 8) | (encode(shape.length()) << 20) | (encode(shape.height()) << 32);
            bedrockSpawn = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            bedrockSpawn.getIntegers().write(0, entityId);
            bedrockSpawn.getUUIDs().write(0, new UUID(BEDROCK_EFFECT_MARKER, data));
            bedrockSpawn.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
            bedrockSpawn.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
            bedrockSpawn.getBytes().writeSafely(1, (byte) Math.round(location.getYaw() * 256.0F / 360.0F));
            bedrockRemove = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            bedrockRemove.getIntLists().writeSafely(0, List.of(entityId));
            bedrockRemove.getIntegerArrays().writeSafely(0, new int[]{entityId});
        }

        private static long encode(float dimension) {
            return Math.clamp(Math.round(dimension * 32.0F), 1, 0xfff);
        }
    }

    static Transformation effectTransform(Shape shape, boolean burst, long age) {
        float t = Math.clamp(age / 8.0F, 0.0F, 1.0F);
        float side = 1.0F;
        float height = 1.0F;
        float offset = 0.0F;
        if (burst && shape.style() == Style.IMPACT) {
            // 着弾の内側の絵柄だけを膨らませる。予兆の寸法と命中範囲には触れない。
            side = 0.55F + 1.10F * (1.0F - (1.0F - t) * (1.0F - t));
            height = 0.75F + 0.60F * (float) Math.sin(Math.PI * t) - 0.30F * t;
        } else if (burst && shape.style() == Style.IMPACT_LINE) {
            // エンティティの向きに従うローカル座標。線の長さは変えない。
            offset = -0.69F * shape.length() * t;
        }
        return new Transformation(new Vector3f(0, 0, offset), new Quaternionf(),
            new Vector3f(shape.width() * side, shape.height() * height, shape.length() * side), new Quaternionf());
    }

    private enum Presentation { NONE, LEGACY, SMOOTH }

    private static final class Body {
        private final Entity source;
        private final World world;
        private ItemDisplay legacy;
        private final Map<SunbirdDisplayRig.Part, ItemDisplay> rig = new EnumMap<>(SunbirdDisplayRig.Part.class);
        private final SunbirdDisplayRig rigMotion = new SunbirdDisplayRig();
        private final Set<Player> javaLegacyViewers = new HashSet<>();
        private final Set<Player> javaRigViewers = new HashSet<>();
        private final Set<UUID> masked = ConcurrentHashMap.newKeySet();
        private BukkitTask restoreTint;
        private BodyPose pose;
        private Motion motion = Motion.IDLE;
        private Motion resumeMotion = Motion.IDLE;
        private boolean rigWasStriking;
        private boolean invulnerable;

        private Body(Entity source) {
            this.source = source;
            world = source.getWorld();
        }

        private List<ItemDisplay> displays() {
            List<ItemDisplay> parts = new ArrayList<>(rig.values());
            if (legacy != null) parts.add(legacy);
            return parts;
        }

        private boolean tracks(Player viewer, Presentation presentation) {
            return switch (presentation) {
                case LEGACY -> legacy != null && legacy.isValid() && legacy.getTrackedBy().contains(viewer);
                case SMOOTH -> rig.size() == SunbirdDisplayRig.Part.values().length
                    && rig.values().stream().allMatch(part -> part.isValid() && part.getTrackedBy().contains(viewer));
                default -> false;
            };
        }

        private void removeRig() {
            rig.values().forEach(ItemDisplay::remove);
            rig.clear();
            javaRigViewers.clear();
        }
    }

    private enum BodyPose {
        REST(""), UP("wing_up"), DOWN("wing_down"), TUCK("wing_tuck"), STRIKE("strike");

        private final String key;

        BodyPose(String key) {
            this.key = key;
        }
    }

    private enum Motion {
        IDLE(0), MOVE(1), CAST(2), STRIKE(3);

        private static final List<BodyPose> IDLE_POSES = List.of(
            BodyPose.REST, BodyPose.REST, BodyPose.UP, BodyPose.REST, BodyPose.DOWN, BodyPose.REST);
        private static final List<BodyPose> MOVE_POSES = List.of(
            BodyPose.UP, BodyPose.REST, BodyPose.DOWN, BodyPose.REST);
        private final int id;

        Motion(int id) {
            this.id = id;
        }

        BodyPose poseAt(long tick) {
            long phase = tick / POSE_TICKS;
            return switch (this) {
                case IDLE -> IDLE_POSES.get((int) (phase % IDLE_POSES.size()));
                case MOVE -> MOVE_POSES.get((int) (phase % MOVE_POSES.size()));
                case CAST -> phase % 4 == 2 ? BodyPose.UP : BodyPose.TUCK;
                case STRIKE -> BodyPose.STRIKE;
            };
        }
    }

    private static final class MotionState {
        private final Entity source;
        private final Set<Player> viewers = new HashSet<>();
        private Location position;
        private long mode = -1L;

        private MotionState(Entity source, Location position) {
            this.source = source;
            this.position = position;
        }
    }

    private record BodyItems(ItemStack normal, ItemStack hurt) {
        ItemStack get(boolean tinted) {
            return tinted ? hurt : normal;
        }
    }

    private record TimedEffect(Effect effect, long endTick) {}
}
