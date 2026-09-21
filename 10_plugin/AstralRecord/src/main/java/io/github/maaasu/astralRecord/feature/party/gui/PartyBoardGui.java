package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.github.maaasu.astralRecord.shared.gui.paging.PagedGuiView;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 現在のサーバーで公開中のパーティーをページング表示する掲示板GUIです。
 */
public final class PartyBoardGui {
    private final PartyService partyService;
    private final PagedGuiView pagedGuiView = new PagedGuiView();

    /**
     * パーティー掲示板GUIを生成します。
     *
     * @param partyService 公開パーティーを参照するサービス
     */
    public PartyBoardGui(@NotNull PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * 公開中のパーティーを指定ページから表示します。
     *
     * @param player 表示対象プレイヤー
     * @param pageIndex 0始まりのページ番号
     */
    public void open(@NotNull Player player, int pageIndex) {
        List<Party> parties = partyService.getPublishedParties();
        int normalizedPage = pagedGuiView.normalizePage(pageIndex, parties.size());
        int start = normalizedPage * PagedGuiView.CONTENT_SLOT_COUNT;
        int end = Math.min(parties.size(), start + PagedGuiView.CONTENT_SLOT_COUNT);
        List<UUID> visiblePartyIds = parties.subList(start, end).stream()
            .map(Party::getPartyId)
            .toList();
        Inventory inventory = Bukkit.createInventory(
            new Holder(player.getUniqueId(), normalizedPage, visiblePartyIds),
            PagedGuiView.SIZE,
            Component.text("パーティー掲示板", NamedTextColor.AQUA)
        );
        List<ItemStack> entries = parties.stream().map(this::partyEntry).toList();
        pagedGuiView.render(inventory, entries, normalizedPage);
        if (parties.isEmpty()) {
            inventory.setItem(22, GuiItems.create(
                Material.WRITABLE_BOOK,
                Component.text("現在公開中のパーティーはありません", NamedTextColor.YELLOW),
                List.of(Component.text("時間をおいてもう一度確認してください", NamedTextColor.GRAY))
            ));
        }
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 対象インベントリがパーティー掲示板GUIか判定します。
     *
     * @param inventory 判定対象
     * @return 掲示板GUIなら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * クリックされた表示枠に対応するパーティーIDを返します。
     *
     * @param inventory 掲示板インベントリ
     * @param rawSlot クリックされたraw slot
     * @return 対応するパーティーID。項目外なら {@code null}
     */
    public @Nullable UUID getPartyId(@Nullable Inventory inventory, int rawSlot) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)
            || rawSlot < 0 || rawSlot >= holder.visiblePartyIds().size()) {
            return null;
        }
        return holder.visiblePartyIds().get(rawSlot);
    }

    /**
     * 表示中のページ番号を返します。
     *
     * @param inventory 掲示板インベントリ
     * @return 0始まりのページ番号。対象外なら0
     */
    public int getPageIndex(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder.pageIndex() : 0;
    }

    private @NotNull ItemStack partyEntry(@NotNull Party party) {
        String leaderName = playerName(party.getLeaderId());
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(
            party.isRecruitmentApprovalRequired() ? "参加方式: リーダー承認制" : "参加方式: 承認不要",
            party.isRecruitmentApprovalRequired() ? NamedTextColor.YELLOW : NamedTextColor.GREEN
        ));
        lore.add(Component.text(
            "メンバー: " + party.size() + "/" + PartyService.MAX_MEMBERS,
            NamedTextColor.WHITE
        ));
        for (UUID memberId : party.members()) {
            lore.add(Component.text("・" + playerName(memberId), NamedTextColor.GRAY));
        }
        lore.add(Component.empty());
        lore.add(Component.text("募集内容", NamedTextColor.AQUA, TextDecoration.BOLD));
        lore.add(Component.text(party.getRecruitmentMessage(), NamedTextColor.WHITE));
        lore.add(Component.empty());
        lore.add(Component.text(
            party.isRecruitmentApprovalRequired() ? "クリックで参加申請を送ります" : "クリックですぐ参加します",
            NamedTextColor.GREEN
        ));
        return playerHead(
            party.getLeaderId(),
            Component.text(leaderName + "のパーティー", NamedTextColor.GOLD, TextDecoration.BOLD),
            lore
        );
    }

    private @NotNull ItemStack playerHead(
        @NotNull UUID playerId,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(playerId));
            skullMeta.displayName(GuiItems.noItalic(name));
            skullMeta.lore(lore.stream().map(GuiItems::noItalic).toList());
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
        }
        return itemStack;
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            return astPlayer == null ? player.getName() : AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        return offlinePlayer.getName() == null ? "不明なプレイヤー" : offlinePlayer.getName();
    }

    private record Holder(
        @NotNull UUID viewerId,
        int pageIndex,
        @NotNull List<UUID> visiblePartyIds
    ) implements HotbarShortcutGuiHolder {
        private Holder {
            visiblePartyIds = List.copyOf(visiblePartyIds);
        }

        @Override
        public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.WRITABLE_BOOK, "パーティー募集掲示板");
        }

        @Override
        public int getBackSlot() {
            return PagedGuiView.BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, PagedGuiView.SIZE);
        }
    }
}
