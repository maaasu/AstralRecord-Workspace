package io.github.maaasu.astralRecord.feature.boss.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
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
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.Witch;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
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

/** セレネアのウィッチを戦闘実体のまま維持し、対応パックの閲覧者へ外観だけを配信します。 */
final class SeleneaPackDisplay implements Listener {
    // Geyser拡張と共有。実体IDと当たり判定は変更しない。
    private static final long BEDROCK_BODY = 0x41535453454c4231L;
    private static final long BEDROCK_EFFECT = 0x41535453454c4631L;
    private static final long BEDROCK_MOTION = 0x41535453454c4d31L;
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    private static final int FRAMES = 4;
    private static final int BODY_POSES = 7;
    private static final float COMBAT_SCALE = 1.25F;
    private static final int UPDATE_TICKS = 5;
    private static final long HURT_COOLDOWN = 10L;
    private static final Runnable EMPTY_EFFECT = () -> { };
    private static final int MAX_TRANSIENTS = 48;
    private static final double VIEW_DISTANCE = 64.0D;
    private static final ItemStack EMPTY_ITEM = ItemStack.of(Material.AIR);
    private static final Color IMMUNE_TINT = Color.fromRGB(0xFF97FF);
    private static final Color HURT_TINT = Color.fromRGB(0xFF6666);

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final NamespacedKey templateKey;
    private final PacketAdapter packets;
    private final Map<Integer, Carrier> carriers = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final Map<UUID, List<Effect>> effects = new HashMap<>();
    private final Map<Style, List<ItemStack>> effectItems = new EnumMap<>(Style.class);
    private final List<List<ItemStack>> bodyItems;

