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

/**
 * ニクセロンのアイアンゴーレムを戦闘実体のまま維持し、対応パックの閲覧者へ天球の心臓の外観と技の形状を配信します。
 *
 * <p>Java版は核と三本の環を別々のItemDisplayで描き、環の傾きと回転を補間で動かします。
 * 統合版はGeyser拡張がカスタムエンティティへ置き換え、姿勢番号と無敵状態だけを通知します。</p>
 */
final class NyxeronPackDisplay implements Listener {
    // Geyser拡張と共有する UUID マーカー（ASTNYXB1 / ASTNYXF1 / ASTNYXM1）。
    private static final long BEDROCK_BODY = 0x4153544e59584231L;
    private static final long BEDROCK_EFFECT = 0x4153544e59584631L;
    private static final long BEDROCK_MOTION = 0x4153544e59584d31L;
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    private static final int FRAMES = 4;
    private static final int UPDATE_TICKS = 5;
    private static final long HURT_COOLDOWN = 10L;
    private static final int MAX_TRANSIENTS = 64;
    private static final double VIEW_DISTANCE = 72.0D;
    private static final float CORE_HEIGHT = 2.35F;
    private static final float CORE_SCALE = 2.0F;
    private static final float RING_SCALE = 1.25F;
    private static final Runnable EMPTY_EFFECT = () -> { };
    private static final Color GUARD_TINT = Color.fromRGB(0xD7B4FF);
    private static final Color HURT_TINT = Color.fromRGB(0xFF6666);
    private static final Color EXPOSED_TINT = Color.fromRGB(0xFFF1B0);

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final NamespacedKey templateKey;
    private final PacketAdapter packets;
    private final Map<Integer, Carrier> carriers = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, List<Effect>> effects = new HashMap<>();
    private final Map<Style, List<ItemStack>> effectItems = new EnumMap<>(Style.class);
    private final Map<String, ItemStack> coreItems = new HashMap<>();
    private final ItemStack[] ringItems = {
        item("nyxeron_ring_outer", 0, null), item("nyxeron_ring_mid", 0, null), item("nyxeron_ring_inner", 0, null)};

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
     * 5tick周期で本体の姿勢と閲覧者を更新します。モデルが追跡されるまで実体は隠しません。
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
        Pose motion = tick < body.strikeUntil ? Pose.ATTACK : pose;
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
        float scale = nativeScale == null ? 1.0F : (float) nativeScale.getValue();
        body.advance(motion, tick);
        String tint = tick < body.hurtUntil ? "hurt" : immune ? "guard" : motion == Pose.EXPOSED ? "exposed" : "none";
        ItemStack core = coreItems.computeIfAbsent(motion.frame + ":" + tint, ignored -> item("nyxeron_core",
            motion.frame, switch (tint) {
                case "hurt" -> HURT_TINT;
                case "guard" -> GUARD_TINT;
                case "exposed" -> EXPOSED_TINT;
                default -> null;
            }));
        if (body.parts.isEmpty()) {
            body.parts.add(spawn(target, core, true, body.coreTransform(scale)));
            for (int ring = 0; ring < 3; ring++)
                body.parts.add(spawn(target, ringItems[ring], true, body.ringTransform(ring, scale)));
        } else {
            for (ItemDisplay part : body.parts) {
                if (!part.isValid()) {
                    removeModel(body);
                    return;
                }
            }
            ItemDisplay coreDisplay = body.parts.getFirst();
            if (!core.equals(coreDisplay.getItemStack())) coreDisplay.setItemStack(core);
            interpolate(coreDisplay, body.coreTransform(scale));
            for (int ring = 0; ring < 3; ring++) interpolate(body.parts.get(ring + 1), body.ringTransform(ring, scale));
            for (ItemDisplay part : body.parts) {
                Location previous = part.getLocation();
                if (!previous.equals(target)) {
                    part.setTeleportDuration(previous.distanceSquared(target) > 9.0D ? 0 : UPDATE_TICKS);
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

    private static void interpolate(ItemDisplay display, Transformation transformation) {
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(UPDATE_TICKS);
        display.setTransformation(transformation);
    }

    /** @param bossId 被弾した個体 @param tick 現在tick。Javaの着色と統合版の被弾を短時間にまとめます */
    void hurt(UUID bossId, long tick) {
        Body body = bodies.get(bossId);
        if (body == null || tick < body.nextHurt) return;
        body.nextHurt = tick + HURT_COOLDOWN;
        body.hurtUntil = tick + UPDATE_TICKS + 1L;
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

    /** @param bossId 攻撃を発動した個体 @param tick 現在tick */
    void strike(UUID bossId, long tick) {
        Body body = bodies.get(bossId);
        if (body != null) body.strikeUntil = tick + 10L;
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
     * 形状を個体に関連付けます。危険範囲の正確な輪郭は床の粒子が引き続き担います。
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
                Effect effect = new Effect(shape, tick, tick + duration);
                owned.add(effect);
                created.add(effect);
            }
            syncEffects(owned, tick);
            return () -> {
                for (Effect effect : created) if (owned.remove(effect)) effect.remove();
            };
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6548, exception);
            return EMPTY_EFFECT;
        }
    }

    /** @param tick 現在tick。期限切れと閲覧者を確認し、表示済みフレームを更新します */
    void animate(long tick) {
        effects.values().removeIf(owned -> {
            syncEffects(owned, tick);
            return owned.isEmpty();
        });
    }

    private void syncEffects(List<Effect> owned, long tick) {
        Iterator<Effect> iterator = owned.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            try {
                if (tick >= effect.ends) {
                    iterator.remove();
                    effect.remove();
                } else {
                    if (effect.started == tick || tick % UPDATE_TICKS == 0) effect.syncViewers(tick);
                    effect.animate(tick);
                }
            } catch (RuntimeException exception) {
                if (tick < effect.ends) iterator.remove();
                effect.remove();
                Logger.log(LogId.E_6548, exception);
            }
        }
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

    private static List<ItemStack> effectItems(Style style) {
        List<ItemStack> result = new ArrayList<>(FRAMES);
        for (int i = 0; i < FRAMES; i++) result.add(item(style.model, i, null));
        return List.copyOf(result);
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

    /** 形状IDと寸法単位は両版のパックおよびGeyser拡張で共有します。 */
    enum Style {
        DISC(0, "disc"), BAND1(1, "band_1"), BAND2(2, "band_2"), BAND3(3, "band_3"), BAND4(4, "band_4"),
        SPOKE(5, "spoke"), SEAL(6, "seal"), SEAL_DONE(7, "seal_done"), SCAR(8, "scar"), IMPACT(9, "impact"),
        GAZE(10, "gaze"), METEOR(11, "meteor"), HEART(12, "heart");

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
    }

    /** 固定原点と1ブロックを基準にした表示寸法。範囲判定には使用しません。 */
    record Shape(Style style, Location origin, float width, float height, float length) {
        /** @param style 形状 @param center 中心 @param radius 外周の半径 @param height 高さ @return 円状の表示 */
        static Shape circle(Style style, Location center, double radius, double height) {
            Location location = center.clone().add(0, 0.06D, 0);
            location.setYaw(0);
            location.setPitch(0);
            float diameter = (float) (radius * 2);
            return new Shape(style, location, diameter, (float) height, diameter);
        }

        /** @param style 形状 @param first 始点 @param last 終点 @param width 幅 @param height 高さ @return 線状の表示 */
        static Shape segment(Style style, Location first, Location last, float width, float height) {
            Vector direction = last.toVector().subtract(first.toVector()).setY(0);
            Location center = first.clone().add(direction.clone().multiply(0.5D)).add(0, 0.07D, 0);
            center.setDirection(direction);
            center.setPitch(0);
            return new Shape(style, center, width, height, (float) direction.length());
        }

        /** @param style 形状 @param origin 表示位置 @param toward 向く先 @param width 幅 @param height 高さ @return 立てた表示 */
        static Shape facing(Style style, Location origin, Location toward, float width, float height) {
            Location location = origin.clone().add(0, 0.06D, 0);
            Vector direction = toward.toVector().subtract(origin.toVector()).setY(0);
            if (direction.lengthSquared() > 0.0001D) location.setDirection(direction);
            location.setPitch(0);
            return new Shape(style, location, width, height, width);
        }
    }

    /** 1つの追加演出の閲覧者、フレーム、寿命を所有します。 */
    private final class Effect {
        private final Shape shape;
        private final long started;
        private final long ends;
        private final Set<Player> java = new HashSet<>();
        private final Set<Player> bedrock = new HashSet<>();
        private ItemDisplay model;
        private PacketContainer spawnPacket;
        private PacketContainer removePacket;
        private int frame = -1;

        private Effect(Shape shape, long started, long ends) {
            this.shape = shape;
            this.started = started;
            this.ends = ends;
        }

        private void syncViewers(long tick) {
            Location position = shape.origin;
            if (position.getWorld() == null
                || !position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) return;
            var nearby = position.getWorld().getNearbyPlayers(position, VIEW_DISTANCE);
            if (model != null && !model.isValid()) {
                model = null;
                java.clear();
            }
            java.removeIf(viewer -> {
                if (nearby.contains(viewer) && packs.hasJavaNyxeronPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline() && model != null) viewer.hideEntity(plugin, model);
                return true;
            });
            bedrock.removeIf(viewer -> {
                if (nearby.contains(viewer) && packs.hasBedrockNyxeronPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline()) send(viewer, removePacket);
                return true;
            });
            for (Player viewer : nearby) {
                UUID id = viewer.getUniqueId();
                if (packs.hasJavaNyxeronPresentation(id)) {
                    if (model == null) {
                        frame = frameAt(tick);
                        model = spawn(position, effectItems.computeIfAbsent(shape.style, NyxeronPackDisplay::effectItems)
                            .get(frame), true, new Transformation(new Vector3f(), new Quaternionf(),
                            new Vector3f(shape.width, shape.height, shape.length), new Quaternionf()));
                    }
                    if (java.add(viewer)) viewer.showEntity(plugin, model);
                } else if (packs.hasBedrockNyxeronPresentation(id) && !bedrock.contains(viewer)) {
                    if (spawnPacket == null) {
                        int entityId = PacketEntityIdAllocator.nextEntityId();
                        long bits = (ThreadLocalRandom.current().nextLong() & ~0xfff_ffff_ffffL) | shape.style.id
                            | (dimension(shape.width) << 8) | (dimension(shape.length) << 20) | (dimension(shape.height) << 32);
                        spawnPacket = marker(entityId, BEDROCK_EFFECT, bits, position);
                        removePacket = destroy(entityId);
                    }
                    if (send(viewer, spawnPacket)) bedrock.add(viewer);
                }
            }
            if (java.isEmpty() && model != null) {
                model.remove();
                model = null;
            }
        }

        private void animate(long tick) {
            int next = frameAt(tick);
            if (model == null || !model.isValid() || frame == next) return;
            model.setItemStack(effectItems.get(shape.style).get(next));
            frame = next;
        }

        /** @return 衝撃と星墜は経過で進み、それ以外は脈動を繰り返す4枚のうち表示中の番号 */
        private int frameAt(long tick) {
            long age = Math.max(0, tick - started);
            return switch (shape.style) {
                case IMPACT -> (int) Math.min(FRAMES - 1, age / 3);
                case METEOR -> (int) Math.min(FRAMES - 1, age * FRAMES / Math.max(1, ends - started));
                default -> (int) (age / 4 % FRAMES);
            };
        }

        private void remove() {
            if (model != null) model.remove();
            for (Player viewer : bedrock) if (viewer.isOnline()) send(viewer, removePacket);
            java.clear();
            bedrock.clear();
        }
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

    /** 1個体のJava表示と、環の傾き・回転の累積角を所有します。 */
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
        long lastTick = -1L;
        float ringScale = 1.0F;
        float lift;
        long hurtUntil;
        long nextHurt;
        long strikeUntil;

        Body(IronGolem source, Carrier carrier, Location position) {
            this.source = source;
            this.carrier = carrier;
            this.position = position;
            for (int i = 0; i < 3; i++) precession[i] = PRECESSION_OFFSET[i];
        }

        /** 姿勢に応じた速さで角度を進め、傾き・大きさ・高さを目標へ寄せます。 */
        void advance(Pose pose, long tick) {
            long elapsed = lastTick < 0 ? 0 : Math.min(20L, tick - lastTick);
            lastTick = tick;
            float rate = switch (pose) {
                case CAST -> 2.5F;
                case ATTACK -> 3.2F;
                case GUARD -> 0.35F;
                case EXPOSED -> 0.4F;
                default -> 1.0F;
            };
            for (int i = 0; i < 3; i++) {
                precession[i] = (precession[i] + PRECESSION_RATE[i] * rate * elapsed) % 360F;
                spin[i] = (spin[i] + SPIN_RATE[i] * rate * elapsed) % 360F;
                float targetTilt = switch (pose) {
                    case ATTACK -> 0F;
                    case GUARD -> i == 2 ? 0F : 90F;
                    case EXPOSED -> new float[] {62F, 28F, 84F}[i];
                    default -> BASE_TILT[i];
                };
                tilt[i] += (targetTilt - tilt[i]) * 0.4F;
            }
            float targetScale = switch (pose) {
                case CAST -> 1.1F;
                case ATTACK -> 1.18F;
                case GUARD -> 0.72F;
                case EXPOSED -> 1.04F;
                default -> 1.0F;
            };
            ringScale += (targetScale - ringScale) * 0.5F;
            float targetLift = pose == Pose.EXPOSED ? -0.7F : pose == Pose.CAST ? 0.25F : 0F;
            lift += (targetLift - lift) * 0.5F;
        }

        Transformation coreTransform(float scale) {
            return new Transformation(new Vector3f(0F, (CORE_HEIGHT + lift) * scale, 0F), new Quaternionf(),
                new Vector3f(CORE_SCALE * scale), new Quaternionf().rotateY((float) Math.toRadians(spin[0] * 0.3F)));
        }

        Transformation ringTransform(int ring, float scale) {
            // 環の平面を歳差（Y）→傾き（X）→自転（Y）の順に回す。
            Quaternionf rotation = new Quaternionf()
                .rotateY((float) Math.toRadians(precession[ring]))
                .rotateX((float) Math.toRadians(tilt[ring]))
                .rotateY((float) Math.toRadians(spin[ring]));
            return new Transformation(new Vector3f(0F, (CORE_HEIGHT + lift) * scale, 0F), rotation,
                new Vector3f(RING_SCALE * ringScale * scale), new Quaternionf());
        }
    }
}
