package io.github.maaasu.astralRecord.feature.textdisplay.service;

import io.github.maaasu.astralRecord.feature.textdisplay.model.TextDisplayPlacement;
import io.github.maaasu.astralRecord.feature.textdisplay.repository.TextDisplayPlacementRepository;
import io.github.maaasu.astralRecord.shared.display.DisplayAnchor;
import io.github.maaasu.astralRecord.shared.display.DisplayTextOptions;
import io.github.maaasu.astralRecord.shared.display.DisplayTextService;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定 TextDisplay の配置情報と表示状態を管理します。
 */
public final class TextDisplayPlacementService {

    private final Plugin plugin;
    private final TextDisplayPlacementRepository repository;
    private final Map<String, TextDisplayPlacement> placements = new LinkedHashMap<>();
    private final Map<String, DisplayTextService.ManagedTextDisplay> displayedById = new LinkedHashMap<>();
    private final Map<String, ChunkTicket> chunkTicketById = new LinkedHashMap<>();
    private final Map<ChunkTicket, Integer> chunkTicketRefs = new LinkedHashMap<>();
    private @Nullable DisplayTextService displayTextService;
    private boolean dirty;

    /**
     * サービスを初期化します。
     *
     * @param plugin     プラグイン本体
     * @param repository 固定 TextDisplay 配置リポジトリ
     */
    public TextDisplayPlacementService(
            @NotNull Plugin plugin,
            @NotNull TextDisplayPlacementRepository repository
    ) {
        this.plugin = plugin;
        this.repository = repository;
    }

    /**
     * 表示基盤サービスを設定します。
     *
     * @param displayTextService TextDisplay 生成サービス
     */
    public void setDisplayTextService(@NotNull DisplayTextService displayTextService) {
        this.displayTextService = displayTextService;
    }

    /**
     * 固定 TextDisplay 配置 YAML を読み込み、ロード済みワールドの表示を生成します。
     *
     * @return 読み込んだ配置数
     */
    public int loadAll() {
        destroyDisplayedTexts();
        releaseAllChunkTickets();
        placements.clear();
        displayedById.clear();
        for (TextDisplayPlacement placement : repository.loadAll()) {
            placements.put(placement.id(), placement);
        }
        dirty = false;
        spawnLoadedWorlds();
        return placements.size();
    }

    /**
     * 固定 TextDisplay を登録して即時表示します。
     *
     * @param id       表示 ID
     * @param text     表示テキスト
     * @param location 配置座標
     * @return 登録した配置情報
     */
    @NotNull
    public TextDisplayPlacement place(@NotNull String id, @NotNull String text, @NotNull Location location) {
        TextDisplayPlacement placement = TextDisplayPlacement.from(id, text, location);
        removeDisplayed(id);
        placements.put(id, placement);
        dirty = true;
        saveIfDirty();
        spawn(placement);
        return placement;
    }

    /**
     * 指定 ID の固定 TextDisplay 配置を削除します。
     *
     * @param id 表示 ID
     * @return 削除できた場合は {@code true}
     */
    public boolean remove(@NotNull String id) {
        TextDisplayPlacement removed = placements.remove(id);
        removeDisplayed(id);
        if (removed == null) {
            return false;
        }
        dirty = true;
        saveIfDirty();
        return true;
    }

    /**
     * 登録済み固定 TextDisplay 配置一覧を返します。
     *
     * @return 固定 TextDisplay 配置一覧
     */
    @NotNull
    public Collection<TextDisplayPlacement> getPlacements() {
        return List.copyOf(placements.values());
    }

