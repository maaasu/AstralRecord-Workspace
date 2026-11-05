package io.github.maaasu.astralRecord.shared.display;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.model.DamageNumberSize;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.resourcepack.service.ResourcePackService;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.Plugin;
import org.joml.Vector3f;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.StreamSupport;

/**
 * 共有のダメージ数値を、接続先のエディションと閲覧者の設定に合わせて送信します。
 */
final class DamageNumberPacketAdapter extends PacketAdapter implements Listener {
    private static final int NO_GRAVITY_INDEX = 5;
    private static final int DISPLAY_SCALE_INDEX = 12;
    private static final int DISPLAY_TEXT_INDEX = 23;
    private static final long BEDROCK_DISPLAY_TAG = 0xa57ad00d00000000L;
    private static final long MAX_BEDROCK_AMOUNT = 999_999_999_999L;
    private static final Key DAMAGE_FONT = Key.key("astralrecord", "damage_number");

    private final Map<Integer, NumberDisplay> activeNumbers;
    private final PlayerSettingService playerSettingService;
    private final ResourcePackService resourcePackService;
    private final Map<UUID, Map<Integer, Integer>> bedrockMarkers = new ConcurrentHashMap<>();

    /**
     * TextDisplay の追跡開始、メタデータ、削除の送信を受け取ります。
     *
     * @param plugin リスナーを所有するプラグイン
     * @param activeNumbers 表示サービスが更新する並行アクセス可能な実体情報
     * @param playerSettingService ロード済みの設定の参照先
     * @param resourcePackService エディションとJavaパック適用状態の参照先
     */
    DamageNumberPacketAdapter(
            Plugin plugin,
            Map<Integer, NumberDisplay> activeNumbers,
            PlayerSettingService playerSettingService,
            ResourcePackService resourcePackService
    ) {
        super(params(plugin, PacketType.Play.Server.BUNDLE, PacketType.Play.Server.ENTITY_METADATA,
                PacketType.Play.Server.ENTITY_DESTROY).optionAsync());
        this.activeNumbers = activeNumbers;
        this.playerSettingService = playerSettingService;
        this.resourcePackService = resourcePackService;
    }

