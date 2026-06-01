package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.model.PartyInvite;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
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
public final class PartyGui {
    public static final int SIZE = 54;
    public static final int CREATE_SLOT = 22;
    public static final int INVITE_SLOT = 15;
    public static final int LEAVE_OR_DISBAND_SLOT = 51;
    public static final int BACK_SLOT = 49;
    public static final int CLOSE_SLOT = -1;
    private static final int INFO_SLOT = 4;
    private static final int LEADER_SLOT = 13;
    private static final int[] MEMBER_SLOTS = {31, 30, 32, 29, 33};
    private static final int[] INVITE_SLOTS = {29, 30, 31, 32, 33};

    private final PartyService partyService;

    public PartyGui(@NotNull PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * パーティー GUI を開きます。
     *
     * @param player 表示対象プレイヤー
     */
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
        player.openInventory(inventory);
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
        inventory.setItem(INFO_SLOT, item(
            Material.WRITABLE_BOOK,
            Component.text("現在パーティーに所属していません", NamedTextColor.YELLOW),
            List.of(Component.text("作成または招待承諾ができます", NamedTextColor.GRAY))
        ));
        inventory.setItem(CREATE_SLOT, item(
            Material.EMERALD,
            Component.text("パーティーを作成", NamedTextColor.GREEN),
            List.of(Component.text("最大 " + PartyService.MAX_MEMBERS + " 人まで参加できます", NamedTextColor.GRAY))
        ));

        for (int index = 0; index < INVITE_SLOTS.length && index < invites.size(); index++) {
            PartyInvite invite = invites.get(index);
            String leaderName = playerName(invite.leaderId());
            inventory.setItem(INVITE_SLOTS[index], playerHead(
                invite.leaderId(),
                Component.text(leaderName + " からの招待", NamedTextColor.AQUA),
                List.of(Component.text("クリックで参加します", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(BACK_SLOT, backItem());
    }

    private void renderParty(@NotNull Inventory inventory, @NotNull Party party, @NotNull UUID viewerId) {
        fill(inventory);
        inventory.setItem(INFO_SLOT, item(
            Material.NETHER_STAR,
            Component.text("パーティー " + party.size() + "/" + PartyService.MAX_MEMBERS, NamedTextColor.GOLD),
            List.of(Component.text("リーダー: " + playerName(party.getLeaderId()), NamedTextColor.GRAY))
        ));

        List<UUID> members = orderedMembers(party);
        if (!members.isEmpty()) {
            UUID leaderId = members.get(0);
            inventory.setItem(LEADER_SLOT, playerHead(
                leaderId,
                Component.text(playerName(leaderId) + " ★", NamedTextColor.GOLD),
                List.of(Component.text("リーダー", NamedTextColor.GRAY))
            ));
        }

        for (int index = 0; index < MEMBER_SLOTS.length; index++) {
            int memberIndex = index + 1;
            if (memberIndex < members.size()) {
                UUID memberId = members.get(memberIndex);
                inventory.setItem(MEMBER_SLOTS[index], playerHead(
                    memberId,
                    Component.text(playerName(memberId), NamedTextColor.WHITE),
                    List.of(Component.text("メンバー", NamedTextColor.GRAY))
                ));
                continue;
            }
            inventory.setItem(MEMBER_SLOTS[index], item(
                Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                Component.text("空きスロット", NamedTextColor.DARK_GRAY),
                List.of()
            ));
        }

        boolean viewerLeader = party.getLeaderId().equals(viewerId);
        if (viewerLeader && party.size() < PartyService.MAX_MEMBERS) {
            inventory.setItem(INVITE_SLOT, item(
                Material.WRITABLE_BOOK,
                Component.text("プレイヤー招待", NamedTextColor.GREEN),
                List.of(Component.text("未所属プレイヤー一覧を開く", NamedTextColor.GRAY))
            ));
        }
        inventory.setItem(LEAVE_OR_DISBAND_SLOT, item(
            viewerLeader ? Material.TNT : Material.OAK_DOOR,
            Component.text(viewerLeader ? "パーティーを解散" : "パーティーを抜ける", viewerLeader ? NamedTextColor.RED : NamedTextColor.YELLOW),
            List.of(Component.text(viewerLeader ? "全メンバーを解散します" : "自分だけが離脱します", NamedTextColor.GRAY))
        ));
        inventory.setItem(BACK_SLOT, backItem());
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        ItemStack panel = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            boolean isBorder = slot < 9 || slot >= 45 || slot % 9 == 0 || slot % 9 == 8;
            inventory.setItem(slot, isBorder ? border : panel);
        }
    }

    private @NotNull ItemStack playerHead(@NotNull UUID playerId, @NotNull Component name, @NotNull List<Component> lore) {
        ItemStack itemStack = new ItemStack(Material.PLAYER_HEAD);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta instanceof SkullMeta skullMeta) {
            OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
            skullMeta.setOwningPlayer(offlinePlayer);
            skullMeta.displayName(name);
            skullMeta.lore(lore);
            skullMeta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(skullMeta);
            return itemStack;
        }
        return item(Material.PLAYER_HEAD, name, lore);
    }

    private @NotNull ItemStack backItem() {
        return item(
            Material.ARROW,
            Component.text("戻る", NamedTextColor.WHITE),
            List.of(Component.text("メニューへ戻る", NamedTextColor.GRAY))
        );
    }

    private @NotNull ItemStack item(@NotNull Material material, @NotNull Component name, @NotNull List<Component> lore) {
        ItemStack itemStack = new ItemStack(material);
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.lore(new ArrayList<>(lore));
            meta.addItemFlags(ItemFlag.values());
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull String playerName(@NotNull UUID playerId) {
        Player player = Bukkit.getPlayer(playerId);
        if (player != null) {
            return player.getName();
        }
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(playerId);
        return offlinePlayer.getName() == null ? playerId.toString() : offlinePlayer.getName();
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
    ) implements InventoryHolder {
        private Holder {
            inviteLeaderIds = List.copyOf(inviteLeaderIds);
            partyMembers = List.copyOf(partyMembers);
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
