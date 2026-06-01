package io.github.maaasu.astralRecord.feature.menu.player;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * 条件付きプレイヤー一覧を共通レイアウトで描画するページング GUI です。
 */
public final class PlayerListGui {
    public static final int SIZE = PagedGuiView.SIZE;
    public static final int CONTENT_SLOT_COUNT = PagedGuiView.CONTENT_SLOT_COUNT;
    public static final int PREVIOUS_SLOT = PagedGuiView.PREVIOUS_SLOT;
    public static final int BACK_SLOT = PagedGuiView.BACK_SLOT;
    public static final int CLOSE_SLOT = PagedGuiView.CLOSE_SLOT;
    public static final int NEXT_SLOT = PagedGuiView.NEXT_SLOT;

    private final PagedGuiView pagedGuiView = new PagedGuiView();

    /**
     * 指定条件のプレイヤー一覧を開きます。
     *
     * @param viewer      閲覧プレイヤー
     * @param purpose     一覧の用途
     * @param backTarget  戻り先
     * @param title       画面タイトル
     * @param candidateIds 表示対象プレイヤー UUID 一覧
     * @param pageIndex   0 始まりのページ番号
     */
    public void open(
        @NotNull Player viewer,
        @NotNull PlayerListPurpose purpose,
        @NotNull PlayerListBackTarget backTarget,
        @NotNull String title,
        @NotNull List<UUID> candidateIds,
        int pageIndex
    ) {
        List<UUID> sortedIds = candidateIds.stream()
            .distinct()
            .sorted(Comparator.comparing(this::playerName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, sortedIds.size());
        int totalPages = pagedGuiView.totalPages(sortedIds.size());
        Inventory inventory = Bukkit.createInventory(
            new Holder(purpose, backTarget, normalizedPage, sortedIds),
            SIZE,
            Component.text(title + " " + (normalizedPage + 1) + "/" + totalPages, NamedTextColor.AQUA)
        );
        List<ItemStack> items = sortedIds.stream()
            .map(playerId -> createPlayerItem(viewer, purpose, playerId))
            .toList();
        pagedGuiView.render(inventory, items, normalizedPage);
        viewer.openInventory(inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable PlayerListPurpose getPurpose(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.purpose();
        }
        return null;
    }

    public @Nullable PlayerListBackTarget getBackTarget(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.backTarget();
        }
        return null;
    }

    public int getPageIndex(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.pageIndex();
        }
        return 0;
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pagedGuiView.hasPreviousPage(pageIndex);
    }

    public boolean hasNextPage(@Nullable Inventory inventory) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)) {
            return false;
        }
        return pagedGuiView.hasNextPage(holder.pageIndex(), holder.playerIds().size());
    }

    public @Nullable UUID getPlayerId(@Nullable Inventory inventory, int rawSlot) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)) {
            return null;
        }
        if (rawSlot < 0 || rawSlot >= CONTENT_SLOT_COUNT) {
            return null;
        }
        int index = holder.pageIndex() * CONTENT_SLOT_COUNT + rawSlot;
        if (index < 0 || index >= holder.playerIds().size()) {
            return null;
        }
        return holder.playerIds().get(index);
    }

    private @NotNull ItemStack createPlayerItem(
        @NotNull Player viewer,
        @NotNull PlayerListPurpose purpose,
        @NotNull UUID playerId
    ) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        String playerName = playerName(playerId);
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(noItalic(Component.text(playerName, NamedTextColor.WHITE)));
            skullMeta.lore(new ArrayList<>(buildLore(viewer, purpose, playerId)));
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
            return itemStack;
        }
        return itemStack;
    }

    private @NotNull List<Component> buildLore(
        @NotNull Player viewer,
        @NotNull PlayerListPurpose purpose,
        @NotNull UUID playerId
    ) {
        Player target = Bukkit.getPlayer(playerId);
        AstPlayer astTarget = target == null ? null : AstPlayerCache.get(target);
        List<Component> lore = new ArrayList<>();
        lore.add(noItalic(Component.text("ワールド: " + (target == null ? "Unknown" : target.getWorld().getName()), NamedTextColor.GRAY)));
        if (astTarget != null) {
            lore.add(noItalic(Component.text("モード: " + astTarget.getAccount().getMode().getDisplayName(), NamedTextColor.GRAY)));
            lore.add(noItalic(Component.text("Lv: " + astTarget.getAccount().getLevel(), NamedTextColor.YELLOW)));
        }
        if (purpose == PlayerListPurpose.PARTY_INVITE) {
            lore.add(Component.empty());
            lore.add(noItalic(Component.text("クリックでパーティーへ招待", NamedTextColor.GREEN)));
        } else {
            if (playerId.equals(viewer.getUniqueId())) {
                lore.add(noItalic(Component.text("自分自身", NamedTextColor.AQUA)));
            }
            lore.add(Component.empty());
            lore.add(noItalic(Component.text("クリックで詳細情報を表示", NamedTextColor.GREEN)));
        }
        return lore;
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player online = Bukkit.getPlayer(playerId);
        if (online != null) {
            return online.getName();
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        return offlinePlayer.getName() == null ? playerId.toString() : offlinePlayer.getName();
    }

    private @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private record Holder(
        @NotNull PlayerListPurpose purpose,
        @NotNull PlayerListBackTarget backTarget,
        int pageIndex,
        @NotNull List<UUID> playerIds
    ) implements InventoryHolder {
        private Holder {
            playerIds = List.copyOf(playerIds);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
