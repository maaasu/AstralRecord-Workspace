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
    private static final int ADVENTURER_SLOT = 10;
    private static final int SWORDSMAN_SLOT = 12;
    private static final int HUNTER_SLOT = 13;
    private static final int MAGE_SLOT = 14;
    private static final int ACOLYTE_SLOT = 15;

    private static final String LABEL_ROLE_AND_TYPE =
        "\u30ed\u30fc\u30eb: %s / \u7a2e\u5225: %s";
    private static final String LABEL_UNLOCK_CONDITIONS = "\u8ee2\u8077\u6761\u4ef6";
    private static final String LABEL_CURRENT_CLASS = "\u73fe\u5728\u306e\u30af\u30e9\u30b9\u3067\u3059";
    private static final String LABEL_CHANGE_AVAILABLE = "\u30af\u30ea\u30c3\u30af\u3067\u8ee2\u8077";
    private static final String LABEL_CHANGE_BLOCKED =
        "\u8ee2\u8077\u6761\u4ef6\u3092\u6e80\u305f\u3057\u3066\u3044\u307e\u305b\u3093";
    private static final String LABEL_BLOCKED_REASONS = "\u672a\u9054\u6210\u6761\u4ef6";
    private static final String LABEL_DESCRIPTION = "\u8aac\u660e";
    private static final String LABEL_BASE_STATS = "\u57fa\u672c\u30b9\u30c6\u30fc\u30bf\u30b9";
    private static final String LABEL_GROWTH_PER_LEVEL = "\u30ec\u30d9\u30eb\u6210\u9577";
    private static final String LABEL_STARTER_SKILLS = "\u521d\u671f\u7fd2\u5f97\u30b9\u30ad\u30eb";
    private static final String LABEL_LEVEL_SKILLS = "\u30ec\u30d9\u30eb\u7fd2\u5f97\u30b9\u30ad\u30eb";
    private static final String BULLET = "\u30fb";
    private static final String SELECTED_PREFIX = "\u25cf ";
    private static final String AVAILABLE_PREFIX = "\u25cb ";
    private static final String BLOCKED_PREFIX = "\u00d7 ";

    private final NamespacedKey classIdKey;

    public ClassScreenView(@NotNull NamespacedKey classIdKey) {
        this.classIdKey = classIdKey;
    }

    public void render(
        @NotNull Inventory inventory,
        @NotNull AstPlayer astPlayer,
        @NotNull List<ClassViewEntry> classes
    ) {
        fill(inventory);
        for (ClassViewEntry entry : classes) {
            int slot = slotFor(entry.getId());
            if (slot >= 0) {
                inventory.setItem(slot, classItem(entry, astPlayer));
            }
        }
    }

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
        boolean available = selected || entry.getChangeAvailable();
        Material material = parseMaterial(entry.getIcon(), selected ? Material.NETHER_STAR : Material.IRON_SWORD);
        List<Component> lore = new ArrayList<>();

        lore.add(noItalic(Component.text(
            LABEL_ROLE_AND_TYPE.formatted(entry.getRoleDisplay(), entry.getTypeDisplay()),
            NamedTextColor.GRAY
        )));
        addList(lore, LABEL_UNLOCK_CONDITIONS, entry.getUnlockConditions(), NamedTextColor.GOLD);

        if (selected) {
            lore.add(noItalic(Component.text(LABEL_CURRENT_CLASS, NamedTextColor.GREEN, TextDecoration.BOLD)));
        } else if (available) {
            lore.add(noItalic(Component.text(LABEL_CHANGE_AVAILABLE, NamedTextColor.GOLD, TextDecoration.BOLD)));
        } else {
            lore.add(noItalic(Component.text(LABEL_CHANGE_BLOCKED, NamedTextColor.RED, TextDecoration.BOLD)));
            addList(lore, LABEL_BLOCKED_REASONS, entry.getChangeBlockedReasons(), NamedTextColor.RED);
        }

        if (entry.getDescription() != null && !entry.getDescription().isBlank()) {
            lore.add(Component.empty());
            lore.add(noItalic(Component.text(LABEL_DESCRIPTION, NamedTextColor.GOLD, TextDecoration.BOLD)));
            lore.add(legacy(entry.getDescription()));
        }

        addList(lore, LABEL_BASE_STATS, entry.getBaseStats(), NamedTextColor.AQUA);
        addList(lore, LABEL_GROWTH_PER_LEVEL, entry.getGrowthPerLevel(), NamedTextColor.GREEN);
        addList(lore, LABEL_STARTER_SKILLS, entry.getStarterSkills(), NamedTextColor.LIGHT_PURPLE);
        addList(lore, LABEL_LEVEL_SKILLS, entry.getLevelSkills(), NamedTextColor.YELLOW);

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
            lore.add(noItalic(Component.text(BULLET, NamedTextColor.GRAY).append(legacy(values.get(i)))));
        }
        if (values.size() > maxLines) {
            lore.add(noItalic(Component.text("... +" + (values.size() - maxLines), NamedTextColor.DARK_GRAY)));
        }
    }

    private @NotNull Component className(@NotNull ClassViewEntry entry, boolean selected) {
        String prefixText = selected ? SELECTED_PREFIX : entry.getChangeAvailable() ? AVAILABLE_PREFIX : BLOCKED_PREFIX;
        NamedTextColor prefixColor = selected
            ? NamedTextColor.GREEN
            : entry.getChangeAvailable() ? NamedTextColor.GRAY : NamedTextColor.RED;
        Component prefix = Component.text(prefixText, prefixColor);
        return noItalic(prefix.append(legacy(entry.getName()).decorate(TextDecoration.BOLD)));
    }

    private int slotFor(@NotNull String classId) {
        return switch (classId.toLowerCase(Locale.ROOT)) {
            case "adventurer" -> ADVENTURER_SLOT;
            case "swordsman" -> SWORDSMAN_SLOT;
            case "hunter" -> HUNTER_SLOT;
            case "mage" -> MAGE_SLOT;
            case "acolyte" -> ACOLYTE_SLOT;
            default -> -1;
        };
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
