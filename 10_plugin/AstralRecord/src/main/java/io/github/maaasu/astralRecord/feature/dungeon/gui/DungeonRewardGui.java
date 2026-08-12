package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.dungeon.model.DungeonRewardEntry;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** プレイヤー固有のダンジョンクリア報酬をページ表示します。 */
public final class DungeonRewardGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SIZE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int NEXT_SLOT = 53;
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;

    /** @param itemService アイテム定義 @param itemStackFactory GUI表示スタック生成 */
    public DungeonRewardGui(@NotNull ItemService itemService, @NotNull ItemStackFactory itemStackFactory) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
    }

    /**
     * 指定プレイヤーの未受取報酬を開きます。
     *
     * @param player 表示対象
     * @param sessionId セッション ID
     * @param dungeonName ダンジョン表示名
     * @param rewards 未受取報酬
     * @param requestedPage 0始まりページ
     */
    public void open(
            @NotNull Player player,
            @NotNull UUID sessionId,
            @NotNull String dungeonName,
            @NotNull List<DungeonRewardEntry> rewards,
            int requestedPage
    ) {
        int page = GuiPagination.normalizePage(requestedPage, rewards.size(), CONTENT_SIZE);
        int start = GuiPagination.pageStart(page, CONTENT_SIZE);
        int end = GuiPagination.pageEnd(page, rewards.size(), CONTENT_SIZE);
        List<UUID> visibleClaimIds = rewards.subList(start, end).stream()
                .map(DungeonRewardEntry::claimId)
                .toList();
        Inventory inventory = Bukkit.createInventory(
                new Holder(sessionId, player.getUniqueId(), page, visibleClaimIds),
                SIZE,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_7040.getId(), dungeonName)
        );
        for (int index = start; index < end; index++) {
            inventory.setItem(index - start, rewardItem(rewards.get(index)));
        }
        if (GuiPagination.hasPreviousPage(page)) {
            inventory.setItem(PREVIOUS_SLOT, GuiItems.create(Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7041.getId()), List.of()));
        }
        if (GuiPagination.hasNextPage(page, rewards.size(), CONTENT_SIZE)) {
            inventory.setItem(NEXT_SLOT, GuiItems.create(Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7042.getId()), List.of()));
        }
        GuiOpenSupport.open(player, inventory);
    }

    private @NotNull ItemStack rewardItem(@NotNull DungeonRewardEntry reward) {
        ItemModel model = itemService.findLoadedById(reward.itemId());
        if (model == null) model = itemService.loadItem(reward.itemId());
        if (model == null) {
            return GuiItems.create(Material.BARRIER,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7043.getId()),
                    List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_7044.getId())));
        }
        ItemStack stack = itemStackFactory.create(model, Math.min(reward.amount(), Math.max(1, model.getMaxStack())));
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(PlayerMsgResource.formatComponent(PlayerMsgId.P_7045.getId(), reward.amount()));
        lore.add(PlayerMsgResource.getComponent(PlayerMsgId.P_7047.getId()));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    /** @return 報酬 GUI なら {@code true} */
    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /** @return 報酬 GUI holder。対象外なら {@code null} */
    public @Nullable Holder holder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder holder ? holder : null;
    }

    /** 報酬 GUI の不変識別情報です。 */
    public record Holder(
            @NotNull UUID sessionId,
            @NotNull UUID playerId,
            int pageIndex,
            @NotNull List<UUID> visibleClaimIds
    )
            implements HotbarShortcutGuiHolder {
        public Holder {
            visibleClaimIds = List.copyOf(visibleClaimIds);
        }

        /** @return 表示時点で slot に紐付いた claim ID。対象外なら {@code null} */
        public @Nullable UUID claimIdAt(int slot) {
            return slot >= 0 && slot < visibleClaimIds.size() ? visibleClaimIds.get(slot) : null;
        }

        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }
}
