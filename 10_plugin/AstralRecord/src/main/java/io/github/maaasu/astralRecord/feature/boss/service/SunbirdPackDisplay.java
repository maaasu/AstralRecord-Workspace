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

/** サンバードの実体を維持し、閲覧者のエディションに合わせて外観とギミックを描画します。 */
final class SunbirdPackDisplay implements Listener {
    // Geyser 拡張と共有する UUID マーカー。実体の entity ID は維持する。
    private static final long BEDROCK_BODY_MARKER = 0x41535453554e4231L;
    private static final long BEDROCK_EFFECT_MARKER = 0x41535453554e4631L;
    private static final int INVISIBLE = 0x20;
    private static final double VIEW_DISTANCE = 64.0D;
    private static final int POSE_TICKS = 5;
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
    private final ItemStack normalBody = modelItem(BODY_MODEL);
    private final ItemStack hurtBody = createHurtBody();
    private final Map<UUID, List<TimedEffect>> transients = new HashMap<>();
    private final PacketAdapter packets;

    /** ボスの描画に使うパック判定とパケット監視を準備します。 */
    SunbirdPackDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        this.templateKey = new NamespacedKey(plugin, "mob_template_id");
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
    }

    /** パケット送信前に、このボスの追跡対象を記録します。 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTrack(PlayerTrackEntityEvent event) {
        Entity entity = event.getEntity();
        if (entity.getType() == EntityType.PARROT && BossMechanicProfile.MIDGARD_SAVANNA_SUNBIRD.equals(
                entity.getPersistentDataContainer().get(templateKey, PersistentDataType.STRING))) {
            trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
        }
    }

    /** Java モデルの姿勢・移動を更新し、対応パックの閲覧者だけ実体の絵柄を置き換えます。 */
    void body(UUID bossId, Entity entity, long tick, boolean casting) {
        if (entity.getType() != EntityType.PARROT) return;
        trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
        Body body = bodies.get(bossId);
        if (body != null && (body.source != entity || !body.model.isValid()
                || body.model.getWorld() != entity.getWorld())) {
            clearBody(bossId);
            trackedBosses.put(entity.getEntityId(), entity.getUniqueId());
            body = null;
        }
        Set<Player> viewers = new HashSet<>();
        for (Player viewer : entity.getTrackedBy()) {
            if (packs.hasSunbirdPresentation(viewer.getUniqueId())) viewers.add(viewer);
        }
        if (body == null && viewers.isEmpty()) return;
        Location target = entity.getLocation();
        target.setPitch(0.0F);
        if (body == null) {
            body = new Body(entity, spawn(target, normalBody, false));
            bodies.put(bossId, body);
            maskedViewers.put(entity.getEntityId(), body.masked);
        }
        Location previous = body.model.getLocation();
        if (!previous.equals(target)) {
            body.model.setTeleportDuration(previous.distanceSquared(target) > 9.0D ? 0 : POSE_TICKS);
            body.model.teleport(target);
        }
        float wave = (float) Math.sin(tick * (casting ? 0.31D : 0.18D));
        body.model.setInterpolationDelay(0);
        body.model.setInterpolationDuration(POSE_TICKS);
        body.model.setTransformation(new Transformation(new Vector3f(0.0F, 0.09F + wave * 0.08F, 0.0F),
            new Quaternionf().rotateX(casting ? -0.17F + wave * 0.08F : wave * 0.04F),
            new Vector3f(casting ? 1.06F : 1.0F, 1.0F, 1.0F), new Quaternionf()));

        Set<Player> tracked = entity.getTrackedBy();
        Iterator<Player> iterator = body.viewers.iterator();
        while (iterator.hasNext()) {
            Player viewer = iterator.next();
            if (!viewers.contains(viewer)) {
                body.masked.remove(viewer.getUniqueId());
                viewer.hideEntity(plugin, body.model);
                if (viewer.isOnline() && tracked.contains(viewer)) sendFlags(viewer, entity, false);
                iterator.remove();
            }
        }
        for (Player viewer : viewers) {
            if (body.viewers.add(viewer)) viewer.showEntity(plugin, body.model);
            UUID viewerId = viewer.getUniqueId();
            if (!body.model.getTrackedBy().contains(viewer)) {
                if (body.masked.remove(viewerId)) sendFlags(viewer, entity, false);
            } else if (body.masked.add(viewerId) && !sendFlags(viewer, entity, true)) {
                body.masked.remove(viewerId);
            }
        }
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
            if (body != null && body.source == source && body.model.isValid() && !body.model.getTrackedBy().isEmpty()) {
                body.restoreTint = plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    body.restoreTint = null;
                    if (body.model.isValid()) body.model.setItemStack(normalBody);
                }, HURT_TINT_TICKS);
                body.model.setItemStack(hurtBody);
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

    /** 統合版で実体を追跡している閲覧者へ、同じ被弾パケットを使って通知します。 */
    private void sendBedrockHurt(Entity source) {
        PacketContainer packet = null;
        for (Player viewer : source.getTrackedBy()) {
            if (!isBedrock(viewer)) continue;
            if (packet == null) {
                packet = SunbirdBedrockHurt.create(source);
            }
            send(viewer, packet);
        }
    }

    /** 停止したボスの本体・短時間演出を回収します。 */
    void clear(UUID bossId) {
        nextHurtTick.remove(bossId);
        clearBody(bossId);
        List<TimedEffect> effects = transients.remove(bossId);
        if (effects != null) {
            for (TimedEffect effect : effects) effect.effect().remove();
        }
    }

    /** 今回確認できなかったボスと、表示期限を過ぎたエフェクトを取り除きます。 */
    void retain(Set<UUID> activeBosses, long tick) {
        nextHurtTick.keySet().retainAll(activeBosses);
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
        body.model.remove();
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

    /** 実体の現在フラグを取得し、不可視ビットだけを閲覧者ごとに調整します。 */
    private boolean sendFlags(Player viewer, Entity source, boolean hide) {
        try {
            for (WrappedDataValue value : WrappedDataWatcher.getEntityWatcher(source).toDataValueCollection()) {
                if (value.getIndex() == 0 && value.getValue() instanceof Byte flags) {
                    PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
                    packet.getIntegers().write(0, source.getEntityId());
                    packet.getDataValueCollectionModifier().write(0, List.of(flagValue(value, flags, hide)));
                    protocol.sendServerPacket(viewer, packet, false);
                    return true;
                }
            }
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_6513, exception);
        }
        return false;
    }

    private static WrappedDataValue flagValue(WrappedDataValue source, byte flags, boolean hide) {
        return new WrappedDataValue(source.getIndex(), source.getSerializer(),
            (byte) (hide ? flags | INVISIBLE : flags));
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

    /** モデルと UV を共用し、着色値だけを指定した被弾用アイテムを作ります。 */
    private static ItemStack createHurtBody() {
        ItemStack item = modelItem(BODY_MODEL);
        var meta = item.getItemMeta();
        var modelData = meta.getCustomModelDataComponent();
        modelData.setColors(List.of(HURT_TINT));
        meta.setCustomModelDataComponent(modelData);
        item.setItemMeta(meta);
        return item;
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
        private final Map<UUID, Player> bedrockViewers = new HashMap<>();
        private final Set<Player> javaViewers = new HashSet<>();
        private ItemDisplay model;
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
            javaViewers.removeIf(viewer -> {
                if (viewers.contains(viewer) && packs.hasSunbirdPresentation(viewer.getUniqueId())
                        && model != null && model.isValid()) return false;
                if (model != null) viewer.hideEntity(plugin, model);
                return true;
            });
            bedrockViewers.values().removeIf(viewer -> {
                if (viewers.contains(viewer)) return false;
                if (viewer.isOnline()) send(viewer, bedrockRemove);
                return true;
            });
            for (Player viewer : viewers) {
                if (packs.hasSunbirdPresentation(viewer.getUniqueId())) {
                    if (model == null || !model.isValid()) spawnJava();
                    if (javaViewers.add(viewer)) viewer.showEntity(plugin, model);
                } else if (isBedrock(viewer) && bedrockViewers.putIfAbsent(viewer.getUniqueId(), viewer) == null) {
                    if (bedrockSpawn == null) prepareBedrock();
                    send(viewer, bedrockSpawn);
                }
            }
        }

        /** モデルの追跡が始まった Java 閲覧者だけ粒子の重複送信を省きます。 */
        boolean visibleTo(Player viewer) {
            return model != null && model.isValid() && packs.hasSunbirdPresentation(viewer.getUniqueId())
                && model.getTrackedBy().contains(viewer);
        }

        /** Java と統合版の表示を両方回収します。 */
        void remove() {
            if (model != null) model.remove();
            for (Player viewer : bedrockViewers.values()) {
                if (viewer.isOnline() && bedrockRemove != null) send(viewer, bedrockRemove);
            }
            bedrockViewers.clear();
            javaViewers.clear();
        }

        private void spawnJava() {
            model = spawn(shape.location(), modelItem(shape.style().model), true);
            model.setTransformation(scale(burst ? 0.40F : 1.0F, burst ? 0.25F : 1.0F));
            if (burst) {
                ItemDisplay display = model;
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (!display.isValid()) return;
                    display.setInterpolationDuration(7);
                    display.setInterpolationDelay(0);
                    display.setTransformation(scale(1.0F, 1.0F));
                });
            }
        }

        private Transformation scale(float horizontal, float vertical) {
            // ItemDisplay の既定の Y 軸反転で、モデルの -Z がゲーム内の +Z に合う。
            return new Transformation(new Vector3f(), new Quaternionf(),
                new Vector3f(shape.width() * horizontal, shape.height() * vertical, shape.length() * horizontal),
                new Quaternionf());
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

    private static final class Body {
        private final Entity source;
        private final ItemDisplay model;
        private final Set<Player> viewers = new HashSet<>();
        private final Set<UUID> masked = ConcurrentHashMap.newKeySet();
        private BukkitTask restoreTint;

        private Body(Entity source, ItemDisplay model) {
            this.source = source;
            this.model = model;
        }
    }

    private record TimedEffect(Effect effect, long endTick) {}
}
