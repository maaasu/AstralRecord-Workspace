package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

public class AccountModeCommand extends AstCommand {

    public AccountModeCommand() {
        super("accountmode", "Set account mode.",
                "/account mode <mode> [<player|accountUuid>]", false, 99);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || astPlayer.getUser().getPermission() < UserPermission.ADMIN.getValue()) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }

        if (args.length < 1) {
            sendUsage(sender);
            return;
        }

        var accountService = AstralRecord.getInstance().getAccountService();
        var userService = AstralRecord.getInstance().getUserService();
        if (accountService == null || userService == null) {
            sendError(sender, "AccountService/UserService が初期化されていません。");
            return;
        }

        AccountMode mode = AccountMode.Companion.parse(args[0]);
        if (mode == null) {
            sendError(sender, "mode は名称（PLAYER/BUILDER/ADMIN）または 0, 1, 2 のいずれかで指定してください。");
            return;
        }

        UUID accountUuid = resolveTargetAccountUuid(sender, args);
        if (accountUuid == null) {
            return;
        }

        AccountModel updated = accountService.setMode(accountUuid, mode, getUpdatedBy(sender));
        applyOnlineAccountMode(updated);
        sendSuccess(sender, "account mode を更新しました: " + updated.getAccountName() + " = " + updated.getMode().name());
    }

    /**
     * 対象アカウント UUID を解決します。
     * <p>
     * プレイヤー指定が省略された場合、送信者がプレイヤーであれば自身の選択中アカウントを対象にします。
     * コンソール実行で省略された場合はエラーメッセージを送信して `null` を返します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 対象アカウント UUID。解決できない場合は `null`
     */
    @Nullable
    private UUID resolveTargetAccountUuid(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            if (sender instanceof Player player) {
                var astPlayer = AstPlayerCache.get(player);
                if (astPlayer == null) {
                    sendError(sender, "対象アカウントが見つかりません: " + player.getName());
                    return null;
                }
                return astPlayer.getAccount().getUuid();
            }
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
            return null;
        }

        UUID resolved = resolveAccountUuid(args[1]);
        if (resolved == null) {
            sendError(sender, "対象アカウントが見つかりません: " + args[1]);
        }
        return resolved;
    }

    private UUID resolveAccountUuid(@NotNull String value) {
        Player player = Bukkit.getPlayerExact(value);
        if (player != null) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer != null) {
                return astPlayer.getAccount().getUuid();
            }
        }

        try {
            UUID uuid = UUID.fromString(value);
            var accountService = AstralRecord.getInstance().getAccountService();
            AccountModel account = accountService == null ? null : accountService.getAccount(uuid);
            if (account != null) {
                return account.getUuid();
            }

            var userService = AstralRecord.getInstance().getUserService();
            var user = userService == null ? null : userService.getUser(uuid);
            if (user != null) {
                AccountModel selected = accountService.getSelectedAccount(user.getUuid(), user.getAccountId());
                return selected == null ? null : selected.getUuid();
            }
        } catch (IllegalArgumentException ignored) {
            return null;
        }
        return null;
    }

    private void applyOnlineAccountMode(@NotNull AccountModel updated) {
        for (var astPlayer : AstPlayerCache.getAll()) {
            if (!astPlayer.getAccount().getUuid().equals(updated.getUuid())) {
                continue;
            }
            var previousMode = astPlayer.getAccount().getMode();
            var inventoryService = AstralRecord.getInstance().getInventoryService();
            if (inventoryService != null && previousMode == AccountMode.BUILDER && updated.getMode() != AccountMode.BUILDER) {
                inventoryService.saveBuilderInventorySnapshot(astPlayer);
            }
            astPlayer.applyAccountMode(updated);
            if (inventoryService == null) {
                continue;
            }
            if (updated.getMode().shouldReflectInventoryToGui()) {
                inventoryService.applyInventoriesToGui(astPlayer);
            } else if (updated.getMode() == AccountMode.BUILDER) {
                inventoryService.applyBuilderInventoryToGui(astPlayer);
            } else {
                inventoryService.clearGuiInventory(astPlayer);
            }
        }
    }

    private UUID getUpdatedBy(@NotNull CommandSender sender) {
        if (sender instanceof Player player) {
            return player.getUniqueId();
        }
        return SystemUser.INSTANCE.getUuid();
    }
}
