package io.github.maaasu.astralRecord.feature.masterdata;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import org.bukkit.command.CommandSender;
import org.jetbrains.annotations.NotNull;

import java.util.stream.Collectors;

/** マスタ再読込の完了結果を管理者向けに通知します。 */
public final class MasterDataReloadNotification {
    private MasterDataReloadNotification() {
    }

    /**
     * 手動再読込の実行者へ通常完了または部分反映失敗を通知します。
     * メインスレッドから呼び出してください。
     *
     * @param messages 共通メッセージサービス
     * @param sender 現在の送信先
     * @param result 公開済みの再読込結果
     */
    public static void sendCompletion(
        @NotNull PlayerMessageService messages,
        @NotNull CommandSender sender,
        @NotNull MasterDataReloadResult result
    ) {
        if (result.failedActivations().isEmpty()) {
            messages.send(sender, PlayerMsgId.P_5097, result.loadedCount());
        } else {
            messages.send(sender, PlayerMsgId.P_5099, result.loadedCount(), failedTargets(result));
        }
    }

    /**
     * 自動再読込の部分反映失敗だけをオンライン管理者へ一度通知します。
     * メインスレッドから呼び出してください。正常完了時は何も送信しません。
     *
     * @param messages 共通メッセージサービス
     * @param result 公開済みの再読込結果
     */
    public static void notifyAdminsIfPartial(
        @NotNull PlayerMessageService messages,
        @NotNull MasterDataReloadResult result
    ) {
        if (result.failedActivations().isEmpty()) {
            return;
        }
        String targets = failedTargets(result);
        for (var player : AstPlayerCache.getAll()) {
            if (player.hasAdminPermission() && player.getBukkit().isOnline()) {
                messages.send(player, PlayerMsgId.P_5099, result.loadedCount(), targets);
            }
        }
    }

    /**
     * @param result 公開済みの再読込結果
     * @return 失敗した対象の日本語表示名を試行順にまとめた文字列
     */
    private static String failedTargets(MasterDataReloadResult result) {
        return result.failedActivations().stream()
            .map(target -> PlayerMsgResource.getMessage(target.messageId().getId()))
            .collect(Collectors.joining("、"));
    }
}
