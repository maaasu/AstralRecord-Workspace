package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.mob.model.MobInstance;
import io.github.maaasu.astralRecord.feature.mob.service.MobService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstCommand;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;

/**
 * /mob コマンドです。
 */
public class MobCommand extends AstCommand {

    private final MobService mobService;

    /**
     * MobCommand を初期化します。
     *
     * @param mobService Mob サービス
     */
    public MobCommand(@NotNull MobService mobService) {
        super("mob", "Manage packet mobs.", "/mob <load|list|spawn> [mobId]", true);
        this.mobService = mobService;
    }

    @Override
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length == 0) {
            sendUsage(player.getBukkit());
            return;
        }

        String action = args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "load" -> handleLoad(player);
            case "list" -> handleList(player);
            case "spawn" -> handleSpawn(player, args);
            default -> sendUsage(player.getBukkit());
        }
    }

    private void handleLoad(@NotNull AstPlayer player) {
        int count = mobService.loadAll();
        sendSuccess(player.getBukkit(), "Mob template loaded: " + count);
    }

    private void handleList(@NotNull AstPlayer player) {
        var ids = mobService.getLoadedMobIds();
        sendInfo(player.getBukkit(), ids.isEmpty() ? "Loaded mob: none" : "Loaded mob: " + String.join(", ", ids));
    }

    private void handleSpawn(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length < 2) {
            sendUsage(player.getBukkit());
            return;
        }

        MobInstance instance = mobService.spawn(args[1], player.getBukkit().getLocation());
        if (instance == null) {
            sendError(player.getBukkit(), "Mob template not found: " + args[1]);
            return;
        }

        sendSuccess(player.getBukkit(), "Spawned packet mob: " + instance.template().id());
    }
}
