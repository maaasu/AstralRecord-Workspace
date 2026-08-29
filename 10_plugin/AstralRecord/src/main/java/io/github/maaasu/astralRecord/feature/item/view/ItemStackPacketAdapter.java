package io.github.maaasu.astralRecord.feature.item.view;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.wrappers.EnumWrappers;
import com.comphenix.protocol.wrappers.Pair;
import io.github.maaasu.astralRecord.feature.item.service.ItemStackFactory;
import io.github.maaasu.astralRecord.feature.playersetting.service.PlayerSettingService;
import io.github.maaasu.astralRecord.feature.skill.service.SkillActionRingService;
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndEvent;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionEndReason;
import io.github.maaasu.astralRecord.shared.gui.session.GuiSessionTransitionService;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.CrossbowMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ProtocolLib パケットアダプタ。
 * <p>
 * サーバ側 {@link Material#PAPER} の ItemStack に埋め込まれた icon 情報を読み取り、
 * クライアントへ送信するパケット内の Material を icon に差し替えます。
 * <p>
 * 対象パケット:
 * <ul>
 *   <li>{@code SET_SLOT} — 単一スロットの更新</li>
 *   <li>{@code WINDOW_ITEMS} — インベントリ全体の一括送信</li>
 *   <li>{@code ENTITY_EQUIPMENT} — 本人を含むエンティティの手持ち・装備更新</li>
 * </ul>
 * <p>
 * <b>性能上の考慮:</b>
 * <ul>
 *   <li>Material 名 → Material の変換結果を {@link ConcurrentHashMap} でキャッシュし、
 *       毎パケットでの {@link Material#matchMaterial} 呼び出しを回避します。</li>
 *   <li>AstralRecord アイテムでない ItemStack は PDC チェック 1 回で即スキップします。</li>
 *   <li>パケット内の ItemStack を直接 clone → 書き換えすることで、
 *       不要なオブジェクト生成を最小限にしています。</li>
 * </ul>
 */
public class ItemStackPacketAdapter {

    /** Material 名キャッシュ (大文字名 → Material) */
    private static final Map<String, Material> MATERIAL_CACHE = new ConcurrentHashMap<>();
    private static final long EQUIPMENT_OVERRIDE_TTL_MILLIS = 5_000L;
    private static final int PLAYER_INVENTORY_WINDOW_ID = 0;
    private static final int PLAYER_INVENTORY_DIRECT_WINDOW_ID = -2;
    private static final int PLAYER_INVENTORY_HOTBAR_START_SLOT = 36;

    private final Plugin plugin;
    private final PlayerSettingService playerSettingService;
    private final SkillActionRingService actionRingService;
    private final EquipmentOverrideRegistry equipmentOverrideRegistry = new EquipmentOverrideRegistry();
    private final Map<UUID, Integer> selectedHotbarSlots = new ConcurrentHashMap<>();
    private boolean registered = false;

    /**
     * アダプタを初期化します。
     *
     * @param plugin プラグインインスタンス
     * @param playerSettingService 受信者ごとの防具・オフハンド表示設定を参照するサービス
     * @param actionRingService 受信者のアクションリング表示状態を参照するサービス
     */
    public ItemStackPacketAdapter(
        @NotNull Plugin plugin,
        @NotNull PlayerSettingService playerSettingService,
        @NotNull SkillActionRingService actionRingService
    ) {
        this.plugin = plugin;
        this.playerSettingService = playerSettingService;
        this.actionRingService = actionRingService;
    }

    /**
     * ProtocolLib にパケットリスナーを登録します。
     * 複数回呼び出しても二重登録されません。
     */
    public void register() {
        if (registered) {
            return;
        }

        ProtocolManager manager = ProtocolLibrary.getProtocolManager();

        manager.addPacketListener(new PacketAdapter(
                plugin,
                ListenerPriority.NORMAL,
                PacketType.Play.Server.SET_SLOT,
                PacketType.Play.Server.WINDOW_ITEMS,
                PacketType.Play.Server.ENTITY_EQUIPMENT
        ) {
            @Override
            public void onPacketSending(PacketEvent event) {
                if (event.isCancelled()) {
                    return;
                }

                PacketContainer packet = event.getPacket();
                PacketType type = packet.getType();
                Player viewer = event.getPlayer();
                if (type == PacketType.Play.Server.ENTITY_EQUIPMENT
                    && equipmentOverrideRegistry.consume(packet, viewer.getUniqueId(), System.currentTimeMillis())) {
                    return;
                }
                boolean armorDisplayEnabled = playerSettingService.isArmorDisplayEnabled(
                    viewer.getUniqueId()
                );
                boolean offHandDisplayEnabled = playerSettingService.isOffHandDisplayEnabled(
                    viewer.getUniqueId()
                );
                boolean actionRingHoldSelectEnabled = playerSettingService.isActionRingHoldSelectEnabled(
                    viewer.getUniqueId()
                );
                boolean actionRingOpen = actionRingService.isOpen(viewer);
                int selectedHotbarSlot = actionRingOpen
                    ? actionRingService.getSelectedHotbarSlot(viewer)
                    : selectedHotbarSlots.getOrDefault(viewer.getUniqueId(), -1);

                if (type == PacketType.Play.Server.SET_SLOT) {
                    handleSetSlot(
                        packet,
                        armorDisplayEnabled,
                        shouldVirtualizeHotbarWeapon(
                            actionRingHoldSelectEnabled,
                            hotbarSlotForSetSlot(packet),
                            selectedHotbarSlot
                        )
                    );
                } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                    handleWindowItems(
                        packet,
                        armorDisplayEnabled,
                        actionRingHoldSelectEnabled,
                        selectedHotbarSlot
                    );
                } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                    handleEntityEquipment(
                        event,
                        armorDisplayEnabled,
                        offHandDisplayEnabled,
                        actionRingHoldSelectEnabled,
                        selectedHotbarSlot
                    );
                }
            }
        });
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
            public void onPlayerJoin(@NotNull PlayerJoinEvent event) {
                selectedHotbarSlots.put(
                    event.getPlayer().getUniqueId(),
                    event.getPlayer().getInventory().getHeldItemSlot()
                );
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
            public void onPlayerItemHeld(@NotNull PlayerItemHeldEvent event) {
                if (event.isCancelled()) {
                    return;
                }
                Player player = event.getPlayer();
                selectedHotbarSlots.put(player.getUniqueId(), event.getNewSlot());
                if (playerSettingService.isActionRingHoldSelectEnabled(player.getUniqueId())) {
                    player.updateInventory();
                }
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
            public void onGuiSessionEnd(@NotNull GuiSessionEndEvent event) {
                if (event.getReason() != GuiSessionEndReason.MANUAL_CLOSE) {
                    return;
                }
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    Player player = event.getPlayer();
                    if (player.isOnline()
                        && playerSettingService.isActionRingHoldSelectEnabled(player.getUniqueId())
                        && isPlayerInventoryOnlyOpen(player)) {
                        player.updateInventory();
                    }
                });
            }

            @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
            public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
                selectedHotbarSlots.remove(event.getPlayer().getUniqueId());
                equipmentOverrideRegistry.discardViewer(event.getPlayer().getUniqueId());
            }
        }, plugin);

        registered = true;
        Logger.log(LogId.I_5210);
    }

    // region --- パケットハンドラ ---

    /**
     * SET_SLOT パケット内の単一 ItemStack を書き換えます。
     *
     * @param packet 書き換え対象パケット
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     * @param virtualTrident 選択中の主武器を長押し入力用トライデントとして表示する場合は {@code true}
     */
    private void handleSetSlot(
        @NotNull PacketContainer packet,
        boolean armorDisplayEnabled,
        boolean virtualTrident
    ) {
        var original = packet.getItemModifier().readSafely(0);
        if (original == null || original.getType() == Material.AIR) {
            return;
        }

        var replaced = replaceIcon(original, armorDisplayEnabled, virtualTrident);
        if (replaced != null) {
            packet.getItemModifier().writeSafely(0, replaced);
        }
    }

    /**
     * WINDOW_ITEMS パケット内の ItemStack リストを書き換えます。
     *
     * @param packet 書き換え対象パケット
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     * @param actionRingHoldSelectEnabled 長押し選択設定が有効な場合は {@code true}
     * @param selectedHotbarSlot 選択中 hotbar slot（0-8）、不明な場合は負値
     */
    private void handleWindowItems(
        @NotNull PacketContainer packet,
        boolean armorDisplayEnabled,
        boolean actionRingHoldSelectEnabled,
        int selectedHotbarSlot
    ) {
        var items = packet.getItemListModifier().readSafely(0);
        if (items == null || items.isEmpty()) {
            return;
        }

        var modified = false;
        for (int i = 0; i < items.size(); i++) {
            var original = items.get(i);
            if (original == null || original.getType() == Material.AIR) {
                continue;
            }

            boolean virtualTrident = shouldVirtualizeHotbarWeapon(
                actionRingHoldSelectEnabled,
                playerInventoryHotbarSlot(packet, i),
                selectedHotbarSlot
            );
            var replaced = replaceIcon(original, armorDisplayEnabled, virtualTrident);
            if (replaced != null) {
                items.set(i, replaced);
                modified = true;
            }
        }

        if (modified) {
            packet.getItemListModifier().writeSafely(0, items);
        }
    }

    /**
     * ENTITY_EQUIPMENT パケットを中止し、Paper API が符号化する受信者専用の装備更新へ置き換えます。
     * 本人のメインハンドだけは、選択中 hotbar slot の仮想トライデント表示も再適用します。
     *
     * @param event 中止対象のパケットイベント
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     * @param offHandDisplayEnabled 受信者が自身のオフハンドを通常表示する場合は {@code true}
     * @param actionRingHoldSelectEnabled 長押し選択設定が有効な場合は {@code true}
     * @param selectedHotbarSlot 受信者が選択中の hotbar slot
     */
    private void handleEntityEquipment(
        @NotNull PacketEvent event,
        boolean armorDisplayEnabled,
        boolean offHandDisplayEnabled,
        boolean actionRingHoldSelectEnabled,
        int selectedHotbarSlot
    ) {
        PacketContainer packet = event.getPacket();
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> equipment = packet.getSlotStackPairLists().readSafely(0);
        if (equipment == null || equipment.isEmpty()) {
            return;
        }

        Integer entityId = packet.getIntegers().readSafely(0);
        if (entityId == null) {
            return;
        }

        Player viewer = event.getPlayer();
        boolean virtualizeSelectedMainHand = shouldVirtualizeSelectedMainHand(
            actionRingHoldSelectEnabled,
            selectedHotbarSlot,
            entityId == viewer.getEntityId()
        );
        List<EquipmentUpdate> updates = new java.util.ArrayList<>(equipment.size());
        boolean requiresOverride = false;
        for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipment) {
            ItemStack original = pair.getSecond();
            boolean hideOwnOffHand = shouldHideOwnOffHand(
                offHandDisplayEnabled,
                entityId,
                viewer.getEntityId(),
                pair.getFirst()
            );
            boolean virtualTrident = virtualizeSelectedMainHand
                && pair.getFirst() == EnumWrappers.ItemSlot.MAINHAND;
            if (original != null && original.getType() != Material.AIR
                && (hideOwnOffHand
                    || replaceIcon(original, armorDisplayEnabled, virtualTrident) != null)) {
                requiresOverride = true;
            }
            updates.add(new EquipmentUpdate(
                toBukkitEquipmentSlot(pair.getFirst()),
                original == null ? new ItemStack(Material.AIR) : original.clone()
            ));
        }

        if (!requiresOverride) {
            return;
        }

        PacketContainer originalPacket = packet.deepClone();
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> sendEquipmentOverride(
                viewer,
                entityId,
                updates,
                originalPacket,
                virtualizeSelectedMainHand,
                entityId == viewer.getEntityId() && !offHandDisplayEnabled
            )
        );
    }

    // endregion

    // region --- Material 差し替えロジック ---

    /**
     * AstralRecord アイテムであれば、clone して Material を icon に差し替えた ItemStack を返します。
     * AstralRecord アイテムでなければ {@code null} を返します。
     *
     * @param original 元の ItemStack
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     * @return icon 適用済み ItemStack、または {@code null}
     */
    private ItemStack replaceIcon(@NotNull ItemStack original, boolean armorDisplayEnabled) {
        return replaceIcon(original, armorDisplayEnabled, false);
    }

    /**
     * AstralRecord アイテムをクライアント表示用へ変換します。
     *
     * @param original サーバー側 ItemStack
     * @param armorDisplayEnabled 防具の身体描画を表示する場合は {@code true}
     * @param virtualTrident hotbar 内の武器を長押し入力用トライデントとして表示する場合は {@code true}
     * @return 変換済み ItemStack。変換不要の場合は {@code null}
     */
    private ItemStack replaceIcon(
        @NotNull ItemStack original,
        boolean armorDisplayEnabled,
        boolean virtualTrident
    ) {
        var iconName = ItemStackFactory.getIconName(original);
        var customModelData = ItemStackFactory.getCustomModelData(original);
        var appearanceColor = ItemStackFactory.getAppearanceColor(original);
        var potionType = ItemStackFactory.getPotionType(original);
        boolean hookshotLoaded = ItemStackFactory.isHookshotLoaded(original);
        boolean virtualWeapon = virtualTrident && ItemStackFactory.isWeapon(original);

        if (!virtualWeapon
            && iconName == null
            && customModelData == null
            && appearanceColor == null
            && potionType == null) {
            return null;
        }

        ItemStack replaced = original.clone();
        boolean modified = false;

        if (virtualWeapon) {
            replaced = original.withType(Material.TRIDENT);
            modified = true;
        } else if (iconName != null) {
            var iconMaterial = resolveMaterial(iconName);
            if (iconMaterial != null && iconMaterial != original.getType()) {
                replaced = original.withType(iconMaterial);
                modified = true;
            }
        }

        modified |= applyHookshotChargedIcon(replaced, hookshotLoaded);
        modified |= ItemStackFactory.hideBundleContentsTooltip(replaced);

        if (customModelData != null) {
            var meta = replaced.getItemMeta();
            if (meta != null) {
                Integer currentCustomModelData = CustomModelDataComponentUtil.readAsInt(meta);
                if (needsCustomModelDataUpdate(customModelData, currentCustomModelData)) {
                    CustomModelDataComponentUtil.writeFromInt(meta, customModelData);
                    replaced.setItemMeta(meta);
                    modified = true;
                }
            }
        }

        if (ItemStackFactory.applyAppearance(replaced)) {
            modified = true;
        }
        if (ItemStackFactory.applyDurabilityVisual(replaced)) {
            modified = true;
        }
        if (!armorDisplayEnabled && replaced.hasData(DataComponentTypes.EQUIPPABLE)) {
            if (replaced == original) {
                replaced = original.clone();
            }
            modified |= removeEquippableComponent(replaced);
        }

        return modified ? replaced : null;
    }

    /**
     * 装填済みフックショットの送信コピーへ、バニラのチャージ済みクロスボウ状態を適用します。
     *
     * @param item 送信コピー
     * @param hookshotLoaded 元 ItemStack が装填済みフックショット表示を持つ場合は true
     * @return ItemMeta を変更した場合は true
     */
    static boolean applyHookshotChargedIcon(@NotNull ItemStack item, boolean hookshotLoaded) {
        if (!hookshotLoaded || item.getType() != Material.CROSSBOW) {
            return false;
        }
        ItemMeta meta = item.getItemMeta();
        if (!(meta instanceof CrossbowMeta crossbowMeta) || crossbowMeta.hasChargedProjectiles()) {
            return false;
        }
        crossbowMeta.setChargedProjectiles(List.of(new ItemStack(Material.ARROW)));
        item.setItemMeta(crossbowMeta);
        return true;
    }

    private int hotbarSlotForSetSlot(@NotNull PacketContainer packet) {
        Integer windowId = packet.getIntegers().readSafely(0);
        Integer slot = packet.getIntegers().readSafely(2);
        if (slot == null) {
            Short shortSlot = packet.getShorts().readSafely(0);
            slot = shortSlot == null ? null : Short.toUnsignedInt(shortSlot);
        }
        if (windowId == null || slot == null) {
            return -1;
        }
        if (windowId == PLAYER_INVENTORY_WINDOW_ID) {
            return playerInventoryHotbarSlot(slot);
        }
        return windowId == PLAYER_INVENTORY_DIRECT_WINDOW_ID && slot >= 0 && slot < 9 ? slot : -1;
    }

    /**
     * player inventory window 内の hotbar item index を hotbar slot へ変換します。
     *
     * @param packet WINDOW_ITEMS パケット
     * @param itemIndex パケット内の item index
     * @return hotbar slot（0-8）、対象外の場合は負値
     */
    private int playerInventoryHotbarSlot(@NotNull PacketContainer packet, int itemIndex) {
        Integer windowId = packet.getIntegers().readSafely(0);
        return windowId != null && windowId == PLAYER_INVENTORY_WINDOW_ID
            ? playerInventoryHotbarSlot(itemIndex)
            : -1;
    }

    private static int playerInventoryHotbarSlot(int inventorySlot) {
        return inventorySlot >= PLAYER_INVENTORY_HOTBAR_START_SLOT
            && inventorySlot < PLAYER_INVENTORY_HOTBAR_START_SLOT + 9
            ? inventorySlot - PLAYER_INVENTORY_HOTBAR_START_SLOT
            : -1;
    }

    /**
     * 長押し選択用の仮想トライデントを表示する条件を判定します。
     *
     * @param actionRingHoldSelectEnabled 長押し選択設定が有効か
     * @param hotbarSlot 判定対象 hotbar slot
     * @param selectedHotbarSlot プレイヤーが選択中の hotbar slot
     * @return 選択中の武器を仮想トライデント化する場合は {@code true}
     */
    static boolean shouldVirtualizeHotbarWeapon(
        boolean actionRingHoldSelectEnabled,
        int hotbarSlot,
        int selectedHotbarSlot
    ) {
        return actionRingHoldSelectEnabled
            && hotbarSlot >= 0
            && hotbarSlot < 9
            && hotbarSlot == selectedHotbarSlot;
    }

    /**
     * 本人の ENTITY_EQUIPMENT メインハンドを仮想トライデント表示にする条件を判定します。
     *
     * @param actionRingHoldSelectEnabled 長押し選択設定が有効か
     * @param selectedHotbarSlot 受信者が選択中の hotbar slot
     * @param viewerEntityPacket 本人のエンティティ向けパケットか
     * @return 本人のメインハンドを仮想トライデント化する場合は {@code true}
     */
    static boolean shouldVirtualizeSelectedMainHand(
        boolean actionRingHoldSelectEnabled,
        int selectedHotbarSlot,
        boolean viewerEntityPacket
    ) {
        return actionRingHoldSelectEnabled
            && viewerEntityPacket
            && selectedHotbarSlot >= 0
            && selectedHotbarSlot < 9;
    }

    /**
     * 本人向け ENTITY_EQUIPMENT のオフハンドを表示用アイテムへ置換する条件を判定します。
     *
     * @param offHandDisplayEnabled オフハンドを通常表示する設定
     * @param entityId 装備更新対象のエンティティ ID
     * @param viewerEntityId パケット受信者のエンティティ ID
     * @param slot 判定対象の ProtocolLib 装備スロット
     * @return 本人のオフハンドを置換する場合は {@code true}
     */
    static boolean shouldHideOwnOffHand(
        boolean offHandDisplayEnabled,
        int entityId,
        int viewerEntityId,
        @NotNull EnumWrappers.ItemSlot slot
    ) {
        return !offHandDisplayEnabled
            && entityId == viewerEntityId
            && slot == EnumWrappers.ItemSlot.OFFHAND;
    }

    /**
     * オフハンドの表示用コピーを作成します。
     *
     * @param original サーバー側のオフハンド ItemStack
     * @return 表示用コピー。空手の場合は {@code null}
     */
    static @Nullable ItemStack replaceOwnOffHandDisplay(@NotNull ItemStack original) {
        if (original.getType() == Material.AIR) {
            return null;
        }
        return new ItemStack(Material.STONE_BUTTON);
    }

    /**
     * プレイヤーインベントリだけが表示されている状態かを判定します。
     *
     * <p>GUI セッション終了後の再同期に使います。プラグイン管理 GUI の遷移中や、
     * バニラの別インベントリが表示中の場合は {@code false} を返します。</p>
     *
     * @param player 判定対象プレイヤー
     * @return プレイヤーインベントリだけが表示中の場合は {@code true}
     */
    static boolean isPlayerInventoryOnlyOpen(@NotNull Player player) {
        var view = player.getOpenInventory();
        return view.getType() == InventoryType.CRAFTING
            && !GuiSessionTransitionService.isPluginManagedGui(view.getTopInventory());
    }

    /**
     * 指定プレイヤーの inventory と、そのプレイヤーが追跡中の全プレイヤーの防具表示を再送します。
     *
     * <p>Bukkit メインスレッドから呼び出してください。設定変更時とログイン設定ロード完了時に、
     * 次の通常装備更新を待たず、現在選択中 hotbar slot の仮想表示と本人のオフハンド表示を含む
     * 表示状態を即時反映します。</p>
     *
     * @param viewer 表示設定を反映する受信プレイヤー
     */
    public void refreshEquipmentView(@NotNull Player viewer) {
        if (!viewer.isOnline()) {
            return;
        }
        selectedHotbarSlots.put(viewer.getUniqueId(), viewer.getInventory().getHeldItemSlot());
        viewer.updateInventory();
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target != viewer && !target.isTrackedBy(viewer)) {
                continue;
            }
            List<EquipmentUpdate> updates = new java.util.ArrayList<>(5);
            updates.add(new EquipmentUpdate(EquipmentSlot.HEAD, copyOrAir(target.getInventory().getHelmet())));
            updates.add(new EquipmentUpdate(EquipmentSlot.CHEST, copyOrAir(target.getInventory().getChestplate())));
            updates.add(new EquipmentUpdate(EquipmentSlot.LEGS, copyOrAir(target.getInventory().getLeggings())));
            updates.add(new EquipmentUpdate(EquipmentSlot.FEET, copyOrAir(target.getInventory().getBoots())));
            boolean viewerTarget = target == viewer;
            if (viewerTarget) {
                updates.add(new EquipmentUpdate(
                    EquipmentSlot.OFF_HAND,
                    copyOrAir(target.getInventory().getItemInOffHand())
                ));
            }
            sendEquipmentOverride(
                viewer,
                target,
                updates,
                false,
                viewerTarget && !playerSettingService.isOffHandDisplayEnabled(viewer.getUniqueId())
            );
        }
    }

    /**
     * ProtocolLib から中止した装備更新を、Paper API 経由で受信者専用の表示として送信します。
     *
     * @param viewer 装備表示を受信するプレイヤー
     * @param entityId 元パケットのエンティティ ID
     * @param updates 元パケットから退避した装備更新
     * @param originalPacket 解決失敗時にフィルタを通さず再送する元パケット
     * @param virtualizeSelectedMainHand 本人のメインハンドを仮想トライデント化する場合は {@code true}
     * @param hideOwnOffHand 本人のオフハンドを表示用アイテムへ置換する場合は {@code true}
     */
    private void sendEquipmentOverride(
        @NotNull Player viewer,
        int entityId,
        @NotNull List<EquipmentUpdate> updates,
        @NotNull PacketContainer originalPacket,
        boolean virtualizeSelectedMainHand,
        boolean hideOwnOffHand
    ) {
        if (!viewer.isOnline()) {
            return;
        }

        var target = viewer.getWorld().getEntities().stream()
            .filter(entity -> entity.getEntityId() == entityId)
            .filter(org.bukkit.entity.LivingEntity.class::isInstance)
            .map(org.bukkit.entity.LivingEntity.class::cast)
            .findFirst()
            .orElse(null);
        if (target == null) {
            sendOriginalPacketWithoutFilters(ProtocolLibrary.getProtocolManager(), viewer, originalPacket);
            return;
        }

        sendEquipmentOverride(viewer, target, updates, virtualizeSelectedMainHand, hideOwnOffHand);
    }

    /**
     * Paper API 経由で受信者専用の表示として装備更新を送信します。
     *
     * @param viewer 装備表示を受信するプレイヤー
     * @param target 装備を表示する対象エンティティ
     * @param updates 送信する装備更新
     */
    private void sendEquipmentOverride(
        @NotNull Player viewer,
        @NotNull org.bukkit.entity.LivingEntity target,
        @NotNull List<EquipmentUpdate> updates
    ) {
        sendEquipmentOverride(viewer, target, updates, false);
    }

    /**
     * Paper API 経由で装備更新を送信し、必要な場合は本人のメインハンドだけを仮想化します。
     *
     * @param viewer 装備表示を受信するプレイヤー
     * @param target 装備を表示する対象エンティティ
     * @param updates 送信する装備更新
     * @param virtualizeSelectedMainHand 本人のメインハンドを仮想トライデント化する場合は {@code true}
     */
    private void sendEquipmentOverride(
        @NotNull Player viewer,
        @NotNull org.bukkit.entity.LivingEntity target,
        @NotNull List<EquipmentUpdate> updates,
        boolean virtualizeSelectedMainHand
    ) {
        sendEquipmentOverride(viewer, target, updates, virtualizeSelectedMainHand, false);
    }

    /**
     * Paper API 経由で装備更新を送信し、受信者本人の表示だけを必要に応じて仮想化します。
     *
     * @param viewer 装備表示を受信するプレイヤー
     * @param target 装備を表示する対象エンティティ
     * @param updates 送信する装備更新
     * @param virtualizeSelectedMainHand 本人のメインハンドを仮想トライデント化する場合は {@code true}
     * @param hideOwnOffHand 本人のオフハンドを表示用アイテムへ置換する場合は {@code true}
     */
    private void sendEquipmentOverride(
        @NotNull Player viewer,
        @NotNull org.bukkit.entity.LivingEntity target,
        @NotNull List<EquipmentUpdate> updates,
        boolean virtualizeSelectedMainHand,
        boolean hideOwnOffHand
    ) {

        boolean armorDisplayEnabled = playerSettingService.isArmorDisplayEnabled(viewer.getUniqueId());
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentUpdate update : updates) {
            boolean virtualTrident = virtualizeSelectedMainHand && update.slot() == EquipmentSlot.HAND;
            ItemStack replaced = hideOwnOffHand && update.slot() == EquipmentSlot.OFF_HAND
                ? replaceOwnOffHandDisplay(update.item())
                : replaceIcon(update.item(), armorDisplayEnabled, virtualTrident);
            equipment.put(update.slot(), replaced != null ? replaced : update.item());
        }
        if (equipment.isEmpty()) {
            return;
        }

        equipmentOverrideRegistry.mark(viewer.getUniqueId(), target.getEntityId(), equipment, System.currentTimeMillis());
        viewer.sendEquipmentChange(target, equipment);
    }

    /**
     * cancel 済みの元パケットを、ProtocolLib listener filter を通さず再送します。
     *
     * @param manager ProtocolLib の送信管理器
     * @param viewer パケットを受信するプレイヤー
     * @param originalPacket pair-list を書き換えていない元パケットのコピー
     */
    static void sendOriginalPacketWithoutFilters(
        @NotNull ProtocolManager manager,
        @NotNull Player viewer,
        @NotNull PacketContainer originalPacket
    ) {
        manager.sendServerPacket(viewer, originalPacket, false);
    }

    /**
     * ProtocolLib の装備スロットを Paper API の装備スロットへ対応付けます。
     *
     * @param slot ProtocolLib が通知した装備スロット
     * @return Paper API で同じ装備位置を表すスロット
     */
    static EquipmentSlot toBukkitEquipmentSlot(@NotNull EnumWrappers.ItemSlot slot) {
        return switch (slot) {
            case MAINHAND -> EquipmentSlot.HAND;
            case OFFHAND -> EquipmentSlot.OFF_HAND;
            case FEET -> EquipmentSlot.FEET;
            case LEGS -> EquipmentSlot.LEGS;
            case CHEST -> EquipmentSlot.CHEST;
            case HEAD -> EquipmentSlot.HEAD;
            case BODY -> EquipmentSlot.BODY;
            case SADDLE -> EquipmentSlot.SADDLE;
        };
    }

    private record EquipmentUpdate(@NotNull EquipmentSlot slot, @NotNull ItemStack item) {
    }

    private static ItemStack copyOrAir(@Nullable ItemStack item) {
        return item == null ? new ItemStack(Material.AIR) : item.clone();
    }

    /**
     * Paper API で再送した装備更新を、ProtocolLib の dispatch スレッドに依存せず一度だけ通過させます。
     */
    static final class EquipmentOverrideRegistry {
        private final Map<EquipmentOverrideKey, PendingEquipmentOverride> pendingOverrides = new ConcurrentHashMap<>();

        /**
         * 再送予定の全装備更新を登録します。同じ更新が連続しても件数を保持します。
         */
        void mark(
            @NotNull UUID viewerId,
            int entityId,
            @NotNull Map<EquipmentSlot, ItemStack> equipment,
            long nowMillis
        ) {
            discardExpired(nowMillis);
            for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
                EquipmentOverrideKey key = new EquipmentOverrideKey(viewerId, entityId, entry.getKey(), entry.getValue());
                pendingOverrides.compute(key, (ignored, pending) -> pending == null
                    ? new PendingEquipmentOverride(1, nowMillis + EQUIPMENT_OVERRIDE_TTL_MILLIS)
                    : pending.add(nowMillis + EQUIPMENT_OVERRIDE_TTL_MILLIS));
            }
        }

        /**
         * 対応する再送パケットなら登録を一回分だけ消費します。
         */
        synchronized boolean consume(@NotNull PacketContainer packet, @NotNull UUID viewerId, long nowMillis) {
            int entityId = packet.getIntegers().readSafely(0);
            List<Pair<EnumWrappers.ItemSlot, ItemStack>> equipment = packet.getSlotStackPairLists().readSafely(0);
            if (equipment == null || equipment.isEmpty()) {
                return false;
            }

            Map<EquipmentSlot, ItemStack> updates = new EnumMap<>(EquipmentSlot.class);
            for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipment) {
                updates.put(
                    toBukkitEquipmentSlot(pair.getFirst()),
                    pair.getSecond() == null ? new ItemStack(Material.AIR) : pair.getSecond()
                );
            }
            return consume(viewerId, entityId, updates, nowMillis);
        }

        /**
         * 対応する再送装備更新なら登録を一回分だけ消費します。
         */
        synchronized boolean consume(
            @NotNull UUID viewerId,
            int entityId,
            @NotNull Map<EquipmentSlot, ItemStack> equipment,
            long nowMillis
        ) {
            discardExpired(nowMillis);
            List<EquipmentOverrideKey> keys = new java.util.ArrayList<>(equipment.size());
            for (Map.Entry<EquipmentSlot, ItemStack> entry : equipment.entrySet()) {
                EquipmentOverrideKey key = new EquipmentOverrideKey(
                    viewerId,
                    entityId,
                    entry.getKey(),
                    entry.getValue() == null ? new ItemStack(Material.AIR) : entry.getValue()
                );
                if (!pendingOverrides.containsKey(key)) {
                    return false;
                }
                keys.add(key);
            }

            for (EquipmentOverrideKey key : keys) {
                pendingOverrides.computeIfPresent(key, (ignored, pending) -> pending.consume());
            }
            return true;
        }

        /**
         * viewer の logout 時に未消費の再送識別を破棄します。
         */
        void discardViewer(@NotNull UUID viewerId) {
            pendingOverrides.keySet().removeIf(key -> key.viewerId().equals(viewerId));
        }

        private void discardExpired(long nowMillis) {
            pendingOverrides.entrySet().removeIf(entry -> entry.getValue().expiresAtMillis() <= nowMillis);
        }
    }

    private record EquipmentOverrideKey(
        @NotNull UUID viewerId,
        int entityId,
        @NotNull EquipmentSlot slot,
        @NotNull ItemStack item
    ) {
        private EquipmentOverrideKey {
            item = item.clone();
        }
    }

    private record PendingEquipmentOverride(int count, long expiresAtMillis) {
        private PendingEquipmentOverride add(long expiresAtMillis) {
            return new PendingEquipmentOverride(count + 1, expiresAtMillis);
        }

        private PendingEquipmentOverride consume() {
            return count > 1 ? new PendingEquipmentOverride(count - 1, expiresAtMillis) : null;
        }
    }

    static boolean needsCustomModelDataUpdate(int desiredCustomModelData, @Nullable Integer currentCustomModelData) {
        return currentCustomModelData == null || currentCustomModelData != desiredCustomModelData;
    }

    /**
     * 送信 ItemStack から身体装備レイヤーを描画する component を除去します。
     *
     * @param item 送信専用の ItemStack コピー
     * @return component を除去した場合は {@code true}
     */
    static boolean removeEquippableComponent(@NotNull ItemStack item) {
        if (!item.hasData(DataComponentTypes.EQUIPPABLE)) {
            return false;
        }
        item.unsetData(DataComponentTypes.EQUIPPABLE);
        return true;
    }



    /**
     * Material 名を解決します。キャッシュにヒットしない場合のみ {@link Material#matchMaterial} を呼び出します。
     *
     * @param name Material 名（大文字）
     * @return 解決された Material。不正な名前なら {@code null}
     */
    private static Material resolveMaterial(@NotNull String name) {
        var upper = name.toUpperCase(Locale.ROOT);
        return MATERIAL_CACHE.computeIfAbsent(upper, k -> {
            var mat = MaterialNameResolver.match(k);
            if (mat == null) {
                Logger.log(LogId.W_5210, k);
            }
            return mat;
        });
    }

    // endregion
}
