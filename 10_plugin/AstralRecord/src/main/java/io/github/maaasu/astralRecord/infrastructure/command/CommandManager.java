package io.github.maaasu.astralRecord.infrastructure.command;

import io.github.maaasu.astralRecord.AstralRecord;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * コマンドの登録・管理を行うSingletonクラス。
 * <p>
 * 使用方法:
 * <pre>
 * // 1. onLoad() でコマンドを事前登録
 * CommandManager.getInstance().registerCommand("mycommand", new MyCommand());
 *
 * // 2. onLoad() の末尾で initialize() を呼び出す
 * CommandManager.getInstance().initialize(plugin);
 * </pre>
 * <p>
 * {@code initialize()} は Paper の Lifecycle API の制約上、
 * {@code onLoad()} 内で呼び出してください。
 */
public class CommandManager {

    private static CommandManager instance;
    private static final String ARGUMENTS_NODE_NAME = "args";

    /** 登録されたコマンドのマップ */
    private final Map<String, AstCommand> registeredCommands = new HashMap<>();

    /** 登録されたタブ補完のマップ */
    private final Map<String, AstTabCompleter> registeredTabCompleters = new HashMap<>();

    /** 初期化済みフラグ */
    private boolean initialized = false;

    private CommandManager() {
    }

    /**
     * CommandManagerのインスタンスを取得します。
     *
     * @return CommandManagerのインスタンス
     */
    public static CommandManager getInstance() {
        if (instance == null) {
            instance = new CommandManager();
        }
        return instance;
    }

