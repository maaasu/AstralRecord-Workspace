package io.github.maaasu.astralRecord.feature.fishing.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.shared.display.PacketEntityIdAllocator;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** 釣りの表示を担当し、キャストの判定・魚の移動には関与しません。 */
public final class FishingVisuals {
    // AstralRecordGeyserExtension の FishingVisualEntity と共有するパケット契約。
    private static final long BEDROCK_SPAWN = 0x4153544649534831L;
    private static final long BEDROCK_UPDATE = 0x4153464000000000L;
    private static final int NO_GRAVITY_INDEX = 5;
    private static final double POSE_UNITS = 256.0D;
    private static final int MAX_POSE_SHIFT = 19;
    private static final double MOVE_UNITS = 4096.0D;
    private static final double VIEW_DISTANCE = 64.0D;
    private static final int MAX_VIEWERS = 32;
    private static final int MAX_EFFECTS = 128;
    private static final int NODE_STEP = 2;
    private static final int INTERPOLATION_TICKS = 2;
    private static final float LINE_WIDTH = 0.025F;
    private static final float SEGMENT_OVERLAP = 0.01F;

    private final Plugin plugin;
    private final ResourcePackService resourcePacks;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final ItemStack line = model(Material.BLACK_CONCRETE, "fishing_line");
    private final ItemStack hook = model(Material.TRIPWIRE_HOOK, "fishing_hook");
    private final ItemStack splash = model(Material.LIGHT_BLUE_CARPET, "fishing_splash");
    private final ItemStack wake = model(Material.LIGHT_BLUE_CARPET, "fishing_wake");
    private final Set<Effect> effects = new HashSet<>();
    private @Nullable BukkitTask effectTask;

    /**
     * パックの描画を管理するサービスを構成します。Bukkit メインスレッドで使用します。
     *
     * @param plugin 表示とタスクを所有するプラグイン
     * @param resourcePacks ログイン直後の統合版判定に使用するサービス
     */
    public FishingVisuals(Plugin plugin, ResourcePackService resourcePacks) {
        this.plugin = plugin;
        this.resourcePacks = resourcePacks;
    }

    /**
     * 一回のキャストに対応する表示状態を生成します。初回更新まで表示実体は生成しません。
     *
     * @param owner キャストしたプレイヤー
     * @return 針と糸の表示状態
     */
    public CastDisplay createCast(Player owner) {
        return new CastDisplay(owner);
    }

    /**
     * 着水、食いつき、魚の水面移動を示す短い水しぶきを表示します。
     *
     * @param owner 釣りをしているプレイヤー
     * @param location しぶきが生じた位置
     */
    public void splash(Player owner, Location location) {
        showEffect(owner, location, splash, 1, 10);
    }

    /**
     * 水中で移動する魚の位置を示す短い波紋を表示します。
     *
     * @param owner 釣りをしているプレイヤー
     * @param location 魚の位置
     */
    public void wake(Player owner, Location location) {
        showEffect(owner, location, wake, 2, 6);
    }

    /** プラグイン停止時に期限前の水演出を回収します。 */
    public void shutdown() {
        if (effectTask != null) {
            effectTask.cancel();
            effectTask = null;
        }
        effects.forEach(this::removeEffect);
        effects.clear();
    }

