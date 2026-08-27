package io.github.maaasu.astralRecord.feature.quest.gui;

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
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
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
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class QuestGui {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacySection();
    private static final long SECONDS_PER_MINUTE = 60L;
    private static final long SECONDS_PER_HOUR = 60L * SECONDS_PER_MINUTE;
    private static final long SECONDS_PER_DAY = 24L * SECONDS_PER_HOUR;
    public static final int SIZE = 54;
    public static final int MAX_LOGICAL_SLOT = 27;
    public static final int PREVIOUS_PAGE_SLOT = 45;
    /** 共通 GUI ナビゲーションの戻る・閉じるボタンを表示するスロットです。 */
    public static final int BACK_SLOT = 49;
    public static final int NEXT_PAGE_SLOT = 53;

    private final QuestService questService;
    private final NamespacedKey questIdKey;

    /**
     * クエスト GUI を初期化します。
     *
     * @param plugin クエスト ID を保持する PersistentDataContainer の名前空間を提供するプラグイン
     * @param questService クエストの表示状態と進行度を取得するサービス
     */
    public QuestGui(@NotNull Plugin plugin, @NotNull QuestService questService) {
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
        renderBoard(inventory, astPlayer, board, page);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 現在表示中の同一クエストボードを最新状態で再描画します。
     *
     * @param player 再描画対象のプレイヤー
     * @param astPlayer クエスト状態を参照するプレイヤー
     * @param expectedBoardId 報告処理を開始したボード ID。別のボードへ移動済みなら再描画しない
     * @return 同一ボードを再描画できた場合は{@code true}、対象GUIが表示されていない場合は{@code false}
     */
    public boolean refreshBoard(
        @NotNull Player player,
        @NotNull AstPlayer astPlayer,
        @NotNull String expectedBoardId
    ) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof BoardHolder holder)
            || !expectedBoardId.equals(holder.boardId())) {
            return false;
        }
        QuestBoardDefinition board = questService.findBoard(holder.boardId());
        if (board == null) {
            return false;
        }
        renderBoard(inventory, astPlayer, board, normalizeBoardPage(board, holder.pageIndex()));
        return true;
    }

    private void renderBoard(
        @NotNull Inventory inventory,
        @NotNull AstPlayer astPlayer,
        @NotNull QuestBoardDefinition board,
        int page
    ) {
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
    }

    public void openList(@NotNull Player player, @NotNull AstPlayer astPlayer) {
        Inventory inventory = Bukkit.createInventory(
            new ListHolder(),
            SIZE,
            Component.text("受領中のクエスト", NamedTextColor.DARK_GREEN, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false)
        );
        renderList(inventory, astPlayer);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
    }

    /**
     * 現在表示中のクエスト一覧を最新状態で再描画します。
     *
     * @param player 再描画対象のプレイヤー
     * @param astPlayer クエスト状態を参照するプレイヤー
     * @return クエスト一覧が表示中で再描画できた場合は{@code true}
     */
    public boolean refreshList(@NotNull Player player, @NotNull AstPlayer astPlayer) {
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (!(inventory.getHolder() instanceof ListHolder)) {
            return false;
        }
        renderList(inventory, astPlayer);
        return true;
    }

    private void renderList(@NotNull Inventory inventory, @NotNull AstPlayer astPlayer) {
        fillFrame(inventory);
        inventory.setItem(BACK_SLOT, GuiItems.backButton());
        List<QuestDefinition> active = questService.activeQuests(astPlayer);
        for (int index = 0; index < Math.min(MAX_LOGICAL_SLOT + 1, active.size()); index++) {
            inventory.setItem(listSlot(index), questItem(astPlayer, active.get(index), true));
        }
        int firstUnavailableSlot = Math.max(active.size(), questService.maxActiveQuests(astPlayer));
        for (int index = firstUnavailableSlot; index <= MAX_LOGICAL_SLOT; index++) {
            inventory.setItem(listSlot(index), questLimitGuideItem(astPlayer));
        }
    }

    private @NotNull ItemStack questLimitGuideItem(@NotNull AstPlayer astPlayer) {
        return GuiItems.create(
            Material.BOOK,
            Component.text("クエスト受領枠を増やすには", NamedTextColor.YELLOW, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false),
            List.of(
                Component.text("現在の受領枠: " + questService.maxActiveQuests(astPlayer) + "件", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text(StatusType.QUEST_LIMIT.getDisplayName() + "を増やすと", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false),
                Component.text("同時に受けられるクエスト数が増えます", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false)
            )
        );
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
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("状態: " + stateLabel(state, quest), color(state)).decoration(TextDecoration.ITALIC, false));
        for (String line : quest.description()) {
            lore.add(ColorCodeUtil.toComponent(line, "", NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        appendQuestInfo(lore, quest);
        appendObjectives(lore, player, quest);
        appendRequirements(lore, quest);
        appendRewards(lore, quest.rewards());
        lore.add(Component.empty());
        if (listMode) {
            lore.add(Component.text("操作: ドロップで破棄します（確認なし）", NamedTextColor.RED).decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(Component.text("操作: " + boardActionLabel(state, quest), color(state)).decoration(TextDecoration.ITALIC, false));
            if (state == QuestDisplayState.COOLDOWN) {
                lore.add(Component.text(
                    "再受領まで: " + formatDuration(questService.cooldownRemainingSeconds(player, quest)),
                    NamedTextColor.YELLOW
                ).decoration(TextDecoration.ITALIC, false));
            }
        }
        ItemStack item = GuiItems.create(quest.icon(), questDisplayName(quest, state), lore);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.lore(lore);
        meta.getPersistentDataContainer().set(questIdKey, PersistentDataType.STRING, quest.id());
        item.setItemMeta(meta);
        return item;
    }

    private void appendQuestInfo(@NotNull List<Component> lore, @NotNull QuestDefinition quest) {
        lore.add(Component.text("クエスト情報", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("完了方法: " + completionLabel(quest), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("再受領: " + repeatLabel(quest), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
    }

    private void appendObjectives(@NotNull List<Component> lore, @NotNull AstPlayer player, @NotNull QuestDefinition quest) {
        QuestProgress progress = questService.progress(player, quest.id());
        lore.add(Component.text("目標", NamedTextColor.GOLD, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        for (QuestObjectiveDefinition objective : quest.objectives()) {
            int current = Math.min(objective.amount(), progress == null ? 0 : progress.progress(objective.id()));
            boolean completed = current >= objective.amount();
            String targetLevel = objective.targetLevel() == null ? "" : " (Lv." + objective.targetLevel() + ")";
            Component line = ColorCodeUtil.toComponent(
                "- " + objective.type().displayName() + ": " + objective.label() + targetLevel,
                "",
                NamedTextColor.WHITE
            ).append(Component.text(
                "  " + current + " / " + objective.amount() + (completed ? "  達成" : "  未達成"),
                completed ? NamedTextColor.GREEN : NamedTextColor.YELLOW
            ));
            lore.add(line.decoration(TextDecoration.ITALIC, false));
        }
    }

    private void appendRequirements(@NotNull List<Component> lore, @NotNull QuestDefinition quest) {
        if (quest.requirements().isEmpty()) {
            return;
        }
        lore.add(Component.empty());
        lore.add(Component.text("受領条件", NamedTextColor.AQUA, TextDecoration.BOLD).decoration(TextDecoration.ITALIC, false));
        for (QuestRequirementDefinition requirement : quest.requirements()) {
            lore.add(Component.text("- " + questService.resolveItemDisplayName(requirement.item()) + " ×" + requirement.item().amount()
                + (requirement.consume() ? "（受領時に消費）" : "（受領時に必要・消費なし）"), NamedTextColor.GRAY).decoration(TextDecoration.ITALIC, false));
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
            lore.add(Component.text("- 経験値 " + rewards.exp(), NamedTextColor.GREEN).decoration(TextDecoration.ITALIC, false));
        }
        if (rewards.gold() > 0) {
            lore.add(Component.text("- ゴールド " + rewards.gold(), NamedTextColor.GOLD).decoration(TextDecoration.ITALIC, false));
        }
        for (var item : rewards.items()) {
            lore.add(Component.text("- " + questService.resolveItemDisplayName(item) + " ×" + item.amount(), NamedTextColor.WHITE).decoration(TextDecoration.ITALIC, false));
        }
    }

    private @NotNull String boardActionLabel(@NotNull QuestDisplayState state, @NotNull QuestDefinition quest) {
        return switch (state) {
            case AVAILABLE -> "クリックで受領します";
            case READY_TO_TURN_IN -> quest.completionMode() == QuestCompletionMode.NPC ? "クリックで報告します" : "報酬を処理中です";
            case IN_PROGRESS -> quest.completionMode() == QuestCompletionMode.NPC
                ? "目標達成後にNPCへ報告します"
                : "目標達成で自動完了します";
            case COMPLETED -> "このクエストは1回のみ受領できます";
            case COOLDOWN -> "再受領まで待機中です";
            case LOCKED -> "受領条件を確認してください";
        };
    }

    private @NotNull String stateLabel(@NotNull QuestDisplayState state, @NotNull QuestDefinition quest) {
        return switch (state) {
            case AVAILABLE -> "受領可能";
            case IN_PROGRESS -> "進行中";
            case READY_TO_TURN_IN -> quest.completionMode() == QuestCompletionMode.NPC ? "報告可能" : "報酬処理中";
            case COMPLETED -> "完了済み";
            case COOLDOWN -> "再受領待ち";
            case LOCKED -> "受領条件不足";
        };
    }

    private @NotNull String completionLabel(@NotNull QuestDefinition quest) {
        return quest.completionMode() == QuestCompletionMode.AUTO
            ? "目標達成で自動完了"
            : quest.turnInNpcId() == null ? "受領したNPCへ報告" : "指定NPCへ報告";
    }

    private @NotNull String repeatLabel(@NotNull QuestDefinition quest) {
        return switch (quest.repeatMode()) {
            case ONCE -> "1回のみ";
            case REPEATABLE -> "条件を満たせば再受領可能";
            case COOLDOWN -> quest.cooldownSeconds() > 0L
                ? "完了後に" + formatDuration(quest.cooldownSeconds()) + "待機"
                : "条件を満たせば再受領可能";
        };
    }

    /**
     * 秒数をプレイヤーが読みやすい日本語の期間へ変換します。
     *
     * @param seconds 変換する秒数。負数は0秒として扱う
     * @return 日・時間・分・秒を組み合わせた期間表示
     */
    static @NotNull String formatDuration(long seconds) {
        long remaining = Math.max(0L, seconds);
        if (remaining < SECONDS_PER_MINUTE) {
            return remaining + "秒";
        }
        if (remaining < SECONDS_PER_HOUR) {
            return remaining / SECONDS_PER_MINUTE + "分" + formatRemainderSeconds(remaining % SECONDS_PER_MINUTE);
        }
        if (remaining < SECONDS_PER_DAY) {
            return remaining / SECONDS_PER_HOUR + "時間"
                + formatRemainderMinutes(remaining % SECONDS_PER_HOUR);
        }
        return remaining / SECONDS_PER_DAY + "日" + formatRemainderHours(remaining % SECONDS_PER_DAY);
    }

    private static @NotNull String formatRemainderSeconds(long seconds) {
        return seconds == 0L ? "" : seconds + "秒";
    }

    private static @NotNull String formatRemainderMinutes(long seconds) {
        long minutes = seconds / SECONDS_PER_MINUTE;
        long remainderSeconds = seconds % SECONDS_PER_MINUTE;
        return (minutes == 0L ? "" : minutes + "分") + formatRemainderSeconds(remainderSeconds);
    }

    private static @NotNull String formatRemainderHours(long seconds) {
        long hours = seconds / SECONDS_PER_HOUR;
        long remainderSeconds = seconds % SECONDS_PER_HOUR;
        return (hours == 0L ? "" : hours + "時間") + formatRemainderMinutes(remainderSeconds);
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

    private int listSlot(int index) {
        return (index / 7 + 1) * 9 + index % 7 + 1;
    }

    public record BoardHolder(@NotNull String boardId, @Nullable String npcId, int pageIndex) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }

    public record ListHolder() implements HotbarShortcutGuiHolder {
        /**
         * クエスト一覧の共通ナビゲーションボタンのスロットを返します。
         *
         * @return 戻るまたは閉じるボタンの raw slot
         */
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
