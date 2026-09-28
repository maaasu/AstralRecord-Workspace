package io.github.maaasu.astralRecord.feature.mob.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerService;
import io.github.maaasu.astralRecord.feature.spawner.service.MobSpawnerVisualMode;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** モブスポナー管理表示コマンドのタブ補完です。 */
public final class MobSpawnerDisplayTabCompleter extends AstTabCompleter {
    private final MobSpawnerService spawnerService;

    /**
     * タブ補完を初期化します。
     *
     * @param spawnerService モブスポナーサービス
     */
    public MobSpawnerDisplayTabCompleter(@NotNull MobSpawnerService spawnerService) {
        super(true);
        this.spawnerService = spawnerService;
    }

    /**
     * 管理者モードの実行者へ表示モード一覧を提示します。
     *
     * @param player 実行者
     * @param args 入力中の引数
     * @return 表示モードのコマンド引数一覧
     */
    @Override
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        if (args.length != 1 || !spawnerService.canViewSpawnerVisual(player)) {
            return List.of();
        }
        return Arrays.stream(MobSpawnerVisualMode.values())
                .map(mode -> mode.name().toLowerCase(Locale.ROOT))
                .toList();
    }
}
