package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.inventory.model.InventoryType;
import io.github.maaasu.astralRecord.feature.inventory.service.InventoryService;
import io.github.maaasu.astralRecord.feature.item.model.ItemCategory;
import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.item.service.ItemService;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * /item コマンドを処理します。
 */
public class ItemCommand extends AstCommand {
    private final ItemService itemService;

    /**
     * ItemCommand を初期化します。
     *
     * @param itemService アイテムサービス
     */
    public ItemCommand(@NotNull ItemService itemService) {
        super("item", "アイテムを読み込み、取得します。", "/item [load|get] <itemId> [amount] [player]",
                false, UserPermission.ADMIN.getValue());
        this.itemService = itemService;
    }

    /**
     * アイテム管理コマンドを実行します。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数。対象プレイヤーは get の末尾へ指定できます
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length == 0) {
            AstPlayer player = getAstPlayer(sender);
            if (player == null) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
                return;
            }
            openAdminGui(player);
            return;
        }

        var action = args[0].toLowerCase(Locale.ROOT);
        if (action.equals("load")) {
            handleLoad(sender, args);
            return;
        }

        if (action.equals("get")) {
            handleGet(sender, args);
            return;
        }

        sendUsage(sender);
    }

    private void openAdminGui(@NotNull AstPlayer player) {
        var handler = AstralRecord.getInstance().getItemAdminGuiEventHandler();
        if (handler == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5063);
            return;
        }
        handler.open(player.getBukkit());
    }

    private void handleLoad(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(sender);
            return;
        }
        var loaded = itemService.loadItem(args[1]);
        if (loaded == null) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5201, args[1]);
            return;
        }

        PlayerMessageService.getInstance().send(
            sender,
            PlayerMsgId.P_5209,
            ItemCategory.displayNameJa(loaded.getCategory()),
            loaded.getId(),
            ColorCodeUtil.toLegacyText(loaded.getName(), loaded.getId())
        );
    }

    private void handleGet(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 2 || args.length > 4) {
            sendUsage(sender);
            return;
        }

        var itemId = args[1];
        int amount = 1;
        String targetName = null;
        if (args.length == 3) {
            Integer parsedAmount = parsePositiveInt(args[2]);
            if (parsedAmount != null) {
                amount = parsedAmount;
            } else {
                targetName = args[2];
            }
        } else if (args.length == 4) {
            Integer parsedAmount = parsePositiveInt(args[2]);
            if (parsedAmount == null) {
                sendUsage(sender);
                return;
            }
            amount = parsedAmount;
            targetName = args[3];
        }

        AstPlayer target = resolveTarget(sender, targetName);
        if (target == null) {
            return;
        }

        var model = itemService.findLoadedById(itemId);
        if (model == null) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5213, itemId);
            return;
        }

        var plugin = AstralRecord.getInstance();
        var inventoryService = plugin.getInventoryService();
        if (inventoryService == null) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5063);
            return;
        }

        var granted = inventoryService.addItemToNormalInventory(target, model, amount);

        if (granted <= 0) {
            PlayerMessageService.getInstance().send(sender, PlayerMsgId.P_5241);
            return;
        }

        InventoryType inventoryType = inventoryService.resolveInventoryType(model);
        if (inventoryType != InventoryType.CURRENCY) {
            inventoryService.applyInventoryToGui(target, inventoryType);
        }
        PlayerMessageService.getInstance().send(
            sender,
            PlayerMsgId.P_5240,
            ColorCodeUtil.toLegacyText(model.getName(), model.getId()),
            granted
        );
        if (sender != target.getBukkit()) {
            PlayerMessageService.getInstance().send(
                target,
                PlayerMsgId.P_5240,
                ColorCodeUtil.toLegacyText(model.getName(), model.getId()),
                granted
            );
        }
    }

    /**
     * 指定された対象プレイヤーを解決します。
     *
     * @param sender コマンド送信者
     * @param targetName 対象プレイヤー名。省略時は送信者自身
     * @return オンラインかつキャッシュ済みの対象プレイヤー。解決できない場合は {@code null}
     */
    private @Nullable AstPlayer resolveTarget(
        @NotNull CommandSender sender,
        @Nullable String targetName
    ) {
        if (targetName != null) {
            Player target = Bukkit.getPlayerExact(targetName);
            AstPlayer astPlayer = target == null ? null : AstPlayerCache.get(target);
            if (astPlayer == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), targetName));
            }
            return astPlayer;
        }

        AstPlayer astPlayer = getAstPlayer(sender);
        if (astPlayer != null) {
            return astPlayer;
        }
        if (sender instanceof Player player) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), player.getName()));
        } else {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
        }
        return null;
    }

    /**
     * 正の整数として数量を解釈します。
     *
     * @param value 数量候補
     * @return 正の整数。解釈できない場合は {@code null}
     */
    private @Nullable Integer parsePositiveInt(@NotNull String value) {
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
