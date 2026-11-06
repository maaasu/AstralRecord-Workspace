package io.github.maaasu.astralRecord.feature.account.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.account.model.ProgressionPreset;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerSessionTransitionGuard.Transition;
import io.github.maaasu.astralRecord.feature.user.model.UserPermission;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** 選択中のアカウントへ戦闘検証用プリセットを適用します。 */
final class AccountProgressionCommand extends AstCommand {
    AccountProgressionCommand() {
        super("account progression", "戦闘検証用の進行プリセットを適用します。",
            "/account progression <1-50> <class> <build>", true, UserPermission.ADMIN.getValue());
    }

    /**
     * 入力された職業、ビルド、レベルから構成を解決して適用します。
     * @param player 実行者の選択中セッション
     * @param args プレイヤーレベル、職業ID、ビルドID
     */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        var sender = player.getBukkit();
        AstralRecord plugin = AstralRecord.getInstance();
        if (!plugin.isProgressionPresetEnabled()) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7433.getId()));
            return;
        }
        if (args.length != 3 || !args[0].matches("[0-9]{1,2}")) {
            sendUsage(sender);
            return;
        }
        int level = Integer.parseInt(args[0]);
        if (level < ProgressionPreset.MIN_LEVEL || level > ProgressionPreset.MAX_LEVEL) {
            sendUsage(sender);
            return;
        }
        ProgressionPreset preset = plugin.getProgressionPresets().stream()
            .filter(candidate -> candidate.classId().equalsIgnoreCase(args[1])
                && candidate.buildId().equalsIgnoreCase(args[2]))
            .findFirst().orElse(null);
        if (preset == null) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7434.getId()));
            return;
        }
        var checkpoint = preset.atLevel(level);
        var guard = plugin.getPlayerSessionTransitionGuard();
        var playerId = sender.getUniqueId();
        if (!sender.getItemOnCursor().getType().isAir() || !guard.tryBegin(playerId, Transition.PROGRESSION_PRESET)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7436.getId()));
            return;
        }
        try {
            switch (plugin.getProgressionPresetApplicationService().apply(player, preset, checkpoint, level)) {
                case APPLIED -> sendSuccess(sender, PlayerMsgResource.format(PlayerMsgId.P_7435.getId(),
                    preset.name(), level, checkpoint.classLevel()));
                case UNAVAILABLE -> sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_7436.getId()));
                case INVALID -> sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_7437.getId(), preset.id()));
            }
        } finally {
            guard.end(playerId, Transition.PROGRESSION_PRESET);
        }
    }
}
