package io.github.maaasu.astralRecord.feature.skill.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetInventoryHolder;
import io.github.maaasu.astralRecord.feature.skill.model.SkillForgetScreen;
import io.github.maaasu.astralRecord.feature.skill.model.SkillManagerEntry;
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
    public void open(@NotNull Player player, @NotNull List<SkillManagerEntry> entries, int pageIndex) {
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
            inventory.setItem(index - start, createSkillItem(entries.get(index)));
        }
        if (page > 0) inventory.setItem(PREVIOUS_PAGE_SLOT, pageItem("前のページ", page, pages));
        if (page + 1 < pages) inventory.setItem(NEXT_PAGE_SLOT, pageItem("次のページ", page + 2, pages));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 選択したスキルの忘却確認画面を開きます。
     *
     * @param player 表示対象プレイヤー
     * @param entry 忘却対象
     * @param returnPage 一覧へ戻るページ番号
     */
    public void openConfirm(
        @NotNull Player player,
        @NotNull SkillManagerEntry entry,
        int returnPage
    ) {
        Inventory inventory = Bukkit.createInventory(
            new SkillForgetInventoryHolder(
                SkillForgetScreen.CONFIRM,
                returnPage,
                entry.bindingId()
            ),
            ConfirmDialogView.SIZE,
            Component.text("スキル忘却の確認", NamedTextColor.YELLOW)
        );
        confirmDialogView.render(
            inventory,
            Component.text("このスキルを忘却しますか？", NamedTextColor.YELLOW),
            List.of(
                SkillPresentationUtil.skillNameComponent(entry.definition(), entry.definition().getId(), NamedTextColor.WHITE),
                Component.text("習得一覧から削除されます。", NamedTextColor.GRAY),
                Component.text("通常の忘却ではジェム・シジルは戻りません。", NamedTextColor.RED)
            ),
            Component.text("はい、忘却する", NamedTextColor.RED),
            Component.text("一覧へ戻る", NamedTextColor.GREEN)
        );
        inventory.setItem(
            PAID_CONFIRM_SLOT,
            GuiItems.create(
                Material.GOLD_INGOT,
                Component.text("忘却の代償を払う", NamedTextColor.GOLD),
                List.of(
                    Component.text("100アストラルドを消費", NamedTextColor.YELLOW),
                    Component.text("忘却するスキルのジェムを1個獲得", NamedTextColor.GREEN),
                    Component.text("シジルは返却されません", NamedTextColor.RED),
                    Component.text("高レベル分のジェムは返却されません", NamedTextColor.RED),
                    Component.text("返却されるジェムは1個のみです", NamedTextColor.GRAY)
                )
            )
        );
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

    private @NotNull ItemStack createSkillItem(@NotNull SkillManagerEntry entry) {
        List<Component> lore = new ArrayList<>(SkillPresentationUtil.skillDescriptionAndFlavorLore(
            entry.definition(), NamedTextColor.GRAY
        ));
        if (!lore.isEmpty()) lore.add(Component.text(" "));
        lore.add(Component.text("習得レベル: " + entry.learnedSkill().getLevel(), NamedTextColor.GOLD));
        lore.add(Component.text("装着シジル: " + entry.learnedSkill().getSigils().size() + "個", NamedTextColor.GRAY));
        lore.add(Component.text("クリック: 忘却確認を開く", NamedTextColor.YELLOW));
        ItemStack item = GuiItems.create(
            parseMaterial(entry.definition().getIcon()),
            SkillPresentationUtil.skillNameComponent(
                entry.definition(), entry.definition().getId(), NamedTextColor.WHITE
            ).append(Component.text(" Lv." + entry.learnedSkill().getLevel(), NamedTextColor.GOLD)),
            lore
        );
        ItemMeta meta = item.getItemMeta();
        meta.getPersistentDataContainer().set(learnedSkillIdKey, PersistentDataType.STRING, entry.bindingId());
        item.setItemMeta(meta);
        return item;
    }

    private @NotNull ItemStack pageItem(@NotNull String name, int page, int pages) {
        return GuiItems.create(
            Material.MAP,
            Component.text(name, NamedTextColor.WHITE),
            List.of(Component.text(page + " / " + pages, NamedTextColor.GRAY))
        );
    }

    private @NotNull Material parseMaterial(@Nullable String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT_SKILL_ICON;
        Material material = MaterialNameResolver.match(raw);
        return material == null ? DEFAULT_SKILL_ICON : material;
    }
}
