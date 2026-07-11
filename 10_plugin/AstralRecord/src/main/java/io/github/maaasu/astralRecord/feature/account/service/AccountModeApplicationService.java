package io.github.maaasu.astralRecord.feature.account.service;

import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/** アカウントモードの永続化とオンラインプレイヤーへの反映を一括で行います。 */
public final class AccountModeApplicationService {
    private final AccountService accountService;
    private final InventoryService inventoryService;

    public AccountModeApplicationService(@NotNull AccountService accountService, @NotNull InventoryService inventoryService) {
        this.accountService = accountService;
        this.inventoryService = inventoryService;
    }

    /**
     * アカウントモードを更新し、オンライン中の対象アカウントへ反映します。
     *
     * @param accountUuid 更新対象アカウント UUID
     * @param mode 更新後のアカウントモード
     * @param updatedBy 更新者 UUID
     * @return 更新後のアカウント
     */
    public @NotNull AccountModel changeMode(@NotNull UUID accountUuid, @NotNull AccountMode mode, @NotNull UUID updatedBy) {
        AccountModel updated = accountService.setMode(accountUuid, mode, updatedBy);
        for (var astPlayer : AstPlayerCache.getAll()) {
            if (!astPlayer.getAccount().getUuid().equals(updated.getUuid())) {
                continue;
            }
            var previousMode = astPlayer.getAccount().getMode();
            if (isToolInventoryMode(previousMode) && previousMode != updated.getMode()) {
                inventoryService.saveToolInventorySnapshot(astPlayer);
            }
            astPlayer.applyAccountMode(updated);
            if (updated.getMode().shouldReflectInventoryToGui()) {
                if (isToolInventoryMode(previousMode) && previousMode != updated.getMode()) {
                    inventoryService.applyInventoriesToGuiForModeSwitch(astPlayer);
                } else {
                    inventoryService.applyInventoriesToGui(astPlayer);
                }
            } else if (isToolInventoryMode(updated.getMode())) {
                inventoryService.applyToolInventoryToGui(astPlayer);
            } else {
                inventoryService.clearGuiInventory(astPlayer);
            }
        }
        return updated;
    }

    private boolean isToolInventoryMode(@NotNull AccountMode mode) {
        return mode == AccountMode.BUILDER || mode == AccountMode.ADMIN;
    }
}
