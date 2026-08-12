package io.github.maaasu.astralRecord.feature.menu.service;

import io.github.maaasu.astralRecord.feature.inventory.model.InventoryInstanceType;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryEntryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryModel;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryProfile;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerInventoryState;
import io.github.maaasu.astralRecord.feature.item.model.EquipmentInstance;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;

/**
 * 通行証の所持状態をフラグとして、参加時のメニュー導線を初期付与します。
 */
public final class MenuToolJoinGrantService {
    public static final String MENU_ITEM_ID = "nox_menu_tool";
    public static final String PASS_ITEM_ID = "nox_city_pass";

    private static final String INSTANCE_SOURCE = "join_initial_menu";

    private final ItemService itemService;
    private final InventoryService inventoryService;

    /**
     * 参加時メニュー導線の付与サービスを生成します。
     *
     * @param itemService アイテムマスタ・装備インスタンスサービス
     * @param inventoryService インベントリ正本サービス
     */
    public MenuToolJoinGrantService(
        @NotNull ItemService itemService,
        @NotNull InventoryService inventoryService
    ) {
        this.itemService = itemService;
        this.inventoryService = inventoryService;
    }

    /**
     * 参加時インベントリのスナップショットを確認し、必要な外部装備インスタンスを非同期で準備します。
     * <p>
     * 装備インスタンス生成は API I/O を伴うため、Bukkit メインスレッドから呼び出してはいけません。
     * 通行証が既にあれば {@code null} を返し、外部状態を変更しません。
     *
     * @param state 参加時に読み込んだアカウント単位のインベントリ状態
     * @return メインスレッドで適用する準備済み付与情報。不要なら {@code null}
     * @throws IllegalStateException 必要なマスターデータまたは装備インスタンス生成が不正な場合
     */
    public @Nullable PreparedGrant prepareIfMissing(@NotNull PlayerInventoryState state) {
        UUID accountId = state.getAccountId();
        if (hasPass(state)) {
            return null;
        }

        ItemModel menuItem = requireItem(MENU_ITEM_ID, ItemCategory.EQUIPMENT);
        ItemModel passItem = requireItem(PASS_ITEM_ID, ItemCategory.CURRENCY);
        EquipmentInstance menuInstance = itemService.createEquipmentInstance(
            menuItem.getId(),
            accountId.toString(),
            INSTANCE_SOURCE,
            accountId.toString()
        );
        if (menuInstance == null) {
            throw new IllegalStateException("failed to create initial menu equipment: " + MENU_ITEM_ID);
        }

        UUID instanceId = parseInstanceId(menuInstance.getEquipmentInstanceId());
        if (instanceId == null) {
            deleteUnreferencedInstance(menuInstance);
            throw new IllegalStateException("invalid initial menu equipment instance id: " + MENU_ITEM_ID);
        }

        return new PreparedGrant(
            accountId,
            menuItem,
            passItem,
            new InventoryService.PreparedInventoryInstance(InventoryInstanceType.EQUIPMENT, instanceId),
            menuInstance.getEquipmentInstanceId()
        );
    }

    /**
     * 準備済みのメニューアイテムと通行証を、メインスレッド上のローカル状態へ適用します。
     * <p>
     * 適用直前に通行証を再確認するため、参加データの準備後に別処理で通行証が追加された場合は
     * 付与せず {@code false} を返します。呼び出し側はその場合も準備済み装備を破棄してください。
     *
     * @param astPlayer 参加反映済みプレイヤー
     * @param preparedGrant 非同期で準備した付与情報
     * @return この呼び出しで付与した場合 {@code true}、既に通行証があった場合 {@code false}
     * @throws IllegalArgumentException アカウントが準備対象と異なる場合
     * @throws IllegalStateException ローカル付与に失敗した場合
     */
    public boolean grantPreparedIfMissing(
        @NotNull AstPlayer astPlayer,
        @NotNull PreparedGrant preparedGrant
    ) {
        UUID accountId = astPlayer.getAccount().getUuid();
        if (!accountId.equals(preparedGrant.accountId())) {
            throw new IllegalArgumentException("prepared menu grant account does not match player account");
        }
        if (inventoryService.getCurrencyAmount(accountId, PASS_ITEM_ID) > 0L) {
            return false;
        }

        InventoryService.InventoryGrantReceipt receipt = inventoryService.addPreparedRewardsToNormalInventory(
            astPlayer,
            List.of(
                new InventoryService.PreparedInventoryReward(
                    preparedGrant.menuItem(),
                    1,
                    List.of(preparedGrant.menuInstance())
                ),
                new InventoryService.PreparedInventoryReward(preparedGrant.passItem(), 1, List.of())
            )
        );
        if (receipt == null) {
            throw new IllegalStateException("failed to add initial menu rewards: " + accountId);
        }
        return true;
    }

    /**
     * 付与前に作成したがインベントリへ登録されなかった装備インスタンスを削除します。
     * <p>
     * API I/O を伴うため、Bukkit メインスレッド外から呼び出してください。
     *
     * @param preparedGrant 破棄対象の準備済み付与情報
     */
    public void cleanupPreparedGrant(@NotNull PreparedGrant preparedGrant) {
        itemService.deleteEquipmentInstance(preparedGrant.externalInstanceId());
    }

    private boolean hasPass(@NotNull PlayerInventoryState state) {
        InventoryModel currencyInventory = state.findInventory(InventoryProfile.GAME, InventoryType.CURRENCY);
        if (currencyInventory == null || !currencyInventory.isEnabled() || currencyInventory.isDeleted()) {
            return false;
        }
        for (InventoryEntryModel entry : state.snapshotEntries(currencyInventory.getInventoryId())) {
            if (!entry.isDeleted()
                && entry.getQuantity() > 0L
                && PASS_ITEM_ID.equalsIgnoreCase(entry.getItemId())) {
                return true;
            }
        }
        return false;
    }

    private @NotNull ItemModel requireItem(@NotNull String itemId, @NotNull ItemCategory expectedCategory) {
        ItemModel model = itemService.findLoadedById(itemId);
        if (model == null) {
            throw new IllegalStateException("missing initial menu item master: " + itemId);
        }
        if (ItemCategory.fromApiValue(model.getCategory()) != expectedCategory) {
            throw new IllegalStateException("unexpected initial menu item category: " + itemId);
        }
        return model;
    }

    private UUID parseInstanceId(@NotNull String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    private void deleteUnreferencedInstance(@NotNull EquipmentInstance instance) {
        itemService.deleteEquipmentInstance(instance.getEquipmentInstanceId());
    }

    /**
     * 非同期に準備し、メインスレッドで適用する参加時付与情報です。
     *
     * @param accountId 対象アカウント ID
     * @param menuItem メニューアイテム定義
     * @param passItem 通行証定義
     * @param menuInstance インベントリへ登録する装備インスタンス参照
     * @param externalInstanceId 破棄時に API へ渡す装備インスタンス ID
     */
    public record PreparedGrant(
        @NotNull UUID accountId,
        @NotNull ItemModel menuItem,
        @NotNull ItemModel passItem,
        @NotNull InventoryService.PreparedInventoryInstance menuInstance,
        @NotNull String externalInstanceId
    ) {
    }
}