    /**
     * 実体を共有したまま、受信者向けパケットだけを変更します。
     *
     * @param event 送信イベント。元パケットは複数の閲覧者が共有するため変更しない
     */
    @Override
    public void onPacketSending(PacketEvent event) {
        Player viewer = event.getPlayer();
        if (viewer == null) {
            return;
        }
        PacketContainer packet = event.getPacket();
        PacketType type = event.getPacketType();
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            NumberDisplay number = activeNumbers.get(packet.getIntegers().read(0));
            if (number != null && !resourcePackService.isBedrock(viewer.getName())) {
                personalizeJavaNumber(event, viewer, number);
            }
        } else if (type == PacketType.Play.Server.BUNDLE) {
            if (!activeNumbers.isEmpty() && resourcePackService.isBedrock(viewer.getName())) {
                pairBedrockMarker(event, viewer);
            }
        } else if (type == PacketType.Play.Server.ENTITY_DESTROY && !bedrockMarkers.isEmpty()) {
            appendBedrockRemovals(event, viewer.getUniqueId());
        }
    }

    /**
     * フォント適用に成功したJava接続には専用の数字フォントを選び、設定倍率は全Java接続に反映します。
     *
     * @param event 対象実体のメタデータ
     * @param viewer Java版の閲覧者
     * @param number 表示内容
     */
    private void personalizeJavaNumber(PacketEvent event, Player viewer, NumberDisplay number) {
        DamageNumberSize size = resolveSize(viewer);
        boolean fontAvailable = resourcePackService.hasManagedJavaPack(viewer.getUniqueId());
        if (size == DamageNumberSize.NORMAL && !fontAvailable) {
            return;
        }
        PacketContainer packet = event.getPacket();
        List<WrappedDataValue> metadata = packet.getDataValueCollectionModifier().read(0);
        List<WrappedDataValue> changes = null;
        for (int index = 0; index < metadata.size(); index++) {
            WrappedDataValue value = metadata.get(index);
            WrappedDataValue replacement = null;
            if (size != DamageNumberSize.NORMAL && value.getIndex() == DISPLAY_SCALE_INDEX
                    && value.getRawValue() instanceof Vector3f scale) {
                replacement = new WrappedDataValue(DISPLAY_SCALE_INDEX, value.getSerializer(),
                        new Vector3f(scale).mul(size.getScale()));
            } else if (fontAvailable && value.getIndex() == DISPLAY_TEXT_INDEX) {
                String json = GsonComponentSerializer.gson().serialize(number.text().font(DAMAGE_FONT));
                replacement = WrappedDataValue.fromWrappedValue(DISPLAY_TEXT_INDEX, value.getSerializer(),
                        WrappedChatComponent.fromJson(json));
            }
            if (replacement != null) {
                if (changes == null) changes = new ArrayList<>(metadata);
                changes.set(index, replacement);
            }
        }
        if (changes != null) {
            PacketContainer personalized = packet.shallowClone();
            personalized.getDataValueCollectionModifier().write(0, changes);
            event.setPacket(personalized);
        }
    }

    /**
     * Paper の追跡開始バンドルに、Geyser がモデルに置き換えられる空の表示を添えます。
     * パック未取得時は Geyser が元の文字表示を選びます。
     *
     * @param event 追跡開始のバンドル
     * @param viewer 統合版の閲覧者
     */
    private void pairBedrockMarker(PacketEvent event, Player viewer) {
        PacketContainer bundle = event.getPacket();
        Iterable<PacketContainer> packets = bundle.getPacketBundles().read(0);
        Map<Integer, Integer> viewerMarkers = bedrockMarkers.get(viewer.getUniqueId());
        if (viewerMarkers == null || StreamSupport.stream(packets.spliterator(), false)
                .noneMatch(packet -> bedrockNumber(packet) != null)) {
            return;
        }
        List<PacketContainer> outgoing = new ArrayList<>();
        boolean addedMarker = false;
        for (PacketContainer packet : packets) {
            NumberDisplay number = bedrockNumber(packet);
            if (number == null) {
                outgoing.add(packet);
                continue;
            }
            int realId = packet.getIntegers().read(0);
            DamageNumberSize size = resolveSize(viewer);
            int markerId = viewerMarkers.computeIfAbsent(realId, ignored -> PacketEntityIdAllocator.nextEntityId());
            long payload = number.roundedAmount() | ((long) number.style() << 48)
                    | ((long) sizeCode(size) << 50);
            long tag = BEDROCK_DISPLAY_TAG | Integer.toUnsignedLong(realId);
            PacketContainer original = packet.shallowClone();
            original.getUUIDs().write(0, new UUID(tag, payload));
            PacketContainer marker = packet.shallowClone();
            marker.getIntegers().write(0, markerId);
            marker.getUUIDs().write(0, new UUID(tag, payload | (1L << 52)));
            outgoing.add(original);
            outgoing.add(marker);
            outgoing.add(noGravity(markerId));
            addedMarker = true;
        }
        if (addedMarker) {
            PacketContainer personalized = bundle.shallowClone();
            personalized.getPacketBundles().write(0, outgoing);
            event.setPacket(personalized);
        }
    }

    /**
     * @param packet バンドル内のパケット
     * @return 数字モデルで表示可能な生成パケットなら対応する数値、それ以外はnull
     */
    private NumberDisplay bedrockNumber(PacketContainer packet) {
        if (packet.getType() != PacketType.Play.Server.SPAWN_ENTITY) {
            return null;
        }
        NumberDisplay number = activeNumbers.get(packet.getIntegers().read(0));
        return number != null && number.roundedAmount() >= 0 && number.roundedAmount() <= MAX_BEDROCK_AMOUNT
                ? number : null;
    }

    /**
     * 名前を持たないパック用実体に、落下を止めるメタデータだけを送ります。
     *
     * @param markerId パック用のクライアント実体ID
     * @return 重力を無効にするパケット
     */
    private static PacketContainer noGravity(int markerId) {
        PacketContainer metadata = ProtocolLibrary.getProtocolManager().createPacket(PacketType.Play.Server.ENTITY_METADATA);
        metadata.getIntegers().write(0, markerId);
        metadata.getDataValueCollectionModifier().write(0, List.of(
                new WrappedDataValue(NO_GRAVITY_INDEX, WrappedDataWatcher.Registry.get((Type) Boolean.class, false), true)));
        return metadata;
    }

    /**
     * 統合版接続の追跡状態を作ります。パケット送信側では状態を新規作成しません。
     *
     * @param event プレイヤー参加イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        trackViewer(event.getPlayer());
    }

    /**
     * @param player 参加またはアダプター起動時点ですでに接続中のプレイヤー
     */
    void trackViewer(Player player) {
        if (resourcePackService.isBedrock(player.getName())) {
            bedrockMarkers.computeIfAbsent(player.getUniqueId(), ignored -> new ConcurrentHashMap<>());
        }
    }

    /**
     * 追跡終了した元実体と同じタイミングで、統合版のパック用表示も削除します。
     *
     * @param event 元の実体削除イベント
     * @param viewerId 閲覧者のUUID
     */
    private void appendBedrockRemovals(PacketEvent event, UUID viewerId) {
        Map<Integer, Integer> viewerMarkers = bedrockMarkers.get(viewerId);
        if (viewerMarkers == null || viewerMarkers.isEmpty()) {
            return;
        }
        PacketContainer packet = event.getPacket();
        List<Integer> removals = packet.getIntLists().read(0);
        List<Integer> both = null;
        for (int realId : removals) {
            Integer markerId = viewerMarkers.remove(realId);
            if (markerId != null) {
                if (both == null) both = new ArrayList<>(removals);
                both.add(markerId);
            }
        }
        if (both != null) {
            PacketContainer personalized = packet.shallowClone();
            personalized.getIntLists().write(0, both);
            event.setPacket(personalized);
        }
    }

    /**
     * 接続に保持していた仮想実体IDを退出時に破棄します。
     *
     * @param event プレイヤー退出イベント
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        bedrockMarkers.remove(event.getPlayer().getUniqueId());
    }

    /**
     * アダプターの解除前に、接続中の統合版クライアントからパック用実体を回収します。
     *
     * @param plugin 接続中のプレイヤーを解決するプラグイン
     */
    void removeMarkers(Plugin plugin) {
        var protocol = ProtocolLibrary.getProtocolManager();
        for (var entry : bedrockMarkers.entrySet()) {
            Player viewer = plugin.getServer().getPlayer(entry.getKey());
            if (viewer != null && !entry.getValue().isEmpty()) {
                PacketContainer destroy = protocol.createPacket(PacketType.Play.Server.ENTITY_DESTROY);
                destroy.getIntLists().write(0, List.copyOf(entry.getValue().values()));
                protocol.sendServerPacket(viewer, destroy, false);
            }
        }
        bedrockMarkers.clear();
    }

    /**
     * メモリ上の設定のみを参照し、参加直後の未読込状態は標準として扱います。
     *
     * @param viewer パケットの閲覧者
     * @return 選択された数値サイズ
     */
    private DamageNumberSize resolveSize(Player viewer) {
        AstPlayer cached = AstPlayerCache.get(viewer);
        return cached == null ? DamageNumberSize.NORMAL
                : playerSettingService.getCachedDamageNumberSize(cached.getUser().getUuid());
    }

    /**
     * @param size 閲覧者の設定
     * @return Geyserと共有するサイズのコード
     */
    private static int sizeCode(DamageNumberSize size) {
        return switch (size) {
            case SMALL -> 0;
            case NORMAL -> 1;
            case LARGE -> 2;
        };
    }

    /** @param roundedAmount パック用の整数値（有限の範囲外なら負値）
     * @param style 通常0、会心1、シールド2
     * @param text Javaで表示する既存書式の本文
     */
    record NumberDisplay(long roundedAmount, int style, Component text) { }
}
