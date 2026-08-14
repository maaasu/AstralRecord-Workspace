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
import io.github.maaasu.astralRecord.infrastructure.util.MaterialNameResolver;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.github.maaasu.astralRecord.infrastructure.util.CustomModelDataComponentUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
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
 *   <li>{@code ENTITY_EQUIPMENT} — 他エンティティの手持ち・装備更新</li>
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

    private final Plugin plugin;
    private final PlayerSettingService playerSettingService;
    private final EquipmentOverrideRegistry equipmentOverrideRegistry = new EquipmentOverrideRegistry();
    private boolean registered = false;

    /**
     * アダプタを初期化します。
     *
     * @param plugin プラグインインスタンス
     * @param playerSettingService 受信者ごとの防具表示設定を参照するサービス
     */
    public ItemStackPacketAdapter(
        @NotNull Plugin plugin,
        @NotNull PlayerSettingService playerSettingService
    ) {
        this.plugin = plugin;
        this.playerSettingService = playerSettingService;
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
                if (type == PacketType.Play.Server.ENTITY_EQUIPMENT
                    && equipmentOverrideRegistry.consume(packet, event.getPlayer().getUniqueId(), System.currentTimeMillis())) {
                    return;
                }
                boolean armorDisplayEnabled = playerSettingService.isArmorDisplayEnabled(
                    event.getPlayer().getUniqueId()
                );

                if (type == PacketType.Play.Server.SET_SLOT) {
                    handleSetSlot(packet, armorDisplayEnabled);
                } else if (type == PacketType.Play.Server.WINDOW_ITEMS) {
                    handleWindowItems(packet, armorDisplayEnabled);
                } else if (type == PacketType.Play.Server.ENTITY_EQUIPMENT) {
                    handleEntityEquipment(event, armorDisplayEnabled);
                }
            }
        });
        plugin.getServer().getPluginManager().registerEvents(new Listener() {
            @EventHandler
            public void onPlayerQuit(@NotNull PlayerQuitEvent event) {
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
     */
    private void handleSetSlot(@NotNull PacketContainer packet, boolean armorDisplayEnabled) {
        var original = packet.getItemModifier().readSafely(0);
        if (original == null || original.getType() == Material.AIR) {
            return;
        }

        var replaced = replaceIcon(original, armorDisplayEnabled);
        if (replaced != null) {
            packet.getItemModifier().writeSafely(0, replaced);
        }
    }

    /**
     * WINDOW_ITEMS パケット内の ItemStack リストを書き換えます。
     *
     * @param packet 書き換え対象パケット
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     */
    private void handleWindowItems(@NotNull PacketContainer packet, boolean armorDisplayEnabled) {
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

            var replaced = replaceIcon(original, armorDisplayEnabled);
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
     *
     * @param event 中止対象のパケットイベント
     * @param armorDisplayEnabled 受信者が防具の身体描画を表示する場合は {@code true}
     */
    private void handleEntityEquipment(@NotNull PacketEvent event, boolean armorDisplayEnabled) {
        PacketContainer packet = event.getPacket();
        List<Pair<EnumWrappers.ItemSlot, ItemStack>> equipment = packet.getSlotStackPairLists().readSafely(0);
        if (equipment == null || equipment.isEmpty()) {
            return;
        }

        List<EquipmentUpdate> updates = new java.util.ArrayList<>(equipment.size());
        boolean requiresOverride = false;
        for (Pair<EnumWrappers.ItemSlot, ItemStack> pair : equipment) {
            ItemStack original = pair.getSecond();
            if (original != null && original.getType() != Material.AIR
                && replaceIcon(original, armorDisplayEnabled) != null) {
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

        int entityId = packet.getIntegers().readSafely(0);
        Player viewer = event.getPlayer();
        PacketContainer originalPacket = packet.deepClone();
        event.setCancelled(true);
        plugin.getServer().getScheduler().runTask(
            plugin,
            () -> sendEquipmentOverride(viewer, entityId, updates, originalPacket)
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
        var iconName = ItemStackFactory.getIconName(original);
        var customModelData = ItemStackFactory.getCustomModelData(original);
        var appearanceColor = ItemStackFactory.getAppearanceColor(original);
        var potionType = ItemStackFactory.getPotionType(original);

        if (iconName == null && customModelData == null && appearanceColor == null && potionType == null) {
            return null;
        }

        ItemStack replaced = original.clone();
        boolean modified = false;

        if (iconName != null) {
            var iconMaterial = resolveMaterial(iconName);
            if (iconMaterial != null && iconMaterial != original.getType()) {
                replaced = original.withType(iconMaterial);
                modified = true;
            }
        }

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
     * 指定プレイヤーの inventory と、そのプレイヤーが追跡中の全プレイヤーの防具表示を再送します。
     *
     * <p>Bukkit メインスレッドから呼び出してください。設定変更時とログイン設定ロード完了時に、
     * 次の通常装備更新を待たず表示状態を即時反映します。</p>
     *
     * @param viewer 表示設定を反映する受信プレイヤー
     */
    public void refreshEquipmentView(@NotNull Player viewer) {
        if (!viewer.isOnline()) {
            return;
        }
        viewer.updateInventory();
        for (Player target : plugin.getServer().getOnlinePlayers()) {
            if (target != viewer && !target.isTrackedBy(viewer)) {
                continue;
            }
            sendEquipmentOverride(viewer, target, List.of(
                new EquipmentUpdate(EquipmentSlot.HEAD, copyOrAir(target.getInventory().getHelmet())),
                new EquipmentUpdate(EquipmentSlot.CHEST, copyOrAir(target.getInventory().getChestplate())),
                new EquipmentUpdate(EquipmentSlot.LEGS, copyOrAir(target.getInventory().getLeggings())),
                new EquipmentUpdate(EquipmentSlot.FEET, copyOrAir(target.getInventory().getBoots()))
            ));
        }
    }

    /**
     * ProtocolLib から中止した装備更新を、Paper API 経由で受信者専用の表示として送信します。
     *
     * @param viewer 装備表示を受信するプレイヤー
     * @param entityId 元パケットのエンティティ ID
     * @param updates 元パケットから退避した装備更新
     * @param originalPacket 解決失敗時にフィルタを通さず再送する元パケット
     */
    private void sendEquipmentOverride(
        @NotNull Player viewer,
        int entityId,
        @NotNull List<EquipmentUpdate> updates,
        @NotNull PacketContainer originalPacket
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

        sendEquipmentOverride(viewer, target, updates);
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

        boolean armorDisplayEnabled = playerSettingService.isArmorDisplayEnabled(viewer.getUniqueId());
        Map<EquipmentSlot, ItemStack> equipment = new EnumMap<>(EquipmentSlot.class);
        for (EquipmentUpdate update : updates) {
            ItemStack replaced = replaceIcon(update.item(), armorDisplayEnabled);
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
