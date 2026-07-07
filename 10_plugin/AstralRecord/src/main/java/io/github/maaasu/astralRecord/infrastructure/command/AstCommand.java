package io.github.maaasu.astralRecord.infrastructure.command;

import io.github.maaasu.astralRecord.feature.player.AccountModeGuard;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.util.ColorCodeUtil;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jspecify.annotations.NonNull;

import java.util.Arrays;

/**
 * すべてのコマンドの基底クラス。
 * <p>
 * プレイヤー限定コマンド（{@code playerOnly = true}）の場合は
 * {@link #executePlayerCommand(AstPlayer, String[])} をオーバーライドしてください。
 * それ以外の場合は {@link #executeCommand(CommandSender, String[])} をオーバーライドしてください。
 */
public abstract class AstCommand implements CommandExecutor {

    /** 権限チェック不要を示す定数 */
    public static final int PERMISSION_NONE = -1;

    /** コマンド名 */
    private final String commandName;

    /** コマンドの説明 */
    private final String description;

    /** 使用方法 */
    private final String usage;

    /** 必要な権限レベル（PERMISSION_NONE の場合は権限チェックなし） */
    private int requiredPermissionLevel = PERMISSION_NONE;

    /** プレイヤー限定コマンドかどうか */
    private boolean playerOnly = false;

    /**
     * AstCommandを初期化します。
     *
     * @param commandName コマンド名
     * @param description コマンドの説明
     * @param usage 使用方法
     */
    protected AstCommand(@NotNull String commandName, @NotNull String description, @NotNull String usage) {
        this.commandName = commandName;
        this.description = description;
        this.usage = usage;
    }

    /**
     * AstCommandを初期化します（プレイヤー限定設定付き）。
     *
     * @param commandName コマンド名
     * @param description コマンドの説明
     * @param usage 使用方法
     * @param playerOnly プレイヤー限定コマンドかどうか
     */
    protected AstCommand(@NotNull String commandName, @NotNull String description, @NotNull String usage,
                         boolean playerOnly) {
        this.commandName = commandName;
        this.description = description;
        this.usage = usage;
        this.playerOnly = playerOnly;
    }

    /**
     * AstCommandを初期化します（プレイヤー限定 + 権限レベル設定付き）。
     * <p>
     * コンストラクタ内で {@link #setRequiredPermissionLevel} を呼び出すと
     * 「サブクラスが初期化される前の 'this' エスケープ」警告が発生するため、
     * 権限レベルをコンストラクタ引数で直接設定する場合はこのオーバーロードを使用してください。
     *
     * @param commandName             コマンド名
     * @param description             コマンドの説明
     * @param usage                   使用方法
     * @param playerOnly              プレイヤー限定コマンドかどうか
     * @param requiredPermissionLevel 必要な権限レベル（{@link #PERMISSION_NONE} で権限チェックなし）
     */
    protected AstCommand(@NotNull String commandName, @NotNull String description, @NotNull String usage,
                         boolean playerOnly, int requiredPermissionLevel) {
        this.commandName = commandName;
        this.description = description;
        this.usage = usage;
        this.playerOnly = playerOnly;
        this.requiredPermissionLevel = requiredPermissionLevel;
    }

    /**
     * 必要な権限レベルを設定します。
     * {@link #PERMISSION_NONE} を指定した場合は権限チェックを行いません。
     *
     * @param requiredPermissionLevel 必要な権限レベル
     * @return 自分自身（メソッドチェーン用）
     */
    public final AstCommand setRequiredPermissionLevel(int requiredPermissionLevel) {
        this.requiredPermissionLevel = requiredPermissionLevel;
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
    public final boolean onCommand(@NotNull CommandSender sender, @Nullable Command command,
                                   @NotNull String label, @NotNull String @NonNull [] args) {
        try {
            // プレイヤー限定チェック
            if (playerOnly && !(sender instanceof Player)) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
                return true;
            }

            if (!hasRequiredPermission(sender)) {
                sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5061.getId()));
                return true;
            }

