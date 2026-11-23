package io.github.maaasu.astralRecord.feature.playersetting.gui;

import io.github.maaasu.astralRecord.feature.menu.view.screen.BaseMenuScreenView;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.playersetting.PlayerSettingMsgId;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingKey;
import io.github.maaasu.astralRecord.feature.playersetting.model.PlayerSettingSnapshot;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingGuiSaveService;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.shared.gui.GuiOpenSupport;
import io.github.maaasu.astralRecord.shared.gui.GuiResourcePackIcons;
import io.github.maaasu.astralRecord.shared.gui.hotbar.HotbarShortcutGuiHolder;
import io.github.maaasu.astralRecord.shared.gui.navigation.GuiNavigationDestination;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
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
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * プレイヤー設定 GUI です。
 */
public final class PlayerSettingGui extends BaseMenuScreenView {
    private static final Component TITLE = Component.text("プレイヤー設定", NamedTextColor.AQUA);
    public static final int SIZE = BaseMenuScreenView.SIZE;
    public static final int SCREEN_HUD_SETTINGS_SLOT = 51;
    public static final int SUPER_MODE_SECRET_SLOT = 53;
    public static final int BACK_TO_MENU_SLOT = BaseMenuScreenView.BACK_SLOT;
    private static final int SAVE_GUIDE_SLOT = 47;

    private static final List<SettingGroup> GENERAL_GROUPS = List.of(
        new SettingGroup(10, Material.ORANGE_BANNER, NamedTextColor.GOLD, PlayerSettingMsgId.P_6816, List.of(
            new SettingItem(12, PlayerSettingKey.DAMAGE_LOG_DISPLAY, Material.REDSTONE, "setting_damage_log",
                List.of(PlayerSettingMsgId.P_6831)),
            new SettingItem(13, PlayerSettingKey.DAMAGE_NUMBER_SIZE, Material.NAME_TAG, "setting_damage_size"),
            new SettingItem(14, PlayerSettingKey.DAMAGE_LOG_MESSAGE, Material.PAPER, "setting_damage_message",
                List.of(PlayerSettingMsgId.P_6832)),
            new SettingItem(15, PlayerSettingKey.PARTICLE_DENSITY, Material.BLAZE_POWDER, "setting_particles"),
            new SettingItem(16, PlayerSettingKey.BUFF_SIDEBAR_DISPLAY, Material.POTION, "setting_buff_sidebar",
                List.of(PlayerSettingMsgId.P_6833))
        )),
        new SettingGroup(19, Material.CYAN_BANNER, NamedTextColor.AQUA, PlayerSettingMsgId.P_6817, List.of(
            new SettingItem(21, PlayerSettingKey.ARMOR_DISPLAY, Material.IRON_CHESTPLATE, "equipment"),
            new SettingItem(22, PlayerSettingKey.NIGHT_VISION, Material.SPYGLASS, "setting_night_vision"),
            new SettingItem(23, PlayerSettingKey.SKILL_TREE_COMPACT_DISPLAY, Material.BOOK, "setting_skill_tree",
                List.of(PlayerSettingMsgId.P_5327)),
            new SettingItem(24, PlayerSettingKey.RANDOM_STATUS_RANGE_DISPLAY, Material.COMPARATOR, "setting_status_range"),
            new SettingItem(25, PlayerSettingKey.ACTION_RING_HOLD_SELECT, Material.TRIDENT, "setting_action_ring",
                List.of(PlayerSettingMsgId.P_5395, PlayerSettingMsgId.P_6827, PlayerSettingMsgId.P_6828))
        )),
        new SettingGroup(28, Material.YELLOW_BANNER, NamedTextColor.YELLOW, PlayerSettingMsgId.P_6818, List.of(
            new SettingItem(30, PlayerSettingKey.DROP_LOG_DISPLAY, Material.CHEST, "setting_drop_log"),
            new SettingItem(31, PlayerSettingKey.AUTO_SAVE_MESSAGE, Material.WRITABLE_BOOK, "setting_auto_save"),
            new SettingItem(32, PlayerSettingKey.GUIDE_REMINDER, Material.KNOWLEDGE_BOOK, "guide")
        ))
    );
    private static final List<SettingGroup> HUD_GROUPS = List.of(
        new SettingGroup(10, Material.ORANGE_BANNER, NamedTextColor.GOLD, PlayerSettingMsgId.P_6820, List.of(
            new SettingItem(12, PlayerSettingKey.QUEST_HUD_DISPLAY, Material.MAP, "quest"),
            new SettingItem(13, PlayerSettingKey.ACTION_RING_COOLDOWN_DISPLAY, Material.TRIDENT, "setting_action_ring_cooldown"),
            new SettingItem(14, PlayerSettingKey.EQUIPPED_SKILL_COOLDOWN_DISPLAY, Material.BOOK, "setting_equipped_skill_cooldown"),
            new SettingItem(15, PlayerSettingKey.COMBAT_DPS_DISPLAY, Material.CLOCK, "setting_dps")
        )),
        new SettingGroup(19, Material.CYAN_BANNER, NamedTextColor.AQUA, PlayerSettingMsgId.P_6821, List.of(
            new SettingItem(21, PlayerSettingKey.WORLD_NAME_DISPLAY, Material.FILLED_MAP, "setting_world_name"),
            new SettingItem(22, PlayerSettingKey.AREA_NAME_DISPLAY, Material.COMPASS, "setting_area_name")
        )),
        new SettingGroup(28, Material.YELLOW_BANNER, NamedTextColor.YELLOW, PlayerSettingMsgId.P_6822, List.of(
            new SettingItem(30, PlayerSettingKey.MSPT_DISPLAY, Material.CLOCK, "setting_performance"),
            new SettingItem(31, PlayerSettingKey.PING_DISPLAY, Material.REDSTONE, "setting_ping")
        ))
    );

