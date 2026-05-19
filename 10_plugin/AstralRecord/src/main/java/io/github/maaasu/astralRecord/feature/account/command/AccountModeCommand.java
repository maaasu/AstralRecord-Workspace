package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.AccountMode;
import io.github.maaasu.astralRecord.feature.account.model.AccountModel;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.user.model.SystemUser;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public class AccountModeCommand extends AstCommand {

    public AccountModeCommand() {
        super("accountmode", "Set account mode.", "/account mode <player|accountUuid> <0|1|2>", false, 99);
    }

    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || astPlayer.getUser().getPermission() < 99) {
                sendError(sender, "このコマンドを実行する権限がありません。");
                return;
            }
        }

        if (args.length < 2) {
            sendUsage(sender);
            return;
        }

        var accountService = AstralRecord.getInstance().getAccountService();
        var userService = AstralRecord.getInstance().getUserService();
        if (accountService == null || userService == null) {
            sendError(sender, "AccountService/UserService が初期化されていません。");
            return;
        }

        AccountMode mode = parseMode(args[1]);
        if (mode == null) {
            sendError(sender, "mode は 0, 1, 2 のいずれかで指定してください。");
            return;
        }

        UUID accountUuid = resolveAccountUuid(args[0]);
        if (accountUuid == null) {
            sendError(sender, "対象アカウントが見つかりません: " + args[0]);
            return;
        }

        AccountModel updated = accountService.setMode(accountUuid, mode, getUpdatedBy(sender));
        applyOnlineAccountMode(updated);
        sendSuccess(sender, "account mode を更新しました: " + updated.getAccountName() + " = " + updated.getMode().getValue());
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

    private AccountMode parseMode(@NotNull String value) {
        try {
            int parsed = Integer.parseInt(value);
            if (parsed < 0 || parsed > 2) {
                return null;
            }
            return AccountMode.Companion.fromValue((byte) parsed);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void applyOnlineAccountMode(@NotNull AccountModel updated) {
        for (var astPlayer : AstPlayerCache.getAll()) {
            if (!astPlayer.getAccount().getUuid().equals(updated.getUuid())) {
                continue;
            }
            astPlayer.applyAccountMode(updated);
            var inventoryService = AstralRecord.getInstance().getInventoryService();
            if (inventoryService != null && updated.getMode().shouldReflectInventoryToGui()) {
                inventoryService.applyInventoriesToGui(astPlayer);
            } else if (inventoryService != null) {
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
