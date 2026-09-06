package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.feature.world.service.AdminWorldTeleportItemService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /adminitem} コマンドを処理します。
 */
public final class AdminWorldTeleportItemCommand extends AstCommand {
    private final AdminWorldTeleportItemService itemService;

    /**
     * 管理者用ワールドテレポートアイテム取得コマンドを初期化します。
     *
     * @param itemService 管理者用アイテムサービス
     */
    public AdminWorldTeleportItemCommand(@NotNull AdminWorldTeleportItemService itemService) {
        super(
                "adminitem",
                "管理者用ワールドテレポートアイテムを取得します。",
                "/adminitem",
                true,
                UserPermission.ADMIN.getValue()
        );
        this.itemService = itemService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 0) {
            sendUsage(player.getBukkit());
            return;
        }

        ItemStack item = itemService.createItem();
        if (!player.getBukkit().getInventory().addItem(item).isEmpty()) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5241);
            return;
        }
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7160);
    }
}
