package io.github.maaasu.astralRecord.feature.dungeon.service;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonLayout;
import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonMapRoomState;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.MapId;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.map.MapCanvas;
import org.bukkit.map.MapRenderer;
import org.bukkit.map.MapView;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 装備実体は変更せず、登録済みカルトグラフの手持ち表示と地図データを本人に送ります。 */
public final class CartographHeldMap {
    private static final BufferedImage EMPTY = new BufferedImage(
            CartographMapRaster.SIZE, CartographMapRaster.SIZE, BufferedImage.TYPE_INT_ARGB);

    private final NamespacedKey mapKey;
    private final Map<UUID, Projection> projections = new ConcurrentHashMap<>();
    private final Map<UUID, Frame> frames = new HashMap<>();
    private final MapRenderer renderer = new MapRenderer(true) {
        @Override
        public void render(@NotNull MapView map, @NotNull MapCanvas canvas, @NotNull Player player) {
            renderFrame(canvas, frames.get(player.getUniqueId()));
        }
    };
    private @Nullable MapView map;

    CartographHeldMap(@NotNull Plugin plugin) {
        mapKey = new NamespacedKey(plugin, "cartograph_map_id");
    }

    /**
     * 現在有効な表示を同期します。位置・向き・進行が同じ場合はパケットを送りません。
     * Bukkit メインスレッドから呼び出します。
     */
    void update(
            @NotNull Player player,
            @NotNull String equipmentInstanceId,
            @NotNull DungeonLayout layout,
            @NotNull Map<Integer, DungeonMapRoomState> states,
            @Nullable Integer currentRoom,
            @NotNull Location location
    ) {
        UUID playerId = player.getUniqueId();
        int slot = player.getInventory().getHeldItemSlot();
        if (!equipmentInstanceId.equals(ItemStackFactory.getEquipmentInstanceId(player.getInventory().getItemInMainHand()))) {
            clear(playerId);
            return;
        }

        MapView view = mapView();
        Projection projection = new Projection(slot, equipmentInstanceId, MapId.mapId(view.getId()));
        Frame previous = frames.get(playerId);
        CartographMapRaster raster = previous != null && previous.raster().matches(layout, states, currentRoom)
                ? previous.raster() : new CartographMapRaster(layout, states, currentRoom);
        CartographMapRaster.Marker marker = raster.marker(location.getX(), location.getZ(), location.getYaw());
        boolean sameItem = projection.equals(projections.get(playerId));
        if (sameItem && previous != null && previous.raster() == raster && marker.equals(previous.marker())) {
            return;
        }

        frames.put(playerId, new Frame(raster, marker,
                previous != null && previous.raster() == raster ? previous.marker() : null));
        if (!sameItem) {
            projections.put(playerId, projection);
            player.updateInventory();
        }
        player.sendMap(view);
    }

    /**
     * パケットアダプタが参照する、受信者ごとの不変表示情報を返します。
     *
     * @param playerId 受信者の UUID
     * @return 手持ち地図の送信情報。表示対象外では null
     */
    public @Nullable Projection projection(@NotNull UUID playerId) {
        return projections.get(playerId);
    }

    Set<UUID> viewers() {
        return Set.copyOf(frames.keySet());
    }

    void clear(@NotNull UUID playerId) {
        frames.remove(playerId);
        if (projections.remove(playerId) != null) {
            Player player = Bukkit.getPlayer(playerId);
            if (player != null && player.isOnline()) {
                player.updateInventory();
            }
        }
    }

    void stop() {
        viewers().forEach(this::clear);
        if (map != null) {
            map.removeRenderer(renderer);
            map = null;
        }
    }

    private @NotNull MapView mapView() {
        if (map != null) {
            return map;
        }
        // 一時インスタンスではなく常設ワールドに表示用の地図 ID を一つ確保します。
        World world = Bukkit.getWorlds().getFirst();
        var data = world.getPersistentDataContainer();
        Integer savedId = data.get(mapKey, PersistentDataType.INTEGER);
        MapView view = savedId == null ? null : Bukkit.getMap(savedId);
        if (view == null) {
            view = Bukkit.createMap(world);
            data.set(mapKey, PersistentDataType.INTEGER, view.getId());
        }
        view.getRenderers().forEach(view::removeRenderer);
        view.setTrackingPosition(false);
        view.setLocked(true);
        view.addRenderer(renderer);
        map = view;
        return view;
    }

    private static void renderFrame(@NotNull MapCanvas canvas, @Nullable Frame frame) {
        if (frame == null) {
            canvas.drawImage(0, 0, EMPTY);
            return;
        }
        BufferedImage background = frame.raster().background();
        CartographMapRaster.Marker previous = frame.previousMarker();
        if (previous == null) {
            canvas.drawImage(0, 0, background);
        } else {
            canvas.drawImage(previous.left(), previous.top(), previous.backgroundPatch(background));
        }
        CartographMapRaster.Marker marker = frame.marker();
        canvas.drawImage(marker.left(), marker.top(), marker.paintedPatch(background));
    }

    /** 特定の本人・選択中スロット・装備個体にだけ使う送信用の見た目です。 */
    public record Projection(int slot, @NotNull String equipmentInstanceId, @NotNull MapId mapId) {
        /**
         * 通常の送信表示を元に地図のコピーを作成します。
         *
         * @param hotbarSlot 送信先のホットバースロット。ホットバー外は負値
         * @param item 通常の送信表示
         * @return 地図の表示コピー。スロットまたは装備個体が異なる場合は null
         */
        public @Nullable ItemStack apply(int hotbarSlot, @NotNull ItemStack item) {
            if (hotbarSlot != slot || !equipmentInstanceId.equals(ItemStackFactory.getEquipmentInstanceId(item))) {
                return null;
            }
            ItemStack display = item.withType(Material.FILLED_MAP);
            display.setData(DataComponentTypes.MAP_ID, mapId);
            return display;
        }
    }

    private record Frame(
            @NotNull CartographMapRaster raster,
            @NotNull CartographMapRaster.Marker marker,
            @Nullable CartographMapRaster.Marker previousMarker
    ) {
    }
}
