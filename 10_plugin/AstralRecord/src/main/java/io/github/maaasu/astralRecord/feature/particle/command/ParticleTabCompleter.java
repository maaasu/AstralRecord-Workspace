package io.github.maaasu.astralRecord.feature.particle.command;

import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.jetbrains.annotations.NotNull;

import java.util.List;

import static io.github.maaasu.astralRecord.shared.effect.SharedParticleDefinitions.getDefinitionIds;

/**
 * /particle の引数を補完します。
 */
public final class ParticleTabCompleter extends AstTabCompleter {

    /**
     * パーティクル ID、量、秒数の候補を返します。
     *
     * @param sender コマンド送信者
     * @param args 入力済み引数
     * @return 現在の引数位置に対応する候補
     */
    @Override
    protected @NotNull List<String> getCompletions(
            @NotNull org.bukkit.command.CommandSender sender,
            @NotNull String[] args
    ) {
        if (args.length <= 1) {
            return getDefinitionIds();
        }
        if (args.length == 2) {
            return List.of("1", "8", "16", "32", "64", "128", "256");
        }
        if (args.length == 3) {
            return List.of("1", "5", "10", "30", "60", "300");
        }
        return List.of();
    }
}
