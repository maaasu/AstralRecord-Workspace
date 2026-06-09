package io.github.maaasu.astralRecord.feature.status.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.status.model.StatusSnapshot;
import io.github.maaasu.astralRecord.feature.status.model.StatusType;
import io.github.maaasu.astralRecord.feature.status.model.StatusValue;
import io.github.maaasu.astralRecord.feature.status.service.StatusService;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

/**
 * /status コマンドの実装クラスです。
 * <p>
 * 基本的なステータスシステムのサンプルとして、現在の合計値表示・詳細表示・再計算を提供します。
 */
public class StatusCommand extends AstCommand {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final StatusService statusService;

    /**
     * StatusCommand を初期化します。
     */
    public StatusCommand() {
        super("status", "現在のステータスを表示します", "/status [show|detail|refresh]", true);
        this.statusService = resolveStatusService();
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0 || args[0].equalsIgnoreCase("show")) {
            showStatus(player, false);
            return;
        }

        if (args[0].equalsIgnoreCase("detail")) {
            showStatus(player, true);
            return;
        }

        if (args[0].equalsIgnoreCase("refresh")) {
            resolveStatusService().refreshStatus(player);
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5102);
            showStatus(player, false);
            return;
        }

        sendUsage(player.getBukkit());
    }

    private void showStatus(@NotNull AstPlayer player, boolean detail) {
        StatusService service = resolveStatusService();
        StatusSnapshot snapshot = service.getStatus(player);
        double maxHp = snapshot.getMaxValue(StatusType.MAX_HEALTH);
        double maxMp = snapshot.getMaxValue(StatusType.MAX_MANA);
        double maxEnergy = snapshot.getMaxValue(StatusType.MAX_ENERGY);
        PlayerMessageService playerMessageService = PlayerMessageService.getInstance();

        playerMessageService.send(player, PlayerMsgId.P_5100, player.getAccount().getAccountName());
        playerMessageService.send(
            player,
            PlayerMsgId.P_5105,
            StatusType.MAX_HEALTH.formatValue(snapshot.getCurrentHp()),
            StatusType.MAX_HEALTH.formatValue(maxHp),
            StatusType.MAX_MANA.formatValue(snapshot.getCurrentMp()),
            StatusType.MAX_MANA.formatValue(maxMp)
        );
        playerMessageService.send(
            player,
            PlayerMsgId.P_5106,
            StatusType.MAX_ENERGY.formatValue(snapshot.getCurrentEnergy()),
            StatusType.MAX_ENERGY.formatValue(maxEnergy)
        );
        for (StatusType type : StatusType.values()) {
            StatusValue value = snapshot.getValue(type);
            if (value == null) {
                continue;
            }

            if (detail) {
                playerMessageService.send(
                    player,
                    PlayerMsgId.P_5103,
                    type.getDisplayName(),
                    type.formatValue(value.getBaseValue()),
                    type.formatSignedValue(value.getBonusValue()),
                    type.formatValue(value.getTotalValue())
                );
                continue;
            }

            playerMessageService.send(
                player,
                PlayerMsgId.P_5101,
                type.getDisplayName(),
                type.formatValue(value.getTotalValue())
            );
        }
        if (detail) {
            var activeSetEffects = service.getActiveSetEffects(player);
            if (!activeSetEffects.isEmpty()) {
                playerMessageService.send(player, PlayerMsgId.P_5107);
                for (StatusService.ActiveSetEffect effect : activeSetEffects) {
                    String activeCounts = effect.activePieceCounts().stream()
                        .map(count -> count + "set")
                        .collect(Collectors.joining(", "));
                    playerMessageService.send(
                        player,
                        PlayerMsgId.P_5108,
                        effect.setName(),
                        Integer.toString(effect.equippedCount()),
                        activeCounts
                    );
                }
            }
        }

        playerMessageService.send(player, PlayerMsgId.P_5104, DATE_TIME_FORMATTER.format(snapshot.getCalculatedAt()));
    }

    private @NotNull StatusService resolveStatusService() {
        AstralRecord plugin = AstralRecord.getInstance();
        if (plugin != null && plugin.getStatusService() != null) {
            return plugin.getStatusService();
        }
        return statusService != null ? statusService : new StatusService();
    }
}
