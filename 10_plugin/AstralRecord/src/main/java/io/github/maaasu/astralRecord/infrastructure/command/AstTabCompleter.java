package io.github.maaasu.astralRecord.infrastructure.command;

import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * すべてのタブ補完の基底クラス。
 * <p>
 * プレイヤー限定コマンドのタブ補完は {@link #getPlayerCompletions(AstPlayer, String[])} をオーバーライドしてください。
 * それ以外の場合は {@link #getCompletions(CommandSender, String[])} をオーバーライドしてください。
 */
public abstract class AstTabCompleter implements TabCompleter {

    /** 権限ノード（nullの場合は権限チェックなし） */
    private String permission;

    /** プレイヤー限定コマンドかどうか */
    private boolean playerOnly = false;

    /**
     * デフォルトコンストラクタ。
     */
    protected AstTabCompleter() {
    }

    /**
     * プレイヤー限定設定付きコンストラクタ。
     *
     * @param playerOnly プレイヤー限定コマンドかどうか
     */
    protected AstTabCompleter(boolean playerOnly) {
        this.playerOnly = playerOnly;
    }

    /**
     * 権限ノードを設定します。
     *
     * @param permission 権限ノード
     * @return 自分自身（メソッドチェーン用）
     */
    public final AstTabCompleter setPermission(String permission) {
        this.permission = permission;
        return this;
    }

    /**
     * プレイヤー限定コマンドに設定します。
     *
     * @param playerOnly プレイヤー限定かどうか
     */
    public final void setPlayerOnly(boolean playerOnly) {
        this.playerOnly = playerOnly;
    }

    @Override
    public final @NotNull List<String> onTabComplete(@NotNull CommandSender sender, @Nullable Command command,
                                                     @NotNull String label, @NotNull String[] args) {
        try {
            // プレイヤー限定チェック
            if (playerOnly && !(sender instanceof Player)) {
                return Collections.emptyList();
            }

            // 権限チェック
            if (permission != null && !sender.hasPermission(permission)) {
                return Collections.emptyList();
            }

            List<String> completions;

            // プレイヤー限定の場合は AstPlayer を解決して専用メソッドへ委譲
            if (playerOnly) {
                AstPlayer astPlayer = AstPlayerCache.get((Player) sender);
                if (astPlayer == null) {
                    return Collections.emptyList();
                }
                completions = getPlayerCompletions(astPlayer, args);
            } else {
                completions = getCompletions(sender, args);
            }

            // 入力に基づいてフィルタリング
            if (completions != null && args.length > 0) {
                String currentInput = args[args.length - 1].toLowerCase();
                return completions.stream()
                        .filter(completion -> completion.toLowerCase().startsWith(currentInput))
                        .collect(Collectors.toList());
            }

            return completions != null ? completions : Collections.emptyList();

        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    /**
     * タブ補完の候補を取得します。
     * プレイヤー限定でないコマンドはこのメソッドをオーバーライドしてください。
     * <p>
     * プレイヤー限定コマンド（{@code playerOnly = true}）の場合は
     * {@link #getPlayerCompletions(AstPlayer, String[])} をオーバーライドしてください。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     * @return 補完候補のリスト
     */
    protected List<String> getCompletions(@NotNull CommandSender sender, @NotNull String[] args) {
        return Collections.emptyList();
    }

    /**
     * プレイヤー限定コマンドのタブ補完候補を取得します。
     * {@code playerOnly = true} のコマンドはこのメソッドをオーバーライドしてください。
     *
     * @param player コマンドを実行した {@link AstPlayer}
     * @param args コマンド引数
     * @return 補完候補のリスト
     */
    protected List<String> getPlayerCompletions(@NotNull AstPlayer player, @NotNull String[] args) {
        return Collections.emptyList();
    }

    /**
     * 送信者が {@link Player} かチェックし、対応する {@link AstPlayer} を返します。
     * プレイヤーでない場合、またはキャッシュに存在しない場合は {@code null} を返します。
     *
     * @param sender コマンド送信者
     * @return {@link AstPlayer}、または {@code null}
     */
    @Nullable
    protected AstPlayer getAstPlayer(@NotNull CommandSender sender) {
        if (sender instanceof Player) {
            return AstPlayerCache.get((Player) sender);
        }
        return null;
    }

    /**
     * オンラインのプレイヤー名リストを取得します。
     *
     * @return オンラインプレイヤー名のリスト
     */
    protected List<String> getOnlinePlayerNames() {
        return org.bukkit.Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .collect(Collectors.toList());
    }

    /**
     * 引数の位置に応じてタブ補完候補を返すヘルパーメソッド。
     *
     * @param args 現在の引数
     * @param position 位置（0から始まる）
     * @param completions その位置での候補
     * @return 該当位置の場合は候補、そうでなければ空のリスト
     */
    protected List<String> completeAtPosition(@NotNull String[] args, int position, @NotNull String... completions) {
        if (args.length == position + 1) {
            return Arrays.asList(completions);
        }
        return Collections.emptyList();
    }

    /**
     * 引数の位置に応じてタブ補完候補を返すヘルパーメソッド（リスト版）。
     *
     * @param args 現在の引数
     * @param position 位置（0から始まる）
     * @param completions その位置での候補リスト
     * @return 該当位置の場合は候補、そうでなければ空のリスト
     */
    protected List<String> completeAtPosition(@NotNull String[] args, int position, @NotNull List<String> completions) {
        if (args.length == position + 1) {
            return new ArrayList<>(completions);
        }
        return Collections.emptyList();
    }

    /**
     * boolean値の補完候補を返します。
     *
     * @return true/falseの候補リスト
     */
    protected List<String> getBooleanCompletions() {
        return Arrays.asList("true", "false");
    }

    /**
     * 数値範囲の補完候補を返します。
     *
     * @param min 最小値
     * @param max 最大値
     * @return 数値候補のリスト
     */
    protected List<String> getNumberCompletions(int min, int max) {
        List<String> numbers = new ArrayList<>();
        for (int i = min; i <= max; i++) {
            numbers.add(String.valueOf(i));
        }
        return numbers;
    }

    // ゲッター
    public String getPermission() { return permission; }
    public boolean isPlayerOnly() { return playerOnly; }
}
