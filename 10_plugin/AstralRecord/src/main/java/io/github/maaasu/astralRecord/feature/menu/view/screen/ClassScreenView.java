package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playerclass.model.ClassViewEntry;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class ClassScreenView extends BaseMenuScreenView {
    private static final int[] CONTENT_SLOTS = {
        10, 11, 12, 13, 14, 15, 16,
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    };

    private final NamespacedKey classIdKey;

    /**
     * クラス選択画面を生成します。
     *
     * @param classIdKey クリック対象のクラス ID を保持する PDC キー
     */
    public ClassScreenView(@NotNull NamespacedKey classIdKey) {
        this.classIdKey = classIdKey;
    }

    /**
     * クラス情報一覧を GUI に描画します。
     *
     * @param inventory 描画先インベントリ
     * @param astPlayer 現在のプレイヤー状態
     * @param classes 表示対象のクラス情報
     */
    public void render(
        @NotNull Inventory inventory,
        @NotNull AstPlayer astPlayer,
        @NotNull List<ClassViewEntry> classes
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        for (int index = 0; index < Math.min(CONTENT_SLOTS.length, classes.size()); index++) {
            inventory.setItem(CONTENT_SLOTS[index], classItem(classes.get(index), astPlayer));
        }
    }

    /**
     * GUI アイテムに埋め込まれたクラス ID を取得します。
     *
     * @param itemStack クリックされた ItemStack
     * @return 埋め込まれたクラス ID。クラス項目でない場合は {@code null}
     */
    public @Nullable String getClassId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(classIdKey, PersistentDataType.STRING);
    }

    private @NotNull ItemStack classItem(@NotNull ClassViewEntry entry, @NotNull AstPlayer astPlayer) {
        boolean selected = entry.getId().equalsIgnoreCase(astPlayer.getClassId());
        Material material = parseMaterial(entry.getIcon(), selected ? Material.NETHER_STAR : Material.IRON_SWORD);
        List<Component> lore = new ArrayList<>();

        lore.add(noItalic(Component.text("ID: " + entry.getId(), NamedTextColor.DARK_GRAY)));
        lore.add(noItalic(Component.text("Role: " + entry.getRole() + " / Type: " + entry.getType(), NamedTextColor.GRAY)));
        lore.add(noItalic(Component.text("Unlock Player Lv: " + entry.getUnlockLevel(), NamedTextColor.YELLOW)));
        addList(lore, "Unlock Requirements", entry.getUnlockRequirements(), NamedTextColor.GOLD);
        lore.add(noItalic(Component.text(selected ? "現在のクラス" : "クリックで転職", selected ? NamedTextColor.GREEN : NamedTextColor.GOLD, TextDecoration.BOLD)));

        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            lore.add(Component.empty());
            lore.add(noItalic(Component.text("説明", NamedTextColor.GOLD, TextDecoration.BOLD)));
            lore.add(legacy(entry.getDescription()));
        }

        addList(lore, "基礎ステータス", entry.getBaseStats(), NamedTextColor.AQUA);
        addList(lore, "成長", entry.getGrowthPerLevel(), NamedTextColor.GREEN);
        addList(lore, "初期スキル", entry.getStarterSkills(), NamedTextColor.LIGHT_PURPLE);
        addList(lore, "レベル習得", entry.getLevelSkills(), NamedTextColor.YELLOW);
        addList(lore, "タグ", entry.getTags(), NamedTextColor.DARK_GRAY);

        ItemStack itemStack = createItem(material, className(entry, selected), lore);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(classIdKey, PersistentDataType.STRING, entry.getId());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private void addList(
        @NotNull List<Component> lore,
        @NotNull String title,
        @NotNull List<String> values,
        @NotNull NamedTextColor accent
    ) {
        if (values.isEmpty()) {
            return;
        }
        lore.add(Component.empty());
        lore.add(noItalic(Component.text(title, accent, TextDecoration.BOLD)));
        int maxLines = Math.min(values.size(), 6);
        for (int i = 0; i < maxLines; i++) {
            lore.add(noItalic(Component.text("・" + values.get(i), NamedTextColor.GRAY)));
        }
        if (values.size() > maxLines) {
            lore.add(noItalic(Component.text("... +" + (values.size() - maxLines), NamedTextColor.DARK_GRAY)));
        }
    }

    private @NotNull Component className(@NotNull ClassViewEntry entry, boolean selected) {
        Component prefix = Component.text(selected ? "◆ " : "◇ ", selected ? NamedTextColor.GREEN : NamedTextColor.DARK_GRAY);
        return noItalic(prefix.append(legacy(entry.getName()).decorate(TextDecoration.BOLD)));
    }

    private @NotNull Component legacy(@NotNull String text) {
        return noItalic(LegacyComponentSerializer.legacySection().deserialize(
            ColorCodeUtil.translateAlternateColorCodes(text)
        ));
    }

    protected @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull Material parseMaterial(@Nullable String value, @NotNull Material fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(value.trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }
}
