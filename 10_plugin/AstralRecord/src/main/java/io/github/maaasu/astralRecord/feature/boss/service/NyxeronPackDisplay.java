package io.github.maaasu.astralRecord.feature.boss.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.IronGolem;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
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

/**
 * ニクセロンのアイアンゴーレムを戦闘実体のまま維持し、対応パックの閲覧者へ天球の心臓の外観と技の形状を配信します。
 *
 * <p>Java版は核・三本の環・花弁・結晶籠・星屑を別々のItemDisplayで描き、2tickごとに補間をつないで動かします。
 * 技の形状は縮む輪・伸びる帯・飛ぶ弾などを一度の長い補間で描きます。統合版はGeyser拡張がカスタムエンティティへ
 * 置き換え、形状・寸法・残りtickをマーカーに載せてMolangで同じ動きを再生します。</p>
 */
final class NyxeronPackDisplay implements Listener {
    // Geyser拡張と共有する UUID マーカー（ASTNYXB1 / ASTNYXF1 / ASTNYXM1）。
    private static final long BEDROCK_BODY = 0x4153544e59584231L;
    private static final long BEDROCK_EFFECT = 0x4153544e59584631L;
    private static final long BEDROCK_MOTION = 0x4153544e59584d31L;
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    /** 本体の送信間隔。補間は少し長くして、次の送信まで途切れずにつなぐ。 */
    private static final int BODY_STEP = 2;
    private static final int BODY_INTERPOLATION = 3;
    private static final int SYNC_TICKS = 5;
    private static final int BEAM_SNAPSHOT = 5;
    private static final long HURT_COOLDOWN = 10L;
    private static final int MAX_TRANSIENTS = 640;
    private static final double VIEW_DISTANCE = 72.0D;
    private static final float CORE_HEIGHT = 2.35F;
    private static final float CORE_SCALE = 2.0F;
    private static final float RING_SCALE = 1.25F;
    private static final float PETAL_OFFSET = 1.0F;
    private static final float MOTE_RADIUS = 1.625F;
    private static final float MOTE_SCALE = 1.2F;
    private static final int PARTS = 13;
    private static final int RING_PART = 1;
    private static final int PETAL_PART = 4;
    private static final int CAGE_PART = 8;
    private static final int MOTE_PART = 9;
    private static final Runnable EMPTY_EFFECT = () -> { };
    private static final Color GUARD_TINT = Color.fromRGB(0xD7B4FF);
    private static final Color HURT_TINT = Color.fromRGB(0xFF6666);
    private static final Color EXPOSED_TINT = Color.fromRGB(0xFFF1B0);
    static final int JAVA = 1;
    static final int BEDROCK = 2;
    static final int BOTH = JAVA | BEDROCK;

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final NamespacedKey templateKey;
    private final PacketAdapter packets;
    private final Map<Integer, Carrier> carriers = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, List<Effect>> effects = new HashMap<>();
    private final Map<Style, ItemStack> effectItems = new EnumMap<>(Style.class);
    private final Map<String, ItemStack> coreItems = new HashMap<>();
    private final ItemStack[] partItems = {
        null, item("nyxeron_ring_outer", 0, null), item("nyxeron_ring_mid", 0, null), item("nyxeron_ring_inner", 0, null),
        item("nyxeron_petal", 0, null), item("nyxeron_cage", 0, null), item("nyxeron_mote", 0, null)};
    private long nearbyTick = Long.MIN_VALUE;
    private final Map<UUID, Collection<Player>> nearby = new HashMap<>();

