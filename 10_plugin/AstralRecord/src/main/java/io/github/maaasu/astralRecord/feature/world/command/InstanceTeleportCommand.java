package io.github.maaasu.astralRecord.feature.world.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.feature.world.event.InstanceTeleportGuiEventHandler;
import io.github.maaasu.astralRecord.feature.world.model.InstanceTeleportFilter;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

/** コマンド入口ごとの初期種別だけを指定して、共通受付 GUI を開きます。 */
public final class InstanceTeleportCommand extends AstCommand {
    private final InstanceTeleportFilter initialFilter;

    /**
     * 引数なし・通常プレイヤー向けの GUI コマンドを構築します。
     * @param name 登録するコマンド名
     * @param initialFilter GUI の初期表示種別
     */
    public InstanceTeleportCommand(@NotNull String name, @NotNull InstanceTeleportFilter initialFilter) {
        super(name, "ボス・ダンジョンの受付転送GUIを開きます。", "/" + name, true);
        this.initialFilter = initialFilter;
    }

    /**
     * 初期フィルターで共通 GUI を開きます。サービス未初期化時は利用不可を通知します。
     * @param player コマンド実行者
     * @param args コマンド引数。引数なしで利用します
     */
    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (!requireGameplayMode(player)) {
            return;
        }
        AstralRecord plugin = AstralRecord.getInstance();
        InstanceTeleportGuiEventHandler handler = plugin == null ? null : plugin.getInstanceTeleportGuiEventHandler();
        if (handler == null) {
            PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5794);
            return;
        }
        handler.open(player, initialFilter);
    }
}