    /**
     * 現在ロード済みのワールドに紐づく固定 TextDisplay をすべて表示します。
     *
     * @return 新たに表示を生成した件数
     */
    public int spawnLoadedWorlds() {
        int count = 0;
        for (TextDisplayPlacement placement : placements.values()) {
            if (spawn(placement)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 指定ワールドに紐づく固定 TextDisplay を表示します。
     *
     * @param world ロードされたワールド
     * @return 新たに表示を生成した件数
     */
    public int spawnForWorld(@NotNull World world) {
        int count = 0;
        for (TextDisplayPlacement placement : placements.values()) {
            if (!placement.worldName().equals(world.getName())) {
                continue;
            }
            if (spawn(placement)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 未表示の固定 TextDisplay 配置が残っているかを返します。
     *
     * @return 1 件以上の未表示配置がある場合は {@code true}
     */
    public boolean hasPendingPlacements() {
        for (TextDisplayPlacement placement : placements.values()) {
            if (!displayedById.containsKey(placement.id())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 保存が必要な固定 TextDisplay 配置を YAML に書き込みます。
     */
    public void saveIfDirty() {
        if (!dirty) {
            return;
        }
        repository.saveAll(new ArrayList<>(placements.values()));
        dirty = false;
    }

    /**
     * 表示中の固定 TextDisplay を破棄し、チャンク保持を解放します。
     */
    public void stop() {
        destroyDisplayedTexts();
        releaseAllChunkTickets();
        displayedById.clear();
    }

    private boolean spawn(@NotNull TextDisplayPlacement placement) {
        if (displayedById.containsKey(placement.id())) {
            return false;
        }
        DisplayTextService service = displayTextService;
        if (service == null) {
            return false;
        }

        Location location = prepareDisplayLocation(placement);
        if (location == null) {
            return false;
        }
        if (!retainChunkTicket(placement.id(), location)) {
            return false;
        }

        displayedById.put(
                placement.id(),
                service.create(
                        DisplayAnchor.fixed(location),
                        DisplayTextOptions.defaults(placement.text())
                                .withLineWidth(320)
                                .withViewRange(96.0F)
                                .withShadowed(true)
                )
        );
        return true;
    }

    @Nullable
    private Location prepareDisplayLocation(@NotNull TextDisplayPlacement placement) {
        Location location = placement.toLocation();
        if (location == null || location.getWorld() == null) {
            return null;
        }

        Chunk chunk = location.getChunk();
        if (!chunk.isLoaded() && !chunk.load()) {
            return null;
        }
        return location;
    }

    private void destroyDisplayedTexts() {
        for (DisplayTextService.ManagedTextDisplay display : List.copyOf(displayedById.values())) {
            try {
                display.destroy();
            } catch (IllegalStateException ignored) {
                // DisplayTextService 側で破棄済みの場合は状態同期だけ進めます。
            }
        }
    }

    private void removeDisplayed(@NotNull String id) {
        DisplayTextService.ManagedTextDisplay display = displayedById.remove(id);
        if (display != null) {
            try {
                display.destroy();
            } catch (IllegalStateException ignored) {
                // DisplayTextService 側で破棄済みの場合は状態同期だけ進めます。
            }
        }
        releaseChunkTicket(id);
    }

    private boolean retainChunkTicket(@NotNull String id, @NotNull Location location) {
        Chunk chunk = location.getChunk();
        ChunkTicket ticket = new ChunkTicket(chunk.getWorld().getName(), chunk.getX(), chunk.getZ());
        ChunkTicket currentTicket = chunkTicketById.get(id);
        if (ticket.equals(currentTicket)) {
            return true;
        }
        if (currentTicket != null) {
            releaseChunkTicket(id);
        }

        int refs = chunkTicketRefs.getOrDefault(ticket, 0);
        if (refs == 0) {
            try {
                chunk.addPluginChunkTicket(plugin);
            } catch (RuntimeException ignored) {
                return true;
            }
        }

        chunkTicketById.put(id, ticket);
        chunkTicketRefs.put(ticket, refs + 1);
        return true;
    }

    private void releaseChunkTicket(@NotNull String id) {
        ChunkTicket ticket = chunkTicketById.remove(id);
        if (ticket == null) {
            return;
        }

        int refs = chunkTicketRefs.getOrDefault(ticket, 0) - 1;
        if (refs > 0) {
            chunkTicketRefs.put(ticket, refs);
            return;
        }

        chunkTicketRefs.remove(ticket);
        World world = Bukkit.getWorld(ticket.worldName());
        if (world != null) {
            world.getChunkAt(ticket.x(), ticket.z()).removePluginChunkTicket(plugin);
        }
    }

    private void releaseAllChunkTickets() {
        for (ChunkTicket ticket : List.copyOf(chunkTicketRefs.keySet())) {
            World world = Bukkit.getWorld(ticket.worldName());
            if (world != null) {
                world.getChunkAt(ticket.x(), ticket.z()).removePluginChunkTicket(plugin);
            }
        }
        chunkTicketById.clear();
        chunkTicketRefs.clear();
    }

    private record ChunkTicket(@NotNull String worldName, int x, int z) {
    }
}
