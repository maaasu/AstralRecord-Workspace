package io.github.maaasu.astralRecord.core.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigManager;
import io.github.maaasu.astralRecord.infrastructure.database.file.yaml.config.YamlDbConfigUtil;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * /astreload コマンドの実装クラス。
 * <p>
 * AstralRecord プラグインのホットリロードを実行します。
 * <ul>
 *   <li>PlugMan が利用可能な場合: PlugMan 経由でプラグイン全体をリロードします。</li>
 *   <li>PlugMan が利用できない場合: 設定・YAMLデータの内部リロードを実行します。</li>
 * </ul>
 * <p>
 * 使用方法:
 * <pre>
 * /astreload - AstralRecord をリロードする
 * </pre>
 */
public class ReloadCommand extends AstCommand {

    /** このコマンドの実行に必要な最低権限レベル（プレイヤーの場合のみ） */
    private static final int REQUIRED_PERMISSION = 99;

    /**
     * ReloadCommand を初期化します。
     */
    public ReloadCommand() {
        super("astreload", "AstralRecord をホットリロードします", "/astreload", false, REQUIRED_PERMISSION);
    }

    /**
     * コマンドの実行処理。
     * <p>
     * プレイヤーからの実行の場合は permission レベル 99 以上を要求します。
     * コンソールからの実行は常に許可されます。
     *
     * @param sender コマンド送信者
     * @param args   コマンド引数
     */
    @Override
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        // プレイヤーからの実行の場合は権限チェック
        if (sender instanceof Player player) {
            var astPlayer = AstPlayerCache.get(player);
            if (astPlayer == null || !astPlayer.hasPermissionLevel(REQUIRED_PERMISSION)) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return;
            }
        }

        Logger.log(LogId.I_1550, sender.getName());
        sendSuccess(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5090.getId()));

        // PlugMan の利用可否を確認
        Plugin plugMan = Bukkit.getPluginManager().getPlugin("PlugMan");

        if (plugMan != null && plugMan.isEnabled()) {
            // PlugMan 経由でリロード
            // 1 tick 後に実行して現在のコマンドハンドラーの処理を完了させてからリロードする
            Logger.log(LogId.I_1551);
            sendInfo(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5091.getId()));

            AstralRecord plugin = AstralRecord.getInstance();
            Bukkit.getScheduler().runTaskLater(plugin, () ->
                    Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                            "plugman reload " + plugin.getName()), 1L);
        } else {
            // PlugMan が存在しない場合は内部リロード（設定・YAMLデータ）
            Logger.log(LogId.I_1552);
            sendWarning(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5092.getId()));

            try {
                ConfigManager.getInstance().reload();
                YamlDbConfigUtil.INSTANCE.reload();

                Logger.log(LogId.I_1553);
                sendSuccess(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5093.getId()));
            } catch (Exception e) {
                Logger.log(LogId.E_1550, e, e.getMessage());
                sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5094.getId(), e.getMessage()));
            }
        }
    }
}

