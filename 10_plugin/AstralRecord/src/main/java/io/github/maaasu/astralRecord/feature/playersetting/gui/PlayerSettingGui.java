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

import java.util.ArrayList;
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
    public static final int PERFORMANCE_INFO_SLOT = 23;
    public static final int DROP_LOG_SLOT = 24;
    public static final int AUTO_SAVE_MESSAGE_SLOT = 29;
    public static final int BUFF_SIDEBAR_DISPLAY_SLOT = 30;
    public static final int ARMOR_DISPLAY_SLOT = 31;
    public static final int ACTION_RING_HOLD_SELECT_SLOT = 32;
    public static final int OFF_HAND_DISPLAY_SLOT = 33;
    public static final int SUPER_MODE_SECRET_SLOT = 53;
    public static final int BACK_TO_MENU_SLOT = BaseMenuScreenView.BACK_SLOT;

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
        io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport.open(player, inventory);
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
            case PERFORMANCE_INFO_SLOT -> PlayerSettingKey.PERFORMANCE_INFO_DISPLAY;
            case DROP_LOG_SLOT -> PlayerSettingKey.DROP_LOG_DISPLAY;
            case AUTO_SAVE_MESSAGE_SLOT -> PlayerSettingKey.AUTO_SAVE_MESSAGE;
            case BUFF_SIDEBAR_DISPLAY_SLOT -> PlayerSettingKey.BUFF_SIDEBAR_DISPLAY;
            case ARMOR_DISPLAY_SLOT -> PlayerSettingKey.ARMOR_DISPLAY;
            case ACTION_RING_HOLD_SELECT_SLOT -> PlayerSettingKey.ACTION_RING_HOLD_SELECT;
            case OFF_HAND_DISPLAY_SLOT -> PlayerSettingKey.OFF_HAND_DISPLAY;
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
        inventory.setItem(PERFORMANCE_INFO_SLOT, createBooleanItem(
            Material.CLOCK,
            PlayerSettingKey.PERFORMANCE_INFO_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY, draftValues)
        ));
        inventory.setItem(AUTO_SAVE_MESSAGE_SLOT, createBooleanItem(
            Material.WRITABLE_BOOK,
            PlayerSettingKey.AUTO_SAVE_MESSAGE,
            (Boolean) resolveValue(userId, PlayerSettingKey.AUTO_SAVE_MESSAGE, draftValues)
        ));
        inventory.setItem(BUFF_SIDEBAR_DISPLAY_SLOT, createBooleanItem(
            Material.POTION,
            PlayerSettingKey.BUFF_SIDEBAR_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.BUFF_SIDEBAR_DISPLAY, draftValues)
        ));
        inventory.setItem(ARMOR_DISPLAY_SLOT, createBooleanItem(
            Material.IRON_CHESTPLATE,
            PlayerSettingKey.ARMOR_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.ARMOR_DISPLAY, draftValues)
        ));
        inventory.setItem(ACTION_RING_HOLD_SELECT_SLOT, createBooleanItem(
            Material.TRIDENT,
            PlayerSettingKey.ACTION_RING_HOLD_SELECT,
            (Boolean) resolveValue(userId, PlayerSettingKey.ACTION_RING_HOLD_SELECT, draftValues),
            List.of(
                Component.text("※有効にすると、構えている間は武器の見た目がトライデントに変化します。", NamedTextColor.RED),
                Component.text("※動作が不安定になる可能性があります。", NamedTextColor.RED)
            )
        ));
        inventory.setItem(OFF_HAND_DISPLAY_SLOT, createBooleanItem(
            Material.STONE_BUTTON,
            PlayerSettingKey.OFF_HAND_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.OFF_HAND_DISPLAY, draftValues),
            List.of(
                Component.text("※無効にすると、自分の三人称視点で小さく表示します。", NamedTextColor.RED),
                Component.text("※他プレイヤーから見た表示には影響しません。", NamedTextColor.RED)
            )
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
        boolean enabled,
        @NotNull List<Component> additionalLore
    ) {
        List<Component> lore = new ArrayList<>(List.of(
            Component.text("現在: " + key.formatValue(enabled), enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
            Component.text("クリックで切り替え", NamedTextColor.GRAY)
        ));
        lore.addAll(additionalLore);
        return createItem(
            material,
            Component.text(key.getDisplayNameJa(), NamedTextColor.GOLD),
            lore
        );
    }

    private @NotNull ItemStack createBooleanItem(
        @NotNull Material material,
        @NotNull PlayerSettingKey key,
        boolean enabled
    ) {
        return createBooleanItem(material, key, enabled, List.of());
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
        public int getBackSlot() {
            return BACK_TO_MENU_SLOT;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Bukkit.createInventory(this, SIZE);
        }
    }
}
