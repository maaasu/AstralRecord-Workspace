package io.github.maaasu.astralRecord.feature.item.command;

import io.github.maaasu.astralRecord.feature.item.service.ItemEnhanceService;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.command.AstTabCompleter;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** showitem と同じ BAG 番号付き装備名と、強化値・プレイヤーの補完を提供します。 */
public final class EnhanceTabCompleter extends AstTabCompleter {
    private final ItemEnhanceService enhanceService;

    /**
     * 強化コマンド補完を初期化します。
     *
     * @param enhanceService 強化可能な所持装備の解決サービス
     */
    public EnhanceTabCompleter(@NotNull ItemEnhanceService enhanceService) {
        this.enhanceService = enhanceService;
    }

    /**
     * 管理者にだけ、現在の装備指定位置に応じた候補を返します。
     *
     * @param sender 管理者またはコンソール
     * @param args 現在入力中の引数
     * @return 装備名の残り部分、強化値、または末尾プレイヤー名の候補
     */
    @Override
    protected @NotNull List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        AstPlayer player = getAstPlayer(sender);
        if (sender instanceof Player && (player == null || !player.hasAdminPermission())) {
            return List.of();
        }
        List<ItemEnhanceService.Candidate> candidates = player == null ? List.of() : enhanceService.getCandidates(player);
        if (args.length == 0) {
            return candidates.stream().map(ItemEnhanceService.Candidate::commandSelection).toList();
        }
        String[] completedArgs = Arrays.copyOf(args, args.length - 1);
        String completed = String.join(" ", completedArgs);
        var candidate = enhanceService.findCandidate(candidates, completed);
        if (candidate != null) {
            return List.of("0", "1", Integer.toString(candidate.maxLevel()), "+1", "-1").stream().distinct().toList();
        }
        var input = EnhanceCommand.parseArguments(completedArgs);
        if (input != null && input.targetName() == null) {
            return getOnlinePlayerNames();
        }

        String prefix = completed.isEmpty() ? "" : completed + " ";
        String normalized = prefix.toLowerCase(Locale.ROOT);
        return candidates.stream().map(ItemEnhanceService.Candidate::commandSelection)
            .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(normalized))
            .map(name -> prefix.isEmpty() ? name : name.substring(prefix.length()))
            .toList();
    }
}
