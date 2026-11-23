package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.guide.model.GuideEntry;
import io.github.maaasu.astralRecord.feature.guide.model.GuideStep;
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
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public final class GuideScreenView extends BaseMenuScreenView {
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;
    public static final int REWARD_SLOT = 51;
    public static final int TUTORIAL_ACTION_SLOT = 22;
    public static final int ALL_GUIDES_SLOT = 45;
    public static final String TUTORIAL_PREFIX = "$tutorial:";
    public static final String TUTORIAL_LOADING = TUTORIAL_PREFIX + "loading";
    public static final String TUTORIAL_LOAD_FAILED = TUTORIAL_PREFIX + "load_failed";
    public static final String TUTORIAL_UNAVAILABLE = TUTORIAL_PREFIX + "unavailable";
    public static final String TUTORIAL_COMPLETE = TUTORIAL_PREFIX + "complete";

    private static final int SUMMARY_SLOT = 13;
    private static final List<Integer> DETAIL_LINE_SLOTS = List.of(
        19, 20, 21, 22, 23, 24, 25,
        28, 29, 30, 31, 32, 33, 34,
        37, 38, 39, 40, 41, 42, 43
    );
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();

    public @NotNull ItemStack tutorialAction(@NotNull String contentId) {
        return switch (contentId) {
            case TUTORIAL_PREFIX + "open_menu" ->
                tutorialItem(Material.COMPASS, "メインメニューを開く", "クリックでメインメニューを開く");
            case TUTORIAL_PREFIX + "receive_welcome_mail" ->
                tutorialItem(Material.PAPER, "記念の贈り物を受け取る", "メールの「初ログイン記念の贈り物」を選ぼう");
            case TUTORIAL_PREFIX + "open_initial_bundle" ->
                tutorialItem(Material.BUNDLE, "初ログインバンドルを開く", "持ち物で選び、ホットバーに持って画面を閉じて使おう");
            case TUTORIAL_PREFIX + "buy_starter_weapon" ->
                tutorialItem(Material.GOLD_INGOT, "簡素な武器を買う", "雑貨商ミナの場所をもう一度表示する（武器は20ゴールド）");
            case TUTORIAL_PREFIX + "equip_starter_weapon" ->
                tutorialItem(Material.WOODEN_SWORD, "簡素な武器を装備する", "装備画面の下のバッグで、印の付いた武器を選ぼう");
            case TUTORIAL_PREFIX + "defeat_grassboar" ->
                tutorialItem(Material.STONE_SWORD, "グラスボアを1体倒す", "クリックでグラスボアの場所を表示する");
            case TUTORIAL_COMPLETE ->
                tutorialItem(Material.ENCHANTED_BOOK, "次の冒険：アストラルエッジを覚える", "クリックでスキルマネージャーを開く");
            case TUTORIAL_LOADING ->
                tutorialItem(Material.CLOCK, "進行状況を読み込み中", "読み込みが終わると次の行動を表示します");
            case TUTORIAL_LOAD_FAILED ->
                tutorialItem(Material.PAPER, "進行状況を読み込めませんでした", "クリックでもう一度読み込む");
            default ->
                tutorialItem(Material.BOOK, "チュートリアルを表示できません", "「すべてのガイド」からガイド一覧を開けます");
        };
    }

    public void renderTutorial(@NotNull Inventory inventory, @NotNull ItemStack action) {
        inventory.setItem(TUTORIAL_ACTION_SLOT, action);
        inventory.setItem(ALL_GUIDES_SLOT, tutorialItem(Material.BOOK, "すべてのガイド", "クリックでガイド一覧を開く"));
        inventory.setItem(BACK_SLOT, backItem());
    }

    private @NotNull ItemStack tutorialItem(Material material, String title, String instruction) {
        return createItem(
            material,
            Component.text(title, NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(Component.text(instruction, NamedTextColor.GRAY))
        );
    }

    /**
     * ガイド一覧をアカウントの達成状態付きで描画します。
     *
     * @param inventory 描画先inventory
     * @param guides ガイド一覧
     * @param pageIndex 0始まりのページ番号
     * @param guideService ガイドサービス
     * @param accountId 表示対象アカウントID。未ロード時はnull
     * @param rewardClaimable 全ガイド達成報酬を受取可能な場合true
     */
    public void renderList(
        @NotNull Inventory inventory,
        @NotNull List<GuideEntry> guides,
        int pageIndex,
        @NotNull GuideService guideService,
        UUID accountId,
        boolean rewardClaimable
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
            renderGuideItems(inventory, guides, normalizedPage, guideService, accountId);
        }
        renderNavigation(inventory, guides.size(), normalizedPage, rewardClaimable);
    }

    /**
     * ガイド詳細を手順ごとの達成状態付きで描画します。
     *
     * @param inventory 描画先inventory
     * @param guide 対象ガイド
     * @param guideService ガイドサービス
     * @param accountId 表示対象アカウントID。未ロード時はnull
     */
    public void renderDetail(
        @NotNull Inventory inventory,
        @NotNull GuideEntry guide,
        @NotNull GuideService guideService,
        UUID accountId
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());
        inventory.setItem(SUMMARY_SLOT, summaryItem(guide, guideService));

        List<GuideStep> steps = guide.steps();
        for (int i = 0; i < Math.min(steps.size(), DETAIL_LINE_SLOTS.size()); i++) {
            GuideStep step = steps.get(i);
            boolean completed = guideService.isStepCompleted(accountId, guide.id(), step.id());
            inventory.setItem(
                DETAIL_LINE_SLOTS.get(i),
                stepItem(i + 1, guideService.resolveText(step.text()), step, guideService, completed)
            );
        }
        if (steps.size() > DETAIL_LINE_SLOTS.size()) {
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

    /**
     * 詳細画面の物理スロットから手順を解決します。
     *
     * @param guide 対象ガイド
     * @param rawSlot クリックされた物理スロット
     * @return 対応する手順。対象外または表示上限外の場合は null
     */
    public @Nullable GuideStep getStepAtSlot(@NotNull GuideEntry guide, int rawSlot) {
        int index = DETAIL_LINE_SLOTS.indexOf(rawSlot);
        return index < 0 || index >= guide.steps().size() ? null : guide.steps().get(index);
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
        @NotNull GuideService guideService,
        UUID accountId
    ) {
        int start = GuiPagination.pageStart(pageIndex, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(pageIndex, guides.size(), CONTENT_SLOT_COUNT);
        for (int i = start; i < end; i++) {
            GuideEntry guide = guides.get(i);
            inventory.setItem(i - start, listItem(guide, guideService, accountId));
        }
    }

    private void renderNavigation(@NotNull Inventory inventory, int itemCount, int pageIndex, boolean rewardClaimable) {
        ItemStack spacer = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = CONTENT_SLOT_COUNT; slot < SIZE; slot++) {
            inventory.setItem(slot, spacer);
        }

        inventory.setItem(PREVIOUS_SLOT, GuiItems.previousPageButton(
            Component.text("前のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text(pageIndex + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasPreviousPage(pageIndex)
        ));
        inventory.setItem(BACK_SLOT, backItem());
        if (rewardClaimable) {
            inventory.setItem(REWARD_SLOT, createItem(
                Material.CHEST,
                Component.text("全ガイド達成報酬を受け取る", NamedTextColor.GOLD, TextDecoration.BOLD),
                List.of(Component.text("クリックすると報酬がメールに届きます", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(NEXT_SLOT, GuiItems.nextPageButton(
            Component.text("次のページ", NamedTextColor.WHITE, TextDecoration.BOLD),
            List.of(Component.text((pageIndex + 2) + " / " + totalPages(itemCount), NamedTextColor.GRAY)),
            hasNextPage(pageIndex, itemCount)
        ));
    }

    private @NotNull ItemStack listItem(
        @NotNull GuideEntry guide,
        @NotNull GuideService guideService,
        UUID accountId
    ) {
        List<Component> lore = new ArrayList<>();
        if (guide.summary() != null && !guide.summary().isBlank()) {
            lore.add(component(guideService.resolveText(guide.summary()), ""));
        }
        boolean completed = guideService.isGuideCompleted(accountId, guide);
        lore.add(Component.text(completed ? "達成済み" : "未達成", completed ? NamedTextColor.GREEN : NamedTextColor.GRAY));
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

    private @NotNull ItemStack stepItem(
        int lineNumber,
        @NotNull String text,
        @NotNull GuideStep step,
        @NotNull GuideService guideService,
        boolean completed
    ) {
        List<Component> lore = new ArrayList<>();
        lore.add(componentWithFallbackColor(text, NamedTextColor.WHITE));
        for (String detail : step.details()) {
            lore.add(componentWithFallbackColor(guideService.resolveText(detail), NamedTextColor.GRAY));
        }
        if (step.action() != null && !step.action().description().isBlank()) {
            lore.add(componentWithFallbackColor(
                guideService.resolveText(step.action().description()),
                NamedTextColor.GRAY
            ));
        }
        return createItem(
            completed ? Material.LIME_DYE : Material.GRAY_DYE,
            Component.text((completed ? "✓ " : "□ ") + lineNumber + ".", completed ? NamedTextColor.GREEN : NamedTextColor.GRAY),
            lore
        );
    }

    private @NotNull Component component(@NotNull String text, @NotNull String fallback) {
        return GuiItems.noItalic(LEGACY.deserialize(ColorCodeUtil.toLegacyText(text, fallback)));
    }

    private @NotNull Component componentWithFallbackColor(
        @NotNull String text,
        @NotNull NamedTextColor fallbackColor
    ) {
        return GuiItems.noItalic(ColorCodeUtil.toComponent(text, "", fallbackColor));
    }

    private @NotNull Material material(String materialName, @NotNull Material fallback) {
        if (materialName == null || materialName.isBlank()) {
            return fallback;
        }
        Material material = Material.matchMaterial(materialName.trim().toUpperCase(Locale.ROOT));
        return material == null ? fallback : material;
    }
}