    /** @param plugin 登録と表示を所有するプラグイン @param packs 接続ごとのパック適用状態 */
    NyxeronPackDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        this.templateKey = new NamespacedKey(plugin, "mob_template_id");
        this.packets = new PacketAdapter(plugin, PacketType.Play.Server.BUNDLE, PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (carriers.isEmpty()) return;
                try {
                    PacketContainer original = event.getPacket();
                    PacketContainer updated = rewrite(event.getPlayer().getUniqueId(), original);
                    if (updated != original) event.setPacket(updated);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6548, exception);
                }
            }
        };
    }

    /** 実体の追跡とパケットの監視を登録します。 */
    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        protocol.addPacketListener(packets);
    }

    /** 専用表示を回収してパケットの監視を解除します。 */
    void stop() {
        protocol.removePacketListener(packets);
        HandlerList.unregisterAll(this);
        for (UUID id : List.copyOf(bodies.keySet())) clear(id);
        for (UUID id : List.copyOf(effects.keySet())) clearEffects(id);
        carriers.clear();
    }

    /** @param player 閲覧者 @return どちらかの版で専用の外観・効果音を利用できる場合true */
    boolean hasPack(UUID player) {
        return packs.hasJavaNyxeronPresentation(player) || packs.hasBedrockNyxeronPresentation(player);
    }

    /**
     * 実体のスポーンより先にパケット照合用IDを登録します。
     * @param event 追跡開始。別のアイアンゴーレムは登録しません
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof IronGolem source && NyxeronEncounterController.BOSS_ID.equals(
            source.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING))) carrier(source);
    }

    /**
     * 再追跡で姿勢を初期状態から同期できるようにします。
     * @param event 追跡終了
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        Carrier carrier = carriers.get(event.getEntity().getEntityId());
        if (carrier == null) return;
        UUID viewer = event.getPlayer().getUniqueId();
        carrier.bedrock.remove(viewer);
        carrier.masked.remove(viewer);
        carrier.motion.remove(viewer);
    }

    private Carrier carrier(Entity source) {
        return carriers.compute(source.getEntityId(), (id, existing) -> existing != null
            && existing.source.equals(source.getUniqueId()) ? existing : new Carrier(source.getUniqueId()));
    }

    /**
     * 5tick周期で本体の姿勢・閲覧者・統合版の姿勢通知を更新します。部品の動きは {@link #animate(long)} が2tickごとに送ります。
     * モデルが追跡されるまで実体は隠しません。
     * @param bossId ボスID @param entity 戦闘実体 @param tick 共通時計 @param pose 姿勢 @param immune 無敵中
     */
    void body(UUID bossId, Entity entity, long tick, Pose pose, boolean immune) {
        if (!(entity instanceof IronGolem source)) return;
        try {
            updateBody(bossId, source, tick, pose, immune);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
            clearBody(bossId);
        }
    }

    private void updateBody(UUID bossId, IronGolem source, long tick, Pose pose, boolean immune) {
        Body body = bodies.get(bossId);
        if (body != null && (body.source != source || body.position.getWorld() != source.getWorld())) {
            clearBody(bossId);
            body = null;
        }
        Location target = source.getLocation();
        target.setPitch(0);
        if (body == null) {
            body = new Body(source, carrier(source), target);
            bodies.put(bossId, body);
        }
        body.position = target;
        body.pose = pose;
        body.immune = immune;
        Pose motion = body.motion(tick);
        long mode = BossBedrockMotion.state(motion.bedrock, immune, target.getYaw());
        Set<Player> tracked = Set.copyOf(source.getTrackedBy());
        Set<Player> java = new HashSet<>();
        for (Player viewer : tracked) {
            UUID id = viewer.getUniqueId();
            if (packs.hasJavaNyxeronPresentation(id)) java.add(viewer);
            if (!packs.hasBedrockNyxeronPresentation(id)) continue;
            if (!body.carrier.bedrock.contains(id)) {
                // パック準備通知が最初の追跡より遅い場合だけ、通常のスポーンを再送させる。
                viewer.hideEntity(plugin, source);
                viewer.showEntity(plugin, source);
            }
            if (body.carrier.bedrock.contains(id) && !Long.valueOf(mode).equals(body.carrier.motion.get(id))
                && sendMotion(viewer, source.getEntityId(), target, mode)) body.carrier.motion.put(id, mode);
        }
        if (body.parts.isEmpty() && java.isEmpty()) return;
        var nativeScale = source.getAttribute(Attribute.SCALE);
        body.scale = nativeScale == null ? 1.0F : (float) nativeScale.getValue();
        if (body.parts.isEmpty()) {
            body.advance(tick);
            body.parts.add(spawn(target, coreItem(body, tick), true, body.core()));
            for (int part = 1; part < PARTS; part++) {
                body.parts.add(spawn(target, partItem(part), true, body.part(part)));
            }
        } else {
            for (ItemDisplay part : body.parts) {
                if (!part.isValid()) {
                    removeModel(body);
                    return;
                }
            }
            for (ItemDisplay part : body.parts) {
                Location previous = part.getLocation();
                if (!previous.equals(target)) {
                    part.setTeleportDuration(previous.distanceSquared(target) > 9.0D ? 0 : SYNC_TICKS);
                    part.teleport(target);
                }
            }
        }
        ItemDisplay coreDisplay = body.parts.getFirst();
        boolean glowing = source.isGlowing() || immune;
        if (coreDisplay.isGlowing() != glowing) coreDisplay.setGlowing(glowing);
        if (glowing) coreDisplay.setGlowColorOverride(GUARD_TINT);

        Iterator<Player> previousViewers = body.viewers.iterator();
        while (previousViewers.hasNext()) {
            Player viewer = previousViewers.next();
            if (java.contains(viewer)) continue;
            if (body.carrier.masked.remove(viewer.getUniqueId()) && tracked.contains(viewer)) sendMask(viewer, source, false);
            if (viewer.isOnline()) for (ItemDisplay part : body.parts) viewer.hideEntity(plugin, part);
            previousViewers.remove();
        }
        Set<Player> modelTracked = coreDisplay.getTrackedBy();
        for (Player viewer : java) {
            if (body.viewers.add(viewer)) for (ItemDisplay part : body.parts) viewer.showEntity(plugin, part);
            UUID id = viewer.getUniqueId();
            if (!modelTracked.contains(viewer)) {
                if (body.carrier.masked.remove(id)) sendMask(viewer, source, false);
            } else if (body.carrier.masked.add(id) && !sendMask(viewer, source, true)) {
                body.carrier.masked.remove(id);
                sendMask(viewer, source, false);
            }
        }
        if (java.isEmpty()) removeModel(body);
    }

    private ItemStack coreItem(Body body, long tick) {
        Pose motion = body.motion(tick);
        String tint = tick < body.hurtUntil ? "hurt" : body.immune ? "guard" : motion == Pose.EXPOSED ? "exposed" : "none";
        return coreItems.computeIfAbsent(motion.frame + ":" + tint, ignored -> item("nyxeron_core", motion.frame,
            switch (tint) {
                case "hurt" -> HURT_TINT;
                case "guard" -> GUARD_TINT;
                case "exposed" -> EXPOSED_TINT;
                default -> null;
            }));
    }

    private ItemStack partItem(int part) {
        if (part < PETAL_PART) return partItems[part];
        if (part < CAGE_PART) return partItems[4];
        return part == CAGE_PART ? partItems[5] : partItems[6];
    }

    /** 2tickごとに部品の目標姿勢を送り、補間を途切れさせずに動かします。 */
    private void renderBody(Body body, long tick) {
        for (ItemDisplay part : body.parts) if (!part.isValid()) return;
        body.advance(tick);
        ItemDisplay core = body.parts.getFirst();
        ItemStack item = coreItem(body, tick);
        if (!item.equals(core.getItemStack())) core.setItemStack(item);
        interpolate(core, body.core(), BODY_INTERPOLATION);
        for (int part = 1; part < PARTS; part++) interpolate(body.parts.get(part), body.part(part), BODY_INTERPOLATION);
    }

    private static void interpolate(ItemDisplay display, Transformation transformation, int duration) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(duration);
        display.setTransformation(transformation);
    }

    /** @param bossId 被弾した個体 @param tick 現在tick。Javaの着色と統合版の被弾を短時間にまとめます */
    void hurt(UUID bossId, long tick) {
        Body body = bodies.get(bossId);
        if (body == null || tick < body.nextHurt) return;
        body.nextHurt = tick + HURT_COOLDOWN;
        body.hurtUntil = tick + 6L;
        try {
            PacketContainer packet = null;
            for (Player viewer : body.source.getTrackedBy()) {
                if (!body.carrier.bedrock.contains(viewer.getUniqueId())) continue;
                if (packet == null) packet = BossBedrockHurt.create(body.source);
                send(viewer, packet);
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
        }
    }

    /** @param bossId 攻撃を発動した個体 @param tick 現在tick。花弁を開いて攻撃姿勢を短く見せます */
    void strike(UUID bossId, long tick) {
        Body body = bodies.get(bossId);
        if (body != null) body.strikeUntil = Math.max(body.strikeUntil, tick + 10L);
    }

    /** @param bossId 終了したボスのID。専用の本体と短時間演出を解除します */
    void clear(UUID bossId) {
        clearBody(bossId);
        clearEffects(bossId);
    }

    /** @param bossId 一時演出だけを解除する個体ID */
    void clearEffects(UUID bossId) {
        List<Effect> owned = effects.remove(bossId);
        if (owned != null) owned.forEach(Effect::remove);
    }

    private void clearBody(UUID bossId) {
        Body body = bodies.remove(bossId);
        if (body != null) removeModel(body);
    }

    private void removeModel(Body body) {
        if (body.parts.isEmpty()) return;
        for (Player viewer : body.source.getTrackedBy()) {
            if (body.carrier.masked.remove(viewer.getUniqueId())) sendMask(viewer, body.source, false);
        }
        body.carrier.masked.clear();
        for (ItemDisplay part : body.parts) part.remove();
        body.parts.clear();
        body.viewers.clear();
    }

    /** @param active 現在確認できたボスのID */
    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(bodies.keySet())) if (!active.contains(id)) clear(id);
        for (UUID id : List.copyOf(effects.keySet())) if (!active.contains(id)) clearEffects(id);
        carriers.entrySet().removeIf(entry -> plugin.getServer().getEntity(entry.getValue().source) == null);
    }

    /**
     * 形状を個体に関連付けます。動きは形状の種類から決まり、表示期間の終わりに到達します。
     * 危険範囲の正確な輪郭は床の粒子が引き続き担います。
     * @param bossId 所有個体 @param shapes 追加表示 @param tick 開始tick @param duration 表示tick数
     * @return この呼び出しで作った演出だけを解除する処理
     */
    Runnable effects(UUID bossId, List<Shape> shapes, long tick, long duration) {
        if (shapes.isEmpty()) return EMPTY_EFFECT;
        try {
            List<Effect> owned = effects.computeIfAbsent(bossId, ignored -> new ArrayList<>());
            List<Effect> created = new ArrayList<>();
            for (Shape shape : shapes) {
                if (owned.size() >= MAX_TRANSIENTS) break;
                Effect effect = new Effect(shape, tick, tick + Math.max(1L, duration));
                owned.add(effect);
                created.add(effect);
            }
            for (Effect effect : created) sync(bossId, effect, tick);
            return () -> {
                for (Effect effect : created) if (owned.remove(effect)) effect.remove();
            };
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
            return EMPTY_EFFECT;
        }
    }

    /**
     * 中心から伸びて回転する光刃を作ります。Java版は2tickごとに向きを補間し、統合版は5tickごとの向きから回転を再生します。
     * @param bossId 所有個体 @param shape 光刃の形状（originは中心） @param angle 開始方位（度、+Xから+Z方向）
     * @param omega 1tickあたりの回転（度） @param tick 開始tick @param duration 表示tick数
     * @return 向きの更新と解除を行う操作
     */
    Beam beam(UUID bossId, Shape shape, double angle, double omega, long tick, long duration) {
        try {
            List<Effect> owned = effects.computeIfAbsent(bossId, ignored -> new ArrayList<>());
            if (owned.size() >= MAX_TRANSIENTS) return Beam.NONE;
            Effect effect = new Effect(shape, tick, tick + Math.max(1L, duration));
            effect.aim(angle, omega, tick);
            owned.add(effect);
            sync(bossId, effect, tick);
            return new Beam() {
                @Override
                public void aim(double degrees, double degreesPerTick, long now) {
                    effect.aim(degrees, degreesPerTick, now);
                }

                @Override
                public void remove() {
                    if (owned.remove(effect)) effect.remove();
                }
            };
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
            return Beam.NONE;
        }
    }

    /** @param tick 現在tick。本体の補間、期限切れ、閲覧者、形状の動きを更新します */
    void animate(long tick) {
        for (Body body : bodies.values()) {
            if (body.parts.isEmpty() || tick % BODY_STEP != 0) continue;
            try {
                renderBody(body, tick);
            } catch (RuntimeException exception) {
                Logger.log(LogId.E_6548, exception);
                removeModel(body);
            }
        }
        effects.entrySet().removeIf(entry -> {
            syncEffects(entry.getKey(), entry.getValue(), tick);
            return entry.getValue().isEmpty();
        });
    }

    private void syncEffects(UUID bossId, List<Effect> owned, long tick) {
        Iterator<Effect> iterator = owned.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            try {
                if (tick >= effect.ends) {
                    iterator.remove();
                    effect.remove();
                } else {
                    if (tick % SYNC_TICKS == 0) effect.syncViewers(viewers(bossId, effect, tick), tick);
                    effect.animate(tick);
                }
            } catch (RuntimeException exception) {
                if (tick < effect.ends) iterator.remove();
                effect.remove();
                Logger.log(LogId.E_6548, exception);
            }
        }
    }

    private void sync(UUID bossId, Effect effect, long tick) {
        try {
            effect.syncViewers(viewers(bossId, effect, tick), tick);
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
        }
    }

    /** 弾が多い場面でも近傍の閲覧者は1tickに1回だけ求めます。 */
    private Collection<Player> viewers(UUID bossId, Effect effect, long tick) {
        if (tick != nearbyTick) {
            nearby.clear();
            nearbyTick = tick;
        }
        return nearby.computeIfAbsent(bossId, ignored -> {
            Location position = effect.shape.origin;
            if (position.getWorld() == null
                || !position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) return List.of();
            return List.copyOf(position.getWorld().getNearbyPlayers(position, VIEW_DISTANCE));
        });
    }

    /** @param viewer 宛先UUID @param packet 元パケット @return 必要な場合だけ閲覧者専用の複製 */
    private PacketContainer rewrite(UUID viewer, PacketContainer packet) {
        PacketType type = packet.getType();
        if (type == PacketType.Play.Server.BUNDLE) {
            Iterable<PacketContainer> children = packet.getPacketBundles().readSafely(0);
            if (children == null) return packet;
            List<PacketContainer> result = new ArrayList<>();
            boolean changed = false;
            for (PacketContainer child : children) {
                PacketContainer updated = rewrite(viewer, child);
                result.add(updated);
                changed |= updated != child;
            }
            if (!changed) return packet;
            PacketContainer copy = packet.shallowClone();
            copy.getPacketBundles().write(0, result);
            return copy;
        }
        Integer id = packet.getIntegers().readSafely(0);
        Carrier carrier = id == null ? null : carriers.get(id);
        if (carrier == null) return packet;
        if (type == PacketType.Play.Server.SPAWN_ENTITY && packs.hasBedrockNyxeronPresentation(viewer)
            && carrier.source.equals(packet.getUUIDs().readSafely(0))) {
            PacketContainer copy = packet.shallowClone();
            copy.getUUIDs().write(0, new UUID(BEDROCK_BODY, carrier.source.getLeastSignificantBits()));
            carrier.bedrock.add(viewer);
            return copy;
        }
        if (!carrier.masked.contains(viewer) || type != PacketType.Play.Server.ENTITY_METADATA) return packet;
        List<WrappedDataValue> values = packet.getDataValueCollectionModifier().readSafely(0);
        if (values == null) return packet;
        for (int i = 0; i < values.size(); i++) {
            WrappedDataValue value = values.get(i);
            if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                List<WrappedDataValue> result = new ArrayList<>(values);
                result.set(i, new WrappedDataValue(0, value.getSerializer(), maskedFlags(flags)));
                PacketContainer copy = packet.shallowClone();
                copy.getDataValueCollectionModifier().write(0, result);
                return copy;
            }
        }
        return packet;
    }

    private static byte maskedFlags(byte flags) {
        return (byte) ((flags | INVISIBLE) & ~GLOWING);
    }

    /** @param viewer 宛先 @param source 戦闘実体 @param hide 代替モデルで表示するならtrue @return 送信成功 */
    private boolean sendMask(Player viewer, IronGolem source, boolean hide) {
        try {
            for (WrappedDataValue value : WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()) {
                if (value.getIndex() != 0 || !(value.getValue() instanceof Byte flags)) continue;
                PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
                packet.getIntegers().write(0, source.getEntityId());
                packet.getDataValueCollectionModifier().write(0, List.of(new WrappedDataValue(0,
                    WrappedDataWatcher.Registry.get((Type) Byte.class), hide ? maskedFlags(flags) : flags)));
                return send(viewer, packet);
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
        }
        return false;
    }

    private boolean sendMotion(Player viewer, int id, Location position, long mode) {
        int packetId = PacketEntityIdAllocator.nextEntityId();
        long bits = BossBedrockMotion.payload(id, mode);
        boolean sent = send(viewer, marker(packetId, BEDROCK_MOTION, bits, position));
        send(viewer, destroy(packetId));
        return sent;
    }

    private PacketContainer marker(int id, long marker, long bits, Location location) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, id);
        packet.getUUIDs().write(0, new UUID(marker, bits));
        packet.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
        packet.getBytes().writeSafely(1, (byte) Math.round(location.getYaw() * 256.0F / 360.0F));
        return packet;
    }

    private PacketContainer destroy(int id) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().writeSafely(0, List.of(id));
        packet.getIntegerArrays().writeSafely(0, new int[]{id});
        return packet;
    }

    private boolean send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet, false);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
            return false;
        }
    }

    /** @param model アイテム定義名 @param frame モデルのfloat番号 @param tint 差し色またはnull @return 専用アイテム */
    private static ItemStack item(String model, int frame, Color tint) {
        ItemStack item = ItemStack.of(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("astralrecord", model));
        var data = meta.getCustomModelDataComponent();
        data.setFloats(List.of((float) frame));
        if (tint != null) data.setColors(List.of(tint));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }

    private ItemDisplay spawn(Location position, ItemStack item, boolean bright, Transformation transformation) {
        return position.getWorld().spawn(position, ItemDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setItemStack(item);
            display.setViewRange(2);
            if (bright) display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(transformation);
        });
    }

    /** 統合版の姿勢番号とJava版の核モデルの番号を対応付けます。 */
    enum Pose {
        IDLE(0, 0), CAST(1, 1), ATTACK(2, 2), GUARD(3, 3), EXPOSED(4, 4);

        private final int bedrock;
        private final int frame;

        Pose(int bedrock, int frame) {
            this.bedrock = bedrock;
            this.frame = frame;
        }
    }

    /** 形状IDと寸法単位は両版のパックおよびGeyser拡張で共有します（最大23）。 */
    enum Style {
        DISC(0, "disc"), BAND1(1, "band_1"), BAND2(2, "band_2"), BAND3(3, "band_3"), BAND4(4, "band_4"),
        SPOKE(5, "spoke"), SEAL(6, "seal"), SEAL_DONE(7, "seal_done"), SCAR(8, "scar"), IMPACT(9, "impact"),
        GAZE(10, "gaze"), METEOR(11, "meteor"), HEART(12, "heart"),
        APPROACH(13, "approach"), FILL_BAR(14, "fill_bar"), BULLET(15, "bullet"), RING_LOW(16, "ring_low"),
        RING_HIGH(17, "ring_high"), BEAM_LOW(18, "beam_low"), BEAM_HIGH(19, "beam_high"), FRAGMENT(20, "fragment"),
        STAR(21, "star"), NOVA(22, "nova"), SUPERNOVA(23, "supernova");

        private final int id;
        private final String model;

        Style(int id, String suffix) {
            this.id = id;
            this.model = "nyxeron_" + suffix;
        }

        /** @param band 0～4 @return 帯の形状。0は中心円 */
        static Style band(int band) {
            return switch (band) {
                case 1 -> BAND1;
                case 2 -> BAND2;
                case 3 -> BAND3;
                case 4 -> BAND4;
                default -> DISC;
            };
        }

        /** @return 表示期間の終わりへ向けて一度だけ補間する形状 */
        boolean timed() {
            return switch (this) {
                case APPROACH, FILL_BAR, BULLET, RING_LOW, RING_HIGH, STAR, NOVA, SUPERNOVA -> true;
                default -> false;
            };
        }

        boolean beam() {
            return this == BEAM_LOW || this == BEAM_HIGH;
        }
    }

    /**
     * 固定原点と1ブロックを基準にした表示寸法。範囲判定には使用しません。
     * @param param 統合版へ渡す追加値（新星の隙間など）
     * @param travelX Java版の弾が寿命の間に進むX @param travelZ 同Z
     * @param editions 表示する版（{@link #JAVA} / {@link #BEDROCK}）
     */
    record Shape(Style style, Location origin, float width, float height, float length, int param,
                 float travelX, float travelZ, int editions) {
        Shape(Style style, Location origin, float width, float height, float length) {
            this(style, origin, width, height, length, 0, 0F, 0F, BOTH);
        }

        /** @param style 形状 @param center 中心 @param radius 外周の半径 @param height 高さ @return 円状の表示 */
        static Shape circle(Style style, Location center, double radius, double height) {
            Location location = center.clone().add(0, 0.06D, 0);
            location.setYaw(0);
            location.setPitch(0);
            float diameter = (float) (radius * 2);
            return new Shape(style, location, diameter, (float) height, diameter);
        }

        /**
         * 半径が表示期間のうちに from から to へ変わる輪（予告の縮む輪・広がる衝撃環）。
         * @param style APPROACH / RING_LOW / RING_HIGH @param center 中心（床） @param from 開始半径 @param to 終了半径
         * @param height 壁の高さ @param lift 床からの持ち上げ
         */
        static Shape ring(Style style, Location center, double from, double to, double height, double lift) {
            Location location = center.clone().add(0, 0.06D + lift, 0);
            location.setYaw(0);
            location.setPitch(0);
            return new Shape(style, location, (float) Math.max(0.05D, from * 2), (float) height,
                (float) Math.max(0.05D, to * 2));
        }

        /** @param style 形状 @param first 始点 @param last 終点 @param width 幅 @param height 高さ @return 線状の表示 */
        static Shape segment(Style style, Location first, Location last, float width, float height) {
            Vector direction = last.toVector().subtract(first.toVector()).setY(0);
            Location center = first.clone().add(direction.clone().multiply(0.5D)).add(0, 0.07D, 0);
            center.setDirection(direction);
            center.setPitch(0);
            return new Shape(style, center, width, height, (float) direction.length());
        }

        /** 中心から外へ、表示期間のうちに伸びる帯（光条の予告）。 */
        static Shape bar(Location center, double angle, double length, float width) {
            Location location = center.clone().add(0, 0.09D, 0);
            location.setYaw((float) angle - 90.0F);
            location.setPitch(0);
            return new Shape(Style.FILL_BAR, location, width, 0.5F, (float) length);
        }

        /** @param style 形状 @param origin 表示位置 @param toward 向く先 @param width 幅 @param height 高さ @return 立てた表示 */
        static Shape facing(Style style, Location origin, Location toward, float width, float height) {
            Location location = origin.clone().add(0, 0.06D, 0);
            Vector direction = toward.toVector().subtract(origin.toVector()).setY(0);
            if (direction.lengthSquared() > 0.0001D) location.setDirection(direction);
            location.setPitch(0);
            return new Shape(style, location, width, height, width);
        }

        /** 床へ落ちる星。表示期間のうちに fall の高さから着弾点へ降ります。 */
        static Shape star(Location target, double fall, float size) {
            Location location = target.clone().add(0, 0.5D, 0);
            location.setYaw(0);
            location.setPitch(0);
            return new Shape(Style.STAR, location, size, (float) fall, size);
        }

        /**
         * 直進する弾。Java版は世界座標の移動量で、統合版は量子化した方位と秒速で同じ軌跡を描きます。
         * @param origin 発射点（胸の高さ） @param angle 方位（度） @param distance 寿命の間の距離 @param seconds 寿命（秒）
         */
        static Shape bullet(Location origin, double angle, double distance, double seconds, float size) {
            Location location = origin.clone();
            location.setYaw((float) angle - 90.0F);
            location.setPitch(0);
            double radians = Math.toRadians(angle);
            return new Shape(Style.BULLET, location, size, size, (float) (distance / Math.max(0.05D, seconds)), 0,
                (float) (Math.cos(radians) * distance), (float) (Math.sin(radians) * distance), BOTH);
        }

        /** Java版だけの弾（統合版は新星の輪でまとめて描きます）。 */
        Shape javaOnly() {
            return new Shape(style, origin, width, height, length, param, travelX, travelZ, JAVA);
        }

        /**
         * 統合版で一体にまとめた新星の輪。隙間は前方（方位）を中心に、等間隔に gaps か所、各 half*2+1 発分。
         * @param center 中心（胸の高さ） @param angle 最初の隙間の方位 @param start 開始半径 @param speed 秒速
         */
        static Shape nova(Location center, double angle, double start, double speed, int gaps, int half, float size) {
            Location location = center.clone();
            location.setYaw((float) angle - 90.0F);
            location.setPitch(0);
            return new Shape(Style.NOVA, location, size, (float) Math.max(0.05D, start), (float) speed,
                (gaps & 0xf) | ((half & 0xff) << 4), 0F, 0F, BEDROCK);
        }

        /** 中心からlengthまでの光刃。高さ方向はliftから。 */
        static Shape beam(Style style, Location center, double lift, float width, float height, float length) {
            Location location = center.clone().add(0, 0.05D + lift, 0);
            location.setYaw(0);
            location.setPitch(0);
            return new Shape(style, location, width, height, length);
        }
    }

    /** 回転する光刃の操作。 */
    interface Beam {
        Beam NONE = new Beam() {
            @Override
            public void aim(double degrees, double degreesPerTick, long now) {
            }

            @Override
            public void remove() {
            }
        };

        /** @param degrees 現在の方位 @param degreesPerTick 回転の速さ @param now 現在tick */
        void aim(double degrees, double degreesPerTick, long now);

        void remove();
    }

    /** 1つの追加演出の閲覧者、動き、寿命を所有します。 */
    private final class Effect {
        private final Shape shape;
        private final long started;
        private final long ends;
        private final Set<Player> java = new HashSet<>();
        /** 統合版の閲覧者ごとのマーカーID。追跡の開始時刻が違うとIDも変わる。 */
        private final Map<Player, Integer> bedrock = new HashMap<>();
        private ItemDisplay model;
        private int bedrockId = -1;
        private PacketContainer bedrockSpawn;
        private long bedrockSpawnTick = Long.MIN_VALUE;
        private boolean targeted;
        private boolean faded;
        private long nextSpin;
        private float spin;
        private double angle;
        private double omega;
        private long aimedAt;
        private long snapshotAt;

        private Effect(Shape shape, long started, long ends) {
            this.shape = shape;
            this.started = started;
            this.ends = ends;
        }

        private void aim(double degrees, double degreesPerTick, long tick) {
            angle = degrees;
            omega = degreesPerTick;
            aimedAt = tick;
        }

        private float progress(long tick) {
            return Math.clamp((tick - started) / (float) Math.max(1L, ends - started), 0F, 1F);
        }

        private boolean showsJava() {
            return (shape.editions & JAVA) != 0;
        }

        private boolean showsBedrock() {
            return (shape.editions & BEDROCK) != 0;
        }

        private void syncViewers(Collection<Player> nearby, long tick) {
            if (model != null && !model.isValid()) {
                model = null;
                java.clear();
            }
            java.removeIf(viewer -> {
                if (nearby.contains(viewer) && packs.hasJavaNyxeronPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline() && model != null) viewer.hideEntity(plugin, model);
                return true;
            });
            bedrock.entrySet().removeIf(entry -> {
                Player viewer = entry.getKey();
                if (nearby.contains(viewer) && packs.hasBedrockNyxeronPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline()) send(viewer, destroy(entry.getValue()));
                return true;
            });
            for (Player viewer : nearby) {
                UUID id = viewer.getUniqueId();
                if (packs.hasJavaNyxeronPresentation(id)) {
                    if (!showsJava()) continue;
                    if (model == null) {
                        Location position = shape.origin;
                        if (shape.style == Style.BULLET || shape.style.beam()) {
                            position = position.clone();
                            position.setYaw(0);
                        }
                        model = spawn(position, effectItems.computeIfAbsent(shape.style,
                            style -> item(style.model, 0, null)), true, javaTransform(tick));
                        targeted = false;
                    }
                    if (java.add(viewer)) viewer.showEntity(plugin, model);
                } else if (packs.hasBedrockNyxeronPresentation(id) && showsBedrock() && !bedrock.containsKey(viewer)) {
                    PacketContainer spawn = bedrockSpawn(tick);
                    if (send(viewer, spawn)) bedrock.put(viewer, bedrockId);
                }
            }
            if (java.isEmpty() && model != null) {
                model.remove();
                model = null;
            }
        }

        /** Java版の時間変化と、統合版の光刃の向きの更新を進めます。 */
        private void animate(long tick) {
            if (model != null && model.isValid()) {
                if (shape.style.timed() && !targeted && tick > started) {
                    targeted = true;
                    interpolate(model, javaTransform(ends), (int) Math.max(1L, ends - tick));
                } else if (shape.style == Style.IMPACT) {
                    if (!targeted && tick > started) {
                        targeted = true;
                        interpolate(model, scaled(shape.width, shape.height, shape.length), 4);
                    } else if (!faded && tick >= ends - 5L) {
                        faded = true;
                        interpolate(model, scaled(shape.width * 1.12F, shape.height * 0.05F, shape.length * 1.12F), 5);
                    }
                } else if (shape.style == Style.FRAGMENT && tick >= nextSpin) {
                    nextSpin = tick + 10L;
                    spin += 90F;
                    interpolate(model, new Transformation(new Vector3f(), new Quaternionf().rotateY((float) Math.toRadians(spin)),
                        new Vector3f(shape.width), new Quaternionf()), 10);
                } else if (shape.style.beam() && tick % BODY_STEP == 0) {
                    interpolate(model, javaTransform(tick), BODY_INTERPOLATION);
                }
            }
            if (shape.style.beam() && !bedrock.isEmpty() && tick - snapshotAt >= BEAM_SNAPSHOT) {
                // 統合版は回転の向きの差を5tickで打ち消すため、向きを取り直したマーカーへ置き換える。
                PacketContainer spawn = bedrockSpawn(tick);
                for (Map.Entry<Player, Integer> entry : bedrock.entrySet()) {
                    send(entry.getKey(), spawn);
                    send(entry.getKey(), destroy(entry.getValue()));
                    entry.setValue(bedrockId);
                }
            }
        }

        private double currentAngle(long tick) {
            return angle + omega * (tick - aimedAt);
        }

        /** @return 形状と経過に応じたJava版の変換 */
        private Transformation javaTransform(long tick) {
            float progress = progress(tick);
            return switch (shape.style) {
                case APPROACH, RING_LOW, RING_HIGH -> {
                    float diameter = shape.width + (shape.length - shape.width) * progress;
                    yield scaled(diameter, shape.height, diameter);
                }
                case FILL_BAR -> {
                    float length = Math.max(0.05F, shape.length * progress);
                    yield new Transformation(new Vector3f(0F, 0F, length / 2F), new Quaternionf(),
                        new Vector3f(shape.width, shape.height, length), new Quaternionf());
                }
                case BULLET -> new Transformation(new Vector3f(shape.travelX * progress, 0F, shape.travelZ * progress),
                    new Quaternionf(), new Vector3f(shape.width), new Quaternionf());
                case STAR -> new Transformation(new Vector3f(0F, shape.height * (1F - progress), 0F), new Quaternionf(),
                    new Vector3f(shape.width), new Quaternionf());
                case SUPERNOVA -> new Transformation(new Vector3f(), new Quaternionf().rotateY(progress * 6F),
                    new Vector3f(shape.width * (0.2F + 0.8F * progress)), new Quaternionf());
                case FRAGMENT -> new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(shape.width), new Quaternionf());
                case IMPACT -> scaled(shape.width * 0.45F, shape.height * 0.6F, shape.length * 0.45F);
                case BEAM_LOW, BEAM_HIGH -> {
                    double radians = Math.toRadians(currentAngle(tick));
                    float half = shape.length / 2F;
                    yield new Transformation(new Vector3f((float) Math.cos(radians) * half, 0F, (float) Math.sin(radians) * half),
                        new Quaternionf().rotateY((float) Math.toRadians(90.0D - currentAngle(tick))),
                        new Vector3f(shape.width, shape.height, shape.length), new Quaternionf());
                }
                default -> scaled(shape.width, shape.height, shape.length);
            };
        }

        /** 統合版の追跡開始が遅れても、残りtickと現在位置から同じ時刻に終わるマーカーを作ります。 */
        private PacketContainer bedrockSpawn(long tick) {
            if (bedrockSpawn != null && bedrockSpawnTick == tick) return bedrockSpawn;
            float progress = progress(tick);
            long remaining = Math.max(1L, ends - tick);
            Location at = shape.origin.clone();
            float width = shape.width;
            float height = shape.height;
            float length = shape.length;
            int param = shape.param;
            switch (shape.style) {
                case APPROACH, RING_LOW, RING_HIGH -> {
                    width = shape.width + (shape.length - shape.width) * progress;
                    param = (int) remaining;
                }
                case FILL_BAR, SUPERNOVA -> param = (int) remaining;
                case STAR -> {
                    height = Math.max(0.05F, shape.height * (1F - progress));
                    param = (int) remaining;
                }
                case BULLET -> {
                    at.add(shape.travelX * progress, 0, shape.travelZ * progress);
                    param = (int) remaining;
                }
                case NOVA -> height = shape.height + shape.length * (tick - started) / 20F;
                case BEAM_LOW, BEAM_HIGH -> {
                    at.setYaw((float) currentAngle(tick) - 90.0F);
                    param = 2048 + Math.clamp(Math.round((float) omega * 20F * 8F), -2047, 2047);
                    snapshotAt = tick;
                }
                default -> { }
            }
            int entityId = PacketEntityIdAllocator.nextEntityId();
            long bits = shape.style.id | (dimension(width) << 8) | (dimension(length) << 20) | (dimension(height) << 32)
                | ((long) Math.clamp(param, 0, 0xfff) << 44) | ((long) (entityId & 0xff) << 56);
            bedrockId = entityId;
            bedrockSpawn = marker(entityId, BEDROCK_EFFECT, bits, at);
            bedrockSpawnTick = tick;
            return bedrockSpawn;
        }

        private void remove() {
            if (model != null) model.remove();
            for (Map.Entry<Player, Integer> entry : bedrock.entrySet()) {
                if (entry.getKey().isOnline()) send(entry.getKey(), destroy(entry.getValue()));
            }
            java.clear();
            bedrock.clear();
        }
    }

    private static Transformation scaled(float x, float y, float z) {
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(x, y, z), new Quaternionf());
    }

    /** @param blocks 寸法のブロック数 @return 1/32ブロック単位の12ビット値 */
    private static long dimension(float blocks) {
        return Math.clamp(Math.round(blocks * 32), 1, 0xfff);
    }

    /** パケット処理からも参照する実体の追跡情報。 */
    private static final class Carrier {
        final UUID source;
        final Set<UUID> bedrock = ConcurrentHashMap.newKeySet();
        final Set<UUID> masked = ConcurrentHashMap.newKeySet();
        final Map<UUID, Long> motion = new HashMap<>();

        Carrier(UUID source) {
            this.source = source;
        }
    }

    /** 1個体のJava表示と、環・花弁・結晶籠・星屑の累積角と開き具合を所有します。 */
    private static final class Body {
        // 環ごとの通常時の傾き・歳差・自転の速さ（度/tick）。
        private static final float[] BASE_TILT = {24F, 72F, -48F};
        private static final float[] PRECESSION_RATE = {0.9F, -0.6F, 1.4F};
        private static final float[] SPIN_RATE = {1.5F, 2.2F, -3.0F};
        private static final float[] PRECESSION_OFFSET = {0F, 90F, 45F};

        final IronGolem source;
        final Carrier carrier;
        final Set<Player> viewers = new HashSet<>();
        final List<ItemDisplay> parts = new ArrayList<>();
        final float[] precession = new float[3];
        final float[] spin = new float[3];
        final float[] tilt = BASE_TILT.clone();
        Location position;
        Pose pose = Pose.IDLE;
        boolean immune;
        float scale = 1.0F;
        long lastTick = -1L;
        long age;
        float ringScale = 1.0F;
        float lift;
        float petal;
        float cage;
        float cageSpin;
        float moteAngle;
        float moteRadius = 1.0F;
        long hurtUntil;
        long nextHurt;
        long strikeUntil;

        Body(IronGolem source, Carrier carrier, Location position) {
            this.source = source;
            this.carrier = carrier;
            this.position = position;
            for (int i = 0; i < 3; i++) precession[i] = PRECESSION_OFFSET[i];
        }

        Pose motion(long tick) {
            return tick < strikeUntil && pose != Pose.GUARD ? Pose.ATTACK : pose;
        }

        /** 姿勢に応じた速さで角度を進め、傾き・大きさ・開き具合を経過tickに比例して目標へ寄せます。 */
        void advance(long tick) {
            long elapsed = lastTick < 0 ? 0 : Math.min(20L, tick - lastTick);
            lastTick = tick;
            age += elapsed;
            Pose motion = motion(tick);
            float rate = switch (motion) {
                case CAST -> 2.5F;
                case ATTACK -> 3.2F;
                case GUARD -> 0.35F;
                case EXPOSED -> 0.4F;
                default -> 1.0F;
            };
            float slow = 1F - (float) Math.pow(0.88D, elapsed);
            float quick = 1F - (float) Math.pow(0.7D, elapsed);
            for (int i = 0; i < 3; i++) {
                precession[i] = (precession[i] + PRECESSION_RATE[i] * rate * elapsed) % 360F;
                spin[i] = (spin[i] + SPIN_RATE[i] * rate * elapsed) % 360F;
                float targetTilt = switch (motion) {
                    case ATTACK -> 0F;
                    case GUARD -> i == 2 ? 0F : 90F;
                    case EXPOSED -> new float[] {62F, 28F, 84F}[i];
                    default -> BASE_TILT[i];
                };
                tilt[i] += (targetTilt - tilt[i]) * slow;
            }
            float targetScale = switch (motion) {
                case CAST -> 1.1F;
                case ATTACK -> 1.18F;
                case GUARD -> 0.72F;
                case EXPOSED -> 1.04F;
                default -> 1.0F;
            };
            ringScale += (targetScale - ringScale) * slow;
            float targetLift = motion == Pose.EXPOSED ? -0.7F : motion == Pose.CAST ? 0.25F : 0F;
            lift += (targetLift - lift) * slow;
            float targetPetal = motion == Pose.ATTACK ? 1.0F : motion == Pose.CAST ? 0.45F : 0F;
            petal += (targetPetal - petal) * quick;
            cage += ((immune ? 1F : 0F) - cage) * slow;
            cageSpin = (cageSpin + 2.5F * elapsed) % 360F;
            moteAngle = (moteAngle + 3.5F * rate * elapsed) % 360F;
            float targetRadius = switch (motion) {
                case CAST -> 0.7F;
                case ATTACK -> 1.45F;
                case GUARD -> 0.55F;
                default -> 1.0F;
            };
            moteRadius += (targetRadius - moteRadius) * slow;
        }

        private float height() {
            float bob = (float) Math.sin(age * 0.1D) * 0.04F;
            return (CORE_HEIGHT + lift + bob) * scale;
        }

        Transformation core() {
            float beat = pose == Pose.IDLE ? (float) Math.max(0.0D, Math.sin(age * 0.35D)) * 0.05F : 0F;
            return new Transformation(new Vector3f(0F, height(), 0F), new Quaternionf(),
                new Vector3f(CORE_SCALE * scale * (1F + beat)), new Quaternionf().rotateY((float) Math.toRadians(spin[0] * 0.3F)));
        }

        Transformation part(int part) {
            if (part < PETAL_PART) return ring(part - RING_PART);
            if (part < CAGE_PART) return petal(part - PETAL_PART);
            if (part == CAGE_PART) {
                return new Transformation(new Vector3f(0F, height(), 0F), new Quaternionf()
                    .rotateY((float) Math.toRadians(cageSpin)).rotateZ((float) Math.toRadians(Math.sin(age * 0.08D) * 8.0D)),
                    new Vector3f(CORE_SCALE * scale * Math.max(0.001F, cage)), new Quaternionf());
            }
            return mote(part - MOTE_PART);
        }

        private Transformation ring(int ring) {
            // 環の平面を歳差（Y）→傾き（X）→自転（Y）の順に回す。
            Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(precession[ring]))
                .rotateX((float) Math.toRadians(tilt[ring]))
                .rotateY((float) Math.toRadians(spin[ring]));
            return new Transformation(new Vector3f(0F, height(), 0F), rotation,
                new Vector3f(RING_SCALE * ringScale * scale), new Quaternionf());
        }

        private Transformation petal(int index) {
            // 花弁は核の向きと一緒に回り、開くほど外へ押し出されて大きくなる。
            float radians = (float) Math.toRadians(spin[0] * 0.3F + index * 90F);
            float open = Math.max(0.001F, petal);
            float distance = PETAL_OFFSET * CORE_SCALE / 2F * open * scale;
            return new Transformation(new Vector3f((float) Math.sin(radians) * distance, height(), (float) Math.cos(radians) * distance),
                new Quaternionf().rotateY(radians), new Vector3f(CORE_SCALE * scale * open), new Quaternionf());
        }

        private Transformation mote(int index) {
            double radians = Math.toRadians(moteAngle + index * 90F);
            float radius = MOTE_RADIUS * moteRadius * scale;
            float bob = (float) Math.sin(age * 0.14D + index * 1.57D) * 0.1F * scale;
            return new Transformation(new Vector3f((float) Math.cos(radians) * radius, height() + bob, (float) Math.sin(radians) * radius),
                new Quaternionf(), new Vector3f(MOTE_SCALE * scale), new Quaternionf().rotateY((float) radians * 2F));
        }
    }
}
