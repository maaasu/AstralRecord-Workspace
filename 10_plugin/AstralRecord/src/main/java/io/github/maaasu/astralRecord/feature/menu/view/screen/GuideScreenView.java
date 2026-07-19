package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.service.GuideService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class GuideScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;

    private static final int SUMMARY_SLOT = 13;
    private static final List<Integer> DETAIL_LINE_SLOTS = List.of(
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    );
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public void renderList(
        @NotNull Inventory inventory,
        @NotNull List<GuideEntry> guides,
        int pageIndex,
        @NotNull GuideService guideService
    ) {
        int normalizedPage = normalizePage(pageIndex, guides.size());
        clear(inventory);
        if (guides.isEmpty()) {
            inventory.setItem(22, createItem(
                Material.BOOK,
                Component.text("ガイドがありません", NamedTextColor.YELLOW),
                List.of(Component.text("現在表示できるガイドはありません", NamedTextColor.DARK_GRAY))
            ));
        } else {
            renderGuideItems(inventory, guides, normalizedPage, guideService);
        }
        renderNavigation(inventory, guides.size(), normalizedPage);
    }

    public void renderDetail(
        @NotNull Inventory inventory,
        @NotNull GuideEntry guide,
        @NotNull GuideService guideService
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(SUMMARY_SLOT, summaryItem(guide, guideService));

        List<String> resolvedLines = guide.lines().stream()
            .map(guideService::resolveText)
            .toList();
        for (int i = 0; i < Math.min(resolvedLines.size(), DETAIL_LINE_SLOTS.size()); i++) {
            inventory.setItem(DETAIL_LINE_SLOTS.get(i), lineItem(i + 1, resolvedLines.get(i)));
        }
        if (resolvedLines.size() > DETAIL_LINE_SLOTS.size()) {
            inventory.setItem(44, createItem(
                Material.PAPER,
                Component.text("続きがあります", NamedTextColor.YELLOW),
                List.of(Component.text("このガイドは表示上限を超えています", NamedTextColor.GRAY))
            ));
        }
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    public boolean hasPreviousPage(int pageIndex) {
        return GuiPagination.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(int pageIndex, int itemCount) {
        return GuiPagination.hasNextPage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public boolean isContentSlot(int rawSlot) {
        return rawSlot >= 0 && rawSlot < CONTENT_SLOT_COUNT;
    }

    private void clear(@NotNull Inventory inventory) {
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, new ItemStack(Material.AIR));
        }
    }

    private void renderGuideItems(
        @NotNull Inventory inventory,
        @NotNull List<GuideEntry> guides,
        int pageIndex,
        @NotNull GuideService guideService
    ) {
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, guides.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            GuideEntry guide = guides.get(i);
            inventory.setItem(i - start, listItem(guide, guideService));
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        if (hasPreviousPage(pageIndex)) {
            inventory.setItem(PREVIOUS_SLOT, createItem(
                Material.MAP,
                Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
        if (hasNextPage(pageIndex, itemCount)) {
            inventory.setItem(NEXT_SLOT, createItem(
                Material.MAP,
                Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
                List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY))
            ));
        }
    }

    private @NotNull ItemStack listItem(@NotNull GuideEntry guide, @NotNull GuideService guideService) {
        List<Component> lore = new ArrayList<>();
        if (guide.summary() != null && !guide.summary().isBlank()) {
            lore.add(component(guideService.resolveText(guide.summary()), ""));
        }
        lore.add(Component.text("クリックで詳細を開く", NamedTextColor.GRAY));
        return createItem(
            material(guide.iconMaterial(), Material.WRITABLE_BOOK),
            component(guideService.resolveText(guide.title()), guide.id()),
            lore
        );
    }

    private @NotNull ItemStack summaryItem(@NotNull GuideEntry guide, @NotNull GuideService guideService) {
        List<Component> lore = new ArrayList<>();
        if (guide.summary() != null && !guide.summary().isBlank()) {
            lore.add(component(guideService.resolveText(guide.summary()), ""));
        }
        return createItem(
            material(guide.iconMaterial(), Material.WRITABLE_BOOK),
            component(guideService.resolveText(guide.title()), guide.id()),
            lore
        );
    }

    private @NotNull ItemStack lineItem(int lineNumber, @NotNull String text) {
        return createItem(
            Material.PAPER,
            Component.text(lineNumber + ".", NamedTextColor.AQUA),
            List.of(component(text, ""))
        );
    }

    private @NotNull Component component(@NotNull String text, @NotNull String fallback) {
        return GuiItems.noItalic(LEGACY.deserialize(ColorCodeUtil.toLegacyText(text, fallback)));
    }

    private @NotNull Material material(String materialName, @NotNull Material fallback) {
        if (materialName == null || materialName.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }
}
