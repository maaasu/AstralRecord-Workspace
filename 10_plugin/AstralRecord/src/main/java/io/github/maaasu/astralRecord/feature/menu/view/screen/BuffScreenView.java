package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.buff.model.ActiveBuff;
import io.github.maaasu.astralRecord.feature.buff.model.BuffModifier;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 現在付与されているバフ一覧をメニュー GUI として描画します。
 */
public final class BuffScreenView extends BaseMenuScreenView {
    private static final int FIRST_SLOT = 10;
    private static final int LAST_SLOT = 43;
    private static final int[] SKIP_SLOTS = {17, 18, 26, 27, 35, 36};
    private static final int EMPTY_SLOT = 22;

    /**
     * バフ一覧を描画します。
     *
     * @param inventory 描画先インベントリ
     * @param buffs     現在有効なバフ一覧
     */
    public void render(@NotNull Inventory inventory, @NotNull List<ActiveBuff> buffs) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        if (buffs.isEmpty()) {
            inventory.setItem(EMPTY_SLOT, createItem(
                Material.GLASS_BOTTLE,
                noItalic(Component.text("バフなし", NamedTextColor.GRAY)),
                List.of(noItalic(Component.text("現在付与されているバフはありません。", NamedTextColor.DARK_GRAY)))
            ));
            return;
        }

        int slot = FIRST_SLOT;
        for (ActiveBuff buff : buffs) {
            while (slot <= LAST_SLOT && isSkipped(slot)) {
                slot++;
            }
            if (slot > LAST_SLOT) {
                break;
            }
            inventory.setItem(slot, buffItem(buff));
            slot++;
        }
    }

    private @NotNull ItemStack buffItem(@NotNull ActiveBuff buff) {
        NamedTextColor accent = buff.getType().isDebuff() ? NamedTextColor.RED : NamedTextColor.AQUA;
        Component name = noItalic(Component.text(sanitizeDisplayName(buff.getType().getDisplayName()), accent, TextDecoration.BOLD));
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(Component.text(
            "種別: " + (buff.getType().isDebuff() ? "デバフ" : "バフ"),
            NamedTextColor.GRAY
        )));
        lore.add(noItalic(Component.text("残り: " + formatRemaining(buff), NamedTextColor.YELLOW)));
        lore.add(Component.empty());
        for (BuffModifier modifier : buff.getType().getModifiers()) {
            StatusType type = modifier.getStatus();
            lore.add(noItalic(Component.empty()
                .append(Component.text(type.getDisplayName() + " ", type.namedColor()))
                .append(Component.text(type.formatSignedValue(modifier.getValue()), NamedTextColor.WHITE))));
        }
        return createItem(buff.getType().isDebuff() ? Material.FERMENTED_SPIDER_EYE : Material.POTION, name, lore);
    }

    private boolean isSkipped(int slot) {
        for (int skipped : SKIP_SLOTS) {
            if (slot == skipped) {
                return true;
            }
        }
        return false;
    }

    private @NotNull String formatRemaining(@NotNull ActiveBuff buff) {
        long seconds = Math.max(0L, Duration.between(LocalDateTime.now(), buff.getExpiresAt()).toSeconds());
        return String.format(Locale.ROOT, "%d:%02d", seconds / 60L, seconds % 60L);
    }

    protected @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull String sanitizeDisplayName(@NotNull String displayName) {
        return ColorCodeUtil.toPlainText(displayName, displayName);
    }
}