    /** @param plugin 登録と表示を所有するプラグイン @param packs 接続ごとの適用状態 */
    SeleneaPackDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        this.templateKey = new NamespacedKey(plugin, "mob_template_id");
        this.bodyItems = List.of(bodyItems(null), bodyItems(IMMUNE_TINT), bodyItems(HURT_TINT));
        this.packets = new PacketAdapter(plugin, PacketType.Play.Server.BUNDLE, PacketType.Play.Server.SPAWN_ENTITY,
            PacketType.Play.Server.ENTITY_METADATA, PacketType.Play.Server.ENTITY_EQUIPMENT) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (carriers.isEmpty()) return;
                try {
                    PacketContainer original = event.getPacket();
                    PacketContainer updated = rewrite(event.getPlayer().getUniqueId(), original);
                    if (updated != original) event.setPacket(updated);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6514, exception);
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
     * @param event 追跡開始。別のウィッチは登録しません
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        if (event.getEntity() instanceof Witch source && SeleneaEncounterController.BOSS_ID.equals(
            source.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING))) carrier(source);
    }

    /**
     * 再追跡では姿勢・装備を初期状態から同期できるようにします。
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

    /** @param source セレネアのウィッチ @return entity IDと元のUUIDを照合する追跡記録 */
    private Carrier carrier(Witch source) {
        return carriers.compute(source.getEntityId(), (id, existing) -> existing != null
            && existing.source.equals(source.getUniqueId()) ? existing : new Carrier(source.getUniqueId()));
    }

    /**
     * 既存の5tick周期で、本体の姿勢と閲覧者を更新します。モデルの追跡前は実体を隠しません。
     * @param bossId ボスのインスタンスID @param source 有効なウィッチ
     * @param tick 共通時計 @param pose 主呪文の姿勢 @param immune 無敵中ならtrue
     */
    void body(UUID bossId, Witch source, long tick, Pose pose, boolean immune) {
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
        boolean moving = body.position.distanceSquared(target) > 0.04D || source.getVelocity().lengthSquared() > 0.01D;
        body.position = target;
        Pose motion = tick < body.strikeUntil ? Pose.ATTACK : pose != Pose.IDLE ? pose : moving ? Pose.MOVE : Pose.IDLE;
        int mode = motion.bedrock | (immune ? 0x100 : 0);
        Set<Player> tracked = Set.copyOf(source.getTrackedBy());
        Set<Player> java = new HashSet<>();
        for (Player viewer : tracked) {
            UUID id = viewer.getUniqueId();
            if (packs.hasJavaSeleneaPresentation(id)) java.add(viewer);
            if (!packs.hasBedrockSeleneaPresentation(id)) continue;
            if (!body.carrier.bedrock.contains(id)) {
                // パック準備通知が最初の追跡より遅い場合だけ、通常のスポーンを再送させる。
                viewer.hideEntity(plugin, source);
                viewer.showEntity(plugin, source);
            }
            if (body.carrier.bedrock.contains(id) && !Integer.valueOf(mode).equals(body.carrier.motion.get(id))
                    && sendMotion(viewer, source.getEntityId(), target, mode)) body.carrier.motion.put(id, mode);
        }
        if (body.model != null && !body.model.isValid()) removeModel(body);
        if (body.model == null && java.isEmpty()) return;
        int tint = tick < body.hurtUntil ? 2 : immune ? 1 : 0;
        ItemStack item = bodyItems.get(tint).get(motion.frame(tick));
        var nativeScale = source.getAttribute(Attribute.SCALE);
        float visualScale = nativeScale == null ? 1.0F : (float) nativeScale.getValue() / COMBAT_SCALE;
        if (body.model == null) {
            body.model = spawn(target, item, false, new Vector3f(visualScale));
            body.visualScale = visualScale;
        } else if (body.visualScale != visualScale) {
            body.model.setTransformation(new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(visualScale), new Quaternionf()));
            body.visualScale = visualScale;
        }
        if (body.item != item) {
            body.item = item;
            body.model.setItemStack(item);
        }
        boolean glowing = source.isGlowing() || immune;
        if (body.model.isGlowing() != glowing) body.model.setGlowing(glowing);
        if (body.immune != immune) {
            body.immune = immune;
            body.model.setGlowColorOverride(immune ? IMMUNE_TINT : null);
        }
        Location previous = body.model.getLocation();
        if (!previous.equals(target)) {
            body.model.setTeleportDuration(previous.distanceSquared(target) > 9.0D ? 0 : UPDATE_TICKS);
            body.model.teleport(target);
        }

        Iterator<Player> previousViewers = body.viewers.iterator();
        while (previousViewers.hasNext()) {
            Player viewer = previousViewers.next();
            if (java.contains(viewer)) continue;
            if (body.carrier.masked.remove(viewer.getUniqueId()) && tracked.contains(viewer)) sendMask(viewer, source, false);
            if (viewer.isOnline()) viewer.hideEntity(plugin, body.model);
            previousViewers.remove();
        }
        Set<Player> modelTracked = body.model.getTrackedBy();
        for (Player viewer : java) {
            if (body.viewers.add(viewer)) viewer.showEntity(plugin, body.model);
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
            Logger.log(LogId.E_6514, exception);
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

    /** @param bossId Javaの代替モデルを解除する個体ID */
    void clearBody(UUID bossId) {
        Body body = bodies.remove(bossId);
        if (body != null) removeModel(body);
    }

    /** @param body 実体を通常のメタデータへ戻し、代替モデルを回収する追跡記録 */
    private void removeModel(Body body) {
        if (body.model == null) return;
        for (Player viewer : body.source.getTrackedBy()) {
            if (body.carrier.masked.remove(viewer.getUniqueId())) sendMask(viewer, body.source, false);
        }
        body.carrier.masked.clear();
        body.model.remove();
        body.model = null;
        body.item = null;
        body.immune = false;
        body.viewers.clear();
    }

    /** @param active 現在確認できたボスのID */
    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(bodies.keySet())) if (!active.contains(id)) clear(id);
        carriers.entrySet().removeIf(entry -> plugin.getServer().getEntity(entry.getValue().source) == null);
    }

    /**
     * 表示を個体に関連付けます。警告の正確な輪郭は既存の床粒子が引き続き担います。
     * @param bossId 所有個体 @param shapes 追加表示する形状 @param tick 開始tick @param duration 表示tick数
     * @return 詠唱中断時に、この呼び出しで作った演出だけを解除する処理
     */
    Runnable effects(UUID bossId, List<Shape> shapes, long tick, long duration) {
        if (shapes.isEmpty()) return EMPTY_EFFECT;
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
    }

    /** @param tick 現在tick。期限切れと閲覧者を確認し、表示済みフレームを更新します */
    void animate(long tick) {
        effects.values().removeIf(owned -> {
            syncEffects(owned, tick);
            return owned.isEmpty();
        });
    }

    /** @param owned 同じボスの演出群 @param tick 現在tick */
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
                Logger.log(LogId.E_6514, exception);
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
        if (type == PacketType.Play.Server.SPAWN_ENTITY && packs.hasBedrockSeleneaPresentation(viewer)
                && carrier.source.equals(packet.getUUIDs().readSafely(0))) {
            PacketContainer copy = packet.shallowClone();
            copy.getUUIDs().write(0, new UUID(BEDROCK_BODY, carrier.source.getLeastSignificantBits()));
            carrier.bedrock.add(viewer);
            return copy;
        }
        if (!carrier.masked.contains(viewer)) return packet;
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
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
        } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> original = packet.getSlotStackPairLists().readSafely(0);
            if (original == null) return packet;
            PacketContainer copy = packet.shallowClone();
            copy.getSlotStackPairLists().write(0, original.stream()
                .map(pair -> new Pair<>(pair.getFirst(), EMPTY_ITEM)).toList());
            return copy;
        }
        return packet;
    }

    /** @param flags 実体の共有フラグ @return 無色の実体だけを不可視にするクライアント用フラグ */
    private static byte maskedFlags(byte flags) {
        return (byte) ((flags | INVISIBLE) & ~GLOWING);
    }

    /**
     * モデルが確認できた閲覧者の描画だけ切り替えます。
     * @param viewer 宛先 @param source 戦闘実体 @param hide 代替モデルで表示するならtrue @return 送信成功
     */
    private boolean sendMask(Player viewer, Witch source, boolean hide) {
        try {
            for (WrappedDataValue value : WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()) {
                if (value.getIndex() != 0 || !(value.getValue() instanceof Byte flags)) continue;
                PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
                packet.getIntegers().write(0, source.getEntityId());
                packet.getDataValueCollectionModifier().write(0, List.of(new WrappedDataValue(0,
                    WrappedDataWatcher.Registry.get((Type) Byte.class), hide ? maskedFlags(flags) : flags)));
                if (!send(viewer, packet)) return false;
                var equipment = source.getEquipment();
                if (equipment != null) {
                    viewer.sendEquipmentChange(source, Map.of(
                        EquipmentSlot.HAND, hide ? EMPTY_ITEM : equipment.getItemInMainHand(),
                        EquipmentSlot.OFF_HAND, hide ? EMPTY_ITEM : equipment.getItemInOffHand(),
                        EquipmentSlot.HEAD, hide ? EMPTY_ITEM : equipment.getItem(EquipmentSlot.HEAD),
                        EquipmentSlot.CHEST, hide ? EMPTY_ITEM : equipment.getItem(EquipmentSlot.CHEST),
                        EquipmentSlot.LEGS, hide ? EMPTY_ITEM : equipment.getItem(EquipmentSlot.LEGS),
                        EquipmentSlot.FEET, hide ? EMPTY_ITEM : equipment.getItem(EquipmentSlot.FEET)));
                }
                return true;
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6514, exception);
        }
        return false;
    }

    /** @param viewer 宛先 @param id 戦闘実体ID @param position 本体位置 @param mode 姿勢と無敵の値 @return 送信成功 */
    private boolean sendMotion(Player viewer, int id, Location position, int mode) {
        int packetId = PacketEntityIdAllocator.nextEntityId();
        long bits = Integer.toUnsignedLong(id) | ((long) mode << 32);
        boolean sent = send(viewer, marker(packetId, BEDROCK_MOTION, bits, position));
        send(viewer, destroy(packetId));
        return sent;
    }

    /** @param id 仮想実体ID @param marker 通知種別 @param bits 共有契約のデータ @param location 表示原点 @return スポーン通知 */
    private PacketContainer marker(int id, long marker, long bits, Location location) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, id);
        packet.getUUIDs().write(0, new UUID(marker, bits));
        packet.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().write(0, location.getX()).write(1, location.getY()).write(2, location.getZ());
        packet.getBytes().writeSafely(1, (byte) Math.round(location.getYaw() * 256.0F / 360.0F));
        return packet;
    }

    /** @param id 仮想実体ID @return 表示を回収する通知 */
    private PacketContainer destroy(int id) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().writeSafely(0, List.of(id));
        packet.getIntegerArrays().writeSafely(0, new int[]{id});
        return packet;
    }

    /** @param viewer 宛先 @param packet 作成済み通知 @return 送信成功 */
    private boolean send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet, false);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6514, exception);
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

    /** @param tint 差し色またはnull @return 姿勢ごとの本体アイテム */
    private static List<ItemStack> bodyItems(Color tint) {
        List<ItemStack> result = new ArrayList<>(BODY_POSES);
        for (int i = 0; i < BODY_POSES; i++) result.add(item("selenea", i, tint));
        return List.copyOf(result);
    }

    /** @param style 形状 @return 4フレーム分のアイテム */
    private static List<ItemStack> effectItems(Style style) {
        List<ItemStack> result = new ArrayList<>(FRAMES);
        for (int i = 0; i < FRAMES; i++) result.add(item(style.model, i, null));
        return List.copyOf(result);
    }

    /** @param position 表示原点 @param item モデル @param bright 明るさ固定 @param dimensions 拡大率 @return 閲覧者限定表示 */
    private ItemDisplay spawn(Location position, ItemStack item, boolean bright, Vector3f dimensions) {
        return position.getWorld().spawn(position, ItemDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setItemStack(item);
            display.setViewRange(2);
            if (bright) display.setBrightness(new Display.Brightness(15, 15));
            display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), dimensions, new Quaternionf()));
        });
    }

    /** 統合版クライアントの姿勢番号とJava版の差し替えフレームを対応付けます。 */
    enum Pose {
        IDLE(0), MOVE(1), CAST(2), ATTACK(3), RECOVERY(4);

        private final int bedrock;
        Pose(int bedrock) { this.bedrock = bedrock; }

        /** @param tick 共通時計 @return Javaアイテムのフレーム番号 */
        int frame(long tick) {
            int phase = (int) (tick / UPDATE_TICKS % 2);
            return switch (this) {
                case IDLE -> 0;
                case MOVE -> 1 + phase;
                case CAST -> 3 + phase;
                case ATTACK -> 5;
                case RECOVERY -> 6;
            };
        }
    }

    /** 形状IDと寸法単位は両版のパックおよびGeyser拡張で共有します。 */
    enum Style {
        CIRCLE(0, "warning_circle"), LINE(1, "warning_line"), BOUNDARY(2, "warning_boundary"),
        SAFE(3, "warning_safe"), IMPACT(4, "impact"), IMPACT_LINE(5, "impact_line"),
        WAVE(6, "wave"), BOLT(7, "bolt"), EDGE(8, "warning_edge"),
        GRIMOIRE(9, "warning_grimoire"), GRIMOIRE_IMPACT(10, "impact_grimoire");

        private final int id;
        private final String model;

        Style(int id, String suffix) { this.id = id; this.model = "selenea_" + suffix; }
    }

    /** 固定原点と1ブロックを基準にした表示寸法。範囲判定には使用しません。 */
    record Shape(Style style, Location origin, float width, float height, float length) {
        /** @param style 形状 @param center 中心 @param radius 半径 @param height 高さ @return 円状の表示 */
        static Shape circle(Style style, Location center, double radius, double height) {
            Location location = center.clone().add(0, 0.08D, 0);
            location.setYaw(0);
            location.setPitch(0);
            float diameter = (float) (radius * 2);
            return new Shape(style, location, diameter, (float) height, diameter);
        }

        /** @param style 形状 @param first 始点 @param last 終点 @param width 幅 @param height 高さ @return 線状の表示 */
        static Shape segment(Style style, Location first, Location last, float width, float height) {
            Vector direction = last.toVector().subtract(first.toVector()).setY(0);
            Location center = first.clone().add(direction.clone().multiply(0.5D)).add(0, 0.08D, 0);
            center.setDirection(direction);
            return new Shape(style, center, width, height, (float) direction.length());
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

        /** @param tick 追跡開始時に必要なフレームの時計 */
        private void syncViewers(long tick) {
            Location position = shape.origin;
            if (!SeleneaArena.loaded(position)) return;
            var nearby = position.getWorld().getNearbyPlayers(position, VIEW_DISTANCE);
            if (model != null && !model.isValid()) {
                model = null;
                java.clear();
            }
            java.removeIf(viewer -> {
                if (nearby.contains(viewer) && packs.hasJavaSeleneaPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline() && model != null) viewer.hideEntity(plugin, model);
                return true;
            });
            bedrock.removeIf(viewer -> {
                if (nearby.contains(viewer) && packs.hasBedrockSeleneaPresentation(viewer.getUniqueId())) return false;
                if (viewer.isOnline()) send(viewer, removePacket);
                return true;
            });
            for (Player viewer : nearby) {
                UUID id = viewer.getUniqueId();
                if (packs.hasJavaSeleneaPresentation(id)) {
                    if (model == null) {
                        frame = frameAt(tick);
                        model = spawn(position, effectItems.computeIfAbsent(shape.style, SeleneaPackDisplay::effectItems).get(frame),
                            true, new Vector3f(shape.width, shape.height, shape.length));
                    }
                    if (java.add(viewer)) viewer.showEntity(plugin, model);
                } else if (packs.hasBedrockSeleneaPresentation(id) && !bedrock.contains(viewer)) {
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

        /** @param tick 共通時計。フレームが変わったJava表示だけを更新します */
        private void animate(long tick) {
            int next = frameAt(tick);
            if (model == null || !model.isValid() || frame == next) return;
            model.setItemStack(effectItems.get(shape.style).get(next));
            frame = next;
        }

        /** @param tick 共通時計 @return 4枚のうち表示中の番号 */
        private int frameAt(long tick) {
            long age = Math.max(0, tick - started);
            return switch (shape.style) {
                case BOUNDARY, EDGE -> 0;
                case IMPACT, IMPACT_LINE, GRIMOIRE_IMPACT, WAVE -> (int) Math.min(FRAMES - 1, age / 2);
                default -> (int) (age / 3 % FRAMES);
            };
        }

        /** 両エディションの表示を回収します。 */
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

    /** パケット処理からも参照するウィッチの追跡情報。 */
    private static final class Carrier {
        final UUID source;
        final Set<UUID> bedrock = ConcurrentHashMap.newKeySet();
        final Set<UUID> masked = ConcurrentHashMap.newKeySet();
        final Map<UUID, Integer> motion = new HashMap<>();

        Carrier(UUID source) { this.source = source; }
    }

    /** 1個体のJava表示と短時間の姿勢を所有します。 */
    private static final class Body {
        final Witch source;
        final Carrier carrier;
        final Set<Player> viewers = new HashSet<>();
        Location position;
        ItemDisplay model;
        ItemStack item;
        boolean immune;
        float visualScale;
        long hurtUntil;
        long nextHurt;
        long strikeUntil;

        Body(Witch source, Carrier carrier, Location position) {
            this.source = source;
            this.carrier = carrier;
            this.position = position;
        }
    }
}
