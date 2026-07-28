package io.github.maaasu.astralRecord.shared.gui.gold;

import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Gold 金額を増減して確定する共通 GUI を描画します。
 */
public final class GoldAmountSettingGui {
    public static final int SIZE = 27;
    public static final int CLEAR_SLOT = 9;
    public static final int STEP_DOWN_SLOT = 10;
    public static final int MINUS_SLOT = 11;
    public static final int HALF_SLOT = 12;
    public static final int AMOUNT_SLOT = 13;
    public static final int DOUBLE_SLOT = 14;
    public static final int PLUS_SLOT = 15;
    public static final int STEP_UP_SLOT = 16;
    public static final int MAX_SLOT = 17;
    public static final int BACK_SLOT = 21;
    public static final int CONFIRM_SLOT = 23;
    private static final long MAX_STEP = 1_000_000_000_000_000_000L;

    /**
     * 指定した金額設定 GUI を開きます。
     *
     * @param viewer 表示対象プレイヤー
     * @param sourceKey 呼び出し元を識別するキー
     * @param contextId 呼び出し元側で扱うコンテキスト ID
     * @param amount 初期金額
     * @param maxAmount 設定可能な最大金額
     */
    public void open(
        @NotNull Player viewer,
        @NotNull String sourceKey,
        @NotNull UUID contextId,
        long amount,
        long maxAmount
    ) {
        long normalizedMax = Math.max(0L, maxAmount);
        long normalizedAmount = clamp(amount, normalizedMax);
        Inventory inventory = Bukkit.createInventory(
            new GoldAmountHolder(sourceKey, contextId, viewer.getUniqueId(), normalizedAmount, normalizedMax),
            SIZE,
            Component.text("ゴールド金額", NamedTextColor.GOLD)
        );
        render(inventory, normalizedAmount, normalizedMax, 1L);
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(viewer, inventory);
    }

