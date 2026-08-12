package io.github.maaasu.astralRecord.feature.dungeon.gui;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiPagination;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** 踏破済みダンジョンと設定報酬をカルトグラフ専用で表示します。 */
public final class DungeonArchiveGui {
    public static final int SIZE = 54;
    public static final int CONTENT_SIZE = 45;
    public static final int PREVIOUS_SLOT = 45;
    public static final int BACK_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter
            .ofPattern("yyyy/MM/dd HH:mm")
            .withZone(ZoneId.systemDefault());
    private final ItemService itemService;
    private final ItemStackFactory itemStackFactory;

    public DungeonArchiveGui(
            @NotNull ItemService itemService,
            @NotNull ItemStackFactory itemStackFactory
    ) {
        this.itemService = itemService;
        this.itemStackFactory = itemStackFactory;
    }

    /** 踏破済みダンジョン一覧を表示します。 */
    public void openList(
            @NotNull Player player,
            @NotNull UUID accountId,
            @NotNull List<ArchiveDungeon> entries,
            int requestedPage
    ) {
        int page = GuiPagination.normalizePage(requestedPage, entries.size(), CONTENT_SIZE);
        int start = GuiPagination.pageStart(page, CONTENT_SIZE);
        int end = GuiPagination.pageEnd(page, entries.size(), CONTENT_SIZE);
        List<String> visibleIds = entries.subList(start, end).stream().map(ArchiveDungeon::dungeonId).toList();
        Inventory inventory = Bukkit.createInventory(
                new ListHolder(player.getUniqueId(), accountId, page, visibleIds),
                SIZE,
                PlayerMsgResource.getComponent(PlayerMsgId.P_7068.getId())
        );
        if (entries.isEmpty()) {
            inventory.setItem(22, GuiItems.create(
                    Material.MAP,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7067.getId()),
                    List.of()));
        } else {
            for (int index = start; index < end; index++) {
                inventory.setItem(index - start, dungeonItem(entries.get(index)));
            }
        }
        renderNavigation(inventory, page, entries.size(), false);
        GuiOpenSupport.open(player, inventory);
    }

    /** 選択ダンジョンの設定報酬を表示します。 */
    public void openDetails(
            @NotNull Player player,
            @NotNull UUID accountId,
            @NotNull ArchiveDungeon dungeon,
            int listPage,
            int requestedPage
    ) {
        List<ArchiveReward> rewards = dungeon.rewards();
        int page = GuiPagination.normalizePage(requestedPage, rewards.size(), CONTENT_SIZE);
        int start = GuiPagination.pageStart(page, CONTENT_SIZE);
        int end = GuiPagination.pageEnd(page, rewards.size(), CONTENT_SIZE);
        Inventory inventory = Bukkit.createInventory(
                new DetailHolder(
                        player.getUniqueId(), accountId, dungeon.dungeonId(), listPage, page),
                SIZE,
                PlayerMsgResource.formatComponent(PlayerMsgId.P_7072.getId(), dungeon.displayName())
        );
        if (rewards.isEmpty()) {
            inventory.setItem(22, GuiItems.create(
                    Material.CHEST,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7073.getId()),
                    List.of()));
        } else {
            for (int index = start; index < end; index++) {
                inventory.setItem(index - start, rewardItem(rewards.get(index)));
            }
        }
        renderNavigation(inventory, page, rewards.size(), true);
        GuiOpenSupport.open(player, inventory);
    }

    private @NotNull ItemStack dungeonItem(@NotNull ArchiveDungeon entry) {
        return GuiItems.create(
                Material.FILLED_MAP,
                ColorCodeUtil.toComponent(entry.displayName(), "", NamedTextColor.GOLD),
                List.of(
                        PlayerMsgResource.formatComponent(PlayerMsgId.P_7069.getId(), entry.clearCount()),
                        PlayerMsgResource.formatComponent(
                                PlayerMsgId.P_7070.getId(), DATE_FORMAT.format(entry.lastClearedAt())),
                        Component.empty(),
                        PlayerMsgResource.getComponent(PlayerMsgId.P_7071.getId())
                )
        );
    }

    private @NotNull ItemStack rewardItem(@NotNull ArchiveReward reward) {
        ItemModel model = itemService.findLoadedById(reward.itemId());
        if (model == null) {
            return GuiItems.create(
                    Material.BARRIER,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7043.getId()),
                    List.of(PlayerMsgResource.getComponent(PlayerMsgId.P_7044.getId())));
        }
        ItemStack stack = itemStackFactory.create(model, 1);
        ItemMeta meta = stack.getItemMeta();
        List<Component> lore = meta.hasLore() && meta.lore() != null
                ? new ArrayList<>(meta.lore()) : new ArrayList<>();
        lore.add(Component.empty());
        lore.add(PlayerMsgResource.formatComponent(PlayerMsgId.P_7074.getId(), reward.amount()));
        lore.add(PlayerMsgResource.formatComponent(
                PlayerMsgId.P_7046.getId(),
                String.format(Locale.ROOT, "%.2f", reward.rate())
        ));
        meta.lore(lore);
        stack.setItemMeta(meta);
        return stack;
    }

    private void renderNavigation(
            @NotNull Inventory inventory,
            int page,
            int entryCount,
            boolean details
    ) {
        if (GuiPagination.hasPreviousPage(page)) {
            inventory.setItem(PREVIOUS_SLOT, GuiItems.create(
                    Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7041.getId()),
                    List.of()));
        }
        inventory.setItem(BACK_SLOT, details ? GuiItems.backButton() : GuiItems.closeButton());
        if (GuiPagination.hasNextPage(page, entryCount, CONTENT_SIZE)) {
            inventory.setItem(NEXT_SLOT, GuiItems.create(
                    Material.ARROW,
                    PlayerMsgResource.getComponent(PlayerMsgId.P_7042.getId()),
                    List.of()));
        }
    }

    public @Nullable ListHolder listHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ListHolder holder ? holder : null;
    }

    public @Nullable DetailHolder detailHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof DetailHolder holder ? holder : null;
    }

    /** 表示用ダンジョン踏破記録です。 */
    public record ArchiveDungeon(
            @NotNull String dungeonId,
            @NotNull String displayName,
            long clearCount,
            @NotNull Instant lastClearedAt,
            @NotNull List<ArchiveReward> rewards
    ) {
        public ArchiveDungeon { rewards = List.copyOf(rewards); }
    }

    /** カルトグラフだけが表示する設定報酬です。 */
    public record ArchiveReward(@NotNull String itemId, @NotNull String amount, double rate) {
    }

    public record ListHolder(
            @NotNull UUID playerId,
            @NotNull UUID accountId,
            int pageIndex,
            @NotNull List<String> visibleDungeonIds
    ) implements HotbarShortcutGuiHolder {
        public ListHolder { visibleDungeonIds = List.copyOf(visibleDungeonIds); }
        public @Nullable String dungeonIdAt(int slot) {
            return slot >= 0 && slot < visibleDungeonIds.size() ? visibleDungeonIds.get(slot) : null;
        }
        @Override public int getBackSlot() { return BACK_SLOT; }
        @Override public boolean isAlwaysCloseNavigation() { return true; }
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }

    public record DetailHolder(
            @NotNull UUID playerId,
            @NotNull UUID accountId,
            @NotNull String dungeonId,
            int listPageIndex,
            int pageIndex
    ) implements HotbarShortcutGuiHolder {
        @Override public int getBackSlot() { return BACK_SLOT; }
        @Override public boolean isDirectBackNavigation() { return false; }
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }
}
