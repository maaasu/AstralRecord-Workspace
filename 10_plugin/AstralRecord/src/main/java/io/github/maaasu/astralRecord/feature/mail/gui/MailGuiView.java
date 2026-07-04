package io.github.maaasu.astralRecord.feature.mail.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.RewardDisplayFormatter;
import io.github.maaasu.astralRecord.feature.mail.model.MailEntry;
import io.github.maaasu.astralRecord.feature.mail.model.MailFilter;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * メール一覧 GUI を描画します。
 */
public final class MailGuiView {
    public static final int SIZE = PagedGuiView.SIZE;
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int CLOSE_SLOT = PagedGuiView.CLOSE_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;
    public static final int FILTER_SLOT = 47;

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm");

    private final PagedGuiView pagedGuiView = new PagedGuiView();
    private final NamespacedKey mailIdKey;
    private final ItemService itemService;

    public MailGuiView(@NotNull AstralRecord plugin, @NotNull ItemService itemService) {
        this.mailIdKey = new NamespacedKey(plugin, "mail_id");
        this.itemService = itemService;
    }

    public void open(
        @NotNull Player player,
        @NotNull List<MailEntry> mails,
        @NotNull MailFilter filter,
        int pageIndex
    ) {
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, mails.size());
        int totalPages = pagedGuiView.totalPages(mails.size());
        Inventory inventory = Bukkit.createInventory(
            new Holder(normalizedPage, filter),
            SIZE,
            Component.text("メール " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.AQUA)
        );
        render(inventory, mails, filter, normalizedPage);
        player.openInventory(inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    public @NotNull MailFilter getFilter(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.filter();
        }
        return MailFilter.ALL;
    }

    public @Nullable String getMailId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(mailIdKey, PersistentDataType.STRING);
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pagedGuiView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(@NotNull List<MailEntry> mails, int pageIndex) {
        return pagedGuiView.hasNextPage(pageIndex, mails.size());
    }

    private void render(
        @NotNull Inventory inventory,
        @NotNull List<MailEntry> mails,
        @NotNull MailFilter filter,
        int pageIndex
    ) {
        List<ItemStack> items = mails.stream().map(this::createMailItem).toList();
        pagedGuiView.render(inventory, items, pageIndex);
        inventory.setItem(FILTER_SLOT, createFilterItem(filter));
    }

    private @NotNull ItemStack createMailItem(@NotNull MailEntry mail) {
        Material material = Material.matchMaterial(mail.icon());
        if (material == null || !material.isItem()) {
            material = Material.PAPER;
        }
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta == null) {
            return itemStack;
        }

        meta.displayName(Component.text(mail.title(), mail.read() ? NamedTextColor.GRAY : NamedTextColor.GOLD));
        meta.lore(createMailLore(mail));
        meta.getPersistentDataContainer().set(mailIdKey, PersistentDataType.STRING, mail.id());
        meta.addItemFlags(ItemFlag.values());
        if (!mail.read()) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
        }
        itemStack.setItemMeta(meta);
        return itemStack;
    }

    private @NotNull List<Component> createMailLore(@NotNull MailEntry mail) {
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(mail.read() ? "既読" : "未読", mail.read() ? NamedTextColor.DARK_GRAY : NamedTextColor.YELLOW));
        lore.add(Component.text("公開: " + DATE_FORMATTER.format(mail.publishFrom()), NamedTextColor.GRAY));
        if (mail.publishTo() != null) {
            lore.add(Component.text("期限: " + DATE_FORMATTER.format(mail.publishTo()), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        for (String line : mail.body().split("\\R")) {
            if (!line.isBlank()) {
                lore.add(Component.text(line, NamedTextColor.WHITE));
            }
        }
        if (!mail.rewards().isEmpty()) {
            lore.add(Component.empty());
            lore.add(Component.text("報酬:", NamedTextColor.AQUA));
            mail.rewards().forEach(reward -> {
                ItemModel model = itemService.findLoadedById(reward.itemId());
                if (model == null) {
                    model = itemService.loadItem(reward.itemId(), reward.category());
                }
                lore.add(RewardDisplayFormatter.rewardBullet(model, reward.itemId(), reward.amount()));
            });
        }
        lore.add(Component.empty());
        lore.add(Component.text("左クリック: 既読/受け取り", NamedTextColor.GREEN));
        lore.add(Component.text("右クリック: 一覧から削除", NamedTextColor.RED));
        return lore;
    }

    private @NotNull ItemStack createFilterItem(@NotNull MailFilter filter) {
        ItemStack itemStack = new ItemStack(Material.HOPPER);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("フィルター: " + filter.getDisplayNameJa(), NamedTextColor.AQUA));
            meta.lore(List.of(Component.text("クリックで切り替え", NamedTextColor.GRAY)));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    public record Holder(int pageIndex, @NotNull MailFilter filter) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
