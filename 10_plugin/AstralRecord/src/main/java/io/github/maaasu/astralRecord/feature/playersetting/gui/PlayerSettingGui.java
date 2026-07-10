package io.github.maaasu.astralRecord.feature.playersetting.gui;

import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * プレイヤー設定 GUI です。
 */
public final class PlayerSettingGui extends BaseMenuScreenView {
    public static final int SIZE = BaseMenuScreenView.SIZE;
    public static final int DAMAGE_LOG_SLOT = 20;
    public static final int DAMAGE_LOG_MESSAGE_SLOT = 21;
    public static final int PARTICLE_DENSITY_SLOT = 22;
    public static final int DROP_LOG_SLOT = 24;
    public static final int SUPER_MODE_SECRET_SLOT = 53;
    public static final int BACK_TO_MENU_SLOT = BaseMenuScreenView.BACK_SLOT;
    public static final int CLOSE_SLOT = BaseMenuScreenView.CLOSE_SLOT;

    private final PlayerSettingService playerSettingService;

    public PlayerSettingGui(@NotNull PlayerSettingService playerSettingService) {
        this.playerSettingService = playerSettingService;
    }

    public void open(@NotNull Player player) {
        UUID userId = player.getUniqueId();
        PlayerSettingSnapshot snapshot = playerSettingService.getSnapshot(userId);
        Inventory inventory = Bukkit.createInventory(
            new Holder(userId),
            SIZE,
            Component.text("プレイヤー設定", NamedTextColor.AQUA)
        );
        render(inventory, snapshot.getUserId(), null);
        player.openInventory(inventory);
    }

    public void refresh(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        render(inventory, userId, draftValues);
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    public @Nullable PlayerSettingKey getKeyAtSlot(int rawSlot) {
        return switch (rawSlot) {
            case DAMAGE_LOG_SLOT -> PlayerSettingKey.DAMAGE_LOG_DISPLAY;
            case DAMAGE_LOG_MESSAGE_SLOT -> PlayerSettingKey.DAMAGE_LOG_MESSAGE;
            case PARTICLE_DENSITY_SLOT -> PlayerSettingKey.PARTICLE_DENSITY;
            case DROP_LOG_SLOT -> PlayerSettingKey.DROP_LOG_DISPLAY;
            default -> null;
        };
    }

    public @Nullable UUID getUserId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.userId();
        }
        return null;
    }

    private void render(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        inventory.clear();
        fill(inventory);
        inventory.setItem(DAMAGE_LOG_SLOT, createBooleanItem(
            Material.REDSTONE,
            PlayerSettingKey.DAMAGE_LOG_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, draftValues)
        ));
        inventory.setItem(DAMAGE_LOG_MESSAGE_SLOT, createBooleanItem(
            Material.PAPER,
            PlayerSettingKey.DAMAGE_LOG_MESSAGE,
            (Boolean) resolveValue(userId, PlayerSettingKey.DAMAGE_LOG_MESSAGE, draftValues)
        ));
        inventory.setItem(PARTICLE_DENSITY_SLOT, createParticleItem(
            (ParticleDensity) resolveValue(userId, PlayerSettingKey.PARTICLE_DENSITY, draftValues)
        ));
        inventory.setItem(DROP_LOG_SLOT, createBooleanItem(
            Material.CHEST,
            PlayerSettingKey.DROP_LOG_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.DROP_LOG_DISPLAY, draftValues)
        ));
        inventory.setItem(BACK_TO_MENU_SLOT, backItem());
    }

    private @NotNull Object resolveValue(
        @NotNull UUID userId,
        @NotNull PlayerSettingKey key,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        if (draftValues != null && draftValues.containsKey(key)) {
            return draftValues.get(key);
        }
        return playerSettingService.getPlayerSetting(userId, key);
    }

    private @NotNull ItemStack createBooleanItem(
        @NotNull Material material,
        @NotNull PlayerSettingKey key,
        boolean enabled
    ) {
        return createItem(
            material,
            Component.text(key.getDisplayNameJa(), NamedTextColor.GOLD),
            List.of(
                Component.text("現在: " + key.formatValue(enabled), enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
                Component.text("クリックで切り替え", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack createParticleItem(@NotNull ParticleDensity density) {
        return createItem(
            Material.BLAZE_POWDER,
            Component.text(PlayerSettingKey.PARTICLE_DENSITY.getDisplayNameJa(), NamedTextColor.GOLD),
            List.of(
                Component.text("現在: " + density.getDisplayNameJa(), NamedTextColor.AQUA),
                Component.text("クリックで次の密度へ", NamedTextColor.GRAY)
            )
        );
    }

    private record Holder(@NotNull UUID userId) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
