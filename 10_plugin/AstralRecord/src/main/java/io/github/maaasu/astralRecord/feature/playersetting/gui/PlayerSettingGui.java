package io.github.maaasu.astralRecord.feature.playersetting.gui;

import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.model.DamageNumberSize;
import io.github.maaasu.astralRecord.feature.playersetting.model.ParticleDensity;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingGuiSaveService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.SingleOptionDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * プレイヤー設定 GUI です。
 */
public final class PlayerSettingGui extends BaseMenuScreenView {
    private static final Component TITLE = Component.text("プレイヤー設定", NamedTextColor.AQUA);
    public static final int SIZE = BaseMenuScreenView.SIZE;
    public static final int DAMAGE_LOG_SLOT = 20;
    public static final int DAMAGE_NUMBER_SIZE_SLOT = 21;
    public static final int DAMAGE_LOG_MESSAGE_SLOT = 22;
    public static final int PARTICLE_DENSITY_SLOT = 29;
    public static final int PERFORMANCE_INFO_SLOT = 41;
    public static final int DROP_LOG_SLOT = 38;
    public static final int AUTO_SAVE_MESSAGE_SLOT = 39;
    public static final int BUFF_SIDEBAR_DISPLAY_SLOT = 24;
    public static final int ARMOR_DISPLAY_SLOT = 30;
    public static final int ACTION_RING_HOLD_SELECT_SLOT = 42;
    public static final int SKILL_TREE_COMPACT_DISPLAY_SLOT = 32;
    public static final int NIGHT_VISION_SLOT = 31;
    public static final int RANDOM_STATUS_RANGE_DISPLAY_SLOT = 33;
    public static final int COMBAT_DPS_DISPLAY_SLOT = 23;
    public static final int GUIDE_REMINDER_SLOT = 40;
    public static final int SAVE_GUIDE_SLOT = 13;
    public static final int SUPER_MODE_SECRET_SLOT = 53;
    public static final int BACK_TO_MENU_SLOT = BaseMenuScreenView.BACK_SLOT;

    private final PlayerSettingService playerSettingService;
    private final PlayerSettingGuiSaveService guiSaveService;

    /**
     * @param playerSettingService 表示するプレイヤー設定の参照元
     * @param guiSaveService 統合版フォームの保存処理
     */
    public PlayerSettingGui(
        @NotNull PlayerSettingService playerSettingService,
        @NotNull PlayerSettingGuiSaveService guiSaveService
    ) {
        this.playerSettingService = playerSettingService;
        this.guiSaveService = guiSaveService;
    }

