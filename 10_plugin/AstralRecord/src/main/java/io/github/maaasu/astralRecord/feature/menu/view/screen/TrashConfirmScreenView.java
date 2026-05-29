package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public final class TrashConfirmScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = 0;
    public static final int PREVIOUS_SLOT = -1;
    public static final int DISPOSE_SLOT = ConfirmDialogView.CONFIRM_SLOT;
    public static final int RETURN_TO_TRASH_SLOT = ConfirmDialogView.CANCEL_SLOT;
    public static final int NEXT_SLOT = -1;

    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public TrashConfirmScreenView(@NotNull NamespacedKey contentPlaceholderKey) {
    }

    public void render(@NotNull Inventory inventory, @NotNull List<ItemStack> items, int pageIndex) {
        confirmDialogView.render(
            inventory,
            Component.text("ゴミ箱の内容を破棄しますか", NamedTextColor.YELLOW),
            Component.text("破棄する", NamedTextColor.RED),
            Component.text("戻る", NamedTextColor.GREEN)
        );
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return 0;
    }

    public int totalPages(int itemCount) {
        return 1;
    }

    public boolean hasPreviousPage(int pageIndex) {
        return false;
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return false;
    }

    public boolean isContentPlaceholder(@Nullable ItemStack itemStack) {
        return false;
    }
}
