package io.github.maaasu.astralRecord.feature.mob.service;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.WrappedBlockData;
import com.comphenix.protocol.wrappers.WrappedDataValue;
import com.comphenix.protocol.wrappers.WrappedDataWatcher;
import io.github.maaasu.astralRecord.feature.resourcepack.service.BedrockPlayerDetector;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigProperties;
import io.github.maaasu.astralRecord.shared.display.WorldDisplayModel;
import org.bukkit.Material;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** NPC の表示を追跡する統合版接続に、モデルと従来表示を区別する印を付けます。 */
final class NpcWorldModelPackets extends PacketAdapter {
    private static final int DISPLAY_TRANSLATION = 11;
    private static final int DISPLAY_CONTENT = 23;
    private static final int ITEM_DISPLAY_TRANSFORM = 24;

    private final Map<Integer, Binding> displays = new ConcurrentHashMap<>();
    private final ConfigProperties config = ConfigProperties.getInstance();

    /** @param plugin 送信リスナーを所有するプラグイン */
    NpcWorldModelPackets(Plugin plugin) {
        super(params(plugin, PacketType.Play.Server.BUNDLE, PacketType.Play.Server.SPAWN_ENTITY,
                PacketType.Play.Server.ENTITY_METADATA).optionAsync());
    }

    /**
     * @param marker 専用モデルへ変換する空の表示
     * @param model 統合版に渡すモデル種別
     */
    void trackMarker(TextDisplay marker, WorldDisplayModel model) {
        int entityId = marker.getEntityId();
        displays.put(entityId, new Binding(marker.getUniqueId(), model.bedrockMarker(entityId, false), false));
    }

    /**
     * @param display Java の表示と統合版の従来表示を兼ねる実体
     * @param model 表示するモデル
     * @param markerAvailable 対応パックの接続で従来表示を抑止できる場合は true
     */
    void trackFallback(ItemDisplay display, WorldDisplayModel model, boolean markerAvailable) {
        int entityId = display.getEntityId();
        boolean blockFallback = model == WorldDisplayModel.BLAST_FURNACE;
        if (markerAvailable || blockFallback) {
            UUID marker = markerAvailable ? model.bedrockMarker(entityId, true) : null;
            displays.put(entityId, new Binding(display.getUniqueId(), marker, blockFallback));
        } else {
            untrack(entityId);
        }
    }

    /** @param entityId 破棄した表示の実体ID */
    void untrack(int entityId) {
        displays.remove(entityId);
    }

    /**
     * @param viewer 表示を受け取る接続
     * @return 統合版として扱う接続の場合は true
     */
    boolean isBedrock(Player viewer) {
        return BedrockPlayerDetector.isBedrock(viewer.getName(), config.getResourcePackBedrockNamePrefixes());
    }

    /**
     * 元パケットを共有する Java の閲覧者を残し、統合版用パケットのコピーだけを変更します。
     *
     * @param event 追跡開始またはメタデータの送信
     */
    @Override
    public void onPacketSending(PacketEvent event) {
        var viewer = event.getPlayer();
        if (displays.isEmpty() || viewer == null || !isBedrock(viewer)) {
            return;
        }
        PacketContainer packet = event.getPacket();
        if (packet.getType() != PacketType.Play.Server.BUNDLE) {
            PacketContainer adapted = adapt(packet);
            if (adapted != packet) {
                event.setPacket(adapted);
            }
            return;
        }
        List<PacketContainer> outgoing = new ArrayList<>();
        boolean changed = false;
        for (PacketContainer member : packet.getPacketBundles().read(0)) {
            PacketContainer adapted = adapt(member);
            outgoing.add(adapted);
            changed |= adapted != member;
        }
        if (changed) {
            PacketContainer bundle = packet.shallowClone();
            bundle.getPacketBundles().write(0, outgoing);
            event.setPacket(bundle);
        }
    }

    /**
     * @param packet 統合版に送る個別パケット
     * @return 対象NPCの表示を変換したコピー、または変更対象外の元パケット
     */
    private PacketContainer adapt(PacketContainer packet) {
        PacketType type = packet.getType();
        if (type != PacketType.Play.Server.SPAWN_ENTITY && type != PacketType.Play.Server.ENTITY_METADATA) {
            return packet;
        }
        int entityId = packet.getIntegers().read(0);
        Binding binding = displays.get(entityId);
        if (binding == null) {
            return packet;
        }
        if (type == PacketType.Play.Server.ENTITY_METADATA) {
            return binding.blockFallback() ? furnaceFallbackMetadata(packet) : packet;
        }
        if (!binding.uuid().equals(packet.getUUIDs().read(0))) {
            return packet;
        }
        PacketContainer spawn = packet.shallowClone();
        if (binding.marker() != null) {
            spawn.getUUIDs().write(0, binding.marker());
        }
        if (binding.blockFallback()) {
            spawn.getEntityTypeModifier().write(0, EntityType.BLOCK_DISPLAY);
        }
        return spawn;
    }

    /**
     * @param packet 溶鉱炉の ItemDisplay メタデータ
     * @return 従来の BlockDisplay と同じ位置、素材のメタデータ
     */
    private PacketContainer furnaceFallbackMetadata(PacketContainer packet) {
        List<WrappedDataValue> values = new ArrayList<>();
        for (WrappedDataValue value : packet.getDataValueCollectionModifier().read(0)) {
            switch (value.getIndex()) {
                case DISPLAY_TRANSLATION -> values.add(new WrappedDataValue(DISPLAY_TRANSLATION, value.getSerializer(),
                        MobEntityController.blockDisplayTransformation().getTranslation()));
                case DISPLAY_CONTENT -> values.add(WrappedDataValue.fromWrappedValue(DISPLAY_CONTENT,
                        WrappedDataWatcher.Registry.getBlockDataSerializer(false),
                        WrappedBlockData.createData(Material.BLAST_FURNACE)));
                case ITEM_DISPLAY_TRANSFORM -> { }
                default -> values.add(value);
            }
        }
        PacketContainer metadata = packet.shallowClone();
        metadata.getDataValueCollectionModifier().write(0, values);
        return metadata;
    }

    private record Binding(UUID uuid, @Nullable UUID marker, boolean blockFallback) { }
}
