package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
import io.github.maaasu.astralRecord.feature.skill.service.SkillIconCatalog;
import io.github.maaasu.astralRecord.feature.skill.service.SkillPresentationUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.confirm.ConfirmDialogView;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/** 習得済みスキルの忘却一覧と確認画面を描画します。 */
public final class SkillForgetGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SLOT_COUNT = 45;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int PAID_CONFIRM_SLOT = 13;
    public static final int NEXT_PAGE_SLOT = 53;

    private static final Material DEFAULT_SKILL_ICON = Material.AMETHYST_SHARD;
    private final NamespacedKey learnedSkillIdKey;
    private final ConfirmDialogView confirmDialogView = new ConfirmDialogView();

    public SkillForgetGui(@NotNull AstralRecord plugin) {
        learnedSkillIdKey = new NamespacedKey(plugin, "skill_forget_learned_skill_id");
    }

    /**
     * 忘却対象の一覧画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param entries 表示する習得済みスキル
     * @param pageIndex 開くページ番号（0 始まり）
     */
    public void open(
        @NotNull Player player,
        @NotNull List<SkillManagerEntry> entries,
        @NotNull java.util.Set<String> permittedSkillIds,
        int pageIndex
    ) {
        int page = normalizePage(pageIndex, entries.size());
        int pages = totalPages(entries.size());
        Inventory inventory = Bukkit.createInventory(
            new SkillForgetInventoryHolder(SkillForgetScreen.LIST, page),
            SIZE,
            Component.text("スキル忘却 " + (page + 1) + "/" + pages, NamedTextColor.DARK_PURPLE)
        );
        ItemStack spacer = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) inventory.setItem(slot, spacer);

        int start = GuiPagination.pageStart(page, CONTENT_SLOT_COUNT);
        int end = GuiPagination.pageEnd(page, entries.size(), CONTENT_SLOT_COUNT);
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, createSkillItem(entries.get(index), permittedSkillIds));
        }
        inventory.setItem(PREVIOUS_PAGE_SLOT, pageItem("前のページ", page, pages, page > 0));
        inventory.setItem(NEXT_PAGE_SLOT, pageItem("次のページ", page + 2, pages, page + 1 < pages));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 選択したスキルの忘却確認画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param entry 忘却対象
     * @param permittedSkillIds 条件付き効果の説明に使う現在の使用許可
     * @param returnPage 一覧へ戻るページ番号
     * @param paidOption 有償忘却の消費と返却の表示内容
     */
    public void openConfirm(
        @NotNull Player player,
        @NotNull SkillManagerEntry entry,
        @NotNull java.util.Set<String> permittedSkillIds,
        int returnPage,
        @Nullable PaidOption paidOption
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillForgetInventoryHolder(
                SkillForgetScreen.CONFIRM,
                returnPage,
                entry.bindingId(),
                paidOption == null ? null : new SkillForgetInventoryHolder.PaidQuote(
                    paidOption.item().getId(), paidOption.amount(), paidOption.cost())
            ),
            ConfirmDialogView.SIZE,
            Component.text("スキル忘却の確認", NamedTextColor.YELLOW)
        );
        List<Component> lore = skillLore(entry, permittedSkillIds);
        SkillLore.section(lore, "忘却したとき", NamedTextColor.RED);
        lore.add(SkillLore.note("このスキルが習得一覧から削除されます。"));
        lore.add(SkillLore.note("強化素材・装着シジルは返却されません。"));
        List<Component> regular = new ArrayList<>();
        SkillLore.section(regular, "通常の忘却", NamedTextColor.RED);
        regular.add(SkillLore.row("消費", SkillLore.value("なし")));
        regular.add(SkillLore.row("返却", SkillLore.value("なし")));
        confirmDialogView.render(
            inventory,
            skillName(entry),
            SkillLore.finish(lore),
            Component.text("はい、忘却する", NamedTextColor.RED),
            Component.text("一覧へ戻る", NamedTextColor.GREEN),
            SkillLore.finish(regular)
        );
        ItemStack regularButton = inventory.getItem(ConfirmDialogView.CONFIRM_SLOT);
        if (regularButton != null) inventory.setItem(ConfirmDialogView.CONFIRM_SLOT, SkillLore.style(regularButton, null));
        ItemStack message = inventory.getItem(ConfirmDialogView.MESSAGE_SLOT);
        if (message != null) {
            inventory.setItem(ConfirmDialogView.MESSAGE_SLOT,
                SkillIconCatalog.apply(message, entry.definition().getId()));
        }
        inventory.setItem(PAID_CONFIRM_SLOT, paidButton(paidOption));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public @Nullable SkillForgetInventoryHolder holder(@Nullable Inventory inventory) {
        if (inventory == null) return null;
        InventoryHolder holder = inventory.getHolder();
        return holder instanceof SkillForgetInventoryHolder skillHolder ? skillHolder : null;
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return holder(inventory) != null;
    }

    public @Nullable String learnedSkillId(@Nullable ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(learnedSkillIdKey, PersistentDataType.STRING);
    }

    public int normalizePage(int pageIndex, int itemCount) {
        return GuiPagination.normalizePage(pageIndex, itemCount, CONTENT_SLOT_COUNT);
    }

    public int totalPages(int itemCount) {
        return GuiPagination.totalPages(itemCount, CONTENT_SLOT_COUNT);
    }

    /**
     * 有償忘却の見積もりです。決済時は操作側で所持・空き枠を再検証します。
     *
     * @param item 初回習得分として返却する素材
     * @param amount 素材の数量
     * @param cost 消費するアストラルド
     * @param available 閲覧時点で決済条件を満たすか
     */
    public record PaidOption(@NotNull ItemModel item, int amount, long cost, boolean available) {}

    /** 有償忘却の実費と返却対象を表示します。 */
    private ItemStack paidButton(@Nullable PaidOption option) {
        var lore = new ArrayList<Component>();
        SkillLore.section(lore, "有償の忘却", NamedTextColor.GOLD);
        if (option == null) {
            lore.add(Component.text("返却素材を確認できないため利用不可", NamedTextColor.RED));
        } else {
            lore.add(SkillLore.row("消費", SkillLore.value(option.cost() + "アストラルド")));
            lore.add(SkillLore.materialRow(option.item().getId(), "初回習得分を返却 " + option.amount() + "個",
                SkillPresentationUtil.itemNameComponent(option.item(), "未登録の素材", NamedTextColor.WHITE)));
            if (!option.available()) {
                lore.add(Component.text("所持通貨と所持品の空きを確認してください", NamedTextColor.RED));
            }
        }
        lore.add(SkillLore.note("強化素材・装着シジルは返却されません。"));
        return SkillLore.style(GuiItems.create(
            Material.GOLD_INGOT,
            Component.text("忘却の代償を払う", NamedTextColor.GOLD),
            SkillLore.finish(lore)
        ), null);
    }

    /** 一覧と確認画面の両方に習得個体の現在の効果を表示します。 */
    private List<Component> skillLore(SkillManagerEntry entry, java.util.Set<String> permittedSkillIds) {
        List<Component> lore = new ArrayList<>(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            entry.resolved(), permittedSkillIds, NamedTextColor.GRAY
        ));
        SkillLore.section(lore, "習得状況", NamedTextColor.AQUA);
        lore.add(SkillLore.row("レベル", SkillLore.value(entry.learnedSkill().getLevel() + " / " + entry.definition().getMaxLevel())));
        lore.add(SkillLore.row("装着シジル", SkillLore.value(entry.learnedSkill().getSigils().size() + "個")));
        return lore;
    }

    /** 内部IDを含めず、習得個体の表示名と現在のレベルを返します。 */
    private Component skillName(SkillManagerEntry entry) {
        return SkillPresentationUtil.skillNameComponent(entry.definition(), "未登録のスキル", NamedTextColor.WHITE)
            .append(Component.text(" Lv." + entry.learnedSkill().getLevel(), NamedTextColor.GOLD));
    }

    private @NotNull ItemStack createSkillItem(
        @NotNull SkillManagerEntry entry,
        @NotNull java.util.Set<String> permittedSkillIds
    ) {
        List<Component> lore = skillLore(entry, permittedSkillIds);
        lore.add(Component.text("クリックして忘却確認を開く", NamedTextColor.YELLOW));
        ItemStack item = SkillIconCatalog.apply(GuiItems.create(
            parseMaterial(entry.definition().getIcon()),
            skillName(entry),
            SkillLore.finish(lore),
            entry.definition().getIconTexture()
        ), entry.definition().getId());
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(learnedSkillIdKey, PersistentDataType.STRING, entry.bindingId());
        item.setItemMeta(meta);
        return item;
    }

    private @NotNull ItemStack pageItem(@NotNull String name, int page, int pages, boolean enabled) {
        Component label = Component.text(name, NamedTextColor.WHITE);
        List<Component> lore = List.of(Component.text(page + " / " + pages, NamedTextColor.GRAY));
        return name.startsWith("次")
            ? GuiItems.nextPageButton(label, lore, enabled)
            : GuiItems.previousPageButton(label, lore, enabled);
    }

    private @NotNull Material parseMaterial(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_SKILL_ICON;
        Material material = MaterialNameResolver.match(raw);
        return material == null ? DEFAULT_SKILL_ICON : material;
    }
}