    private final PlayerSettingService playerSettingService;
    private final PlayerSettingGuiSaveService guiSaveService;
    // メインスレッドのみ。切断したプレイヤーを参照し続けません。
    private final Map<Player, Object> currentBedrockForms = new WeakHashMap<>();

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
        open(player, false);
    }

    /** @param player 画面UIの設定を開くプレイヤー */
    public void openScreenHud(@NotNull Player player) {
        open(player, true);
    }

    /**
     * 保存と同じ基準で、現在のプレイヤーが画面UI設定を変更できるか確認します。
     * @param player 設定の所有プレイヤー
     * @param key 編集する設定
     * @return 変更できる場合は true
     */
    public boolean canEdit(@NotNull AstPlayer player, @NotNull PlayerSettingKey key) {
        return guiSaveService.canEdit(player, key);
    }

    /**
     * 版別に指定された設定画面を開きます。
     * @param player 設定画面を開くプレイヤー
     * @param screenHud 画面UI設定を開く場合は true
     */
    private void open(@NotNull Player player, boolean screenHud) {
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null && astPlayer.isBedrock()) {
            currentBedrockForms.remove(player);
            GuiOpenSupport.showDialog(player, () -> AstPlayerCache.get(player) == astPlayer && astPlayer.isBedrock()
                ? createBedrockForm(player, astPlayer, screenHud) : null);
            return;
        }
        UUID userId = astPlayer == null ? player.getUniqueId() : astPlayer.getUser().getUuid();
        Inventory inventory = Bukkit.createInventory(
            new Holder(userId, playerSettingService.captureSessionToken(userId), screenHud), SIZE, screenHud ? screenHudTitle() : TITLE
        );
        refresh(inventory, userId, null);
        GuiOpenSupport.open(player, inventory);
    }

    /**
     * 統合版フォームを組み立てます。非対応な画面UI項目は赤字の固定説明で表示し、送信欄を作りません。
     * @param player フォームを開くプレイヤー
     * @param astPlayer フォーム送信時に照合するプレイヤー状態
     * @param screenHud 画面UI設定フォームの場合は true
     * @return 現在値を選択済みにした統合版フォーム
     */
    private @NotNull Dialog createBedrockForm(@NotNull Player player, @NotNull AstPlayer astPlayer, boolean screenHud) {
        UUID userId = astPlayer.getUser().getUuid();
        long sessionToken = playerSettingService.captureSessionToken(userId);
        PlayerSettingSnapshot snapshot = playerSettingService.getSnapshot(userId);
        Map<PlayerSettingKey, Object> initial = new EnumMap<>(PlayerSettingKey.class);
        List<DialogInput> inputs = new ArrayList<>();
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6824.getId())));
        if (!screenHud) {
            body.add(DialogBody.plainMessage(PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6814.getId())));
            body.add(DialogBody.plainMessage(PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6834.getId())));
        }
        for (SettingGroup group : settingGroups(screenHud)) {
            String groupName = PlayerMsgResource.getMessage(group.title().getId());
            for (SettingItem item : group.items()) {
                PlayerSettingKey key = item.key();
                if (key == PlayerSettingKey.ACTION_RING_HOLD_SELECT) {
                    continue;
                }
                if (!canEdit(astPlayer, key)) {
                    body.add(DialogBody.plainMessage(PlayerMsgResource.formatPlainComponent(
                        PlayerSettingMsgId.P_6812.getId(), key.getDisplayNameJa())));
                    continue;
                }
                var entry = snapshot.getEntry(key);
                Object value = entry == null ? key.getDefaultValue() : entry.getValue();
                initial.put(key, value);
                Component label = PlayerMsgResource.formatPlainComponent(
                    PlayerSettingMsgId.P_6823.getId(), groupName, key.getDisplayNameJa()
                );
                inputs.add(formInput(key, label, value));
            }
        }
        Object token = new Object();
        ActionButton save = ActionButton.builder(Component.text(
            PlayerMsgResource.getMessage(PlayerSettingMsgId.P_5398.getId())
        )).action(bedrockAction(player, astPlayer, sessionToken, token, response -> {
            if (saveBedrockForm(player, userId, sessionToken, initial, response) && screenHud) {
                open(player, false);
            }
        })).build();
        ActionButton cancel = ActionButton.builder(screenHud
            ? PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6813.getId())
            : Component.translatable("gui.cancel"))
            .action(bedrockAction(player, astPlayer, sessionToken, token, response -> {
                if (screenHud) {
                    open(player, false);
                }
            })).build();
        DialogType dialogType;
        if (screenHud) {
            dialogType = DialogType.confirmation(save, cancel);
        } else {
            ActionButton showHud = ActionButton.builder(screenHudTitle())
                .action(bedrockAction(player, astPlayer, sessionToken, token, response -> {
                    if (saveBedrockForm(player, userId, sessionToken, initial, response)) {
                        openScreenHud(player);
                    }
                })).build();
            dialogType = DialogType.multiAction(List.of(save, showHud), cancel, 1);
        }
        Dialog dialog = Dialog.create(factory -> factory.empty()
            .base(DialogBase.builder(screenHud ? screenHudTitle() : TITLE).body(body).inputs(inputs).pause(false).build())
            .type(dialogType));
        currentBedrockForms.put(player, token);
        return dialog;
    }

    /**
     * 最新のフォームを一度だけ受理する操作を作ります。
     * @param player フォームの所有プレイヤー
     * @param astPlayer 開いたときのプレイヤー状態
     * @param sessionToken 開いたときの設定セッション
     * @param token 開いたフォームの一時識別子
     * @param handle 検証後に実行するフォーム操作
     * @return 古い・重複・他プレイヤーの実行を無視する操作
     */
    private @NotNull DialogAction bedrockAction(
        @NotNull Player player, @NotNull AstPlayer astPlayer, long sessionToken,
        @NotNull Object token, @NotNull Consumer<DialogResponseView> handle
    ) {
        return DialogAction.customClick((response, audience) -> {
            if (audience == player && player.isOnline() && sessionToken != 0L
                && AstPlayerCache.get(player) == astPlayer && astPlayer.isBedrock()
                && playerSettingService.captureSessionToken(astPlayer.getUser().getUuid()) == sessionToken
                && currentBedrockForms.remove(player, token)) {
                handle.accept(response);
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(5)).build());
    }

    /** @return 画面UI設定の共通表示名 */
    private static @NotNull Component screenHudTitle() {
        return PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6810.getId());
    }

    /**
     * @param key フォームに表示する設定キー
     * @param label 分類と設定項目名を含む入力欄の見出し
     * @param value 現在値
     * @return 真偽値の切替欄、または列挙値の選択欄
     */
    private static @NotNull DialogInput formInput(
        @NotNull PlayerSettingKey key, @NotNull Component label, @NotNull Object value
    ) {
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
     * @return 全フォーム項目が検証済みで、次の画面へ遷移してよい場合は true
     */
    private boolean saveBedrockForm(
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
                return false;
            }
            if (!submitted.equals(entry.getValue())) {
                changes.put(key, submitted);
            }
        }
        guiSaveService.save(player, userId, sessionToken, changes);
        return true;
    }

    /**
     * 保存済み値と下書きを、対象の設定画面へ描き直します。
     * @param inventory 描画先の設定画面
     * @param userId 設定の所有者
     * @param draftValues 未保存の変更予定値
     */
    public void refresh(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        ItemStack back = inventory.getItem(BACK_TO_MENU_SLOT);
        render(inventory, userId, draftValues);
        if (back != null) {
            inventory.setItem(BACK_TO_MENU_SLOT, back);
        }
    }

    public boolean isInventory(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof Holder;
    }

    /**
     * @param inventory 操作中のインベントリ
     * @return 画面UI設定のインベントリなら true
     */
    public boolean isScreenHudInventory(@NotNull Inventory inventory) {
        return inventory.getHolder() instanceof Holder holder && holder.screenHud();
    }

    /**
     * GUIスロットから設定項目を解決します。
     *
     * @param inventory 操作中のインベントリ
     * @param rawSlot 設定GUI内のスロット番号
     * @return 対応する設定キー。設定項目以外はnull
     */
    public @Nullable PlayerSettingKey getKeyAtSlot(@NotNull Inventory inventory, int rawSlot) {
        for (SettingGroup group : settingGroups(isScreenHudInventory(inventory))) {
            for (SettingItem item : group.items()) {
                if (item.slot() == rawSlot) {
                    return item.key();
                }
            }
        }
        return null;
    }

    /**
     * インベントリと統合版フォームで共有する分類を返します。
     * @param screenHud 画面UI設定の場合は true
     * @return スロット順の分類
     */
    private static @NotNull List<SettingGroup> settingGroups(boolean screenHud) {
        return screenHud ? HUD_GROUPS : GENERAL_GROUPS;
    }

    public @Nullable UUID getUserId(@Nullable Inventory inventory) {
        if (inventory != null && inventory.getHolder() instanceof Holder holder) {
            return holder.userId();
        }
        return null;
    }

    /**
     * @param inventory 対象の設定画面
     * @return 画面を開いた時点の設定セッション。設定画面でない場合は 0
     */
    public long getSessionToken(@NotNull Inventory inventory) {
        return inventory.getHolder() instanceof Holder holder ? holder.sessionToken() : 0L;
    }

    /**
     * Java版の分類と設定項目を、保存済み値と変更予定値から主スレッドで描画します。
     *
     * @param inventory 描画先インベントリ
     * @param userId 設定を所有するユーザーUUID
     * @param draftValues 未保存の変更予定値。nullなら保存済み値を使用
     */
    private void render(
        @NotNull Inventory inventory,
        @NotNull UUID userId,
        @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        boolean screenHud = isScreenHudInventory(inventory);
        inventory.clear();
        for (SettingGroup group : settingGroups(screenHud)) {
            Component title = PlayerMsgResource.getComponent(group.title().getId()).color(group.color());
            inventory.setItem(group.headingSlot(), createItem(group.banner(), title,
                List.of(PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6829.getId()))));
            for (SettingItem item : group.items()) {
                inventory.setItem(item.slot(), createSettingItem(item, userId, draftValues));
            }
        }
        inventory.setItem(SAVE_GUIDE_SLOT, createSettingItem(
            Material.WRITABLE_BOOK, "setting_save_guide",
            PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6819.getId()),
            List.of(
                PlayerMsgResource.getComponent(PlayerSettingMsgId.P_5328.getId()),
                PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6830.getId())
            )
        ));
        inventory.setItem(BACK_TO_MENU_SLOT, backItem());
        if (!screenHud) {
            inventory.setItem(SCREEN_HUD_SETTINGS_SLOT, createSettingItem(
                Material.COMPARATOR, "setting_screen_hud", screenHudTitle(),
                List.of(
                    PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6811.getId()),
                    PlayerMsgResource.getComponent(PlayerSettingMsgId.P_6814.getId())
                )
            ));
        }
    }

    /**
     * 項目の現在値、操作方法、未保存の変更を表示します。
     * @param item 表示する設定項目
     * @param userId 設定の所有者
     * @param draftValues 未保存の変更予定値。nullなら保存済み値を使用
     * @return 設定アイコン
     */
    private @NotNull ItemStack createSettingItem(
        @NotNull SettingItem item, @NotNull UUID userId, @Nullable Map<PlayerSettingKey, Object> draftValues
    ) {
        PlayerSettingKey key = item.key();
        Object savedValue = playerSettingService.getPlayerSetting(userId, key);
        Object chosenValue = draftValues == null ? savedValue : draftValues.getOrDefault(key, savedValue);
        boolean changed = !Objects.equals(savedValue, chosenValue);
        NamedTextColor valueColor = NamedTextColor.AQUA;
        if (chosenValue instanceof Boolean enabled) {
            valueColor = enabled ? NamedTextColor.GREEN : NamedTextColor.RED;
        }
        Component value = changed
            ? PlayerMsgResource.formatPlainComponent(
                PlayerSettingMsgId.P_5329.getId(), key.formatValue(savedValue), key.formatValue(chosenValue))
            : PlayerMsgResource.formatPlainComponent(PlayerSettingMsgId.P_5396.getId(), key.formatValue(savedValue))
                .color(valueColor);
        PlayerSettingMsgId operation = switch (key) {
            case DAMAGE_NUMBER_SIZE -> PlayerSettingMsgId.P_5397;
            case PARTICLE_DENSITY -> PlayerSettingMsgId.P_6826;
            default -> PlayerSettingMsgId.P_6825;
        };
        List<Component> lore = new ArrayList<>(List.of(value, PlayerMsgResource.getComponent(operation.getId())));
        for (PlayerSettingMsgId note : item.notes()) {
            lore.add(PlayerMsgResource.getComponent(note.getId()));
        }
        ItemStack stack = createSettingItem(
            item.material(), item.role(), Component.text(key.getDisplayNameJa(), NamedTextColor.WHITE), lore
        );
        if (changed) {
            stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        }
        return stack;
    }

    private @NotNull ItemStack createSettingItem(
        @NotNull Material material,
        @NotNull String role,
        @NotNull Component name,
        @NotNull List<Component> lore
    ) {
        return GuiResourcePackIcons.apply(createItem(material, name, lore), role);
    }

    private record SettingGroup(
        int headingSlot, Material banner, NamedTextColor color, PlayerSettingMsgId title, List<SettingItem> items
    ) { }

    private record SettingItem(
        int slot, PlayerSettingKey key, Material material, String role, List<PlayerSettingMsgId> notes
    ) {
        /**
         * 共通の現在値と操作方法だけを表示する設定項目を定義します。
         * @param slot 設定項目のスロット
         * @param key 設定キー
         * @param material GUI アイコンのベース素材
         * @param role GUI アイコンの役割
         */
        private SettingItem(int slot, PlayerSettingKey key, Material material, String role) {
            this(slot, key, material, role, List.of());
        }
    }

    private record Holder(@NotNull UUID userId, long sessionToken, boolean screenHud) implements HotbarShortcutGuiHolder {
        @Override
        public @NotNull GuiNavigationDestination getNavigationDestination() {
            return new GuiNavigationDestination(Material.COMPARATOR, screenHud
                ? PlayerMsgResource.getMessage(PlayerSettingMsgId.P_6815.getId()) : "プレイヤー設定");
        }

        /** @return 一般設定へ戻れるよう画面UI設定を別の履歴項目として区別する識別子 */
        @Override
        public @NotNull String getNavigationId() {
            return screenHud ? getClass().getName() + ".screenHud" : getClass().getName();
        }

        /** @return 一般設定へ戻ってから内容を再描画する必要がない場合は true */
        @Override
        public boolean isDirectBackNavigation() {
            return !screenHud;
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
