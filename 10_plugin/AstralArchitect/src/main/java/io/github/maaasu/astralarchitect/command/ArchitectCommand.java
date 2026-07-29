package io.github.maaasu.astralarchitect.command;

import io.github.maaasu.astralarchitect.AstralArchitectPlugin;
import io.github.maaasu.astralarchitect.config.ArchitectConfig;
import io.github.maaasu.astralarchitect.ticket.BlockPosition;
import io.github.maaasu.astralarchitect.ticket.TicketAccessPolicy;
import io.github.maaasu.astralarchitect.ticket.TicketBounds;
import io.github.maaasu.astralarchitect.ticket.TicketMetadata;
import io.github.maaasu.astralarchitect.ticket.TicketOperationResult;
import io.github.maaasu.astralarchitect.ticket.TicketRepository;
import io.github.maaasu.astralarchitect.ticket.TicketService;
import io.github.maaasu.astralarchitect.worldedit.SelectionException;
import io.github.maaasu.astralarchitect.worldedit.WorldEditSelection;
import io.github.maaasu.astralarchitect.worldedit.WorldEditSelectionProvider;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * /architectコマンドの入力検証と非同期サービス呼び出しを担当します。
 */
public final class ArchitectCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "[AstralArchitect] ";
    private static final List<String> ROOT_COMMANDS = List.of("help", "ticket", "reload");
    private static final List<String> PLAYER_TICKET_COMMANDS = List.of(
            "create", "list", "info", "validate", "apply", "rollback", "delete", "restore");
    private static final List<String> CONSOLE_TICKET_COMMANDS = List.of(
            "list", "info", "delete", "restore");

    private final AstralArchitectPlugin plugin;
    private final TicketService ticketService;
    private final WorldEditSelectionProvider selectionProvider;
    private final TicketAccessPolicy accessPolicy;

    /**
     * コマンド処理を作成します。
     *
     * @param plugin AstralArchitect本体
     * @param ticketService チケット操作サービス
     * @param selectionProvider WorldEdit選択取得サービス
     * @param accessPolicy 権限判定
     */
    public ArchitectCommand(
            AstralArchitectPlugin plugin,
            TicketService ticketService,
            WorldEditSelectionProvider selectionProvider,
            TicketAccessPolicy accessPolicy) {
        this.plugin = plugin;
        this.ticketService = ticketService;
        this.selectionProvider = selectionProvider;
        this.accessPolicy = accessPolicy;
    }

    /**
     * /architectのサブコマンドを処理します。
     *
     * @param sender 実行者
     * @param command Bukkitコマンド
     * @param label 入力されたラベル
     * @param args 引数
     * @return 処理済みの場合はtrue
     */
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!canEnterPlugin(sender)) {
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }

        String root = args[0].toLowerCase(Locale.ROOT);
        if (root.equals("reload")) {
            reload(sender, args);
            return true;
        }
        if (!root.equals("ticket")) {
            error(sender, "不明なコマンドです。/architect help を確認してください。");
            return true;
        }
        if (args.length < 2) {
            sendHelp(sender);
            return true;
        }

        String action = args[1].toLowerCase(Locale.ROOT);
        if (sender instanceof ConsoleCommandSender && !CONSOLE_TICKET_COMMANDS.contains(action)) {
            error(sender, "コンソールから実行できないワールド操作です。");
            return true;
        }
        switch (action) {
            case "create" -> create(sender, args);
            case "list" -> list(sender, args);
            case "info" -> info(sender, args);
            case "validate" -> validate(sender, args);
            case "apply" -> apply(sender, args);
            case "rollback" -> rollback(sender, args);
            case "delete" -> delete(sender, args);
            case "restore" -> restore(sender, args);
            default -> error(sender, "不明なticketコマンドです。/architect help を確認してください。");
        }
        return true;
    }

    /**
     * 実行者に利用可能なサブコマンド候補を返します。
     *
     * @param sender 実行者
     * @param command Bukkitコマンド
     * @param alias 入力されたエイリアス
     * @param args 入力途中の引数
     * @return 補完候補
     */
    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!mayEnterPlugin(sender)) {
            return List.of();
        }
        if (args.length == 1) {
            List<String> available = new ArrayList<>(ROOT_COMMANDS);
            if (!accessPolicy.isAdmin(sender)) {
                available.remove("reload");
            }
            return matchPrefix(available, args[0]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("ticket")) {
            List<String> available = sender instanceof ConsoleCommandSender
                    ? CONSOLE_TICKET_COMMANDS
                    : PLAYER_TICKET_COMMANDS;
            return matchPrefix(available, args[1]);
        }
        return List.of();
    }

    private boolean canEnterPlugin(CommandSender sender) {
        if (mayEnterPlugin(sender)) {
            return true;
        }
        if (sender instanceof Player) {
            error(sender, "クリエイティブモードかつastralarchitect.use権限が必要です。");
        } else {
            error(sender, "この実行者からは操作できません。");
        }
        return false;
    }

    private boolean mayEnterPlugin(CommandSender sender) {
        return sender instanceof ConsoleCommandSender
                || sender instanceof Player player && accessPolicy.canUse(player);
    }

    private void create(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            error(sender, "このコマンドはプレイヤー専用です。");
            return;
        }
        if (args.length < 3) {
            error(sender, "使い方: /architect ticket create <名前>");
            return;
        }
        String name = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
        ArchitectConfig config = plugin.architectConfig();
        final WorldEditSelection selection;
        try {
            selection = selectionProvider.capture(
                    player,
                    config.maxBlockCount(),
                    config.targetBlockDistance());
        } catch (SelectionException exception) {
            error(sender, exception.getMessage());
            return;
        }

        String minecraftVersion = plugin.getServer().getMinecraftVersion();
        Plugin fawe = plugin.getServer().getPluginManager().getPlugin("FastAsyncWorldEdit");
        String faweVersion = fawe == null ? "unknown" : fawe.getPluginMeta().getVersion();
        final CompletableFuture<TicketOperationResult> future;
        try {
            future = ticketService.create(
                    selection,
                    name,
                    player.getUniqueId(),
                    player.getName(),
                    minecraftVersion,
                    faweVersion);
        } catch (IllegalArgumentException exception) {
            error(sender, exception.getMessage());
            return;
        }
        info(sender, "選択範囲を保存しています…");
        complete(sender, future, result -> {
            info(sender, "チケットを作成しました: " + result.ticket().id());
            plain(sender, "サーバー内保存先: " + plugin.getDataFolder().toPath()
                    .resolve("tickets")
                    .resolve(result.ticket().id())
                    .toAbsolutePath()
                    .normalize());
            info(sender, "Codexでcandidate.schemを編集後、/architect ticket validate "
                    + result.ticket().id() + " を実行してください。");
        });
    }

    private void list(CommandSender sender, String[] args) {
        if (args.length != 2) {
            error(sender, "使い方: /architect ticket list");
            return;
        }
        complete(sender, ticketService.list(), tickets -> {
            List<TicketMetadata> visible = tickets.stream()
                    .filter(ticket -> accessPolicy.canManage(sender, ticket))
                    .toList();
            if (visible.isEmpty()) {
                info(sender, "操作可能なチケットはありません。");
                return;
            }
            info(sender, "チケット一覧 (" + visible.size() + "件)");
            for (TicketMetadata ticket : visible) {
                plain(sender, "- " + ticket.id() + " [" + ticket.state() + "] " + ticket.name());
            }
        });
    }

    private void info(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "info")) {
            return;
        }
        withActiveTicket(sender, args[2], ticket -> {
            TicketBounds bounds = ticket.bounds();
            info(sender, ticket.id() + " [" + ticket.state() + "] " + ticket.name());
            plain(sender, "所有者: " + ticket.ownerName() + " / ワールド: " + ticket.worldName());
            plain(sender, "範囲: " + format(bounds.min()) + " ～ " + format(bounds.max())
                    + " (" + ticket.blockCount() + " blocks)");
            plain(sender, "基準: " + format(ticket.anchor()) + " " + ticket.anchorBlockState());
            plain(sender, "検証済み変更数: " + ticket.changedBlockCount());
            plain(sender, "サーバー内保存先: " + plugin.getDataFolder().toPath()
                    .resolve("tickets")
                    .resolve(ticket.id())
                    .toAbsolutePath()
                    .normalize());
        });
    }

    private void validate(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "validate")) {
            return;
        }
        withActiveTicket(sender, args[2], ticket -> {
            info(sender, "candidate.schemを検証しています…");
            complete(sender, ticketService.validate(ticket.id()), result -> info(
                    sender,
                    "検証に成功しました。変更ブロック数: " + result.affectedBlocks()
                            + " / 次: /architect ticket apply " + result.ticket().id()));
        });
    }

    private void apply(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "apply")) {
            return;
        }
        withActiveTicket(sender, args[2], ticket -> withLoadedWorld(sender, ticket, world -> {
            info(sender, "検証済み差分を適用しています…");
            complete(sender, ticketService.apply(ticket.id(), world), result -> info(
                    sender,
                    "適用しました。変更ブロック数: " + result.affectedBlocks()
                            + " / 戻す場合: /architect ticket rollback " + result.ticket().id()));
        }));
    }

    private void rollback(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "rollback")) {
            return;
        }
        withActiveTicket(sender, args[2], ticket -> withLoadedWorld(sender, ticket, world -> {
            info(sender, "適用済み差分を元へ戻しています…");
            complete(sender, ticketService.rollback(ticket.id(), world), result -> info(
                    sender,
                    "ロールバックしました。変更ブロック数: " + result.affectedBlocks()));
        }));
    }

    private void delete(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "delete")) {
            return;
        }
        withActiveTicket(sender, args[2], ticket -> complete(
                sender,
                ticketService.trash(ticket.id()),
                result -> info(sender, "チケットをtrashへ移動しました: " + result.ticket().id())));
    }

    private void restore(CommandSender sender, String[] args) {
        if (!requireTicketId(sender, args, "restore")) {
            return;
        }
        String ticketId = args[2];
        if (!TicketRepository.isSafeTicketId(ticketId)) {
            error(sender, "チケットIDの形式が不正です。");
            return;
        }
        complete(sender, ticketService.readLatestTrash(ticketId), ticket -> {
            if (!accessPolicy.canManage(sender, ticket)) {
                error(sender, "このチケットを操作する権限がありません。");
                return;
            }
            complete(sender, ticketService.restore(ticketId), result -> info(
                    sender,
                    "チケットを復元しました: " + result.ticket().id()
                            + " [" + result.ticket().state() + "]"));
        });
    }

    private void reload(CommandSender sender, String[] args) {
        if (args.length != 1) {
            error(sender, "使い方: /architect reload");
            return;
        }
        if (!accessPolicy.isAdmin(sender)) {
            error(sender, "astralarchitect.admin権限が必要です。");
            return;
        }
        try {
            ArchitectConfig reloaded = plugin.reloadArchitectConfig();
            info(sender, "設定を再読込しました。範囲上限: " + reloaded.maxBlockCount()
                    + " / 変更上限: " + reloaded.maxChangedBlockCount());
        } catch (IllegalArgumentException exception) {
            error(sender, "設定の再読込に失敗しました: " + exception.getMessage());
        }
    }

    private void withActiveTicket(
            CommandSender sender,
            String ticketId,
            Consumer<TicketMetadata> action) {
        if (!TicketRepository.isSafeTicketId(ticketId)) {
            error(sender, "チケットIDの形式が不正です。");
            return;
        }
        complete(sender, ticketService.read(ticketId), ticket -> {
            if (!accessPolicy.canManage(sender, ticket)) {
                error(sender, "このチケットを操作する権限がありません。");
                return;
            }
            action.accept(ticket);
        });
    }

    private void withLoadedWorld(CommandSender sender, TicketMetadata ticket, Consumer<World> action) {
        final UUID worldUuid;
        try {
            worldUuid = UUID.fromString(ticket.worldUuid());
        } catch (IllegalArgumentException exception) {
            error(sender, "ticket.jsonのワールドUUIDが不正です。");
            return;
        }
        World world = plugin.getServer().getWorld(worldUuid);
        if (world == null) {
            error(sender, "対象ワールドが読み込まれていません: " + ticket.worldName());
            return;
        }
        action.accept(world);
    }

    private boolean requireTicketId(CommandSender sender, String[] args, String action) {
        if (args.length != 3) {
            error(sender, "使い方: /architect ticket " + action + " <ID>");
            return false;
        }
        return true;
    }

    private <T> void complete(CommandSender sender, CompletableFuture<T> future, Consumer<T> success) {
        future.whenComplete((result, throwable) -> {
            if (!plugin.isEnabled()) {
                return;
            }
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (throwable != null) {
                    Throwable cause = unwrap(throwable);
                    plugin.getLogger().log(Level.WARNING, "コマンド処理に失敗しました。", cause);
                    error(sender, cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage());
                    return;
                }
                success.accept(result);
            });
        });
    }

    private void sendHelp(CommandSender sender) {
        info(sender, "基本フロー");
        plain(sender, "1. FAWEでPos1/Pos2を選択し、基準ブロックへ照準を合わせる");
        plain(sender, "2. /architect ticket create <名前>");
        plain(sender, "3. Codexの$astralarchitect-builderでcandidate.schemを編集");
        plain(sender, "4. /architect ticket validate <ID>");
        plain(sender, "5. /architect ticket apply <ID>");
        plain(sender, "6. 必要なら /architect ticket rollback <ID>");
        plain(sender, "管理: ticket list | info | delete | restore");
        if (accessPolicy.isAdmin(sender)) {
            plain(sender, "管理者: /architect reload");
        }
        if (sender instanceof ConsoleCommandSender) {
            plain(sender, "コンソールはhelp/list/info/delete/restore/reloadのみ使用できます。");
        }
    }

    private static List<String> matchPrefix(List<String> candidates, String value) {
        String prefix = value.toLowerCase(Locale.ROOT);
        return candidates.stream().filter(candidate -> candidate.startsWith(prefix)).toList();
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String format(BlockPosition position) {
        return "(" + position.x() + ", " + position.y() + ", " + position.z() + ")";
    }

    private static void info(CommandSender sender, String message) {
        sender.sendMessage(Component.text(PREFIX + message, NamedTextColor.AQUA));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(Component.text(PREFIX + message, NamedTextColor.RED));
    }

    private static void plain(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }
}
