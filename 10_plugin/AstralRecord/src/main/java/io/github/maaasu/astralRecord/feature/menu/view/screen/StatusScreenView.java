package io.github.maaasu.astralRecord.feature.menu.view.screen;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

public final class StatusScreenView extends BaseMenuScreenView {
    private static final int SUMMARY_SLOT = 13;
    private static final int RESOURCE_SLOT = 20;
    private static final int PRIMARY_SLOT = 21;
    private static final int OFFENSE_SLOT = 22;
    private static final int DEFENSE_SLOT = 23;
    private static final int UTILITY_SLOT = 24;

    public void render(
        @NotNull Inventory inventory,
        @NotNull AstPlayer player,
        @NotNull StatusSnapshot snapshot
    ) {
        fill(inventory);
        inventory.setItem(BACK_SLOT, backItem());

        inventory.setItem(SUMMARY_SLOT, createItem(
            Material.PLAYER_HEAD,
            Component.text(player.getAccount().getAccountName(), NamedTextColor.GREEN),
            List.of(
                Component.text("HP: " + StatusType.MAX_HEALTH.formatValue(snapshot.getCurrentHp())
                    + " / " + StatusType.MAX_HEALTH.formatValue(snapshot.getMaxValue(StatusType.MAX_HEALTH)), NamedTextColor.GRAY),
                Component.text("MP: " + StatusType.MAX_MANA.formatValue(snapshot.getCurrentMp())
                    + " / " + StatusType.MAX_MANA.formatValue(snapshot.getMaxValue(StatusType.MAX_MANA)), NamedTextColor.GRAY),
                Component.text("EN: " + StatusType.MAX_ENERGY.formatValue(snapshot.getCurrentEnergy())
                    + " / " + StatusType.MAX_ENERGY.formatValue(snapshot.getMaxValue(StatusType.MAX_ENERGY)), NamedTextColor.GRAY)
            )
        ));

        inventory.setItem(RESOURCE_SLOT, categoryItem(Material.REDSTONE, StatusType.Category.RESOURCE, snapshot));
        inventory.setItem(PRIMARY_SLOT, categoryItem(Material.DIAMOND, StatusType.Category.PRIMARY, snapshot));
        inventory.setItem(OFFENSE_SLOT, categoryItem(Material.NETHERITE_SWORD, StatusType.Category.OFFENSE, snapshot));
        inventory.setItem(DEFENSE_SLOT, categoryItem(Material.SHIELD, StatusType.Category.DEFENSE, snapshot));
        inventory.setItem(UTILITY_SLOT, categoryItem(Material.FEATHER, StatusType.Category.UTILITY, snapshot));
    }

    private @NotNull org.bukkit.inventory.ItemStack categoryItem(
        @NotNull Material material,
        @NotNull StatusType.Category category,
        @NotNull StatusSnapshot snapshot
    ) {
        List<Component> lore = new ArrayList<>();
        for (StatusType type : StatusType.byCategory(category)) {
            StatusValue value = snapshot.getValue(type);
            if (value == null) {
                continue;
            }
            lore.add(Component.text(
                type.getDisplayName() + ": " + type.formatValue(value.getTotalValue())
                    + " (" + type.formatSignedValue(value.getBonusValue()) + ")",
                NamedTextColor.GRAY
            ));
        }
        return createItem(
            material,
            Component.text(category.getDisplayName(), NamedTextColor.YELLOW),
            lore
        );
    }
}
