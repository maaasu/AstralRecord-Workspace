package io.github.maaasu.astralRecord.feature.party.gui;

import io.github.maaasu.astralRecord.feature.party.model.Party;
import io.github.maaasu.astralRecord.feature.party.service.PartyService;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * パーティーリーダーが掲示板の募集内容と参加方式を設定するGUIです。
 */
public final class PartyRecruitmentSettingsGui {
    public static final int SIZE = 27;
    public static final int MESSAGE_SLOT = 11;
    public static final int APPROVAL_SLOT = 13;
    public static final int PUBLISH_SLOT = 15;
    public static final int BACK_SLOT = 21;

    private final PartyService partyService;

    /**
     * 募集設定GUIを生成します。
     *
     * @param partyService パーティー状態を参照するサービス
     */
    public PartyRecruitmentSettingsGui(@NotNull PartyService partyService) {
        this.partyService = partyService;
    }

    /**
     * 現在の募集設定を表示します。
     *
     * @param player 表示対象のパーティーリーダー
     */
    public void open(@NotNull Player player) {
        Party party = partyService.findParty(player.getUniqueId());
        if (party == null || !party.isLeader(player.getUniqueId())) {
            return;
        }
        Inventory inventory = Bukkit.createInventory(
            new Holder(player.getUniqueId()),
            SIZE,
            Component.text("パーティー募集設定", NamedTextColor.AQUA)
        );
        render(inventory, party);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 対象インベントリが募集設定GUIか判定します。
     *
     * @param inventory 判定対象
     * @return 募集設定GUIなら {@code true}
     */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    private void render(@NotNull Inventory inventory, @NotNull Party party) {
        ItemStack filler = GuiItems.create(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }

        boolean messageConfigured = !party.getRecruitmentMessage().isBlank();
        inventory.setItem(MESSAGE_SLOT, GuiItems.create(
            Material.PAPER,
            Component.text("募集内容を設定", NamedTextColor.YELLOW, TextDecoration.BOLD),
            messageConfigured
                ? List.of(
                    Component.text(party.getRecruitmentMessage(), NamedTextColor.WHITE),
                    Component.text("クリックして金床で変更します", NamedTextColor.GRAY)
                )
                : List.of(
                    Component.text("現在は未設定です", NamedTextColor.RED),
                    Component.text("掲載前に必ず設定してください", NamedTextColor.GRAY)
                )
        ));

        boolean approvalRequired = party.isRecruitmentApprovalRequired();
        inventory.setItem(APPROVAL_SLOT, GuiItems.create(
            approvalRequired ? Material.WRITABLE_BOOK : Material.LIME_DYE,
            Component.text(
                approvalRequired ? "参加には承認が必要" : "承認不要で参加可能",
                approvalRequired ? NamedTextColor.YELLOW : NamedTextColor.GREEN,
                TextDecoration.BOLD
            ),
            List.of(Component.text("クリックで参加方式を切り替えます", NamedTextColor.GRAY))
        ));

        if (party.isRecruitmentPublished()) {
            inventory.setItem(PUBLISH_SLOT, GuiItems.create(
                Material.REDSTONE_BLOCK,
                Component.text("掲示板掲載を停止", NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text("募集内容の変更は掲載中も反映されます", NamedTextColor.GRAY))
            ));
        } else {
            inventory.setItem(PUBLISH_SLOT, GuiItems.create(
                messageConfigured ? Material.EMERALD_BLOCK : Material.BARRIER,
                Component.text("パーティー掲示板へ公開", messageConfigured ? NamedTextColor.GREEN : NamedTextColor.RED, TextDecoration.BOLD),
                List.of(Component.text(
                    messageConfigured ? "現在の設定で募集を開始します" : "募集内容の設定が必要です",
                    NamedTextColor.GRAY
                ))
            ));
        }
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
    }

    private record Holder(@NotNull UUID viewerId) implements HotbarShortcutGuiHolder {
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
