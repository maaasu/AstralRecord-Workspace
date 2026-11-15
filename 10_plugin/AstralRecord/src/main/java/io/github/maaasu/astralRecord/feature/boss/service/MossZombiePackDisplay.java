package io.github.maaasu.astralRecord.feature.boss.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.mob.skill.middleearth.MossZombieMotion;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import io.papermc.paper.event.player.PlayerTrackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
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
import org.jetbrains.annotations.Nullable;

import java.lang.reflect.Type;
import java.util.ArrayList;
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
 * 遺跡ボスの実体と通常攻撃は維持し、対応パックにだけ外観を出します。
 * Mob スキルが要求した攻撃姿勢を、Java は姿勢モデル、統合版は {@code astralrecord:motion} で表示します。
 */
final class MossZombiePackDisplay implements Listener {
    static final String BOSS_ID = "all_things_el";
    // Geyser 拡張と共有する表示専用 UUID マーカー。実体の entity ID は変えない。
    private static final long BEDROCK_MARKER = 0x4153544D4F535331L;
    // ASTMOSM1。統合版の攻撃姿勢を Geyser 拡張へ渡す一時 TextDisplay の UUID 上位64bit。
    private static final long BEDROCK_MOTION = 0x4153544D4F534D31L;
    // ASTMOSF1。技の演出を Geyser 拡張へ渡す TextDisplay の UUID 上位64bit。下位は形状・幅・長さ・高さ。
    private static final long BEDROCK_EFFECT = 0x4153544D4F534631L;
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    private static final int STEP_TICKS = 5;
    private static final int EFFECT_FRAMES = 4;
    private static final int MAX_EFFECTS = 48;
    private static final double EFFECT_VIEW_DISTANCE = 64.0D;
    private final Plugin plugin;
    private final ResourcePackService packs;
    private final NamespacedKey templateKey;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final Map<Integer, Tracking> tracking = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final List<ItemStack> poses = List.of(item(null), item("walk_a"), item("walk_b"));
    private final Map<MossZombieMotion.Pose, ItemStack> attackPoses = attackItems();
    private final Map<MossZombieMotion.EffectStyle, List<ItemStack>> effectItems = effectItems();
    private final Map<UUID, List<Effect>> effects = new HashMap<>();
    private final PacketAdapter packets;
    private @Nullable MossZombieMotion motion;
    private long clock;

    MossZombiePackDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        this.templateKey = new NamespacedKey(plugin, "mob_template_id");
        packets = new PacketAdapter(plugin, PacketType.Play.Server.BUNDLE,
            PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.ENTITY_METADATA) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (tracking.isEmpty()) return;
                try {
                    PacketContainer old = event.getPacket();
                    PacketContainer updated = rewrite(event.getPlayer(), old);
                    if (old != updated) event.setPacket(updated);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_6515, exception);
                }
            }
        };
    }

    /** @param motion Mob スキルと共有する攻撃姿勢と演出。null なら待機・歩行だけを表示します */
    void setMotion(@Nullable MossZombieMotion motion) {
        if (this.motion != null) this.motion.setEffectSink(null);
        this.motion = motion;
        if (motion != null) motion.setEffectSink(this::showEffects);
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        protocol.addPacketListener(packets);
        if (motion != null) motion.setEffectSink(this::showEffects);
    }

    void stop() {
        for (UUID id : List.copyOf(bodies.keySet())) clear(id);
        for (UUID id : List.copyOf(effects.keySet())) clearEffects(id);
        protocol.removePacketListener(packets);
        HandlerList.unregisterAll(this);
        tracking.clear();
        if (motion != null) {
            motion.setEffectSink(null);
            motion.clearAll();
        }
    }

    /**
     * Mob スキルの演出を所有個体に関連付けます。危険範囲の正確な形は従来の床粒子も示します。
     * @param bossId 所有個体 @param shapes 表示する演出 @param duration 表示tick数
     * @return この呼び出しで作った演出だけを解除する処理
     */
    private Runnable showEffects(UUID bossId, List<MossZombieMotion.Effect> shapes, long duration) {
        List<Effect> owned = effects.computeIfAbsent(bossId, ignored -> new ArrayList<>());
        List<Effect> created = new ArrayList<>();
        for (MossZombieMotion.Effect shape : shapes) {
            if (owned.size() >= MAX_EFFECTS) break;
            Effect effect = new Effect(shape, clock, clock + duration);
            owned.add(effect);
            created.add(effect);
        }
        syncEffects(owned, clock);
        return () -> {
            for (Effect effect : created) if (owned.remove(effect)) effect.remove();
        };
    }

    /** @param tick 毎tickの共通時計。期限切れと閲覧者を確認し、Java版のフレームを進めます */
    void animate(long tick) {
        clock = tick;
        effects.values().removeIf(owned -> {
            syncEffects(owned, tick);
            return owned.isEmpty();
        });
    }

    /** @param bossId 破棄・帰還した個体の演出をすべて回収します */
    private void clearEffects(UUID bossId) {
        List<Effect> owned = effects.remove(bossId);
        if (owned != null) for (Effect effect : owned) effect.remove();
    }

    /** @param owned 同じ個体の演出群 @param tick 現在tick */
    private void syncEffects(List<Effect> owned, long tick) {
        Iterator<Effect> iterator = owned.iterator();
        while (iterator.hasNext()) {
            Effect effect = iterator.next();
            try {
                if (tick >= effect.ends) {
                    iterator.remove();
                    effect.remove();
                } else {
                    if (effect.started == tick || tick % STEP_TICKS == 0) effect.syncViewers(tick);
                    effect.animate(tick);
                }
            } catch (RuntimeException exception) {
                iterator.remove();
                effect.remove();
                Logger.log(LogId.E_6515, exception);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        Entity source = event.getEntity();
        if (source.getType() != EntityType.ZOMBIE || !BOSS_ID.equals(
            source.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING))) return;
        // 新しい統合版エンティティは姿勢0で生成されるため、次の更新で現在の姿勢を送り直す。
        track(source).bedrockMotion.remove(event.getPlayer().getUniqueId());
    }

    private Tracking track(Entity source) {
        UUID uuid = source.getUniqueId();
        return tracking.compute(source.getEntityId(), (id, previous) -> previous != null && previous.source.equals(uuid)
            ? previous : new Tracking(uuid));
    }

    void update(UUID bossId, Entity source, long tick) {
        if (source.getType() != EntityType.ZOMBIE) return;
        Body body = bodies.get(bossId);
        if (body != null && (body.source != source || body.world != source.getWorld())) {
            clear(bossId);
            body = null;
        }
        Tracking state = track(source);
        if (body == null) {
            body = new Body(source, state);
            bodies.put(bossId, body);
        }
        Set<Player> sourceViewers = source.getTrackedBy();
        Set<Player> suitable = new HashSet<>();
        for (Player viewer : sourceViewers) {
            if (javaReady(viewer)) suitable.add(viewer);
        }
        if (body.model != null && !body.model.isValid()) {
            body.viewers.clear();
            body.model = null;
        }
        Location now = source.getLocation();
        now.setPitch(0.0F);
        Location last = body.position;
        body.position = now;
        boolean moving = last != null && last.distanceSquared(now) > 0.008D || source.getVelocity().lengthSquared() > 0.014D;
        MossZombieMotion.Pose attack = motion == null ? MossZombieMotion.Pose.NONE : motion.current(bossId);
        // 0～2は待機・歩行、3以降は攻撃姿勢。攻撃姿勢中は歩行の足踏みより優先する。
        int pose = attack != MossZombieMotion.Pose.NONE ? 2 + attack.bedrock()
            : moving ? 1 + (int) (tick / STEP_TICKS % 2L) : 0;
        if (body.model == null && !suitable.isEmpty()) {
            body.model = source.getWorld().spawn(now, ItemDisplay.class, display -> {
                display.setVisibleByDefault(false);
                display.setPersistent(false);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setItemStack(item(pose));
                display.setViewRange(2.0F);
            });
            body.pose = pose;
        }
        if (body.model != null) {
            ItemDisplay display = body.model;
            if (body.pose != pose) {
                display.setItemStack(item(pose));
                body.pose = pose;
            }
            if (display.isGlowing() != source.isGlowing()) display.setGlowing(source.isGlowing());
            Location previous = display.getLocation();
            if (!previous.equals(now)) {
                display.setTeleportDuration(previous.distanceSquared(now) > 9.0D ? 0 : STEP_TICKS);
                display.teleport(now);
            }
            body.viewers.removeIf(viewer -> {
                if (suitable.contains(viewer)) return false;
                if (viewer.isOnline()) viewer.hideEntity(plugin, display);
                return true;
            });
            for (Player viewer : suitable) {
                if (body.viewers.add(viewer)) viewer.showEntity(plugin, display);
            }
        }
        Set<UUID> stillTracking = new HashSet<>();
        long bedrockState = BossBedrockMotion.state(attack.bedrock(), false, now.getYaw());
        for (Player viewer : sourceViewers) {
            UUID viewerId = viewer.getUniqueId();
            stillTracking.add(viewerId);
            if (isBedrock(viewer)) {
                int sent = state.bedrockMotion.getOrDefault(viewerId, MossZombieMotion.Pose.NONE.bedrock());
                if (sent != attack.bedrock() && sendMotion(viewer, source.getEntityId(), now, bedrockState)) {
                    state.bedrockMotion.put(viewerId, attack.bedrock());
                }
                continue;
            }
            boolean hide = suitable.contains(viewer) && body.model != null && body.model.getTrackedBy().contains(viewer);
            if (hide) {
                if (state.masked.add(viewerId) && !sendFlags(viewer, source, true)) state.masked.remove(viewerId);
            } else if (state.masked.contains(viewerId) && sendFlags(viewer, source, false)) {
                state.masked.remove(viewerId);
            }
        }
        state.masked.retainAll(stillTracking);
        state.bedrockMotion.keySet().retainAll(stillTracking);
        if (suitable.isEmpty() && body.model != null) {
            body.model.remove();
            body.model = null;
        }
    }

    void retain(Set<UUID> active) {
        for (UUID id : List.copyOf(bodies.keySet())) {
            if (!active.contains(id)) clear(id);
        }
        for (UUID id : List.copyOf(effects.keySet())) {
            if (!active.contains(id)) clear(id);
        }
        tracking.entrySet().removeIf(e -> plugin.getServer().getEntity(e.getValue().source) == null);
    }

    void clear(UUID bossId) {
        if (motion != null) motion.clear(bossId);
        clearEffects(bossId);
        Body body = bodies.remove(bossId);
        if (body == null) return;
        tracking.remove(body.source.getEntityId(), body.tracking);
        if (body.model != null) body.model.remove();
        if (body.source.isValid()) {
            for (Player viewer : body.source.getTrackedBy()) {
                if (body.tracking.masked.contains(viewer.getUniqueId())) sendFlags(viewer, body.source, false);
            }
        }
    }

    private PacketContainer rewrite(Player viewer, PacketContainer packet) {
        if (packet.getType() == PacketType.Play.Server.BUNDLE) {
            Iterable<PacketContainer> children = packet.getPacketBundles().readSafely(0);
            if (children == null) return packet;
            List<PacketContainer> result = new ArrayList<>();
            boolean changed = false;
            for (PacketContainer child : children) {
                PacketContainer updated = rewrite(viewer, child);
                result.add(updated);
                changed |= updated != child;
                if (child.getType() == PacketType.Play.Server.SPAWN_ENTITY) {
                    Integer id = child.getIntegers().readSafely(0);
                    Tracking track = id == null ? null : tracking.get(id);
                    if (track != null && track.source.equals(child.getUUIDs().readSafely(0))
                            && track.masked.contains(viewer.getUniqueId()) && javaReady(viewer)) {
                        var serializer = WrappedDataWatcher.Registry.get((Type) Byte.class);
                        result.add(metadata(id, new WrappedDataValue(0, serializer, (byte) INVISIBLE)));
                        changed = true;
                    }
                }
            }
            if (!changed) return packet;
            PacketContainer out = packet.shallowClone();
            out.getPacketBundles().write(0, result);
            return out;
        }
        Integer id = packet.getIntegers().readSafely(0);
        Tracking track = id == null ? null : tracking.get(id);
        if (track == null) return packet;
        if (packet.getType() == PacketType.Play.Server.SPAWN_ENTITY && isBedrock(viewer)
                && track.source.equals(packet.getUUIDs().readSafely(0))) {
            PacketContainer out = packet.shallowClone();
            out.getUUIDs().write(0, new UUID(BEDROCK_MARKER, track.source.getLeastSignificantBits()));
            return out;
        }
        if (packet.getType() == PacketType.Play.Server.ENTITY_METADATA && javaReady(viewer)
                && track.masked.contains(viewer.getUniqueId())) {
            var data = packet.getDataValueCollectionModifier().readSafely(0);
            if (data == null) return packet;
            for (int i = 0; i < data.size(); i++) {
                WrappedDataValue value = data.get(i);
                if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                    List<WrappedDataValue> changed = new ArrayList<>(data);
                    changed.set(i, new WrappedDataValue(0, value.getSerializer(), maskedFlags(flags)));
                    PacketContainer out = packet.shallowClone();
                    out.getDataValueCollectionModifier().write(0, changed);
                    return out;
                }
            }
        }
        return packet;
    }

    /**
     * 統合版の攻撃姿勢を、Geyser 拡張だけが読む一時 TextDisplay で通知します。
     * @param viewer 統合版の閲覧者 @param id 戦闘実体ID @param position 本体位置 @param mode 姿勢・yawの値
     * @return 生成通知を送れた場合 true
     */
    private boolean sendMotion(Player viewer, int id, Location position, long mode) {
        int packetId = PacketEntityIdAllocator.nextEntityId();
        try {
            PacketContainer marker = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
            marker.getIntegers().write(0, packetId);
            marker.getUUIDs().write(0, new UUID(BEDROCK_MOTION, BossBedrockMotion.payload(id, mode)));
            marker.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
            marker.getDoubles().write(0, position.getX()).write(1, position.getY()).write(2, position.getZ());
            protocol.sendServerPacket(viewer, marker, false);
            PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
            destroy.getIntLists().writeSafely(0, List.of(packetId));
            destroy.getIntegerArrays().writeSafely(0, new int[]{packetId});
            protocol.sendServerPacket(viewer, destroy, false);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6515, exception);
            return false;
        }
    }

    private boolean sendFlags(Player viewer, Entity source, boolean hide) {
        try {
            for (WrappedDataValue value : WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()) {
                if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                    byte next = hide ? maskedFlags(flags) : flags;
                    protocol.sendServerPacket(viewer, metadata(source.getEntityId(),
                        new WrappedDataValue(0, value.getSerializer(), next)), false);
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6515, exception);
        }
        return false;
    }

    static byte maskedFlags(byte flags) {
        return (byte) ((flags | INVISIBLE) & ~GLOWING);
    }

    private PacketContainer metadata(int id, WrappedDataValue value) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        packet.getIntegers().write(0, id);
        packet.getDataValueCollectionModifier().write(0, List.of(value));
        return packet;
    }

    private boolean isBedrock(Player viewer) {
        var player = AstPlayerCache.get(viewer);
        return player == null ? packs.isBedrock(viewer.getName()) : player.isBedrock();
    }

    private boolean javaReady(Player viewer) {
        return !isBedrock(viewer) && packs.hasJavaMossZombiePresentation(viewer.getUniqueId());
    }

    /** @param pose 0～2は待機・歩行、3以降は {@link MossZombieMotion.Pose#bedrock()} + 2 @return 表示アイテム */
    private ItemStack item(int pose) {
        if (pose < poses.size()) return poses.get(pose);
        for (var entry : attackPoses.entrySet()) {
            if (entry.getKey().bedrock() + 2 == pose) return entry.getValue();
        }
        return poses.getFirst();
    }

    /** @return 攻撃姿勢ごとの Java 版モデル */
    private static Map<MossZombieMotion.Pose, ItemStack> attackItems() {
        Map<MossZombieMotion.Pose, ItemStack> result = new java.util.EnumMap<>(MossZombieMotion.Pose.class);
        for (MossZombieMotion.Pose pose : MossZombieMotion.Pose.values()) {
            if (pose.javaCase() != null) result.put(pose, item(pose.javaCase()));
        }
        return result;
    }

    private static ItemStack item(String pose) {
        ItemStack item = ItemStack.of(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(new NamespacedKey("astralrecord", "midgard_moss_zombie"));
        if (pose != null) {
            var data = meta.getCustomModelDataComponent();
            data.setStrings(List.of(pose));
            meta.setCustomModelDataComponent(data);
        }
        item.setItemMeta(meta);
        return item;
    }

    /** @return 形状ごとの4フレーム分の Java 版アイテム */
    private static Map<MossZombieMotion.EffectStyle, List<ItemStack>> effectItems() {
        Map<MossZombieMotion.EffectStyle, List<ItemStack>> result = new java.util.EnumMap<>(MossZombieMotion.EffectStyle.class);
        for (MossZombieMotion.EffectStyle style : MossZombieMotion.EffectStyle.values()) {
            List<ItemStack> frames = new ArrayList<>(EFFECT_FRAMES);
            for (int frame = 0; frame < EFFECT_FRAMES; frame++) {
                ItemStack item = ItemStack.of(Material.PAPER);
                var meta = item.getItemMeta();
                meta.setItemModel(new NamespacedKey("astralrecord", style.model()));
                var data = meta.getCustomModelDataComponent();
                data.setFloats(List.of((float) frame));
                meta.setCustomModelDataComponent(data);
                item.setItemMeta(meta);
                frames.add(item);
            }
            result.put(style, List.copyOf(frames));
        }
        return result;
    }

    /** @param blocks 寸法のブロック数 @return 1/32ブロック単位の12ビット値（Geyser 拡張と共有） */
    private static long dimension(float blocks) {
        return Math.clamp(Math.round(blocks * 32), 1, 0xfff);
    }

    /** @param viewer 宛先 @param packet 作成済み通知 @return 送信成功 */
    private boolean send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet, false);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6515, exception);
            return false;
        }
    }

    /** 技の演出1つの閲覧者・フレーム・寿命を所有します。 */
    private final class Effect {
        private final MossZombieMotion.Effect shape;
        private final long started;
        private final long ends;
        private final Set<Player> java = new HashSet<>();
        private final Set<Player> bedrock = new HashSet<>();
        private ItemDisplay model;
        private PacketContainer spawnPacket;
        private PacketContainer removePacket;
        private int frame = -1;

        private Effect(MossZombieMotion.Effect shape, long started, long ends) {
            this.shape = shape;
            this.started = started;
            this.ends = ends;
        }

        /** @param tick 追跡開始時に必要なフレームの時計 */
        private void syncViewers(long tick) {
            Location position = shape.origin();
            if (position.getWorld() == null
                || !position.getWorld().isChunkLoaded(position.getBlockX() >> 4, position.getBlockZ() >> 4)) return;
            var nearby = position.getWorld().getNearbyPlayers(position, EFFECT_VIEW_DISTANCE);
            if (model != null && !model.isValid()) {
                model = null;
                java.clear();
            }
            java.removeIf(viewer -> {
                if (nearby.contains(viewer) && javaReady(viewer)) return false;
                if (viewer.isOnline() && model != null) viewer.hideEntity(plugin, model);
                return true;
            });
            bedrock.removeIf(viewer -> {
                if (nearby.contains(viewer) && isBedrock(viewer)) return false;
                if (viewer.isOnline() && removePacket != null) send(viewer, removePacket);
                return true;
            });
            for (Player viewer : nearby) {
                if (javaReady(viewer)) {
                    if (model == null) {
                        frame = frameAt(tick);
                        model = spawnEffect(position, effectItems.get(shape.style()).get(frame));
                    }
                    if (java.add(viewer)) viewer.showEntity(plugin, model);
                } else if (isBedrock(viewer) && !bedrock.contains(viewer)) {
                    if (spawnPacket == null) {
                        int entityId = PacketEntityIdAllocator.nextEntityId();
                        long bits = (ThreadLocalRandom.current().nextLong() & ~0xfff_ffff_ffffL)
                            | shape.style().id() | (dimension(shape.width()) << 8)
                            | (dimension(shape.length()) << 20) | (dimension(shape.height()) << 32);
                        spawnPacket = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
                        spawnPacket.getIntegers().write(0, entityId);
                        spawnPacket.getUUIDs().write(0, new UUID(BEDROCK_EFFECT, bits));
                        spawnPacket.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
                        spawnPacket.getDoubles().write(0, position.getX()).write(1, position.getY()).write(2, position.getZ());
                        spawnPacket.getBytes().writeSafely(1, (byte) Math.round(position.getYaw() * 256.0F / 360.0F));
                        removePacket = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                        removePacket.getIntLists().writeSafely(0, List.of(entityId));
                        removePacket.getIntegerArrays().writeSafely(0, new int[]{entityId});
                    }
                    if (send(viewer, spawnPacket)) bedrock.add(viewer);
                }
            }
            if (java.isEmpty() && model != null) {
                model.remove();
                model = null;
            }
        }

        /** @param tick 共通時計。フレームが変わった Java 表示だけを更新します */
        private void animate(long tick) {
            int next = frameAt(tick);
            if (model == null || !model.isValid() || frame == next) return;
            model.setItemStack(effectItems.get(shape.style()).get(next));
            frame = next;
        }

        /** @param tick 共通時計 @return 予告は3tickごとに巡回、着弾は2tickごとに進めて最後のフレームで保持 */
        private int frameAt(long tick) {
            long age = Math.max(0L, tick - started);
            return shape.style().impact() ? (int) Math.min(EFFECT_FRAMES - 1, age / 2) : (int) (age / 3 % EFFECT_FRAMES);
        }

        /** @param position 床上の中心と向き @param item 最初のフレーム @return 閲覧者限定の拡大表示 */
        private ItemDisplay spawnEffect(Location position, ItemStack item) {
            return position.getWorld().spawn(position, ItemDisplay.class, display -> {
                display.setVisibleByDefault(false);
                display.setPersistent(false);
                display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
                display.setItemStack(item);
                display.setViewRange(2.0F);
                display.setBrightness(new org.bukkit.entity.Display.Brightness(15, 15));
                display.setTransformation(new org.bukkit.util.Transformation(new org.joml.Vector3f(),
                    new org.joml.Quaternionf(), new org.joml.Vector3f(shape.width(), shape.height(), shape.length()),
                    new org.joml.Quaternionf()));
            });
        }

        /** 両エディションの表示を回収します。 */
        private void remove() {
            if (model != null) model.remove();
            model = null;
            for (Player viewer : bedrock) if (viewer.isOnline() && removePacket != null) send(viewer, removePacket);
            java.clear();
            bedrock.clear();
        }
    }

    private static final class Tracking {
        private final UUID source;
        private final Set<UUID> masked = ConcurrentHashMap.newKeySet();
        // 統合版の閲覧者ごとに最後に送った姿勢番号。メインスレッドだけで読み書きする。
        private final Map<UUID, Integer> bedrockMotion = new ConcurrentHashMap<>();

        private Tracking(UUID source) {
            this.source = source;
        }
    }

    private static final class Body {
        private final Entity source;
        private final org.bukkit.World world;
        private final Tracking tracking;
        private final Set<Player> viewers = new HashSet<>();
        private Location position;
        private ItemDisplay model;
        private int pose;

        private Body(Entity source, Tracking tracking) {
            this.source = source;
            this.world = source.getWorld();
            this.tracking = tracking;
        }
    }
}
