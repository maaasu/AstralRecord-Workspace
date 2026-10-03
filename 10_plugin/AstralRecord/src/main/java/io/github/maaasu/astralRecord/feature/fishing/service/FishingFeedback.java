package io.github.maaasu.astralRecord.feature.fishing.service;

import io.github.maaasu.astralRecord.feature.fishing.message.FishingMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;

/** 釣り表示をplayer.propertiesと共通通知サービスへ接続します。 */
public final class FishingFeedback {
    private FishingFeedback() {
    }

    /**
     * 共通システムタグ付きで釣りの状態を通知します。
     * @param player 送信先
     * @param id 表示用途
     * @param args 文言の置換引数
     */
    public static void notify(AstPlayer player, FishingMsgId id, Object... args) {
        PlayerMessageService.getInstance().send(player, id.message(), args);
    }

    /**
     * ボスバー用の整形済み文字列を返します。
     * @param id 表示用途
     * @param args 文言の置換引数
     * @return 共通色コード正規化を通した表示文字列
     */
    public static String text(FishingMsgId id, Object... args) {
        return PlayerMsgResource.format(id.message().getId(), args);
    }
}
