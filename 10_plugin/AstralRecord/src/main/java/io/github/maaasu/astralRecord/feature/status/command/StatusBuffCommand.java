package io.github.maaasu.astralRecord.feature.status.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Locale;

/**
 * 管理者がオンラインプレイヤーへ一時的なステータスバフを付与するコマンドです。
 */
public final class StatusBuffCommand extends AstCommand {

    private static final long MAX_DURATION_SECONDS = Integer.MAX_VALUE / 20L;

    /**
     * StatusBuffCommand を初期化します。
     */
    public StatusBuffCommand() {
        super(
            "statusbuff",
            "一時的なステータスバフを付与します。",
            "/statusbuff <statusId> <value> <durationSeconds> [player]",
            true,
            UserPermission.ADMIN.getValue()
        );
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 3 || args.length > 4) {
            sendUsage(player.getBukkit());
            return;
        }

        StatusType statusType = resolveStatusType(args[0]);
        if (statusType == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5110, args[0]);
            return;
        }

        Double value = parsePositiveFiniteDouble(args[1]);
        Long durationSeconds = parseDurationSeconds(args[2]);
        if (value == null || durationSeconds == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5111, MAX_DURATION_SECONDS);
            return;
        }

        AstPlayer target = resolveTarget(player, args);
        if (target == null) {
            return;
        }

        StatusService statusService = resolveStatusService();
        if (statusService == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5063);
            return;
        }

        statusService.applyTemporaryFlatBuff(target, statusType, value, durationSeconds);
        String formattedValue = statusType.formatSignedValue(value);
        PlayerMessageService messageService = PlayerMessageService.getInstance();
        messageService.send(
            player,
            PlayerMsgId.P_5109,
            target.getBukkit().getName(),
            statusType.getDisplayName(),
            formattedValue,
            durationSeconds
        );
        if (target != player) {
            messageService.send(
                target,
                PlayerMsgId.P_5113,
                statusType.getDisplayName(),
                formattedValue,
                durationSeconds
            );
        }
    }

    /**
     * コマンド入力のステータスIDを共有カタログのステータス種別へ変換します。
     *
     * @param rawStatusId 入力された英語ID
     * @return 対応するステータス種別。未定義の場合は {@code null}
     */
    private @Nullable StatusType resolveStatusType(@NotNull String rawStatusId) {
        String normalized = rawStatusId.trim().replace('-', '_').toUpperCase(Locale.ROOT);
        return StatusType.fromId(normalized);
    }

    /**
     * 正の有限値として上昇値を読み取ります。
     *
     * @param rawValue 入力値
     * @return 読み取れた値。条件を満たさない場合は {@code null}
     */
    private @Nullable Double parsePositiveFiniteDouble(@NotNull String rawValue) {
        try {
            double parsed = Double.parseDouble(rawValue);
            return Double.isFinite(parsed) && parsed > 0.0D ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * 有効範囲内の持続秒数を読み取ります。
     *
     * @param rawDurationSeconds 入力された持続秒数
     * @return 読み取れた秒数。条件を満たさない場合は {@code null}
     */
    private @Nullable Long parseDurationSeconds(@NotNull String rawDurationSeconds) {
        try {
            long parsed = Long.parseLong(rawDurationSeconds);
            return parsed > 0L && parsed <= MAX_DURATION_SECONDS ? parsed : null;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /**
     * コマンド引数の末尾から対象プレイヤーを解決します。
     *
     * @param executor コマンド実行者
     * @param args     コマンド引数
     * @return 対象プレイヤー。指定先がオンラインでない場合は {@code null}
     */
    private @Nullable AstPlayer resolveTarget(@NotNull AstPlayer executor, @NotNull String[] args) {
        if (args.length == 3) {
            return executor;
        }

        Player bukkitTarget = Bukkit.getPlayerExact(args[3]);
        AstPlayer target = bukkitTarget == null ? null : AstPlayerCache.get(bukkitTarget);
        if (target == null) {
            PlayerMessageService.getInstance().send(executor, PlayerMsgId.P_5112, args[3]);
        }
        return target;
    }

    /**
     * プラグインが保持するステータスサービスを取得します。
     *
     * @return 初期化済みのステータスサービス。取得できない場合は {@code null}
     */
    private @Nullable StatusService resolveStatusService() {
        AstralRecord plugin = AstralRecord.getInstance();
        return plugin == null ? null : plugin.getStatusService();
    }
}