    /** @param player 設定画面を開くプレイヤー */
    public void open(@NotNull Player player) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null && astPlayer.isBedrock()) {
            GuiOpenSupport.showDialog(player, () -> AstPlayerCache.get(player) == astPlayer
                ? createBedrockForm(player, astPlayer) : null);
            return;
        }
        UUID userId = player.getUniqueId();
        PlayerSettingSnapshot snapshot = playerSettingService.getSnapshot(userId);
        Inventory inventory = Bukkit.createInventory(
            new Holder(userId),
            SIZE,
            TITLE
        );
        render(inventory, snapshot.getUserId(), null);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 現在の設定と接続に対応するフォームを作成します。
     * @param player フォームを開くプレイヤー
     * @param astPlayer フォーム送信時に照合するプレイヤー状態
     * @return 現在値を選択済みにした統合版フォーム
     */
    private @NotNull Dialog createBedrockForm(@NotNull Player player, @NotNull AstPlayer astPlayer) {
        UUID userId = astPlayer.getUser().getUuid();
        long sessionToken = playerSettingService.captureSessionToken(userId);
        PlayerSettingSnapshot snapshot = playerSettingService.getSnapshot(userId);
        Map<PlayerSettingKey, Object> initial = new EnumMap<>(PlayerSettingKey.class);
        List<DialogInput> inputs = new ArrayList<>();
        for (int slot = 0; slot < SIZE; slot++) {
            PlayerSettingKey key = getKeyAtSlot(slot);
            if (key == null) {
                continue;
            }
            var entry = snapshot.getEntry(key);
            Object value = entry == null ? key.getDefaultValue() : entry.getValue();
            initial.put(key, value);
            inputs.add(formInput(key, value));
        }

        DialogAction save = DialogAction.customClick((response, audience) -> {
            if (audience == player && player.isOnline() && AstPlayerCache.get(player) == astPlayer) {
                saveBedrockForm(player, userId, sessionToken, initial, response);
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
        ActionButton saveButton = ActionButton.builder(Component.text(
            PlayerMsgResource.getMessage(PlayerSettingMsgId.P_5398.getId())
        )).action(save).build();
        ActionButton cancelButton = ActionButton.builder(Component.translatable("gui.cancel")).build();
        return Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(TITLE).inputs(inputs).pause(false).build())
            .type(DialogType.confirmation(saveButton, cancelButton)));
    }

    /**
     * @param key フォームに表示する設定キー
     * @param value 現在値
     * @return 真偽値の切替欄、または列挙値の選択欄
     */
    private static @NotNull DialogInput formInput(@NotNull PlayerSettingKey key, @NotNull Object value) {
        Component label = Component.text(key.getDisplayNameJa());
        if (value instanceof Boolean enabled) {
            return DialogInput.bool(key.getCode(), label).initial(enabled).build();
        }
        List<SingleOptionDialogInput.OptionEntry> options = key.completionValues().stream().map(option -> {
            Object parsed = key.parseInputValue(option);
            return SingleOptionDialogInput.OptionEntry.create(
                option, Component.text(key.formatValue(parsed)), value.equals(parsed)
            );
        }).toList();
        return DialogInput.singleOption(key.getCode(), label, options).build();
    }

    /**
     * 全項目を検証し、フォームを開いた時点から変わった項目だけを反映します。
     * @param player フォームを送信したプレイヤー
     * @param userId 設定の所有ユーザー
     * @param sessionToken フォームを開いた時点の設定セッション
     * @param initial フォームを開いた時点の設定値
     * @param response フォームの送信値
     */
    private void saveBedrockForm(
        @NotNull Player player,
        @NotNull UUID userId,
        long sessionToken,
        @NotNull Map<PlayerSettingKey, Object> initial,
        @NotNull DialogResponseView response
    ) {
        Map<PlayerSettingKey, Object> changes = new EnumMap<>(PlayerSettingKey.class);
        for (var entry : initial.entrySet()) {
            PlayerSettingKey key = entry.getKey();
            Object submitted = key.isBooleanValue() ? response.getBoolean(key.getCode())
                : key.parseInputValue(response.getText(key.getCode()));
            if (submitted == null) {
                return;
            }
            if (!submitted.equals(entry.getValue())) {
                changes.put(key, submitted);
            }
        }
        guiSaveService.save(player, userId, sessionToken, changes);
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

    /**
     * GUIスロットから設定項目を解決します。
     *
     * @param rawSlot 設定GUI内のスロット番号
     * @return 対応する設定キー。設定項目以外はnull
     */
    public @Nullable PlayerSettingKey getKeyAtSlot(int rawSlot) {
        return switch (rawSlot) {
            case DAMAGE_LOG_SLOT -> PlayerSettingKey.DAMAGE_LOG_DISPLAY;
            case DAMAGE_NUMBER_SIZE_SLOT -> PlayerSettingKey.DAMAGE_NUMBER_SIZE;
            case DAMAGE_LOG_MESSAGE_SLOT -> PlayerSettingKey.DAMAGE_LOG_MESSAGE;
            case PARTICLE_DENSITY_SLOT -> PlayerSettingKey.PARTICLE_DENSITY;
            case PERFORMANCE_INFO_SLOT -> PlayerSettingKey.PERFORMANCE_INFO_DISPLAY;
            case DROP_LOG_SLOT -> PlayerSettingKey.DROP_LOG_DISPLAY;
            case AUTO_SAVE_MESSAGE_SLOT -> PlayerSettingKey.AUTO_SAVE_MESSAGE;
            case BUFF_SIDEBAR_DISPLAY_SLOT -> PlayerSettingKey.BUFF_SIDEBAR_DISPLAY;
            case ARMOR_DISPLAY_SLOT -> PlayerSettingKey.ARMOR_DISPLAY;
            case ACTION_RING_HOLD_SELECT_SLOT -> PlayerSettingKey.ACTION_RING_HOLD_SELECT;
            case SKILL_TREE_COMPACT_DISPLAY_SLOT -> PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY;
            case NIGHT_VISION_SLOT -> PlayerSettingKey.NIGHT_VISION;
            case RANDOM_STATUS_RANGE_DISPLAY_SLOT -> PlayerSettingKey.RANDOM_STATUS_RANGE_DISPLAY;
            case COMBAT_DPS_DISPLAY_SLOT -> PlayerSettingKey.COMBAT_DPS_DISPLAY;
            case GUIDE_REMINDER_SLOT -> PlayerSettingKey.GUIDE_REMINDER;
            default -> null;
        };
    }

    public @Nullable UUID getUserId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.userId();
        }
        return null;
    }

    /**
     * 設定項目を、保存済み値または下書きから主スレッドで描画します。
     *
     * @param inventory 描画先の設定GUI
     * @param userId 設定を所有するユーザーUUID
     * @param draftValues 未保存の変更予定値。nullなら保存済み値を使用
     */
    private void render(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        inventory.clear();
        fill(inventory);
        inventory.setItem(SAVE_GUIDE_SLOT, createSettingItem(
            Material.WRITABLE_BOOK,
            "setting_save_guide",
            PlayerMsgResource.getComponent(PlayerSettingMsgId.P_5328.getId()),
            List.of()
        ));
        inventory.setItem(DAMAGE_LOG_SLOT, createBooleanItem(
            Material.REDSTONE,
            "setting_damage_log",
            PlayerSettingKey.DAMAGE_LOG_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.DAMAGE_LOG_DISPLAY, draftValues)
        ));
        DamageNumberSize damageNumberSize = (DamageNumberSize) resolveValue(
            userId, PlayerSettingKey.DAMAGE_NUMBER_SIZE, draftValues
        );
        inventory.setItem(DAMAGE_NUMBER_SIZE_SLOT, createSettingItem(
            Material.NAME_TAG,
            "setting_damage_size",
            Component.text(PlayerSettingKey.DAMAGE_NUMBER_SIZE.getDisplayNameJa(), NamedTextColor.GOLD),
            List.of(
                ColorCodeUtil.toComponent(PlayerMsgResource.format(
                    PlayerSettingMsgId.P_5396.getId(), damageNumberSize.getDisplayNameJa()
                ), ""),
                PlayerMsgResource.getComponent(PlayerSettingMsgId.P_5397.getId())
            )
        ));
        inventory.setItem(DAMAGE_LOG_MESSAGE_SLOT, createBooleanItem(
            Material.PAPER,
            "setting_damage_message",
            PlayerSettingKey.DAMAGE_LOG_MESSAGE,
            (Boolean) resolveValue(userId, PlayerSettingKey.DAMAGE_LOG_MESSAGE, draftValues)
        ));
        inventory.setItem(PARTICLE_DENSITY_SLOT, createParticleItem(
            (ParticleDensity) resolveValue(userId, PlayerSettingKey.PARTICLE_DENSITY, draftValues)
        ));
        inventory.setItem(DROP_LOG_SLOT, createBooleanItem(
            Material.CHEST,
            "setting_drop_log",
            PlayerSettingKey.DROP_LOG_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.DROP_LOG_DISPLAY, draftValues)
        ));
        inventory.setItem(PERFORMANCE_INFO_SLOT, createBooleanItem(
            Material.CLOCK,
            "setting_performance",
            PlayerSettingKey.PERFORMANCE_INFO_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.PERFORMANCE_INFO_DISPLAY, draftValues)
        ));
        inventory.setItem(AUTO_SAVE_MESSAGE_SLOT, createBooleanItem(
            Material.WRITABLE_BOOK,
            "setting_auto_save",
            PlayerSettingKey.AUTO_SAVE_MESSAGE,
            (Boolean) resolveValue(userId, PlayerSettingKey.AUTO_SAVE_MESSAGE, draftValues)
        ));
        inventory.setItem(BUFF_SIDEBAR_DISPLAY_SLOT, createBooleanItem(
            Material.POTION,
            "setting_buff_sidebar",
            PlayerSettingKey.BUFF_SIDEBAR_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.BUFF_SIDEBAR_DISPLAY, draftValues)
        ));
        inventory.setItem(ARMOR_DISPLAY_SLOT, createBooleanItem(
            Material.IRON_CHESTPLATE,
            "equipment",
            PlayerSettingKey.ARMOR_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.ARMOR_DISPLAY, draftValues)
        ));
        inventory.setItem(ACTION_RING_HOLD_SELECT_SLOT, createBooleanItem(
            Material.TRIDENT,
            "setting_action_ring",
            PlayerSettingKey.ACTION_RING_HOLD_SELECT,
            (Boolean) resolveValue(userId, PlayerSettingKey.ACTION_RING_HOLD_SELECT, draftValues),
            List.of(
                PlayerMsgResource.getComponent(PlayerSettingMsgId.P_5395.getId()),
                Component.text("※有効にすると、構えている間は武器の見た目がトライデントに変化します。", NamedTextColor.RED),
                Component.text("※動作が不安定になる可能性があります。", NamedTextColor.RED)
            )
        ));
        inventory.setItem(SKILL_TREE_COMPACT_DISPLAY_SLOT, createBooleanItem(
            Material.BOOK,
            "setting_skill_tree",
            PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY, draftValues),
            List.of(PlayerMsgResource.getComponent(PlayerSettingMsgId.P_5327.getId()))
        ));
        inventory.setItem(NIGHT_VISION_SLOT, createBooleanItem(
            Material.SPYGLASS,
            "setting_night_vision",
            PlayerSettingKey.NIGHT_VISION,
            (Boolean) resolveValue(userId, PlayerSettingKey.NIGHT_VISION, draftValues)
        ));
        inventory.setItem(BACK_TO_MENU_SLOT, backItem());
        inventory.setItem(RANDOM_STATUS_RANGE_DISPLAY_SLOT, createBooleanItem(
            Material.COMPARATOR,
            "setting_status_range",
            PlayerSettingKey.RANDOM_STATUS_RANGE_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.RANDOM_STATUS_RANGE_DISPLAY, draftValues)
        ));
        inventory.setItem(COMBAT_DPS_DISPLAY_SLOT, createBooleanItem(
            Material.CLOCK,
            "setting_dps",
            PlayerSettingKey.COMBAT_DPS_DISPLAY,
            (Boolean) resolveValue(userId, PlayerSettingKey.COMBAT_DPS_DISPLAY, draftValues)
        ));
        inventory.setItem(GUIDE_REMINDER_SLOT, createBooleanItem(
            Material.KNOWLEDGE_BOOK,
            "guide",
            PlayerSettingKey.GUIDE_REMINDER,
            (Boolean) resolveValue(userId, PlayerSettingKey.GUIDE_REMINDER, draftValues)
        ));
        markDraftValues(inventory, userId, draftValues);
    }

    /**
     * 保存済み値と異なる下書きだけを明示します。保存・設定反映は行いません。
     *
     * @param inventory 項目を描画済みの設定inventory。主スレッドから呼び出すこと
     * @param userId 読込済み設定を所有するユーザーUUID
     * @param draftValues 変更予定値。nullまたは空なら変更予定表示を追加しない
     */
    private void markDraftValues(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        if (draftValues == null || draftValues.isEmpty()) {
            return;
        }
        for (int slot = 0; slot < SIZE; slot++) {
            PlayerSettingKey key = getKeyAtSlot(slot);
            if (key == null || !draftValues.containsKey(key)) {
                continue;
            }
            Object savedValue = playerSettingService.getPlayerSetting(userId, key);
            Object draftValue = draftValues.get(key);
            if (Objects.equals(savedValue, draftValue)) {
                continue;
            }
            ItemStack item = inventory.getItem(slot);
            if (item == null || !item.hasItemMeta()) {
                continue;
            }
            var meta = item.getItemMeta();
            List<Component> lore = new ArrayList<>(meta.lore() == null ? List.of() : meta.lore());
            Component status = ColorCodeUtil.toComponent(PlayerMsgResource.format(
                PlayerSettingMsgId.P_5329.getId(), key.formatValue(savedValue), key.formatValue(draftValue)
            ), "");
            if (lore.isEmpty()) {
                lore.add(status);
            } else {
                lore.set(0, status);
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
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
        @NotNull String role,
        @NotNull PlayerSettingKey key,
        boolean enabled,
        @NotNull List<Component> additionalLore
    ) {
        List<Component> lore = new ArrayList<>(List.of(
            Component.text("現在: " + key.formatValue(enabled), enabled ? NamedTextColor.GREEN : NamedTextColor.RED),
            Component.text("クリックで切り替え", NamedTextColor.GRAY)
        ));
        lore.addAll(additionalLore);
        return createSettingItem(
            material,
            role,
            Component.text(key.getDisplayNameJa(), NamedTextColor.GOLD),
            lore
        );
    }

    private @NotNull ItemStack createBooleanItem(
        @NotNull Material material,
        @NotNull String role,
        @NotNull PlayerSettingKey key,
        boolean enabled
    ) {
        return createBooleanItem(material, role, key, enabled, List.of());
    }

    private @NotNull ItemStack createParticleItem(@NotNull ParticleDensity density) {
        return createSettingItem(
            Material.BLAZE_POWDER,
            "setting_particles",
            Component.text(PlayerSettingKey.PARTICLE_DENSITY.getDisplayNameJa(), NamedTextColor.GOLD),
            List.of(
                Component.text("現在: " + density.getDisplayNameJa(), NamedTextColor.AQUA),
                Component.text("クリックで次の密度へ", NamedTextColor.GRAY)
            )
        );
    }

    private @NotNull ItemStack createSettingItem(
        @NotNull Material material,
        @NotNull String role,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiResourcePackIcons.apply(createItem(material, name, lore), role);
    }

    private record Holder(@NotNull UUID userId) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.COMPARATOR, "プレイヤー設定");
        }

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
