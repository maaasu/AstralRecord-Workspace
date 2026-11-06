package io.github.maaasu.astralRecord.feature.item.view;

import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipment;
import io.github.maaasu.astralRecord.feature.item.model.ItemEquipmentSlot;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.shared.masterdata.tag.MasterTagIds;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/** 適用済みのパックに合わせて、送信コピーのツールチップだけを装飾します。 */
enum ItemTooltipPresentation {
    JAVA,
    BEDROCK;

    private static final NamespacedKey TOOLTIP_STYLE = new NamespacedKey("astralrecord", "shared_item");
    private static final Key ICON_FONT = Key.key("astralrecord", "tooltip_icons");

    /**
     * アイテム名に種別字形を追加し、Java 版では共通の枠も指定します。
     *
     * @param copy 受信者へ送信する ItemStack のコピー
     * @param master 公開済みのアイテム定義。未解決時は null
     * @return 装飾を適用した場合は true
     */
    boolean apply(ItemStack copy, @Nullable ItemModel master) {
        var meta = copy.getItemMeta();
        if (meta == null) {
            return false;
        }
        Component name = meta.displayName();
        if (name == null) {
            return false;
        }

        Component icon = Component.text(String.valueOf(resolveGlyph(copy, master)), NamedTextColor.WHITE)
            .decoration(TextDecoration.ITALIC, false);
        if (this == JAVA) {
            icon = icon.font(ICON_FONT);
            meta.setTooltipStyle(TOOLTIP_STYLE);
        }
        Component title = name.replaceText(builder -> builder.matchLiteral("◆ ").once().replacement(Component.empty()));
        meta.displayName(Component.empty().append(icon).append(Component.space()).append(title));
        return copy.setItemMeta(meta);
    }

    /** マスタのカテゴリから共通字形を選び、未解決時は保存済みカテゴリを参照します。 */
    private static char resolveGlyph(ItemStack item, @Nullable ItemModel master) {
        ItemCategory category = ItemCategory.fromApiValue(
            master == null ? ItemStackFactory.getCategory(item) : master.getCategory());
        return switch (category) {
            case EQUIPMENT -> equipmentGlyph(item, master == null ? null : master.getEquipment());
            case CONSUMABLE -> '\uE60A';
            case RUNE -> '\uE60B';
            case BUNDLE -> '\uE60C';
            case MATERIAL -> '\uE60D';
            case ORB -> '\uE60E';
            case CURRENCY -> '\uE60F';
            case PET, PET_EGG -> '\uE610';
            case SIGIL -> '\uE611';
            case UNKNOWN -> '\uE600';
        };
    }

    /** 武器タグまたは装備部位に対応する字形を選びます。 */
    private static char equipmentGlyph(ItemStack item, @Nullable ItemEquipment equipment) {
        ItemEquipmentSlot slot = equipment == null || equipment.getSlot() == null
            ? ItemEquipmentSlot.fromApiValue(ItemStackFactory.getEquipmentSlot(item)) : equipment.getSlot();
        if (slot == ItemEquipmentSlot.TOOL) {
            return '\uE608';
        }
        if (slot == ItemEquipmentSlot.ACCESSORY) {
            return '\uE609';
        }
        String tag = equipment == null ? null : equipment.getTag();
        if (tag == null) {
            return '\uE607';
        }
        return switch (tag.trim().toUpperCase(Locale.ROOT)) {
            case MasterTagIds.Equipment.SWORD -> '\uE601';
            case MasterTagIds.Equipment.HAMMER -> '\uE602';
            case MasterTagIds.Equipment.SPEAR -> '\uE603';
            case MasterTagIds.Equipment.BOW, MasterTagIds.Equipment.SHORTBOW, MasterTagIds.Equipment.LONGBOW -> '\uE604';
            case MasterTagIds.Equipment.WAND -> '\uE605';
            case MasterTagIds.Equipment.STAFF -> '\uE606';
            default -> '\uE607';
        };
    }
}