            // プレイヤー限定コマンドの場合は AstPlayer を解決して専用メソッドへ委譲
            if (playerOnly) {
                AstPlayer astPlayer = AstPlayerCache.get((Player) sender);
                if (astPlayer == null) {
                    sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
                    return true;
                }

                executePlayerCommand(astPlayer, args);
            } else {
                executeCommand(sender, args);
            }

        } catch (Exception e) {
            sendError(sender, PlayerMsgResource.format(PlayerMsgId.P_5062.getId(), e.getMessage()));
        }

        return true;
    }

    /**
     * コマンド送信者がこのコマンドを利用できるかを判定します。
     * <p>
     * Brigadier 登録時の可視性判定と実行時ガードを同じ条件に揃えるために使用します。
     * コンソールはプレイヤー専用コマンドでない限り、権限レベル判定の対象外として扱います。
     *
     * @param sender コマンド送信者
     * @return 利用可能な場合は {@code true}
     */
    public final boolean canUse(@NotNull CommandSender sender) {
        if (playerOnly && !(sender instanceof Player)) {
            return false;
        }
        return hasRequiredPermission(sender);
    }

    private boolean hasRequiredPermission(@NotNull CommandSender sender) {
        if (requiredPermissionLevel == PERMISSION_NONE || !(sender instanceof Player player)) {
            return true;
        }

        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer != null && astPlayer.hasPermissionLevel(requiredPermissionLevel)) {
            return true;
        }
        return hasPermissionOverride(sender);
    }

    /**
     * 通常の permission レベル不足時に、コマンド固有の例外許可を判定します。
     * <p>
     * 既定では例外を許可しません。使用する場合は、対象コマンド内で許可範囲を明確に限定してください。
     *
     * @param sender コマンド送信者
     * @return コマンド固有の例外で実行を許可する場合は true
     */
    protected boolean hasPermissionOverride(@NotNull CommandSender sender) {
        return false;
    }

    /**
     * 実際のコマンド処理を行います。
     * プレイヤー限定でないコマンドはこのメソッドをオーバーライドしてください。
     * <p>
     * プレイヤー限定コマンド（{@code playerOnly = true}）の場合は
     * {@link #executePlayerCommand(AstPlayer, String[])} をオーバーライドしてください。
     *
     * @param sender コマンド送信者
     * @param args コマンド引数
     */
    protected void executeCommand(@NotNull CommandSender sender, @NotNull String[] args) {
        // デフォルト実装は何もしない。サブクラスでオーバーライドすること。
    }

    /**
     * プレイヤー限定コマンドの処理を行います。
     * {@code playerOnly = true} のコマンドはこのメソッドをオーバーライドしてください。
     *
     * @param player コマンドを実行した {@link AstPlayer}
     * @param args コマンド引数
     */
    protected void executePlayerCommand(@NotNull AstPlayer player, @NotNull String[] args) {
        // デフォルト実装は何もしない。サブクラスでオーバーライドすること。
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
     * プレイヤーが必要な場合のチェック処理。
     * プレイヤーでない場合はエラーメッセージを送信し、falseを返します。
     *
     * @param sender コマンド送信者
     * @return プレイヤーかどうか
     */
    protected boolean requirePlayer(@NotNull CommandSender sender) {
        if (!(sender instanceof Player)) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5060.getId()));
            return false;
        }
        return true;
    }

    /**
     * 通常プレイ専用コマンドを実行できる account mode かを確認します。
     *
     * @param player コマンド実行者
     * @return 通常プレイ mode の場合は true
     */
    protected boolean requireGameplayMode(@NotNull AstPlayer player) {
        if (AccountModeGuard.isGameplayPlayer(player)) {
            return true;
        }
        PlayerMessageService.getInstance().send(player, PlayerMsgId.P_5065);
        return false;
    }

    /**
     * 成功メッセージを送信します。
     *
     * @param sender 送信先
     * @param message メッセージ
     */
    protected void sendSuccess(@NotNull CommandSender sender, @NotNull String message) {
        sendDecorated(sender, ColorCodeUtil.colorize(ColorCodeUtil.GREEN, message));
    }

    /**
     * 情報メッセージを送信します。
     *
     * @param sender 送信先
     * @param message メッセージ
     */
    protected void sendInfo(@NotNull CommandSender sender, @NotNull String message) {
        sendDecorated(sender, ColorCodeUtil.colorize(ColorCodeUtil.AQUA, message));
    }

    /**
     * 警告メッセージを送信します。
     *
     * @param sender 送信先
     * @param message メッセージ
     */
    protected void sendWarning(@NotNull CommandSender sender, @NotNull String message) {
        sendDecorated(sender, ColorCodeUtil.colorize(ColorCodeUtil.YELLOW, message));
    }

    /**
     * エラーメッセージを送信します。
     *
     * @param sender 送信先
     * @param message メッセージ
     */
    protected void sendError(@NotNull CommandSender sender, @NotNull String message) {
        sendDecorated(sender, ColorCodeUtil.colorize(ColorCodeUtil.RED, message));
    }

    private void sendDecorated(@NotNull CommandSender sender, @NotNull String message) {
        PlayerMessageService.getInstance().sendRaw(sender, message);
    }

    /**
     * 使用方法を表示します。
     *
     * @param sender 送信先
     */
    protected void sendUsage(@NotNull CommandSender sender) {
        sendInfo(sender, PlayerMsgResource.format(PlayerMsgId.P_5064.getId(), usage));
    }

    /**
     * 引数の長さをチェックします。
     *
     * @param args 引数配列
     * @param minLength 最小長
     * @param sender エラー時の送信先
     * @return 条件を満たすかどうか
     */
    protected boolean checkArgsLength(@NotNull String[] args, int minLength, @NotNull CommandSender sender) {
        if (args.length < minLength) {
            sendError(sender, PlayerMsgResource.getMessage(PlayerMsgId.P_5063.getId()));
            sendUsage(sender);
            return false;
        }
        return true;
    }

    /**
     * 引数を結合して文字列にします（スペース区切り）。
     *
     * @param args 引数配列
     * @param startIndex 開始インデックス
     * @return 結合された文字列
     */
    protected String joinArgs(@NotNull String[] args, int startIndex) {
        if (startIndex >= args.length) {
            return "";
        }
        String[] subArray = Arrays.copyOfRange(args, startIndex, args.length);
        return String.join(" ", subArray);
    }

    // ゲッター
    public String getCommandName() { return commandName; }
    public String getDescription() { return description; }
    public String getUsage() { return usage; }
    public int getRequiredPermissionLevel() { return requiredPermissionLevel; }
    public boolean isPlayerOnly() { return playerOnly; }

    /**
     * 使用方法の定義から、このコマンドが引数を受け取る可能性があるかを返します。
     *
     * @return 使用方法にコマンド名以外のトークンがある場合は true
     */
    final boolean acceptsArguments() {
        return usage.trim().indexOf(' ') >= 0;
    }
}
