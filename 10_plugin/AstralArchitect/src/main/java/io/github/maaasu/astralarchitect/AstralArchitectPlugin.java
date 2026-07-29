package io.github.maaasu.astralarchitect;

import io.github.maaasu.astralarchitect.command.ArchitectCommand;
import io.github.maaasu.astralarchitect.config.ArchitectConfig;
import io.github.maaasu.astralarchitect.ticket.TicketAccessPolicy;
import io.github.maaasu.astralarchitect.ticket.TicketRepository;
import io.github.maaasu.astralarchitect.ticket.TicketService;
import io.github.maaasu.astralarchitect.worldedit.SchematicService;
import io.github.maaasu.astralarchitect.worldedit.WorldEditSelectionProvider;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Objects;
import java.util.logging.Level;

/**
 * AstralArchitectのPaperプラグインエントリーポイントです。
 */
public final class AstralArchitectPlugin extends JavaPlugin {

    private static final long DAILY_TICKS = 20L * 60L * 60L * 24L;

    private volatile ArchitectConfig architectConfig;
    private TicketService ticketService;

    /**
     * 設定、永続化先、コマンド、trash保守処理を初期化します。
     */
    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            architectConfig = ArchitectConfig.load(this);
            TicketRepository repository = new TicketRepository(getDataFolder().toPath());
            repository.initialize();
            ticketService = new TicketService(
                    repository,
                    new SchematicService(),
                    architectConfig,
                    getLogger());

            ArchitectCommand architectCommand = new ArchitectCommand(
                    this,
                    ticketService,
                    new WorldEditSelectionProvider(),
                    new TicketAccessPolicy());
            PluginCommand command = Objects.requireNonNull(
                    getCommand("architect"),
                    "plugin.ymlにarchitectコマンドがありません");
            command.setExecutor(architectCommand);
            command.setTabCompleter(architectCommand);
            scheduleTrashMaintenance();
            getLogger().info("AstralArchitectを有効化しました。");
        } catch (Exception exception) {
            getLogger().log(Level.SEVERE, "AstralArchitectの初期化に失敗しました。", exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    /**
     * 非同期チケットキューを安全に停止します。
     */
    @Override
    public void onDisable() {
        if (ticketService != null) {
            ticketService.close();
            ticketService = null;
        }
    }

    /**
     * 現在有効な検証済み設定を返します。
     *
     * @return 現在の設定
     */
    public ArchitectConfig architectConfig() {
        return architectConfig;
    }

    /**
     * config.ymlを再読込し、後続の非同期処理へ反映します。
     * 不正な設定の場合、実行中設定は変更しません。
     *
     * @return 再読込した設定
     * @throws IllegalArgumentException 設定値が不正な場合
     * @throws IllegalStateException プラグインが初期化されていない場合
     */
    public ArchitectConfig reloadArchitectConfig() {
        reloadConfig();
        ArchitectConfig reloaded = ArchitectConfig.load(this);
        TicketService service = ticketService;
        if (service == null) {
            throw new IllegalStateException("チケットサービスが初期化されていません。");
        }
        service.updateConfig(reloaded);
        architectConfig = reloaded;
        return reloaded;
    }

    private void scheduleTrashMaintenance() {
        getServer().getScheduler().runTaskTimer(this, this::purgeTrash, 20L, DAILY_TICKS);
    }

    private void purgeTrash() {
        TicketService service = ticketService;
        if (service == null) {
            return;
        }
        service.purgeExpiredTrash().whenComplete((deleted, throwable) -> {
            if (!isEnabled()) {
                return;
            }
            getServer().getScheduler().runTask(this, () -> {
                if (throwable != null) {
                    getLogger().log(Level.WARNING, "期限切れtrashの削除に失敗しました。", unwrap(throwable));
                } else if (deleted > 0) {
                    getLogger().info("期限切れtrashを" + deleted + "件削除しました。");
                }
            });
        });
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof java.util.concurrent.CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
