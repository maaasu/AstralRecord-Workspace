package io.github.maaasu.astralRecord.feature.player.service;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.github.maaasu.astralRecord.feature.inventory.state.PlayerStateFailure;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/** 保存不能の証跡を解放前に保存し、メインスレッドで通知と復旧を開始します。 */
public final class PlayerStateIncidentService implements Listener {
    private static final int HISTORY_LIMIT = 64;
    private static final DateTimeFormatter FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
        .withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS XXX")
        .withZone(ZoneId.systemDefault());
    private final JavaPlugin plugin;
    private final Consumer<UUID> recovery;
    private final java.util.function.Predicate<UUID> stillBlocked;
    private final Map<UUID, History> histories = new ConcurrentHashMap<>();

    /**
     * 診断先と復旧処理を設定します。
     * @param plugin 保存先data folderとschedulerの所有者
     * @param stillBlocked 補償等で保存停止が解消済みならfalseを返す判定
     * @param recovery メインスレッドでaccount IDを受け取る復旧処理
     */
    public PlayerStateIncidentService(JavaPlugin plugin, java.util.function.Predicate<UUID> stillBlocked,
                                      Consumer<UUID> recovery) {
        this.plugin = plugin;
        this.stillBlocked = stillBlocked;
        this.recovery = recovery;
    }

    /**
     * ロード完了時にUUID対応と新しい操作履歴を登録します。メインスレッド専用です。
     * @param player ロード済みセッション
     */
    public void onLoaded(AstPlayer player) {
        histories.compute(player.getAccount().getUuid(), (ignored, previous) -> previous == null
            ? new History(player.getBukkit().getUniqueId(), player.getBukkit().getName())
            : previous.nextSession(player.getBukkit().getName()));
        record(player.getBukkit(), "SESSION_LOADED");
    }

    /**
     * ロード途中の保存停止でもMinecraft UUIDを特定できるよう、APIで得た所有者を登録します。
     * @param account APIから取得したaccount。非同期スレッドから呼び出せます
     */
    public void onAccountLoading(io.github.maaasu.astralRecord.feature.account.model.AccountModel account) {
        histories.compute(account.getUuid(), (ignored, previous) -> previous == null
            ? new History(account.getUserId(), "UNKNOWN") : previous.nextSession(previous.name))
            .add(Instant.now() + " ACCOUNT_LOADING");
    }

    /**
     * 経済操作の共通入口から、呼出元の機能を時系列へ追加します。Bukkit APIを使用しません。
     * @param accountId 操作対象
     * @param operation 実行する操作のクラス識別子
     */
    public void recordOperation(UUID accountId, String operation) {
        History history = histories.get(accountId);
        if (history != null) history.add(Instant.now() + " MUTATION " + operation);
    }

