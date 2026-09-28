package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerVisualMode;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/** 管理者本人のモブスポナー管理表示を切り替えるコマンドです。 */
public final class MobSpawnerDisplayCommand extends AstCommand {
    private final MobSpawnerService spawnerService;

    /**
     * コマンドを初期化します。
     *
     * @param spawnerService モブスポナーサービス
     */
    public MobSpawnerDisplayCommand(@NotNull MobSpawnerService spawnerService) {
        super("mobspawnerdisplay", "モブスポナー管理表示を切り替えます。",
                "/mobspawnerdisplay <normal|light|off>", true, PERMISSION_NONE);
        this.spawnerService = spawnerService;
    }

    /**
     * 管理者本人の表示設定だけを変更します。他のスポナー管理操作は許可しません。
     *
     * @param player 実行者
     * @param args 表示モード名を1件含む引数
     */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!spawnerService.canViewSpawnerVisual(player)) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5719.getId()));
            return;
        }
        if (args.length != 1) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5738.getId()));
            return;
        }

        MobSpawnerVisualMode mode;
        try {
            mode = MobSpawnerVisualMode.valueOf(args[0].toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            sendError(player.getBukkit(), PlayerMsgResource.getMessage(PlayerMsgId.P_5738.getId()));
            return;
        }

        spawnerService.setVisualMode(player.getBukkit(), mode);
        PlayerMsgId messageId = switch (mode) {
            case NORMAL -> PlayerMsgId.P_5735;
            case LIGHT -> PlayerMsgId.P_5736;
            case OFF -> PlayerMsgId.P_5737;
        };
        sendSuccess(player.getBukkit(), PlayerMsgResource.getMessage(messageId.getId()));
    }
}
