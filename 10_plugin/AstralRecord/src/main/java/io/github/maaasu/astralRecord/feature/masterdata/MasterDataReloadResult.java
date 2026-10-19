package io.github.maaasu.astralRecord.feature.masterdata;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * マスタ公開完了時の件数と、公開後の実行時反映に失敗した対象です。
 * 部分的な反映失敗は再読込全体の例外や自動再試行を発生させません。
 *
 * @param loadedCount 公開したマスタの件数
 * @param failedActivations 試行順の反映失敗対象
 */
public record MasterDataReloadResult(int loadedCount, @NotNull List<ActivationTarget> failedActivations) {
    /**
     * 完了結果を作成し、呼び出し側による失敗一覧の変更を防ぎます。
     *
     * @param loadedCount 公開したマスタの件数
     * @param failedActivations 試行順の反映失敗対象。null は許可しません
     * @throws NullPointerException 失敗一覧またはその要素がnullの場合
     */
    public MasterDataReloadResult {
        failedActivations = List.copyOf(failedActivations);
    }

    /** 公開後に実行する反映処理の対象です。 */
    public enum ActivationTarget {
        PET("pet", PlayerMsgId.P_5090),
        NPC("npc", PlayerMsgId.P_5091),
        GATHERING("gathering", PlayerMsgId.P_5092),
        WORLD("world", PlayerMsgId.P_5093);

        private final String logName;
        private final PlayerMsgId messageId;

        ActivationTarget(String logName, PlayerMsgId messageId) {
            this.logName = logName;
            this.messageId = messageId;
        }

        /** @return 既存の警告ログで使う対象名 */
        public String logName() {
            return logName;
        }

        /** @return 管理者通知で使う対象表示名のメッセージID */
        public PlayerMsgId messageId() {
            return messageId;
        }
    }
}
