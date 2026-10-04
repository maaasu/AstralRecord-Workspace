package io.github.maaasu.astralRecord.feature.item.service;

import io.github.maaasu.astralRecord.feature.item.model.ItemModel;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** 素材不足の通知に、各アイテムの通常ツールチップを付けます。 */
public final class MissingMaterialMessage {
    private MissingMaterialMessage() { }

    /**
     * 不足素材をシステムメッセージとして送信します。
     *
     * @param player 送信先
     * @param header 操作ごとの素材不足メッセージ
     * @param missingItems 不足数と解決済みマスタ。未解決マスタは汎用名で表示
     * @param factory アイテム表示用ファクトリ
     * @implNote オンラインプレイヤーへ送信する。アイテムと所持状態は変更しない
     */
    public static void send(
        @NotNull Player player,
        @NotNull PlayerMsgId header,
        @NotNull List<MissingItem> missingItems,
        @NotNull ItemStackFactory factory
    ) {
        PlayerMessageService messages = PlayerMessageService.getInstance();
        messages.send(player, header);
        for (MissingItem missing : missingItems) {
            ItemModel model = missing.model();
            String name = model == null ? "未登録の素材" : ColorCodeUtil.toLegacyText(model.getName(), "未登録の素材");
            var line = PlayerMsgResource.formatPlainComponent(PlayerMsgId.P_5254.getId(), name, missing.amount());
            if (model != null) {
                line = line.hoverEvent(factory.createDisplay(model, 1).asHoverEvent());
            }
            messages.sendComponent(player, line);
        }
    }

    /** @param model 素材マスタ。解決できない場合は null @param amount 不足数量 */
    public record MissingItem(@Nullable ItemModel model, long amount) { }
}
