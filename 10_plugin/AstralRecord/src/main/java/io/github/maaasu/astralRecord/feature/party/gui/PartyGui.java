package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyInvite;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
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
import java.util.List;
import java.util.UUID;

/**
 * パーティーの状態と招待を表示する GUI です。
 */
public final class PartyGui extends BaseMenuScreenView {
    public static final int SIZE = 54;
    public static final int CREATE_SLOT = 22;
    public static final int INVITE_SLOT = 15;
    public static final int LEAVE_OR_DISBAND_SLOT = 51;
    public static final int BACK_SLOT = BaseMenuScreenView.BACK_SLOT;
    private static final int INFO_SLOT = 4;
    private static final int LEADER_SLOT = 13;
    private static final int[] MEMBER_SLOTS = {31, 30, 32, 29, 33};
    private static final int[] INVITE_SLOTS = {29, 30, 31, 32, 33};

    private final PartyService partyService;

    public PartyGui(@NotNull PartyService partyService) {
        this.partyService = partyService;
    }

    public void open(@NotNull Player player) {
        Party party = partyService.findParty(player.getUniqueId());
        List<PartyInvite> invites = partyService.getInvites(player.getUniqueId());
        Inventory inventory = Bukkit.createInventory(
            new Holder(
                player.getUniqueId(),
                invites.stream().map(PartyInvite::leaderId).toList(),
                party == null ? List.of() : orderedMembers(party)
            ),
            SIZE,
            Component.text("パーティー", NamedTextColor.AQUA)
        );
        if (party == null) {
            renderNoParty(inventory, invites);
        } else {
            renderParty(inventory, party, player.getUniqueId());
        }
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable UUID getInviteLeaderId(@Nullable Inventory inventory, int rawSlot) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)) {
            return null;
        }
        for (int index = 0; index < INVITE_SLOTS.length && index < holder.inviteLeaderIds().size(); index++) {
            if (INVITE_SLOTS[index] == rawSlot) {
                return holder.inviteLeaderIds().get(index);
            }
        }
        return null;
    }

    public boolean isMemberSlot(int rawSlot) {
        if (rawSlot == LEADER_SLOT) {
            return true;
        }
        for (int memberSlot : MEMBER_SLOTS) {
            if (memberSlot == rawSlot) {
                return true;
            }
        }
        return false;
    }

    public @Nullable UUID getMemberId(@Nullable Inventory inventory, int rawSlot) {
        if (!(inventory != null && inventory.getHolder() instanceof Holder holder)) {
            return null;
        }
        if (rawSlot == LEADER_SLOT) {
            return holder.partyMembers().isEmpty() ? null : holder.partyMembers().get(0);
        }
        for (int index = 0; index < MEMBER_SLOTS.length; index++) {
            if (MEMBER_SLOTS[index] != rawSlot) {
                continue;
            }
            int memberIndex = index + 1;
            if (memberIndex >= holder.partyMembers().size()) {
                return null;
            }
            return holder.partyMembers().get(memberIndex);
        }
        return null;
    }

    private void renderNoParty(@NotNull Inventory inventory, @NotNull List<PartyInvite> invites) {
        fill(inventory);
        inventory.setItem(INFO_SLOT, createItem(
            Material.WRITABLE_BOOK,
            Component.text("まだパーティーに参加していません", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(Component.text("作成または招待の承認を行えます", NamedTextColor.GRAY))
        ));
        inventory.setItem(CREATE_SLOT, createItem(
            Material.EMERALD,
            Component.text("パーティーを作成", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text("最大 " + PartyService.MAX_MEMBERS + " 人まで参加できます", NamedTextColor.GRAY))
        ));

        for (int index = 0; index < INVITE_SLOTS.length && index < invites.size(); index++) {
            PartyInvite invite = invites.get(index);
            inventory.setItem(INVITE_SLOTS[index], playerHead(
                invite.leaderId(),
                playerNameComponent(invite.leaderId(), NamedTextColor.AQUA)
                    .append(Component.text(" からの招待", NamedTextColor.AQUA, TextDecoration.BOLD))
                    .decoration(TextDecoration.BOLD, true),
                List.of(Component.text("クリックでパーティーへ参加します", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
    }

    private void renderParty(@NotNull Inventory inventory, @NotNull Party party, @NotNull UUID viewerId) {
        fill(inventory);
        inventory.setItem(INFO_SLOT, createItem(
            Material.NETHER_STAR,
            Component.text("パーティー " + party.size() + "/" + PartyService.MAX_MEMBERS, NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(Component.text("リーダー: ", NamedTextColor.GRAY)
                .append(playerNameComponent(party.getLeaderId(), NamedTextColor.GRAY)))
        ));

        List<UUID> members = orderedMembers(party);
        if (!members.isEmpty()) {
            UUID leaderId = members.get(0);
            inventory.setItem(LEADER_SLOT, playerHead(
                leaderId,
                playerNameComponent(leaderId, NamedTextColor.GOLD)
                    .append(Component.text(" ★", NamedTextColor.GOLD, TextDecoration.BOLD))
                    .decoration(TextDecoration.BOLD, true),
                List.of(Component.text("パーティーリーダー", NamedTextColor.GRAY))
            ));
        }

        for (int index = 0; index < MEMBER_SLOTS.length; index++) {
            int memberIndex = index + 1;
            if (memberIndex < members.size()) {
                UUID memberId = members.get(memberIndex);
                inventory.setItem(MEMBER_SLOTS[index], playerHead(
                    memberId,
                    playerNameComponent(memberId, NamedTextColor.WHITE)
                        .decoration(TextDecoration.BOLD, true),
                    List.of(Component.text("クリックでメンバー操作", NamedTextColor.GRAY))
                ));
                continue;
            }
            inventory.setItem(MEMBER_SLOTS[index], createItem(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text("空きスロット", NamedTextColor.DARK_GRAY),
                List.of(Component.text("新しい仲間を招待できます", NamedTextColor.GRAY))
            ));
        }

        boolean viewerLeader = party.getLeaderId().equals(viewerId);
        if (viewerLeader && party.size() < PartyService.MAX_MEMBERS) {
            inventory.setItem(INVITE_SLOT, createItem(
                Material.WRITABLE_BOOK,
                Component.text("プレイヤー招待", NamedTextColor.GREEN, TextDecoration.BOLD),
                List.of(Component.text("未招待のプレイヤー一覧を開きます", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(LEAVE_OR_DISBAND_SLOT, createItem(
            viewerLeader ? Material.TNT : Material.OAK_DOOR,
            Component.text(viewerLeader ? "パーティーを解散" : "パーティーを抜ける", viewerLeader ? NamedTextColor.RED : NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(Component.text(viewerLeader ? "全メンバーのパーティーを解散します" : "自分だけが退出します", NamedTextColor.GRAY))
        ));
        inventory.setItem(BACK_SLOT, backItem());
    }

    private @NotNull ItemStack playerHead(@NotNull UUID playerId, @NotNull Component name, @NotNull List<Component> lore) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(name.decoration(TextDecoration.ITALIC, false));
            skullMeta.lore(lore.stream().map(line -> line.decoration(TextDecoration.ITALIC, false)).toList());
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
            return itemStack;
        }
        return createItem(Material.PLAYER_HEAD, name, lore);
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                return AccountDisplayNameFormatter.toPlain(astPlayer.getAccount());
            }
            return player.getName();
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        return offlinePlayer.getName() == null ? playerId.toString() : offlinePlayer.getName();
    }

    private @NotNull Component playerNameComponent(
        @NotNull UUID playerId,
        @NotNull NamedTextColor fallbackColor
    ) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            AstPlayer astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                return AccountDisplayNameFormatter.toComponent(astPlayer.getAccount())
                    .colorIfAbsent(fallbackColor);
            }
        }
        return Component.text(playerName(playerId), fallbackColor);
    }

    private @NotNull List<UUID> orderedMembers(@NotNull Party party) {
        List<UUID> ordered = new ArrayList<>();
        ordered.add(party.getLeaderId());
        for (UUID memberId : party.members()) {
            if (!memberId.equals(party.getLeaderId())) {
                ordered.add(memberId);
            }
        }
        return ordered;
    }

    private record Holder(
        @NotNull UUID viewerId,
        @NotNull List<UUID> inviteLeaderIds,
        @NotNull List<UUID> partyMembers
    ) implements HotbarShortcutGuiHolder {
        private Holder {
            inviteLeaderIds = List.copyOf(inviteLeaderIds);
            partyMembers = List.copyOf(partyMembers);
        }

        @Override
        public int getBackSlot() {
            return BACK_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