    /**
     * CommandManagerを初期化し、Brigadierへコマンドを登録します。
     * <p>
     * <strong>Paper の Lifecycle API の制約上、{@code onLoad()} 内で呼び出してください。</strong>
     *
     * @param plugin プラグインインスタンス
     */
    public void initialize(@NotNull AstralRecord plugin) {
        if (initialized) {
            Logger.log(LogId.W_1500);
            return;
        }

        Logger.log(LogId.I_1500);

        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands commands = event.registrar();

            for (Map.Entry<String, AstCommand> entry : registeredCommands.entrySet()) {
                String commandName = entry.getKey();
                AstCommand astCommand = entry.getValue();
                AstTabCompleter tabCompleter = registeredTabCompleters.get(commandName);

                registerToBrigadier(commands, commandName, astCommand, tabCompleter);
            }
        });

        initialized = true;
        Logger.log(LogId.I_1501, registeredCommands.size());
    }

    /**
     * コマンドを登録します。
     * {@link #initialize(AstralRecord)} を呼び出す前に登録してください。
     *
     * @param commandName コマンド名
     * @param command コマンドオブジェクト
     */
    public void registerCommand(@NotNull String commandName, @NotNull AstCommand command) {
        registerCommand(commandName, command, null);
    }

    /**
     * コマンドとタブ補完を登録します。
     * {@link #initialize(AstralRecord)} を呼び出す前に登録してください。
     *
     * @param commandName コマンド名
     * @param command コマンドオブジェクト
     * @param tabCompleter タブ補完オブジェクト（nullの場合はタブ補完なし）
     */
    public void registerCommand(@NotNull String commandName, @NotNull AstCommand command,
                                @Nullable AstTabCompleter tabCompleter) {
        if (initialized) {
            Logger.log(LogId.W_1501, commandName);
            return;
        }

        registeredCommands.put(commandName, command);
        if (tabCompleter != null) {
            registeredTabCompleters.put(commandName, tabCompleter);
        }

        Logger.log(LogId.D_1500, commandName);
    }

    /**
     * Brigadierにコマンドを登録する内部メソッド。
     */
    private void registerToBrigadier(@NotNull Commands commands, @NotNull String commandName,
                                     @NotNull AstCommand command, @Nullable AstTabCompleter tabCompleter) {
        try {
            var literalBuilder = Commands.literal(commandName)
                    .requires(stack -> command.canUse(stack.getSender()));

            literalBuilder.executes(ctx -> executeRegisteredCommand(ctx, commandName, command, new String[0]));

            var argumentBuilder = Commands.argument(ARGUMENTS_NODE_NAME,
                            com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                    .executes(ctx -> executeRegisteredCommand(
                            ctx,
                            commandName,
                            command,
                            splitArguments(com.mojang.brigadier.arguments.StringArgumentType.getString(
                                    ctx,
                                    ARGUMENTS_NODE_NAME
                            ))
                    ));
            if (tabCompleter != null) {
                argumentBuilder.suggests((ctx, builder) -> {
                    String input = ctx.getInput();
                    String[] allParts = input.split(" ", -1);
                    String[] args = allParts.length > 1
                            ? Arrays.copyOfRange(allParts, 1, allParts.length)
                            : new String[0];

                    List<String> suggestions = tabCompleter.onTabComplete(
                            ctx.getSource().getSender(), null, commandName, args);
                    int currentArgumentStart = input.lastIndexOf(' ') + 1;
                    var suggestionBuilder = builder.createOffset(currentArgumentStart);

                    suggestions.stream()
                            .forEach(suggestionBuilder::suggest);
                    return suggestionBuilder.buildFuture();
                });
            }

            if (command.acceptsArguments()) {
                literalBuilder.then(argumentBuilder);
            }

            commands.register(literalBuilder.build(), command.getDescription(),
                    Collections.emptyList());
            Logger.log(LogId.D_1501, commandName);

        } catch (Exception e) {
            Logger.log(LogId.E_1500, e, commandName);
        }
    }

    /**
     * Brigadier の実行コンテキストから既存の AstCommand 実装へ処理を委譲します。
     *
     * @param ctx Brigadier 実行コンテキスト
     * @param commandName 登録コマンド名
     * @param command 実行対象のコマンド
     * @param args コマンド引数
     * @return Brigadier の成功ステータス
     */
    private int executeRegisteredCommand(
            @NotNull com.mojang.brigadier.context.CommandContext<io.papermc.paper.command.brigadier.CommandSourceStack> ctx,
            @NotNull String commandName,
            @NotNull AstCommand command,
            @NotNull String[] args
    ) {
        command.onCommand(ctx.getSource().getSender(), null, commandName, args);
        return com.mojang.brigadier.Command.SINGLE_SUCCESS;
    }

    /**
     * Brigadier の greedyString で受け取った引数文字列を AstCommand 向けの配列へ分割します。
     *
     * @param argumentText コマンド名を除いた引数文字列
     * @return 空白区切りの引数配列。空文字または空白のみの場合は空配列
     */
    private @NotNull String[] splitArguments(@NotNull String argumentText) {
        String trimmed = argumentText.trim();
        if (trimmed.isEmpty()) {
            return new String[0];
        }
        return trimmed.split("\\s+");
    }

    /**
     * 登録されたコマンドを取得します。
     *
     * @param commandName コマンド名
     * @return コマンドオブジェクト、または存在しない場合はnull
     */
    @Nullable
    public AstCommand getCommand(@NotNull String commandName) {
        return registeredCommands.get(commandName);
    }

    /**
     * 登録されたタブ補完を取得します。
     *
     * @param commandName コマンド名
     * @return タブ補完オブジェクト、または存在しない場合はnull
     */
    @Nullable
    public AstTabCompleter getTabCompleter(@NotNull String commandName) {
        return registeredTabCompleters.get(commandName);
    }

    /**
     * 登録されているコマンド名のセットを取得します。
     *
     * @return コマンド名のセット（変更不可）
     */
    @NotNull
    public Set<String> getRegisteredCommandNames() {
        return Collections.unmodifiableSet(registeredCommands.keySet());
    }

    /**
     * 登録されているコマンドの数を取得します。
     *
     * @return コマンド数
     */
    public int getRegisteredCommandCount() {
        return registeredCommands.size();
    }

    /**
     * 初期化済みかどうかを確認します。
     *
     * @return 初期化済みの場合はtrue
     */
    public boolean isInitialized() {
        return initialized;
    }

    /**
     * CommandManagerをシャットダウンします。
     * プラグインの onDisable で呼び出してください。
     */
    public void shutdown() {
        if (!initialized) {
            return;
        }

        Logger.log(LogId.I_1502);

        registeredCommands.clear();
        registeredTabCompleters.clear();
        initialized = false;

        Logger.log(LogId.I_1503);
    }
}