    /**
     * 指定インベントリが Gold 金額設定 GUI か判定します。
     *
     * @param inventory 判定対象インベントリ
     * @return Gold 金額設定 GUI の場合 true
     */
    public boolean isGoldAmountInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof GoldAmountHolder;
    }

    /**
     * 指定インベントリから Gold 金額設定 holder を取得します。
     *
     * @param inventory 取得元インベントリ
     * @return holder。対象外の場合は null
     */
    public @Nullable GoldAmountHolder getHolder(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof GoldAmountHolder holder ? holder : null;
    }

    /**
     * 現在額へ増減値を加え、0 から上限の範囲へ丸めます。
     * 加算が long の範囲を超える場合は符号に応じて飽和させます。
     *
     * @param holder 金額設定 holder
     * @param delta 増減値
     * @return 丸めた変更後金額
     */
    public long applyDelta(@NotNull GoldAmountHolder holder, long delta) {
        return clamp(saturatingAdd(holder.amount(), delta), holder.maxAmount());
    }

    /**
     * 現在の調整単位を使って金額を増減します。
     *
     * @param holder 金額設定 holder
     * @param direction 増加は正数、減少は負数、0は変更なし
     * @param multiplier 調整単位へ掛ける倍率
     * @return 丸めた変更後金額
     */
    public long applyStepDelta(@NotNull GoldAmountHolder holder, int direction, int multiplier) {
        if (direction == 0) {
            return clamp(holder.amount(), holder.maxAmount());
        }
        int normalizedMultiplier = Math.max(1, multiplier);
        long magnitude = saturatingMultiply(holder.step(), normalizedMultiplier);
        return applyDelta(holder, direction < 0 ? -magnitude : magnitude);
    }

    /**
     * 金額調整単位を10進の指定桁数だけ上下させます。
     *
     * @param holder 金額設定 holder
     * @param digitDelta 正数で桁を上げ、負数で桁を下げる
     * @return 変更後の調整単位
     */
    public long shiftStep(@NotNull GoldAmountHolder holder, int digitDelta) {
        long step = holder.step();
        int direction = Integer.compare(digitDelta, 0);
        int iterations = (int) Math.min(18L, Math.abs((long) digitDelta));
        for (int index = 0; index < iterations; index++) {
            if (direction > 0) {
                step = step >= MAX_STEP / 10L ? MAX_STEP : step * 10L;
            } else if (direction < 0) {
                step = Math.max(1L, step / 10L);
            }
        }
        holder.setStep(step);
        return step;
    }

    /**
     * holder の現在値で Gold 金額設定 GUI を再描画します。
     *
     * @param inventory 描画先インベントリ
     * @param holder 現在の金額・上限・調整単位を持つ holder
     */
    public void rerender(@NotNull Inventory inventory, @NotNull GoldAmountHolder holder) {
        render(
            inventory,
            clamp(holder.amount(), holder.maxAmount()),
            Math.max(0L, holder.maxAmount()),
            holder.step()
        );
    }

    /**
     * Gold 金額設定 GUI の全スロットを現在値で描画します。
     *
     * @param inventory 描画先インベントリ
     * @param amount 現在の設定金額
     * @param maxAmount 設定可能な最大金額
     * @param step 現在の10進調整単位
     */
    private void render(@NotNull Inventory inventory, long amount, long maxAmount, long step) {
        ItemStack filler = item(Material.GRAY_STAINED_GLASS_PANE, Component.text(" "), List.of());
        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setItem(slot, filler.clone());
        }
        inventory.setItem(CLEAR_SLOT, item(
            Material.BARRIER,
            Component.text("0", NamedTextColor.RED, TextDecoration.BOLD),
            List.of(Component.text("0 ゴールドに設定します", NamedTextColor.GRAY))
        ));
        inventory.setItem(STEP_DOWN_SLOT, item(
            Material.REDSTONE,
            Component.text("調整桁を下げる", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(
                Component.text("現在: " + formatAmount(step), NamedTextColor.WHITE),
                Component.text("左: 1桁 / Shift: 3桁", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(MINUS_SLOT, item(
            Material.RED_CONCRETE,
            Component.text("-" + formatAmount(step), NamedTextColor.RED, TextDecoration.BOLD),
            List.of(
                Component.text("右クリック: 5倍", NamedTextColor.GRAY),
                Component.text("Shiftクリック: 10倍", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(HALF_SLOT, item(
            Material.ORANGE_STAINED_GLASS_PANE,
            Component.text("半分", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(Component.text(formatAmount(amount / 2L) + " ゴールド", NamedTextColor.GRAY))
        ));
        inventory.setItem(AMOUNT_SLOT, item(
            Material.GOLD_INGOT,
            Component.text(formatAmount(amount) + " ゴールド", NamedTextColor.GOLD, TextDecoration.BOLD),
            List.of(
                Component.text("上限: " + formatAmount(maxAmount) + " ゴールド", NamedTextColor.YELLOW),
                Component.text("調整単位: " + formatAmount(step), NamedTextColor.WHITE),
                Component.text("桁切替と増減ボタンで金額を変更します", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(DOUBLE_SLOT, item(
            Material.LIME_STAINED_GLASS_PANE,
            Component.text("2倍", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text(formatAmount(clamp(saturatingMultiply(amount, 2L), maxAmount))
                + " ゴールド", NamedTextColor.GRAY))
        ));
        inventory.setItem(PLUS_SLOT, item(
            Material.LIME_CONCRETE,
            Component.text("+" + formatAmount(step), NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(
                Component.text("右クリック: 5倍", NamedTextColor.GRAY),
                Component.text("Shiftクリック: 10倍", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(STEP_UP_SLOT, item(
            Material.GLOWSTONE_DUST,
            Component.text("調整桁を上げる", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of(
                Component.text("現在: " + formatAmount(step), NamedTextColor.WHITE),
                Component.text("左: 1桁 / Shift: 3桁", NamedTextColor.GRAY)
            )
        ));
        inventory.setItem(MAX_SLOT, item(
            Material.EMERALD_BLOCK,
            Component.text("最大", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text(formatAmount(maxAmount) + " ゴールド", NamedTextColor.YELLOW))
        ));
        inventory.setItem(BACK_SLOT, item(
            Material.ARROW,
            Component.text("戻る", NamedTextColor.YELLOW, TextDecoration.BOLD),
            List.of()
        ));
        inventory.setItem(CONFIRM_SLOT, item(
            Material.LIME_CONCRETE,
            Component.text("確定", NamedTextColor.GREEN, TextDecoration.BOLD),
            List.of(Component.text(formatAmount(amount) + " ゴールド", NamedTextColor.YELLOW))
        ));
    }

    private static long clamp(long amount, long maxAmount) {
        return Math.max(0L, Math.min(amount, Math.max(0L, maxAmount)));
    }

    /**
     * long の範囲を超える加算を上限または下限へ飽和させます。
     *
     * @param left 左辺
     * @param right 右辺
     * @return 飽和加算結果
     */
    private static long saturatingAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        if (right < 0L && left < Long.MIN_VALUE - right) {
            return Long.MIN_VALUE;
        }
        return left + right;
    }

    /**
     * 非負数同士の乗算を long 上限へ飽和させます。
     *
     * @param value 被乗数
     * @param multiplier 乗数
     * @return 飽和乗算結果。いずれかが0以下の場合は0
     */
    private static long saturatingMultiply(long value, long multiplier) {
        if (value <= 0L || multiplier <= 0L) {
            return 0L;
        }
        return value > Long.MAX_VALUE / multiplier ? Long.MAX_VALUE : value * multiplier;
    }

    /**
     * 金額を3桁区切りの表示文字列へ変換します。
     *
     * @param amount 表示対象金額
     * @return 3桁区切り文字列
     */
    private static @NotNull String formatAmount(long amount) {
        return String.format(Locale.ROOT, "%,d", amount);
    }

    private @NotNull ItemStack item(
        @NotNull Material material,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiItems.create(material, name, lore);
    }

    public static final class GoldAmountHolder implements InventoryHolder {
        private final String sourceKey;
        private final UUID contextId;
        private final UUID viewerUuid;
        private long amount;
        private final long maxAmount;
        private long step = 1L;

        /**
         * Gold 金額設定 GUI の状態を作成します。
         *
         * @param sourceKey 呼び出し元識別キー
         * @param contextId 呼び出し元コンテキスト ID
         * @param viewerUuid 表示プレイヤー UUID
         * @param amount 初期金額
         * @param maxAmount 設定可能な最大金額
         */
        public GoldAmountHolder(
            @NotNull String sourceKey,
            @NotNull UUID contextId,
            @NotNull UUID viewerUuid,
            long amount,
            long maxAmount
        ) {
            this.sourceKey = sourceKey;
            this.contextId = contextId;
            this.viewerUuid = viewerUuid;
            this.amount = amount;
            this.maxAmount = maxAmount;
        }

        public @NotNull String sourceKey() {
            return sourceKey;
        }

        public @NotNull UUID contextId() {
            return contextId;
        }

        public @NotNull UUID viewerUuid() {
            return viewerUuid;
        }

        public long amount() {
            return amount;
        }

        public long maxAmount() {
            return maxAmount;
        }

        public void setAmount(long amount) {
            this.amount = amount;
        }

        /**
         * 現在の調整単位を返します。
         *
         * @return 1 から 10^18 の10進調整単位
         */
        public long step() {
            return step;
        }

        /**
         * 金額増減に使う10進調整単位を更新します。
         *
         * @param step 新しい調整単位
         */
        public void setStep(long step) {
            this.step = Math.max(1L, Math.min(step, MAX_STEP));
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
