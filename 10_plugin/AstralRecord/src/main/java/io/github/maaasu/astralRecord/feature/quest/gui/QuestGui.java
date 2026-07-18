package io.github.maaasu.astralRecord.feature.quest.gui;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestBoardEntry;
import io.github.maaasu.astralRecord.feature.quest.model.QuestCompletionMode;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestDisplayState;
import io.github.maaasu.astralRecord.feature.quest.model.QuestObjectiveDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestProgress;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRequirementDefinition;
import io.github.maaasu.astralRecord.feature.quest.model.QuestRewardDefinition;
import io.github.maaasu.astralRecord.feature.quest.service.QuestService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class QuestGui {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    public static final int SIZE = 54;
    public static final int MAX_LOGICAL_SLOT = 27;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    public static final int NEXT_PAGE_SLOT = 53;

    private final QuestService questService;
    private final NamespacedKey questIdKey;

    public QuestGui(@NotNull AstralRecord plugin, @NotNull QuestService questService) {
        this.questService = questService;
        this.questIdKey = new NamespacedKey(plugin, "quest_id");
    }

    public void openBoard(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull QuestBoardDefinition board, @Nullable String npcId) {
        openBoard(player, astPlayer, board, npcId, 0);
    }

    public void openBoard(@NotNull Player player, @NotNull AstPlayer astPlayer, @NotNull QuestBoardDefinition board, @Nullable String npcId, int pageIndex) {
        int page = normalizeBoardPage(board, pageIndex);
        Inventory inventory = Bukkit.createInventory(
            new BoardHolder(board.id(), npcId, page),
            SIZE,
            LEGACY.deserialize(ColorCodeUtil.toLegacyText(board.name(), board.id()) + pageSuffix(totalBoardPages(board), page))
        );
        fillFrame(inventory);
        board.entries().stream()
            .filter(entry -> toPageIndex(entry.page()) == page)
            .sorted(Comparator.comparingInt(this::entrySortSlot))
            .forEach(entry -> {
                QuestDefinition quest = questService.findQuest(entry.questId());
                int slot = toGuiSlot(entry.slot(), entry.row(), entry.column());
                if (quest != null && slot >= 0) {
                    inventory.setItem(slot, questItem(astPlayer, quest, false));
                }
            });
        renderPagination(inventory, page, totalBoardPages(board));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public void openList(@NotNull Player player, @NotNull AstPlayer astPlayer) {
        Inventory inventory = Bukkit.createInventory(
            new ListHolder(),
            SIZE,
            Component.text("クエスト一覧", NamedTextColor.DARK_GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        );
        fillFrame(inventory);
        List<QuestDefinition> active = questService.activeQuests(astPlayer);
        for (int index = 0; index < Math.min(MAX_LOGICAL_SLOT + 1, active.size()); index++) {
            int row = index / 7;
            int column = index % 7;
            inventory.setItem((row + 1) * 9 + column + 1, questItem(astPlayer, active.get(index), true));
        }
        inventory.setItem(49, GuiItems.create(
            Material.BOOK,
            Component.text("受領枠", NamedTextColor.WHITE, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
            List.of(Component.text(active.size() + " / " + questService.maxActiveQuests(astPlayer), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false))
        ));
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    public boolean isBoardInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BoardHolder;
    }

    public boolean isListInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof ListHolder;
    }

    public @Nullable String getQuestId(@Nullable ItemStack itemStack) {
        if (itemStack == null || itemStack.getType() == Material.AIR || !itemStack.hasItemMeta()) {
            return null;
        }
        return itemStack.getItemMeta().getPersistentDataContainer().get(questIdKey, PersistentDataType.STRING);
    }

    public @Nullable String getBoardId(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BoardHolder holder ? holder.boardId() : null;
    }

    public @Nullable String getNpcId(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BoardHolder holder ? holder.npcId() : null;
    }

    public int getPageIndex(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof BoardHolder holder ? holder.pageIndex() : 0;
    }

    public boolean hasPreviousPage(int pageIndex) {
        return pageIndex > 0;
    }

    public boolean hasNextPage(@NotNull QuestBoardDefinition board, int pageIndex) {
        return pageIndex + 1 < totalBoardPages(board);
    }

    private @NotNull ItemStack questItem(@NotNull AstPlayer player, @NotNull QuestDefinition quest, boolean listMode) {
        QuestDisplayState state = questService.displayState(player, quest);
        ItemStack item = new ItemStack(quest.icon());
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.displayName(questDisplayName(quest, state));
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("状態: " + stateLabel(state), color(state)).decoration(TextDecoration.ITALIC, false));
        for (String line : quest.description()) {
            lore.add(Component.text(line, NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        appendObjectives(lore, player, quest);
        appendRequirements(lore, quest);
        appendRewards(lore, quest.rewards());
        lore.add(Component.empty());
        if (listMode) {
            lore.add(Component.text("クリックで破棄します", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text(boardActionLabel(state, quest), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
            if (state == QuestDisplayState.COOLDOWN) {
                lore.add(Component.text("残り " + questService.cooldownRemainingSeconds(player, quest) + " 秒", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
            }
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(questIdKey, PersistentDataType.STRING, quest.id());
        item.setItemMeta(meta);
        return item;
    }

    private void appendObjectives(@NotNull List<Component> lore, @NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        QuestProgress progress = questService.progress(player, quest.id());
        lore.add(Component.text("目標", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        for (QuestObjectiveDefinition objective : quest.objectives()) {
            int current = progress == null ? 0 : progress.progress(objective.id());
            lore.add(Component.text("- " + objective.type().displayName() + ": " + objective.label() + " " + current + " / " + objective.amount(), NamedTextColor.WHITE)
                .decoration(TextDecoration.ITALIC, false));
        }
    }

    private void appendRequirements(@NotNull List<Component> lore, @NotNull QuestDefinition quest) {
        if (quest.requirements().isEmpty()) {
            return;
        }
        lore.add(Component.empty());
        lore.add(Component.text("受領条件", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        for (QuestRequirementDefinition requirement : quest.requirements()) {
            lore.add(Component.text("- " + questService.resolveItemDisplayName(requirement.item()) + " x" + requirement.item().amount()
                + (requirement.consume() ? " (消費)" : " (所持)"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
    }

    private void appendRewards(@NotNull List<Component> lore, @NotNull QuestRewardDefinition rewards) {
        lore.add(Component.empty());
        lore.add(Component.text("報酬", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        if (rewards.exp() <= 0 && rewards.gold() <= 0 && rewards.items().isEmpty()) {
            lore.add(Component.text("- なし", NamedTextColor.DARK_GRAY).decoration(TextDecoration.ITALIC, false));
            return;
        }
        if (rewards.exp() > 0) {
            lore.add(Component.text("- EXP " + rewards.exp(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }
        if (rewards.gold() > 0) {
            lore.add(Component.text("- Gold " + rewards.gold(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        for (var item : rewards.items()) {
            lore.add(Component.text("- " + questService.resolveItemDisplayName(item) + " x" + item.amount(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }
    }

    private @NotNull String boardActionLabel(@NotNull QuestDisplayState state, @NotNull QuestDefinition quest) {
        return switch (state) {
            case AVAILABLE -> "クリックで受領します";
            case READY_TO_TURN_IN -> quest.completionMode() == QuestCompletionMode.NPC ? "クリックで報告します" : "報酬受取待ち";
            case IN_PROGRESS -> "進行中です";
            case COMPLETED -> "完了済みです";
            case COOLDOWN -> "再受領まで待機中です";
            case LOCKED -> "受領条件を満たしていません";
        };
    }

    private @NotNull String stateLabel(@NotNull QuestDisplayState state) {
        return switch (state) {
            case AVAILABLE -> "受領可能";
            case IN_PROGRESS -> "進行中";
            case READY_TO_TURN_IN -> "報告可能";
            case COMPLETED -> "完了済み";
            case COOLDOWN -> "クールタイム中";
            case LOCKED -> "条件未達成";
        };
    }

    private @NotNull NamedTextColor color(@NotNull QuestDisplayState state) {
        return switch (state) {
            case AVAILABLE -> NamedTextColor.GREEN;
            case IN_PROGRESS -> NamedTextColor.AQUA;
            case READY_TO_TURN_IN -> NamedTextColor.GOLD;
            case COMPLETED -> NamedTextColor.DARK_GRAY;
            case COOLDOWN -> NamedTextColor.YELLOW;
            case LOCKED -> NamedTextColor.RED;
        };
    }

    private @NotNull Component questDisplayName(@NotNull QuestDefinition quest, @NotNull QuestDisplayState state) {
        String text = ColorCodeUtil.BOLD + colorCode(state) + ColorCodeUtil.toLegacyText(quest.name(), quest.id());
        return LEGACY.deserialize(text).decoration(TextDecoration.ITALIC, false);
    }

    private @NotNull String colorCode(@NotNull QuestDisplayState state) {
        return switch (state) {
            case AVAILABLE -> ColorCodeUtil.GREEN;
            case IN_PROGRESS -> ColorCodeUtil.AQUA;
            case READY_TO_TURN_IN -> ColorCodeUtil.GOLD;
            case COMPLETED -> ColorCodeUtil.DARK_GRAY;
            case COOLDOWN -> ColorCodeUtil.YELLOW;
            case LOCKED -> ColorCodeUtil.RED;
        };
    }

    private int normalizeBoardPage(@NotNull QuestBoardDefinition board, int pageIndex) {
        return Math.max(0, Math.min(pageIndex, totalBoardPages(board) - 1));
    }

    private int totalBoardPages(@NotNull QuestBoardDefinition board) {
        return Math.max(1, board.entries().stream().mapToInt(entry -> toPageIndex(entry.page())).max().orElse(0) + 1);
    }

    private @NotNull String pageSuffix(int totalPages, int pageIndex) {
        return totalPages <= 1 ? "" : ColorCodeUtil.GRAY + " (" + (pageIndex + 1) + "/" + totalPages + ")";
    }

    private void renderPagination(@NotNull Inventory inventory, int pageIndex, int totalPages) {
        if (pageIndex > 0) {
            inventory.setItem(PREVIOUS_PAGE_SLOT, GuiItems.create(Material.MAP, Component.text("前のページ", NamedTextColor.WHITE), List.of()));
        }
        if (pageIndex + 1 < totalPages) {
            inventory.setItem(NEXT_PAGE_SLOT, GuiItems.create(Material.MAP, Component.text("次のページ", NamedTextColor.WHITE), List.of()));
        }
    }

    private void fillFrame(@NotNull Inventory inventory) {
        ItemStack filler = GuiItems.create(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
        for (int logical = 0; logical <= MAX_LOGICAL_SLOT; logical++) {
            int row = logical / 7;
            int column = logical % 7;
            inventory.setItem((row + 1) * 9 + column + 1, new ItemStack(Material.AIR));
        }
    }

    private int entrySortSlot(@NotNull QuestBoardEntry entry) {
        int slot = toGuiSlot(entry.slot(), entry.row(), entry.column());
        return slot < 0 ? Integer.MAX_VALUE : slot;
    }

    private int toGuiSlot(@Nullable Integer logicalSlot, @Nullable Integer row, @Nullable Integer column) {
        Integer slot = logicalSlot;
        if (slot == null && row != null && column != null) {
            slot = (row - 1) * 7 + (column - 1);
        }
        if (slot == null || slot < 0 || slot > MAX_LOGICAL_SLOT) {
            return -1;
        }
        return (slot / 7 + 1) * 9 + slot % 7 + 1;
    }

    private int toPageIndex(int page) {
        return Math.max(0, page - 1);
    }

    public record BoardHolder(@NotNull String boardId, @Nullable String npcId, int pageIndex) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }

    public record ListHolder() implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
