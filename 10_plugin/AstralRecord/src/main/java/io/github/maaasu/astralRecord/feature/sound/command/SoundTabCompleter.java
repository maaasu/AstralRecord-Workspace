package io.github.maaasu.astralRecord.feature.sound.command;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * /sound コマンドのTab補完です。
 */
public final class SoundTabCompleter extends AstTabCompleter {

    /**
     * プレイヤー限定のサウンド補完を初期化します。
     */
    public SoundTabCompleter() {
        super(true);
    }

    /**
     * サウンド名、音量、ピッチの候補を引数位置に応じて返します。
     *
     * @param player 補完を要求したプレイヤー
     * @param args 入力済みのコマンド引数
     * @return 現在の引数位置に対応する候補
     */
    @Override
    protected @NotNull List<String> getPlayerCompletions(
            @NotNull AstPlayer player,
            @NotNull String[] args
    ) {
        if (args.length <= 1) {
            return SoundCommand.getSoundNames();
        }
        if (args.length == 2) {
            return List.of("0.0", "0.25", "0.5", "1.0", "2.0", "5.0", "10.0");
        }
        if (args.length == 3) {
            return List.of("0.5", "0.75", "1.0", "1.25", "1.5", "2.0");
        }
        return List.of();
    }
}
