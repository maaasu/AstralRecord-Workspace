package io.github.maaasu.astralRecord.feature.skill.model;

import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/** スキル忘却 GUI の画面状態を保持します。 */
public record SkillForgetInventoryHolder(
    @NotNull SkillForgetScreen screen,
    int pageIndex,
    @NotNull String learnedSkillId,
    @Nullable PaidQuote paidQuote
) implements InventoryHolder {
    public SkillForgetInventoryHolder(@NotNull SkillForgetScreen screen, int pageIndex) {
        this(screen, pageIndex, "", null);
    }

    /**
     * プレイヤーが確認画面で見た有償忘却の条件です。
     *
     * @param itemId 返却対象の定義 ID
     * @param amount 返却数量
     * @param cost 消費するアストラルド
     */
    public record PaidQuote(@NotNull String itemId, int amount, long cost) { }

    @Override
    public @NotNull Inventory getInventory() {
        return Bukkit.createInventory(this, screen == SkillForgetScreen.LIST ? 54 : 27);
    }
}
