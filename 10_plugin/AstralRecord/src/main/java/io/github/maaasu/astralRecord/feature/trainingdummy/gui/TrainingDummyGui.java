package io.github.maaasu.astralRecord.feature.trainingdummy.gui;

import io.github.maaasu.astralRecord.feature.trainingdummy.model.TrainingDummyDefinition;
import io.github.maaasu.astralRecord.shared.gui.GuiItems;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** カカシの共有検証ステータスを変更する GUI です。 */
public final class TrainingDummyGui {
    public static final int SIZE = 27;
    public static final int MAX_HEALTH_MINUS = 10;
    public static final int MAX_HEALTH_PLUS = 12;
    public static final int DEFENSE_MINUS = 14;
    public static final int DEFENSE_PLUS = 16;
    public static final int MAGIC_DEFENSE_MINUS = 19;
    public static final int MAGIC_DEFENSE_PLUS = 21;
    public static final int SHIELD_TOGGLE = 23;
    public static final int SHIELD_MAX_PLUS = 24;
    public static final int CLOSE = 26;

    /** GUI を開きます。 */
    public void open(@NotNull Player player, @NotNull TrainingDummyDefinition definition) {
        Inventory inventory = Bukkit.createInventory(new Holder(definition.id()), SIZE, Component.text("カカシ設定: " + definition.id(), NamedTextColor.GOLD));
        render(inventory, definition);
        GuiOpenSupport.open(player, inventory);
    }

    /** GUI を再描画します。 */
    public void render(@NotNull Inventory inventory, @NotNull TrainingDummyDefinition definition) {
        inventory.clear();
        ItemStackFactory.set(inventory, MAX_HEALTH_MINUS, Material.RED_DYE, "最大HP -50", List.of());
        ItemStackFactory.set(inventory, 11, Material.APPLE, "最大HP: " + number(definition.maxHealth()), List.of("クリックで調整"));
        ItemStackFactory.set(inventory, MAX_HEALTH_PLUS, Material.LIME_DYE, "最大HP +50", List.of());
        ItemStackFactory.set(inventory, DEFENSE_MINUS, Material.RED_DYE, "防御力 -1", List.of());
        ItemStackFactory.set(inventory, 15, Material.IRON_CHESTPLATE, "防御力: " + number(definition.defense()), List.of("物理ダメージ用"));
        ItemStackFactory.set(inventory, DEFENSE_PLUS, Material.LIME_DYE, "防御力 +1", List.of());
        ItemStackFactory.set(inventory, MAGIC_DEFENSE_MINUS, Material.RED_DYE, "魔法防御 -1", List.of());
        ItemStackFactory.set(inventory, 20, Material.ENCHANTED_BOOK, "魔法防御: " + number(definition.magicDefense()), List.of("魔法ダメージ用"));
        ItemStackFactory.set(inventory, MAGIC_DEFENSE_PLUS, Material.LIME_DYE, "魔法防御 +1", List.of());
        ItemStackFactory.set(inventory, SHIELD_TOGGLE, definition.shieldEnabled() ? Material.SHIELD : Material.BARRIER,
                "シールド: " + (definition.shieldEnabled() ? "有効" : "無効"), List.of("クリックで切替"));
        ItemStackFactory.set(inventory, SHIELD_MAX_PLUS, Material.PRISMARINE_SHARD, "シールド最大値: " + number(definition.shieldMax()), List.of("クリックで +10", "Shift+クリックで -10"));
        inventory.setItem(CLOSE, GuiItems.closeButton());
    }

    public boolean isInventory(@Nullable Inventory inventory) { return inventory != null && inventory.getHolder() instanceof Holder; }
    public @Nullable String dummyId(@Nullable Inventory inventory) { return inventory != null && inventory.getHolder() instanceof Holder holder ? holder.id() : null; }
    private @NotNull String number(double value) { return String.format(java.util.Locale.ROOT, "%.1f", value); }

    private record Holder(@NotNull String id) implements InventoryHolder {
        @Override public @NotNull Inventory getInventory() { return Bukkit.createInventory(this, SIZE); }
    }

    /** GUI アイテムの生成をこの View 内へ限定する補助です。 */
    private static final class ItemStackFactory {
        private static void set(@NotNull Inventory inventory, int slot, @NotNull Material material, @NotNull String name, @NotNull List<String> lore) {
            List<Component> loreComponents = lore.stream()
                    .<Component>map(line -> Component.text(line, NamedTextColor.GRAY))
                    .toList();
            inventory.setItem(slot, GuiItems.create(material, Component.text(name, NamedTextColor.AQUA), loreComponents));
        }
    }
}
