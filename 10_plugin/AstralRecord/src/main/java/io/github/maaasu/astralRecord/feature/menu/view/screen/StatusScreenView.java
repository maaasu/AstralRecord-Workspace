package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class StatusScreenView extends BaseMenuScreenView {
    private static final int SUMMARY_SLOT = 13;
    private static final int RESOURCE_SLOT = 20;
    private static final int PRIMARY_SLOT = 21;
    private static final int OFFENSE_SLOT = 22;
    private static final int DEFENSE_SLOT = 23;
    private static final int UTILITY_SLOT = 24;

    private static final int BAR_LENGTH = 20;
    private static final String SEPARATOR = "━━━━━━━━━━━━━━━━━━━━━━";

    private static final NamedTextColor HP_COLOR = NamedTextColor.RED;
    private static final NamedTextColor MP_COLOR = NamedTextColor.AQUA;
    private static final NamedTextColor EN_COLOR = NamedTextColor.YELLOW;

    private static final TextColor RESOURCE_COLOR = NamedTextColor.GOLD;
    private static final TextColor PRIMARY_COLOR = NamedTextColor.YELLOW;
    private static final TextColor OFFENSE_COLOR = NamedTextColor.RED;
    private static final TextColor DEFENSE_COLOR = NamedTextColor.BLUE;
    private static final TextColor UTILITY_COLOR = NamedTextColor.GREEN;

    public void render(
        @NotNull Inventory inventory,
        @NotNull AstPlayer player,
        @NotNull StatusSnapshot snapshot
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(SUMMARY_SLOT, summaryItem(player, snapshot));
        inventory.setItem(RESOURCE_SLOT, categoryItem(
            Material.GOLDEN_APPLE, "❖", StatusType.Category.RESOURCE, RESOURCE_COLOR, snapshot));
        inventory.setItem(PRIMARY_SLOT, categoryItem(
            Material.DIAMOND, "◆", StatusType.Category.PRIMARY, PRIMARY_COLOR, snapshot));
        inventory.setItem(OFFENSE_SLOT, categoryItem(
            Material.NETHERITE_SWORD, "⚔", StatusType.Category.OFFENSE, OFFENSE_COLOR, snapshot));
        inventory.setItem(DEFENSE_SLOT, categoryItem(
            Material.SHIELD, "✚", StatusType.Category.DEFENSE, DEFENSE_COLOR, snapshot));
        inventory.setItem(UTILITY_SLOT, categoryItem(
            Material.FEATHER, "✤", StatusType.Category.UTILITY, UTILITY_COLOR, snapshot));
    }

    private @NotNull ItemStack summaryItem(@NotNull AstPlayer player, @NotNull StatusSnapshot snapshot) {
        Component name = noItalic(Component.empty()
            .append(Component.text("✦ ", NamedTextColor.YELLOW))
            .append(Component.text(player.getAccount().getAccountName(), NamedTextColor.GOLD, TextDecoration.BOLD))
            .append(Component.text(" ✦", NamedTextColor.YELLOW)));

        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        lore.add(noItalic(Component.text("  〘 冒険者プロフィール 〙", NamedTextColor.GOLD, TextDecoration.BOLD)));
        lore.add(separatorLine());
        lore.add(Component.empty());
        lore.add(resourceLine("HP", "♥",
            snapshot.getCurrentHp(),
            snapshot.getMaxValue(StatusType.MAX_HEALTH),
            HP_COLOR));
        lore.add(resourceLine("MP", "✦",
            snapshot.getCurrentMp(),
            snapshot.getMaxValue(StatusType.MAX_MANA),
            MP_COLOR));
        lore.add(resourceLine("EN", "⚡",
            snapshot.getCurrentEnergy(),
            snapshot.getMaxValue(StatusType.MAX_ENERGY),
            EN_COLOR));
        lore.add(Component.empty());
        lore.add(separatorLine());

        return createItem(Material.PLAYER_HEAD, name, lore);
    }

    private @NotNull ItemStack categoryItem(
        @NotNull Material material,
        @NotNull String icon,
        @NotNull StatusType.Category category,
        @NotNull TextColor color,
        @NotNull StatusSnapshot snapshot
    ) {
        Component name = noItalic(Component.empty()
            .append(Component.text(icon + " ", color))
            .append(Component.text(category.getDisplayName(), color, TextDecoration.BOLD))
            .append(Component.text(" " + icon, color)));

        List<Component> lore = new ArrayList<>();
        lore.add(separatorLine());
        for (StatusType type : StatusType.byCategory(category)) {
            StatusValue value = snapshot.getValue(type);
            if (value == null) {
                continue;
            }
            lore.add(statLine(type, value, color));
        }
        lore.add(separatorLine());

        return createItem(material, name, lore);
    }

    private @NotNull Component statLine(
        @NotNull StatusType type,
        @NotNull StatusValue value,
        @NotNull TextColor accent
    ) {
        Component line = Component.empty()
            .append(Component.text(" ▸ ", accent))
            .append(Component.text(type.getDisplayName(), NamedTextColor.GRAY))
            .append(Component.text(" ", NamedTextColor.DARK_GRAY))
            .append(Component.text(type.formatValue(value.getTotalValue()), NamedTextColor.WHITE, TextDecoration.BOLD));

        double bonus = value.getBonusValue();
        if (bonus != 0.0) {
            NamedTextColor bonusColor = bonus > 0.0 ? NamedTextColor.GREEN : NamedTextColor.RED;
            line = line
                .append(Component.text(" (", NamedTextColor.DARK_GRAY))
                .append(Component.text(type.formatSignedValue(bonus), bonusColor))
                .append(Component.text(")", NamedTextColor.DARK_GRAY));
        }
        return noItalic(line);
    }

    private @NotNull Component resourceLine(
        @NotNull String label,
        @NotNull String icon,
        double current,
        double max,
        @NotNull NamedTextColor color
    ) {
        int percent = max <= 0.0 ? 0 : (int) Math.round(100.0 * Math.min(1.0, Math.max(0.0, current / max)));
        Component line = Component.empty()
            .append(Component.text(" " + icon + " ", color))
            .append(Component.text(label, NamedTextColor.GRAY))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(bar(current, max, color))
            .append(Component.text("  ", NamedTextColor.DARK_GRAY))
            .append(Component.text(formatInt(current) + " / " + formatInt(max), color, TextDecoration.BOLD))
            .append(Component.text("  (" + percent + "%)", NamedTextColor.DARK_GRAY));
        return noItalic(line);
    }

    private @NotNull Component bar(double current, double max, @NotNull NamedTextColor color) {
        int filled;
        if (max <= 0.0) {
            filled = 0;
        } else {
            double ratio = Math.min(1.0, Math.max(0.0, current / max));
            filled = (int) Math.round(BAR_LENGTH * ratio);
        }
        int empty = BAR_LENGTH - filled;
        return Component.empty()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text(repeat("|", filled), color))
            .append(Component.text(repeat("|", empty), NamedTextColor.DARK_GRAY))
            .append(Component.text("]", NamedTextColor.DARK_GRAY));
    }

    private @NotNull Component separatorLine() {
        return noItalic(Component.text(SEPARATOR, NamedTextColor.DARK_GRAY));
    }

    private @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull String formatInt(double value) {
        return String.format(Locale.US, "%,d", Math.round(value));
    }

    private @NotNull String repeat(@NotNull String unit, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(unit.length() * count);
        for (int i = 0; i < count; i++) {
            sb.append(unit);
        }
        return sb.toString();
    }
}
