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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final int INVISIBLE = 0x20;
    private static final int GLOWING = 0x40;
    private static final int STEP_TICKS = 5;
    private final Plugin plugin;
    private final ResourcePackService packs;
    private final NamespacedKey templateKey;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final Map<Integer, Tracking> tracking = new ConcurrentHashMap<>();
    private final Map<UUID, Body> bodies = new HashMap<>();
    private final List<ItemStack> poses = List.of(item(null), item("walk_a"), item("walk_b"));
    private final Map<MossZombieMotion.Pose, ItemStack> attackPoses = attackItems();
    private final PacketAdapter packets;
    private @Nullable MossZombieMotion motion;

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

    /** @param motion Mob スキルと共有する攻撃姿勢。null なら待機・歩行だけを表示します */
    void setMotion(@Nullable MossZombieMotion motion) {
        this.motion = motion;
    }

    void start() {
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        protocol.addPacketListener(packets);
    }

    void stop() {
        for (UUID id : List.copyOf(bodies.keySet())) clear(id);
        protocol.removePacketListener(packets);
        HandlerList.unregisterAll(this);
        tracking.clear();
        if (motion != null) motion.clearAll();
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
        tracking.entrySet().removeIf(e -> plugin.getServer().getEntity(e.getValue().source) == null);
    }

    void clear(UUID bossId) {
        if (motion != null) motion.clear(bossId);
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