    /**
     * 保存スレッド上で証跡を確定しファイル出力します。通知は次のメインtickへ送ります。
     * @param failure 初回保存停止時の不変snapshotと例外
     */
    public void onFailure(PlayerStateFailure failure) {
        History history = histories.get(failure.accountId());
        JsonObject report = new JsonObject();
        report.addProperty("occurredAt", DISPLAY_TIME.format(failure.occurredAt()));
        report.addProperty("accountId", failure.accountId().toString());
        report.addProperty("snapshotId", failure.snapshotId().toString());
        report.addProperty("saveTrigger", failure.trigger());
        report.addProperty("attempt", failure.attempt());
        report.addProperty("thread", Thread.currentThread().getName());
        report.addProperty("failureType", failure.cause().getClass().getName());
        report.addProperty("failureMessage", failure.cause().getMessage());
        StringWriter stack = new StringWriter();
        failure.cause().printStackTrace(new PrintWriter(stack));
        report.addProperty("exception", stack.toString());
        if (failure.cause() instanceof io.github.maaasu.astralRecord.feature.inventory.repository.InventoryApiException api) {
            report.addProperty("httpMethod", api.getMethod());
            report.addProperty("httpPath", api.getPath());
            report.addProperty("httpStatus", api.getStatusCode());
            report.addProperty("httpResponse", api.getResponseBody());
        }
        if (history != null) {
            report.addProperty("playerUuid", history.playerId.toString());
            report.addProperty("playerName", history.name);
            report.add("recentEvents", history.snapshot());
        }
        report.add("rejectedSnapshot", com.google.gson.JsonParser.parseString(failure.payload()));
        Path path = plugin.getDataFolder().toPath().resolve("player-state-incidents")
            .resolve(FILE_TIME.format(failure.occurredAt()) + "_"
                + (history == null ? failure.accountId() : history.playerId) + "_" + failure.snapshotId() + ".json");
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, new GsonBuilder().setPrettyPrinting().create().toJson(report),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            Logger.error(LogId.E_7210, failure.cause(), failure.accountId(), path);
        } catch (java.io.IOException | RuntimeException writeFailure) {
            Logger.error(LogId.E_7211, writeFailure, failure.accountId());
            Logger.error(LogId.E_7210, failure.cause(), failure.accountId(), path);
        }
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            if (!stillBlocked.test(failure.accountId())) return;
            if (history != null) {
                Player player = plugin.getServer().getPlayer(history.playerId);
                AstPlayer current = player == null ? null : AstPlayerCache.get(player);
                if (current != null && current.getAccount().getUuid().equals(failure.accountId())) {
                    PlayerMessageService.getInstance().send(player, PlayerMsgId.P_7210,
                        DISPLAY_TIME.format(failure.occurredAt()), history.playerId);
                }
            }
            recovery.accept(failure.accountId());
        });
    }

    /** 入力受付時点のイベントを上限付きで記録します。因果確定と時系列を区別するため履歴とします。 */
    private void record(Player player, String event) {
        AstPlayer ast = AstPlayerCache.get(player);
        if (ast == null) return;
        History history = histories.computeIfAbsent(ast.getAccount().getUuid(), ignored ->
            new History(player.getUniqueId(), player.getName()));
        history.add(Instant.now() + " " + event + " world=" + player.getWorld().getName()
            + " xyz=" + player.getLocation().getBlockX() + "," + player.getLocation().getBlockY()
            + "," + player.getLocation().getBlockZ());
    }

    /** コマンド引数は認証情報や会話を含み得るため、コマンド名だけを記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        record(event.getPlayer(), event.getEventName() + " command=" + event.getMessage().split("\\s+", 2)[0]);
    }

    /** GUI入力の種別・slot・holderを処理前に記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player) {
            Object holder = event.getView().getTopInventory().getHolder();
            record(player, event.getEventName() + " click=" + event.getClick() + " action=" + event.getAction()
                + " slot=" + event.getRawSlot() + " holder=" + (holder == null ? "null" : holder.getClass().getName()));
        }
    }

    /** ドラッグ対象slotを処理前に記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player) record(player, event.getEventName() + " slots=" + event.getRawSlots());
    }

    /** アイテム・ブロック操作を処理前に記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        record(event.getPlayer(), event.getEventName() + " action=" + event.getAction() + " hand=" + event.getHand()
            + " material=" + (event.getItem() == null ? "null" : event.getItem().getType()));
    }

    /** ドロップ操作を処理前に記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent event) { record(event.getPlayer(), event.getEventName()); }

    /** テレポートの要求理由を処理前に記録します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onTeleport(PlayerTeleportEvent event) { record(event.getPlayer(), event.getEventName() + " cause=" + event.getCause()); }

    /** 退出保存前に最後の操作を記録します。保存停止の非同期通知までは履歴を保持します。 */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        record(event.getPlayer(), event.getEventName());
        UUID playerId = event.getPlayer().getUniqueId();
        histories.forEach((account, history) -> {
            if (history.playerId.equals(playerId)) {
                plugin.getServer().getScheduler().runTaskLater(plugin,
                    () -> histories.remove(account, history), 20L * 300);
            }
        });
    }

    private static final class History {
        private final UUID playerId;
        private final String name;
        private final ArrayDeque<String> events = new ArrayDeque<>();
        private History(UUID playerId, String name) { this.playerId = playerId; this.name = name; }
        private synchronized History nextSession(String playerName) {
            History next = new History(playerId, playerName);
            next.events.addAll(events);
            return next;
        }
        private synchronized void add(String event) {
            if (events.size() == HISTORY_LIMIT) events.removeFirst();
            events.addLast(event);
        }
        private synchronized JsonArray snapshot() {
            JsonArray result = new JsonArray();
            events.forEach(result::add);
            return result;
        }
    }
}
