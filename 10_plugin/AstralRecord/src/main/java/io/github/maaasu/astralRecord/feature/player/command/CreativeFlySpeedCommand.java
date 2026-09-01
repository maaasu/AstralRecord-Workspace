package io.github.maaasu.astralRecord.feature.player.command;

import io.github.maaasu.astralRecord.feature.account.service.AccountDisplayNameFormatter;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.math.BigDecimal;

/**
 * プレイヤーのクリエイティブ飛行速度をパーセンテージで設定する管理者コマンドです。
 */
public final class CreativeFlySpeedCommand extends AstCommand {
    private static final double MIN_PERCENTAGE = 0.0D;
    private static final double MAX_PERCENTAGE = 1_000.0D;
    private static final double CREATIVE_DEFAULT_SPEED = 0.1D;
    private static final double CREATIVE_DEFAULT_PERCENTAGE = 100.0D;

    /**
     * クリエイティブ飛行速度コマンドを初期化します。
     */
    public CreativeFlySpeedCommand() {
        super(
                "flyspeed",
                "クリエイティブ飛行速度を設定します。",
                "/flyspeed <percentage> [player]",
                false,
                UserPermission.ADMIN.getValue()
        );
    }

    /**
     * 指定された対象プレイヤーへクリエイティブ飛行速度を設定します。
     *
     * @param sender コマンド送信者
     * @param args パーセンテージと任意の対象プレイヤー名
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        if (args.length < 1 || args.length > 2) {
            sendUsage(sender);
            return;
        }

        Double percentage = parsePercentage(sender, args[0]);
        if (percentage == null) {
            return;
        }

        Player target = resolveTarget(sender, args.length == 2 ? args[1] : null);
        if (target == null) {
            return;
        }

        target.setFlySpeed(toFlySpeed(percentage));
        AstPlayer targetAstPlayer = AstPlayerCache.get(target);
        String targetDisplayName = targetAstPlayer == null
                ? target.getName()
                : AccountDisplayNameFormatter.toLegacy(targetAstPlayer.getAccount());
        sendSuccess(
                sender,
                PlayerMsgResource.format(
                        PlayerMsgId.P_6920.getId(),
                        targetDisplayName,
                        formatPercentage(percentage)
                )
        );
    }

    /**
     * パーセンテージ入力を検証します。
     *
     * @param sender エラーメッセージの送信先
     * @param value 入力されたパーセンテージ
     * @return 0〜1000 の有限な数値。入力が不正な場合は {@code null}
     */
    private @Nullable Double parsePercentage(@NotNull CommandSender sender, @NotNull String value) {
        final double percentage;
        try {
            percentage = Double.parseDouble(value);
        } catch (NumberFormatException exception) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_6922.getId()));
            return null;
        }

        if (!Double.isFinite(percentage)
                || percentage < MIN_PERCENTAGE
                || percentage > MAX_PERCENTAGE) {
            sendError(
                    sender,
                    PlayerMsgResource.format(
                            PlayerMsgId.P_6921.getId(),
                            formatPercentage(MIN_PERCENTAGE),
                            formatPercentage(MAX_PERCENTAGE)
                    )
            );
            return null;
        }
        return percentage;
    }

    /**
     * 対象プレイヤーを解決します。
     *
     * @param sender コマンド送信者
     * @param targetName 対象プレイヤー名。省略時は {@code null}
     * @return 対象プレイヤー。解決できない場合は {@code null}
     */
    private @Nullable Player resolveTarget(@NotNull CommandSender sender, @Nullable String targetName) {
        if (targetName != null) {
            Player target = Bukkit.getPlayerExact(targetName);
            if (target == null) {
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5814.getId(), targetName));
            }
            return target;
        }

        if (sender instanceof Player player) {
            return player;
        }

        sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5305.getId()));
        return null;
    }

    /**
     * パーセンテージを Bukkit の飛行速度へ変換します。
     * クリエイティブの標準速度 0.1 を 100% として線形変換します。
     *
     * @param percentage パーセンテージ
     * @return {@link Player#setFlySpeed(float)} に渡す飛行速度
     */
    static float toFlySpeed(double percentage) {
        return (float) (percentage / CREATIVE_DEFAULT_PERCENTAGE * CREATIVE_DEFAULT_SPEED);
    }

    /**
     * パーセンテージをメッセージ表示用の小数文字列へ変換します。
     *
     * @param percentage パーセンテージ
     * @return 末尾の不要な 0 を除いた小数文字列
     */
    private @NotNull String formatPercentage(double percentage) {
        return BigDecimal.valueOf(percentage).stripTrailingZeros().toPlainString();
    }
}
