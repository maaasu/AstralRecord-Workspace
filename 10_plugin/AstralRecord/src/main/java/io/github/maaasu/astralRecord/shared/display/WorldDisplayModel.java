package io.github.maaasu.astralRecord.shared.display;

import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

/** ワールド表示モデルの Java アイテムと統合版の生成マーカーを対応付けます。 */
public enum WorldDisplayModel {
    WAYSTONE_LOCKED(0, Material.LODESTONE, "waystone_locked"),
    WAYSTONE_UNLOCKED(1, Material.LODESTONE, "waystone_unlocked"),
    STORAGE(2, Material.CHEST, "storage"),
    BLAST_FURNACE(3, Material.BLAST_FURNACE, "blast_furnace");

    // AstralRecordGeyserExtension の WorldDisplayEntity と同じパケット契約。
    private static final long MODEL_TAG = 0x415354574d4f444cL;
    private static final long FALLBACK_TAG = 0x4153545746414c4cL;

    private final int code;
    private final Material material;
    private final String selector;

    WorldDisplayModel(int code, Material material, String name) {
        this.code = code;
        this.material = material;
        this.selector = "astralrecord:world/" + name;
    }

    /**
     * モデル選択用の文字列を持つ表示専用スタックを生成します。
     *
     * @return 対応パックがなければ元の素材として描画されるアイテム
     */
    public @NotNull ItemStack createItem() {
        ItemStack item = new ItemStack(material);
        item.editMeta(meta -> {
            var data = meta.getCustomModelDataComponent();
            data.setStrings(List.of(selector));
            meta.setCustomModelDataComponent(data);
        });
        return item;
    }

    /**
     * 統合版の拡張が生成時にモデルを特定できる UUID を作ります。
     *
     * @param entityId 生成パケットの実体ID
     * @param fallback 従来の表示を送るパケットの場合は true
     * @return 表示種別と実体IDを含むパケット用 UUID
     */
    public @NotNull UUID bedrockMarker(int entityId, boolean fallback) {
        return new UUID(fallback ? FALLBACK_TAG : MODEL_TAG, ((long) code << 32) | Integer.toUnsignedLong(entityId));
    }
}