    /** 読み込み済みの水域で短時間だけ両版の演出を表示します。 */
    private void showEffect(Player owner, Location location, ItemStack item, int style, int duration) {
        if (effects.size() >= MAX_EFFECTS || !loaded(location)) {
            return;
        }
        Location origin = location.clone().add(0, 0.025D, 0);
        Audience viewers = audience(owner, origin);
        if (viewers.java().isEmpty() && viewers.bedrock().isEmpty()) {
            return;
        }

        ItemDisplay model = null;
        PacketContainer remove = null;
        Set<Player> tracked = new HashSet<>();
        try {
            if (!viewers.java().isEmpty()) {
                model = spawn(origin, item, transform(new Quaternionf(), new Vector3f(0.45F, 1.0F, 0.45F)));
            }
            if (!viewers.bedrock().isEmpty()) {
                int id = PacketEntityIdAllocator.nextEntityId();
                remove = destroy(id);
                PacketContainer packet = spawnMarker(id, style, origin);
                for (Player viewer : viewers.bedrock()) {
                    if (send(viewer, packet)) {
                        tracked.add(viewer);
                    }
                }
            }
            if (model != null) {
                for (Player viewer : viewers.java()) {
                    viewer.showEntity(plugin, model);
                }
            }
            if (effectTask == null) {
                effectTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tickEffects, 1L, 1L);
            }
            effects.add(new Effect(owner, model, remove, tracked, origin, duration, plugin.getServer().getCurrentTick()));
        } catch (RuntimeException exception) {
            if (model != null) {
                model.remove();
            }
            if (remove != null) {
                for (Player viewer : tracked) {
                    send(viewer, remove);
                }
            }
            Logger.log(LogId.E_3002, exception, "fishing_water_visual");
        }
    }

    /** 水演出の拡大と期限切れを、一つのタスクで処理します。 */
    private void tickEffects() {
        int tick = plugin.getServer().getCurrentTick();
        for (var iterator = effects.iterator(); iterator.hasNext();) {
            Effect effect = iterator.next();
            int age = tick - effect.startedAt();
            if (age >= effect.duration() || !effect.owner().isOnline() || effect.owner().isDead()
                || effect.owner().getWorld() != effect.origin().getWorld()) {
                removeEffect(effect);
                iterator.remove();
            } else if (age == 1 && effect.model() != null && effect.model().isValid()) {
                effect.model().setInterpolationDuration(effect.duration() - 1);
                effect.model().setInterpolationDelay(0);
                effect.model().setTransformation(transform(new Quaternionf(), new Vector3f(1.0F)));
            }
        }
        if (effects.isEmpty()) {
            effectTask.cancel();
            effectTask = null;
        }
    }

    /** 終了した水演出のアイテム表示と仮想エンティティを除去します。 */
    private void removeEffect(Effect effect) {
        if (effect.model() != null) {
            effect.model().remove();
        }
        removeForViewers(effect.bedrock(), effect.origin(), effect.remove());
    }

    /**
     * 持ち主の水演出を寿命を待たずに回収します。着水後にキャストが残らない場合にも使用します。
     *
     * @param ownerId 持ち主のプレイヤーUUID
     */
    public void clearWaterEffects(UUID ownerId) {
        effects.removeIf(effect -> {
            if (!effect.owner().getUniqueId().equals(ownerId)) {
                return false;
            }
            removeEffect(effect);
            return true;
        });
    }

    /** 64ブロック以内でキャストの所有者が見える閲覧者を版別に選びます。 */
    private Audience audience(Player owner, Location center) {
        Set<Player> java = new HashSet<>();
        Set<Player> bedrock = new HashSet<>();
        for (Player viewer : center.getWorld().getNearbyPlayers(center, VIEW_DISTANCE)) {
            if (java.size() + bedrock.size() >= MAX_VIEWERS && viewer != owner) {
                continue;
            }
            if (!viewer.isOnline() || viewer.isDead() || viewer.getLocation().distanceSquared(center) > VIEW_DISTANCE * VIEW_DISTANCE
                || (viewer != owner && !viewer.canSee(owner))) {
                continue;
            }
            var ast = AstPlayerCache.get(viewer);
            boolean isBedrock = ast == null ? resourcePacks.isBedrock(viewer.getName()) : ast.isBedrock();
            (isBedrock ? bedrock : java).add(viewer);
        }
        return new Audience(java, bedrock);
    }

    /** アイテムの通常モデルをフォールバックに持つパック用の表示アイテムを作ります。 */
    private static ItemStack model(Material carrier, String key) {
        ItemStack item = ItemStack.of(carrier);
        var meta = item.getItemMeta();
        var data = meta.getCustomModelDataComponent();
        data.setStrings(List.of(key));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }

    /** 読み込み済みチャンクへ、明示的に許可した閲覧者だけに見える表示を生成します。 */
    private ItemDisplay spawn(Location location, ItemStack item, Transformation transformation) {
        Location origin = new Location(location.getWorld(), location.getX(), location.getY(), location.getZ());
        return origin.getWorld().spawn(origin, ItemDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setGravity(false);
            display.setInvulnerable(true);
            display.setPersistent(false);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
            display.setItemStack(item);
            display.setViewRange(2.0F);
            display.setTeleportDuration(INTERPOLATION_TICKS);
            display.setInterpolationDuration(INTERPOLATION_TICKS);
            display.setTransformation(transformation);
        });
    }

    /** 表示位置を変えず、モデルの回転と寸法だけを設定します。 */
    private static Transformation transform(Quaternionf rotation, Vector3f scale) {
        return new Transformation(new Vector3f(), rotation, scale, new Quaternionf());
    }

    /** 表示のためにチャンクを追加で読み込まないようにします。 */
    private static boolean loaded(Location location) {
        return location.getWorld().isChunkLoaded(location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    /** Geyser が描画モデルに置換する仮想表示のスポーンを作ります。 */
    private PacketContainer spawnMarker(int id, int style, Location origin) {
        long data = Integer.toUnsignedLong(id) | ((long) style << 32);
        PacketContainer spawn = marker(id, new UUID(BEDROCK_SPAWN, data), origin);
        PacketContainer metadata = protocol.createPacket(PacketType.Play.Server.ENTITY_METADATA);
        metadata.getIntegers().write(0, id);
        var serializer = WrappedDataWatcher.Registry.get((Type) Boolean.class);
        metadata.getDataValueCollectionModifier().write(0, List.of(new WrappedDataValue(NO_GRAVITY_INDEX, serializer, true)));
        return bundle(spawn, metadata);
    }

    /** Geyser がキャンセルし、対象の糸と針の位置を更新する通知を作ります。 */
    private PacketContainer poseMarker(int targetId, Pose pose, Location origin) {
        int id = PacketEntityIdAllocator.nextEntityId();
        long marker = BEDROCK_UPDATE | ((long) pose.shift() << 32) | Integer.toUnsignedLong(targetId);
        // 旧Geyser拡張が状態通知を通常のTextDisplayとして扱っても、その場で回収します。
        return bundle(marker(id, new UUID(marker, pose.bits()), origin), destroy(id));
    }

    /** 生成と初期設定または回収を同じバンドルにまとめます。 */
    private PacketContainer bundle(PacketContainer first, PacketContainer second) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.BUNDLE);
        packet.getPacketBundles().write(0, List.of(first, second));
        return packet;
    }

    /** 表示または状態通知の TextDisplay スポーンを作ります。 */
    private PacketContainer marker(int id, UUID uuid, Location origin) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.SPAWN_ENTITY);
        packet.getIntegers().write(0, id);
        packet.getUUIDs().write(0, uuid);
        packet.getEntityTypeModifier().write(0, EntityType.TEXT_DISPLAY);
        packet.getDoubles().write(0, origin.getX()).write(1, origin.getY()).write(2, origin.getZ());
        return packet;
    }

    /** 通常は1/256ブロック単位とし、長いキャストだけ3軸共通の単位を広げます。 */
    private static Pose encodePose(Location rodTip, Location hook, double sag) {
        double x = hook.getX() - rodTip.getX();
        double y = hook.getY() - rodTip.getY();
        double z = hook.getZ() - rodTip.getZ();
        double largest = Math.max(Math.abs(x), Math.max(Math.abs(y), Math.abs(z)));
        int shift = 0;
        while (shift < MAX_POSE_SHIFT && Math.round(Math.scalb(largest * POSE_UNITS, -shift)) > Short.MAX_VALUE) {
            shift++;
        }
        double units = Math.scalb(POSE_UNITS, -shift);
        long bits = poseAxis(x * units)
            | (poseAxis(y * units) << 16)
            | (poseAxis(z * units) << 32)
            | (Math.round(sag * POSE_UNITS) << 48);
        return new Pose(shift, bits);
    }

    /** 符号付き16ビットの変位を他の軸に影響しない下位ビットへ詰めます。 */
    private static long poseAxis(double units) {
        return Math.clamp(Math.round(units), Short.MIN_VALUE, Short.MAX_VALUE) & 0xffffL;
    }

    /** Geyserの単精度座標に丸めてから差を取ることで、相対移動の丸め誤差を蓄積させません。 */
    private static float bedrockCoordinate(double blocks) {
        return (float) (Math.round(blocks * MOVE_UNITS) / MOVE_UNITS);
    }

    /** 量子化済みの座標差で仮想表示を移動します。 */
    private PacketContainer move(int id, long dx, long dy, long dz) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.REL_ENTITY_MOVE);
        packet.getIntegers().write(0, id);
        packet.getShorts().write(0, (short) dx).write(1, (short) dy).write(2, (short) dz);
        packet.getBooleans().write(0, false);
        return packet;
    }

    /** 仮想表示を削除するパケットを作ります。 */
    private PacketContainer destroy(int id) {
        PacketContainer packet = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
        packet.getIntLists().writeSafely(0, List.of(id));
        packet.getIntegerArrays().writeSafely(0, new int[] {id});
        return packet;
    }

    /** 別ワールドのキャッシュには削除を送らず、現在の閲覧者だけを片付けます。 */
    private void removeForViewers(Set<Player> viewers, Location origin, @Nullable PacketContainer packet) {
        if (packet != null) {
            for (Player viewer : viewers) {
                if (viewer.isOnline() && viewer.getWorld() == origin.getWorld()) {
                    send(viewer, packet);
                }
            }
        }
    }

    /** 送信失敗を釣りの進行や他の閲覧者へ波及させません。 */
    private boolean send(Player viewer, PacketContainer packet) {
        try {
            protocol.sendServerPacket(viewer, packet, false);
            return true;
        } catch (RuntimeException exception) {
            Logger.log(LogId.E_3002, exception, "fishing_visual_packet");
            return false;
        }
    }

    /** 一回のキャストに対応する、更新可能な針と糸の表示です。 */
    public final class CastDisplay {
        private final Player owner;
        private final Set<Player> javaViewers = new HashSet<>();
        private final Set<Player> bedrockViewers = new HashSet<>();
        private final List<@Nullable ItemDisplay> segments = new ArrayList<>();
        private @Nullable ItemDisplay hookModel;
        private @Nullable Location origin;
        private @Nullable PacketContainer removePacket;
        private int bedrockId;
        private @Nullable Pose pose;
        private boolean closed;

        private CastDisplay(Player owner) {
            this.owner = owner;
        }

        /**
         * 竿先・針・たるみを一つの表示フレームとして反映します。
         *
         * @param nodes 竿先から針までの奇数個の順序付きノード
         * @param sag 糸の中央のたるみ（ブロック）
         */
        public void update(List<Location> nodes, double sag) {
            Location rodTip = nodes.getFirst();
            Location hookLocation = nodes.getLast();
            try {
                Audience viewers = audience(owner, rodTip);
                updateJava(nodes, hookLocation, viewers.java());
                updateBedrock(rodTip, hookLocation, sag, viewers.bedrock());
            } catch (RuntimeException exception) {
                clearRig();
                Logger.log(LogId.E_3002, exception, "fishing_cast_visual");
            }
        }

        /** 回収、魚の取り外し、キャスト中止時に両版の表示を除去します。 */
        public void close() {
            if (closed) {
                return;
            }
            closed = true;
            clearRig();
            clearWaterEffects();
        }

        /** 魚の待機から針だけを回収する場合にも、残った水演出を消去します。 */
        public void clearWaterEffects() {
            FishingVisuals.this.clearWaterEffects(owner.getUniqueId());
        }

        /** 針と糸の永続表示を除去します。 */
        private void clearRig() {
            if (hookModel != null) {
                hookModel.remove();
                hookModel = null;
            }
            for (ItemDisplay display : segments) {
                if (display != null) {
                    display.remove();
                }
            }
            segments.clear();
            javaViewers.clear();
            clearBedrock();
        }

        /** 2ノードごとに線分の中心と姿勢を更新し、接合部をわずかに重ねます。 */
        private void updateJava(List<Location> nodes, Location hookLocation, Set<Player> viewers) {
            hookModel = updateModel(hookModel, hookLocation, hook,
                transform(new Quaternionf(), new Vector3f(1.0F)), viewers);
            for (int i = 0; i + NODE_STEP < nodes.size(); i += NODE_STEP) {
                int index = i / NODE_STEP;
                if (segments.size() <= index) {
                    segments.add(null);
                }
                Location start = nodes.get(i);
                Vector delta = nodes.get(i + NODE_STEP).toVector().subtract(start.toVector());
                float length = (float) delta.length();
                ItemDisplay current = segments.get(index);
                if (length <= 1.0E-5F) {
                    if (current != null) {
                        current.remove();
                    }
                    segments.set(index, null);
                    continue;
                }
                Location middle = start.clone().add(delta.clone().multiply(0.5D));
                Quaternionf rotation = new Quaternionf().rotationTo(0, 0, 1,
                    (float) (delta.getX() / length), (float) (delta.getY() / length), (float) (delta.getZ() / length));
                Transformation shape = transform(rotation, new Vector3f(LINE_WIDTH, LINE_WIDTH, length + SEGMENT_OVERLAP));
                segments.set(index, updateModel(current, middle, line, shape, viewers));
            }
            javaViewers.retainAll(viewers);
            javaViewers.addAll(viewers);
        }

        /** Java版の表示を再利用し、範囲または所有者の可視性を外れた閲覧者からは隠します。 */
        private @Nullable ItemDisplay updateModel(@Nullable ItemDisplay display, Location location, ItemStack item,
                                                   Transformation shape, Set<Player> viewers) {
            if (viewers.isEmpty() || !loaded(location)) {
                if (display != null) {
                    display.remove();
                }
                return null;
            }
            boolean created = display == null || !display.isValid();
            if (created) {
                display = spawn(location, item, shape);
            } else {
                for (Player viewer : javaViewers) {
                    if (!viewers.contains(viewer) && viewer.isOnline()) {
                        viewer.hideEntity(plugin, display);
                    }
                }
                Location previous = display.getLocation();
                if (previous.distanceSquared(location) > 0) {
                    previous.setX(location.getX());
                    previous.setY(location.getY());
                    previous.setZ(location.getZ());
                    display.teleport(previous);
                }
                if (!display.getTransformation().equals(shape)) {
                    display.setInterpolationDelay(0);
                    display.setTransformation(shape);
                }
            }
            for (Player viewer : viewers) {
                if (created || !javaViewers.contains(viewer)) {
                    viewer.showEntity(plugin, display);
                }
            }
            return display;
        }

        /** 統合版は一つのモデルを移動し、変化した場合だけ針と糸の状態を通知します。 */
        private void updateBedrock(Location rodTip, Location hookLocation, double sag, Set<Player> viewers) {
            if (viewers.isEmpty()) {
                clearBedrock();
                return;
            }

            Location current = new Location(rodTip.getWorld(), bedrockCoordinate(rodTip.getX()),
                bedrockCoordinate(rodTip.getY()), bedrockCoordinate(rodTip.getZ()));
            long dx = origin == null ? 0 : Math.round((current.getX() - origin.getX()) * MOVE_UNITS);
            long dy = origin == null ? 0 : Math.round((current.getY() - origin.getY()) * MOVE_UNITS);
            long dz = origin == null ? 0 : Math.round((current.getZ() - origin.getZ()) * MOVE_UNITS);
            if (!fitsMove(dx) || !fitsMove(dy) || !fitsMove(dz)) {
                clearBedrock();
                dx = dy = dz = 0;
            }
            if (origin == null) {
                bedrockId = PacketEntityIdAllocator.nextEntityId();
                removePacket = destroy(bedrockId);
            }

            Pose nextPose = encodePose(current, hookLocation, sag);
            PacketContainer movement = dx == 0 && dy == 0 && dz == 0 ? null : move(bedrockId, dx, dy, dz);
            PacketContainer update = null;
            PacketContainer spawn = null;
            var iterator = bedrockViewers.iterator();
            while (iterator.hasNext()) {
                Player viewer = iterator.next();
                if (!viewers.contains(viewer)) {
                    if (viewer.isOnline() && viewer.getWorld() == current.getWorld()) {
                        send(viewer, removePacket);
                    }
                    iterator.remove();
                }
            }

            for (Player viewer : viewers) {
                boolean tracked = bedrockViewers.contains(viewer);
                if (!tracked) {
                    if (spawn == null) {
                        spawn = spawnMarker(bedrockId, 0, current);
                    }
                    if (!send(viewer, spawn)) {
                        continue;
                    }
                    bedrockViewers.add(viewer);
                } else if (movement != null && !send(viewer, movement)) {
                    send(viewer, removePacket);
                    bedrockViewers.remove(viewer);
                    continue;
                }

                if (!tracked || !nextPose.equals(pose)) {
                    if (update == null) {
                        update = poseMarker(bedrockId, nextPose, current);
                    }
                    if (!send(viewer, update)) {
                        send(viewer, removePacket);
                        bedrockViewers.remove(viewer);
                    }
                }
            }
            origin = current;
            pose = nextPose;
        }

        /** 既存の閲覧者から仮想表示を削除し、次の描画を新しい表示から開始します。 */
        private void clearBedrock() {
            if (origin != null) {
                removeForViewers(bedrockViewers, origin, removePacket);
            }
            bedrockViewers.clear();
            origin = null;
            removePacket = null;
        }

        /** Javaプロトコルの相対移動で表せる範囲かを返します。 */
        private boolean fitsMove(long delta) {
            return delta >= Short.MIN_VALUE && delta <= Short.MAX_VALUE;
        }
    }

    private record Pose(int shift, long bits) {}
    private record Audience(Set<Player> java, Set<Player> bedrock) {}
    private record Effect(Player owner, @Nullable ItemDisplay model, @Nullable PacketContainer remove,
                          Set<Player> bedrock, Location origin, int duration, int startedAt) {}
}
