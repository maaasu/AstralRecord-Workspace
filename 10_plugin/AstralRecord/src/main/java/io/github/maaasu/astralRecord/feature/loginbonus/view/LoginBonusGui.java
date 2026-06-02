package io.github.maaasu.astralRecord.feature.loginbonus.view;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.RewardDisplayFormatter;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * ログイン報酬の月間カレンダー GUI を描画します。
 */
public final class LoginBonusGui {
    public static final int SIZE = 54;
    public static final int PREVIOUS_MONTH_SLOT = 45;
    public static final int NEXT_MONTH_SLOT = 53;

    private static final int INVENTORY_COLUMNS = 9;
    private static final int CALENDAR_FIRST_COLUMN = 1;
    private static final int CALENDAR_COLUMNS = 7;
    private static final int MAX_ITEM_AMOUNT = 64;
    private static final int DAILY_LOGIN_BONUS_GOLD = 1000;
    private static final int HOLIDAY_LOGIN_BONUS_ASTRALD = 10;
    private static final String[] JAPANESE_WEEKDAYS = {"月", "火", "水", "木", "金", "土", "日"};

    /**
     * ログイン報酬画面を開きます。
     *
     * @param player 対象プレイヤー
     * @param displayMonth 表示する年月
     * @param today 今日の日付
     * @param receivedDates 受け取り済み日付
     * @param goldRewardModel 表示する通常報酬アイテム
     * @param astraldRewardModel 表示する休日報酬アイテム
     */
    public void open(
        @NotNull Player player,
        @NotNull YearMonth displayMonth,
        @NotNull LocalDate today,
        @NotNull Set<LocalDate> receivedDates,
        @Nullable ItemModel goldRewardModel,
        @Nullable ItemModel astraldRewardModel
    ) {
        Inventory inventory = Bukkit.createInventory(
            new Holder(displayMonth),
            SIZE,
            Component.text(displayMonth.getYear() + "年" + displayMonth.getMonthValue() + "月 ログイン報酬", NamedTextColor.GOLD)
        );
        fill(inventory);
        renderCalendar(inventory, displayMonth, today, receivedDates, goldRewardModel, astraldRewardModel);
        renderControls(inventory);
        player.openInventory(inventory);
    }

