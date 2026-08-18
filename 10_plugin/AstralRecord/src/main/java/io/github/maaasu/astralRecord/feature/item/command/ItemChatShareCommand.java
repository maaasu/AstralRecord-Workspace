package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemChatShareService;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/**
 * 所持中の AstralRecord アイテムを全体チャットへ共有する {@code /showitem} コマンド。
 */
public final class ItemChatShareCommand extends AstCommand {
    private final ItemChatShareService itemChatShareService;

    /**
     * ItemChatShareCommand を初期化します。
     *
     * @param itemChatShareService アイテム共有サービス
     */
    public ItemChatShareCommand(@NotNull ItemChatShareService itemChatShareService) {
        super("showitem", "所持品のアイテムを全体チャットに表示します。", "/showitem <itemName>", true);
        this.itemChatShareService = itemChatShareService;
    }

    /**
     * 指定表示名の所持アイテムを検索して全体チャットへ共有します。
     *
     * @param player 実行プレイヤー
     * @param args 表示名を構成する引数
     */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String itemName = String.join(" ", args).strip();
        var item = itemChatShareService.findShareableItem(
            player.getBukkit().getInventory().getContents(),
            itemName
        );
        if (item == null || !itemChatShareService.share(player.getBukkit(), item)) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5949);
        }
    }
}
