package io.github.maaasu.astralRecord.feature.skill.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.papermc.paper.event.player.PlayerUntrackEntityEvent;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Parrot;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 戦闘用のオウムを動かさず、閲覧者ごとに不死鳥の本体を表示します。 */
final class ArchmagePhoenixBodyDisplay implements Listener {
    private static final long BEDROCK_MARKER = 0x415250484F454E58L; // ARPHOENX
    private static final NamespacedKey ITEM_MODEL = new NamespacedKey("astralrecord", "archmage_phoenix");
    private static final int MOVE_TICKS = 2;
    private static final int FRAME_TICKS = 6;
    private static final int[] WING_CYCLE = {0, 1, 0, 2};
    private static final double VIEW_DISTANCE_SQUARED = 32.0D * 32.0D;

    private final Plugin plugin;
    private final ResourcePackService packs;
    private final ProtocolManager protocol = ProtocolLibrary.getProtocolManager();
    private final PacketAdapter packets;
    private final Map<Integer, Body> bodies = new ConcurrentHashMap<>();
    private final Map<Integer, Body> javaModels = new HashMap<>();
    private final List<ItemStack> frames = List.of(frameItem(0), frameItem(1), frameItem(2));

    /**
     * ワールド追加時のスポーンパケットを捕捉できるよう、監視を登録します。
     * @param plugin 可視性と監視を所有するプラグイン
     * @param packs 閲覧者のパック適用状態
     */
    ArchmagePhoenixBodyDisplay(Plugin plugin, ResourcePackService packs) {
        this.plugin = plugin;
        this.packs = packs;
        packets = new PacketAdapter(plugin, PacketType.Play.Server.SPAWN_ENTITY, PacketType.Play.Server.BUNDLE) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (bodies.isEmpty()) return;
                UUID viewerId = event.getPlayer().getUniqueId();
                if (!packs.hasBedrockArchmagePhoenixPresentation(viewerId)) return;
                try {
                    PacketContainer original = event.getPacket();
                    PacketContainer updated = rewrite(viewerId, original);
                    if (updated != original) event.setPacket(updated);
                } catch (RuntimeException exception) {
                    Logger.log(LogId.E_5804, exception);
                }
            }
        };
        protocol.addPacketListener(packets);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    /**
     * @param source ワールド追加前の召喚実体
     * @param tick 召喚時のtick
     */
    void track(Parrot source, long tick) {
        bodies.put(source.getEntityId(), new Body(source, tick));
    }

    /**
     * @param event 追跡終了。統合版の記録を解除し、Javaモデルが外れた閲覧者には実体を復帰させます
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onUntrack(PlayerUntrackEntityEvent event) {
        if (event.getEntity() instanceof Parrot source) {
            Body body = bodies.get(source.getEntityId());
            if (body != null && body.source == source) {
                body.bedrock.remove(event.getPlayer().getUniqueId());
            }
        } else if (event.getEntity() instanceof ItemDisplay model) {
            Body body = javaModels.get(model.getEntityId());
            if (body != null && body.model == model) restoreJavaViewer(body, event.getPlayer());
        }
    }

    /**
     * @param viewerId 対応を確認済みの統合版閲覧者
     * @param packet 元パケット
     * @return 召喚実体のUUIDだけを置き換えた、閲覧者専用のパケット
     */
    private PacketContainer rewrite(UUID viewerId, PacketContainer packet) {
        if (packet.getType() == PacketType.Play.Server.BUNDLE) {
            Iterable<PacketContainer> children = packet.getPacketBundles().readSafely(0);
            if (children == null) return packet;
            List<PacketContainer> rewritten = new ArrayList<>();
            boolean changed = false;
            for (PacketContainer child : children) {
                PacketContainer updated = rewrite(viewerId, child);
                rewritten.add(updated);
                changed |= updated != child;
            }
            if (!changed) return packet;
            PacketContainer result = packet.shallowClone();
            result.getPacketBundles().write(0, rewritten);
            return result;
        }
        if (packet.getType() != PacketType.Play.Server.SPAWN_ENTITY) return packet;
        Integer id = packet.getIntegers().readSafely(0);
        Body body = id == null ? null : bodies.get(id);
        if (body == null || !body.sourceId.equals(packet.getUUIDs().readSafely(0))) return packet;
        PacketContainer result = packet.shallowClone();
        result.getUUIDs().write(0, new UUID(BEDROCK_MARKER, body.sourceId.getLeastSignificantBits()));
        body.bedrock.add(viewerId);
        return result;
    }

    /**
     * @param source 召喚実体
     * @param position 実際の飛行位置。移動先を確定できない場合はnull
     * @param tick 現在tick
     */
    void update(Parrot source, @Nullable Location position, long tick) {
        Body body = bodies.get(source.getEntityId());
        if (body == null) return;
        try {
            if (position == null || (body.model != null
                    && (!body.model.isValid() || body.model.getWorld() != position.getWorld()))) {
                removeJavaModel(body);
                if (position == null) return;
            }
            long age = tick - body.started;
            int frame = WING_CYCLE[(int) (age / FRAME_TICKS % WING_CYCLE.length)];
            if (tick >= body.nextViewerSync) {
                body.nextViewerSync = tick + FRAME_TICKS;
                syncViewers(source, body, position, frame);
            }
            if (!body.shown.isEmpty() && age % MOVE_TICKS == 0) {
                if (!moveModel(body, position, frame)) removeJavaModel(body);
            }
        } catch (RuntimeException exception) {
            removeJavaModel(body);
            Logger.log(LogId.E_5804, exception);
        }
    }

    /**
     * @param source 召喚実体
     * @param body この実体の表示状態
     * @param position 実体の表示原点
     * @param frame 現在の翼フレーム
     */
    private void syncViewers(Parrot source, Body body, Location position, int frame) {
        Set<Player> tracked = Set.copyOf(source.getTrackedBy());
        body.bedrock.removeIf(viewerId -> {
            Player viewer = plugin.getServer().getPlayer(viewerId);
            return viewer == null || !tracked.contains(viewer);
        });
        Set<Player> eligible = new HashSet<>();
        for (Player viewer : tracked) {
            UUID viewerId = viewer.getUniqueId();
            if (packs.hasBedrockArchmagePhoenixPresentation(viewerId)) {
                if (!body.bedrock.contains(viewerId)) {
                    // 通知が追跡開始より遅れた場合、対象接続にだけスポーンを送り直す。
                    viewer.hideEntity(plugin, source);
                    viewer.showEntity(plugin, source);
                }
            } else if (canShowJava(viewer, position)) {
                eligible.add(viewer);
            }
        }

        Set<Player> modelTracked = body.model == null ? Set.of() : body.model.getTrackedBy();
        for (Player viewer : body.hidden) {
            if (modelTracked.contains(viewer) && canShowJava(viewer, position)) eligible.add(viewer);
        }
        for (Player viewer : List.copyOf(body.shown)) {
            if (!eligible.contains(viewer)) {
                restoreJavaViewer(body, viewer);
                if (viewer.isOnline()) viewer.hideEntity(plugin, body.model);
                body.shown.remove(viewer);
            }
        }
        if (eligible.isEmpty()) return;
        if (body.model == null) {
            ItemDisplay model = spawnModel(position, frame);
            if (!model.isValid()) return;
            body.model = model;
            javaModels.put(model.getEntityId(), body);
            body.frame = frame;
        } else if (body.shown.isEmpty() && !moveModel(body, position, frame)) {
            removeJavaModel(body);
            return;
        }

        for (Player viewer : eligible) {
            if (body.shown.add(viewer)) viewer.showEntity(plugin, body.model);
        }
        modelTracked = body.model.getTrackedBy();
        for (Player viewer : eligible) {
            if (modelTracked.contains(viewer) && body.hidden.add(viewer)) viewer.hideEntity(plugin, source);
        }
    }

    /**
     * @param viewer 閲覧者
     * @param position 不死鳥の位置
     * @return 同じワールドの表示範囲内にいて対応Javaパックを適用済みならtrue
     */
    private boolean canShowJava(Player viewer, Location position) {
        return viewer.isOnline() && viewer.getWorld() == position.getWorld()
            && packs.hasJavaArchmagePhoenixPresentation(viewer.getUniqueId())
            && viewer.getLocation().distanceSquared(position) <= VIEW_DISTANCE_SQUARED;
    }

    /**
     * @param body Java表示の状態
     * @param position 実体の表示原点
     * @param frame 現在の翼フレーム
     * @return 位置を反映できた場合はtrue
     */
    private boolean moveModel(Body body, Location position, int frame) {
        Location previous = body.model.getLocation();
        if (!position.equals(previous)) {
            body.model.setTeleportDuration(previous.distanceSquared(position) > 9.0D ? 0 : MOVE_TICKS);
            if (!body.model.teleport(position)) return false;
        }
        if (body.frame != frame) {
            body.model.setItemStack(frames.get(frame));
            body.frame = frame;
        }
        return true;
    }

    /**
     * @param position 実体の表示原点
     * @param frame 開始時の翼フレーム
     * @return 対応閲覧者にだけ公開するモデル
     */
    private ItemDisplay spawnModel(Location position, int frame) {
        return position.getWorld().spawn(position, ItemDisplay.class, display -> {
            display.setVisibleByDefault(false);
            display.setPersistent(false);
            display.setInvulnerable(true);
            display.setBrightness(new Display.Brightness(15, 15));
            display.setViewRange(2.0F);
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.FIXED);
            display.setItemStack(frames.get(frame));
        });
    }

    /**
     * @param frame パック定義の翼フレーム番号
     * @return ItemDisplayに設定する専用モデルアイテム
     */
    private static ItemStack frameItem(int frame) {
        ItemStack item = ItemStack.of(Material.PAPER);
        var meta = item.getItemMeta();
        meta.setItemModel(ITEM_MODEL);
        var data = meta.getCustomModelDataComponent();
        data.setFloats(List.of((float) frame));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }

    /**
     * @param viewer 閲覧者
     * @return Floodgateの接続情報または登録前の名前規則で統合版と判定された場合はtrue
     */
    boolean isBedrock(Player viewer) {
        var player = AstPlayerCache.get(viewer);
        return player == null ? packs.isBedrock(viewer.getName()) : player.isBedrock();
    }

    /**
     * @param viewer 閲覧者
     * @param source 召喚実体
     * @return Javaモデルへ切替済み、または統合版向けマーカーを送信済みの場合はtrue
     */
    boolean hasModel(Player viewer, Parrot source) {
        Body body = bodies.get(source.getEntityId());
        return body != null && (body.hidden.contains(viewer) || (body.bedrock.contains(viewer.getUniqueId())
            && packs.hasBedrockArchmagePhoenixPresentation(viewer.getUniqueId())));
    }

    /**
     * @param source 終了した召喚実体
     */
    void remove(Parrot source) {
        Body body = bodies.remove(source.getEntityId());
        if (body != null) removeJavaModel(body);
    }

    /** プラグイン停止時にパケット監視とイベントの購読を解除します。 */
    void stop() {
        protocol.removePacketListener(packets);
        HandlerList.unregisterAll(this);
        for (Body body : bodies.values()) removeJavaModel(body);
        bodies.clear();
    }

    /**
     * @param body 回収するJavaモデルの状態
     */
    private void removeJavaModel(Body body) {
        for (Player viewer : List.copyOf(body.hidden)) restoreJavaViewer(body, viewer);
        body.shown.clear();
        if (body.model != null) {
            javaModels.remove(body.model.getEntityId());
            body.model.remove();
        }
        body.model = null;
    }

    /**
     * @param body Javaモデルで置き換えていた実体
     * @param viewer 元のオウムを再表示する閲覧者
     */
    private void restoreJavaViewer(Body body, Player viewer) {
        if (body.hidden.remove(viewer) && viewer.isOnline()) viewer.showEntity(plugin, body.source);
    }

    /** 1個体の表示と、パケット送信スレッドからも更新する統合版の追跡状態です。 */
    private static final class Body {
        private final Parrot source;
        private final UUID sourceId;
        private final long started;
        private final Set<Player> shown = new HashSet<>();
        private final Set<Player> hidden = new HashSet<>();
        private final Set<UUID> bedrock = ConcurrentHashMap.newKeySet();
        private long nextViewerSync;
        private ItemDisplay model;
        private int frame;

        private Body(Parrot source, long started) {
            this.source = source;
            this.sourceId = source.getUniqueId();
            this.started = started;
            this.nextViewerSync = started;
        }
    }
}