    /**
     * 指定インベントリがログイン報酬 GUI か判定します。
     *
     * @param inventory 判定対象
     * @return ログイン報酬 GUI の場合は true
     */
    public boolean isLoginBonusInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * 表示中の年月を返します。
     *
     * @param inventory 対象インベントリ
     * @return 表示中の年月
     */
    public @Nullable YearMonth getDisplayMonth(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.displayMonth();
        }
        return null;
    }

    /**
     * カレンダー日付スロットから日付を解決します。
     *
     * @param displayMonth 表示中の年月
     * @param rawSlot クリックされた raw slot
     * @return 対応する日付。日付スロットでない場合は null
     */
    public @Nullable LocalDate resolveDate(@NotNull YearMonth displayMonth, int rawSlot) {
        if (rawSlot < 0 || rawSlot >= SIZE) {
            return null;
        }
        int row = rawSlot / INVENTORY_COLUMNS;
        int column = rawSlot % INVENTORY_COLUMNS;
        if (column < CALENDAR_FIRST_COLUMN || column >= CALENDAR_FIRST_COLUMN + CALENDAR_COLUMNS) {
            return null;
        }
        int offset = row * CALENDAR_COLUMNS + (column - CALENDAR_FIRST_COLUMN);
        int firstOffset = sundayFirstColumn(displayMonth.atDay(1).getDayOfWeek());
        int day = offset - firstOffset + 1;
        if (day < 1 || day > displayMonth.lengthOfMonth()) {
            return null;
        }
        return displayMonth.atDay(day);
    }

    private void renderCalendar(
        @NotNull Inventory inventory,
        @NotNull YearMonth displayMonth,
        @NotNull LocalDate today,
        @NotNull Set<LocalDate> receivedDates,
        @Nullable ItemModel goldRewardModel,
        @Nullable ItemModel astraldRewardModel
    ) {
        for (int day = 1; day <= displayMonth.lengthOfMonth(); day++) {
            LocalDate date = displayMonth.atDay(day);
            int slot = dateSlot(displayMonth, date);
            if (slot < 0 || slot >= SIZE || slot == PREVIOUS_MONTH_SLOT || slot == NEXT_MONTH_SLOT) {
                continue;
            }
            boolean received = receivedDates.contains(date);
            inventory.setItem(slot, createDateItem(date, today, received, goldRewardModel, astraldRewardModel));
        }
    }

    private int dateSlot(@NotNull YearMonth displayMonth, @NotNull LocalDate date) {
        int firstOffset = sundayFirstColumn(displayMonth.atDay(1).getDayOfWeek());
        int offset = firstOffset + date.getDayOfMonth() - 1;
        int row = offset / CALENDAR_COLUMNS;
        int column = CALENDAR_FIRST_COLUMN + offset % CALENDAR_COLUMNS;
        return row * INVENTORY_COLUMNS + column;
    }

    private int sundayFirstColumn(@NotNull DayOfWeek dayOfWeek) {
        return dayOfWeek.getValue() % CALENDAR_COLUMNS;
    }

    private void renderControls(@NotNull Inventory inventory) {
        inventory.setItem(PREVIOUS_MONTH_SLOT, createItem(
            Material.ARROW,
            Component.text("前の月", NamedTextColor.YELLOW),
            List.of(Component.text("クリックで前月へ移動", NamedTextColor.GRAY)),
            false
        ));
        inventory.setItem(NEXT_MONTH_SLOT, createItem(
            Material.ARROW,
            Component.text("次の月", NamedTextColor.YELLOW),
            List.of(Component.text("クリックで次月へ移動", NamedTextColor.GRAY)),
            false
        ));
    }

    private @NotNull ItemStack createDateItem(
        @NotNull LocalDate date,
        @NotNull LocalDate today,
        boolean received,
        @Nullable ItemModel goldRewardModel,
        @Nullable ItemModel astraldRewardModel
    ) {
        boolean todayClaimable = date.equals(today) && !received;
        Material material = resolveDateMaterial(date, today, received);
        List<Component> lore = new ArrayList<>();
        lore.add(Component.text(japaneseWeekday(date.getDayOfWeek()), NamedTextColor.DARK_GRAY));
        lore.add(RewardDisplayFormatter.rewardLine(goldRewardModel, DAILY_LOGIN_BONUS_GOLD));
        if (isHoliday(date)) {
            lore.add(RewardDisplayFormatter.rewardLine(astraldRewardModel, HOLIDAY_LOGIN_BONUS_ASTRALD));
        }
        lore.add(Component.empty());
        if (received) {
            lore.add(Component.text("受け取り済み", NamedTextColor.GREEN));
        } else if (todayClaimable) {
            lore.add(Component.text("クリックで受け取り", NamedTextColor.YELLOW));
        } else if (date.isBefore(today)) {
            lore.add(Component.text("受け取り期限切れ", NamedTextColor.GRAY));
        } else {
            lore.add(Component.text("未到達の報酬", NamedTextColor.GRAY));
        }
        return createItem(
            material,
            Component.text(date.getDayOfMonth() + "日", received ? NamedTextColor.GREEN : NamedTextColor.WHITE),
            lore,
            date.getDayOfMonth(),
            todayClaimable
        );
    }

    private @NotNull Material resolveDateMaterial(@NotNull LocalDate date, @NotNull LocalDate today, boolean received) {
        if (received) {
            return Material.LIME_TERRACOTTA;
        }
        if (date.isBefore(today)) {
            return Material.GRAY_TERRACOTTA;
        }
        return Material.PAPER;
    }

    private void fill(@NotNull Inventory inventory) {
        ItemStack filler = createItem(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of(), false);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, filler);
        }
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore,
        boolean enchanted
    ) {
        return createItem(material, name, lore, 1, enchanted);
    }

    private @NotNull ItemStack createItem(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore,
        int amount,
        boolean enchanted
    ) {
        ItemStack itemStack = new ItemStack(material);
        if (material != Material.AIR) {
            itemStack.setAmount(Math.min(MAX_ITEM_AMOUNT, Math.max(1, amount)));
        }
        ItemMeta meta = itemStack.getItemMeta();
        if (meta != null) {
            meta.displayName(noItalic(name));
            meta.lore(lore.stream().map(this::noItalic).toList());
            meta.addItemFlags(ItemFlag.values());
            if (enchanted) {
                meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            }
            itemStack.setItemMeta(meta);
        }
        return itemStack;
    }

    private @NotNull Component noItalic(@NotNull Component component) {
        return component.decoration(TextDecoration.ITALIC, false);
    }

    private boolean isHoliday(@NotNull LocalDate date) {
        return LoginBonusHoliday.isJapaneseHoliday(date);
    }

    private @NotNull String japaneseWeekday(@NotNull DayOfWeek dayOfWeek) {
        return JAPANESE_WEEKDAYS[dayOfWeek.getValue() - 1];
    }

    public record Holder(@NotNull YearMonth displayMonth) implements InventoryHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
