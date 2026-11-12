package io.github.maaasu.astralRecord.feature.builder.service;

import io.github.maaasu.astralRecord.feature.builder.model.BuilderOperation;
import io.github.maaasu.astralRecord.feature.builder.repository.BuilderSchematicRepository;
import io.github.maaasu.astralRecord.feature.builder.repository.BuilderSchematicRepository.Entry;
import io.github.maaasu.astralRecord.feature.player.AstPlayerCache;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgId;
import io.github.maaasu.astralRecord.feature.player.PlayerMsgResource;
import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import io.github.maaasu.astralRecord.feature.player.service.PlayerMessageService;
import io.github.maaasu.astralRecord.infrastructure.config.ConfigKeys;
import io.github.maaasu.astralRecord.infrastructure.logging.LogId;
import io.github.maaasu.astralRecord.infrastructure.logging.Logger;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** dev のアップロード検証と、ビルダー本人のクリップボードへの読み込みを担当します。 */
public final class BuilderSchematicImport {
    private static final long POLL_TICKS = 100;
    private static final int MAX_GUI_REQUESTS = 16;
    private static final String CONFIG = "builder.schematicImport.";
    private final Plugin plugin;
    private final WorldEditGateway gateway;
    private final Set<UUID> requests = new HashSet<>();
    private final AtomicBoolean polling = new AtomicBoolean();
    private BuilderSchematicRepository repository;
    private URI uploadPage;
    private BukkitTask pollTask;
    private volatile boolean stopped;
    private long lastPollWarning;

    BuilderSchematicImport(Plugin plugin, WorldEditGateway gateway) {
        this.plugin = plugin;
        this.gateway = gateway;
    }

    /** プラグイン有効化と API 初期化の後に、dev の検証待ち監視を開始します。 */
    public void start() {
        if (!enabled() || pollTask != null) return;
        uploadPage = uploadPage(plugin.getConfig().getString(CONFIG + "webBaseUrl", ""));
        String devKey = plugin.getConfig().getString(CONFIG + "devKey", "");
        if (devKey.isBlank()) return;
        repository = new BuilderSchematicRepository(devKey);
        pollTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::poll, POLL_TICKS, POLL_TICKS);
    }

    /** @return インポートの対象である Dev サーバーが稼働中なら true */
    public boolean enabled() {
        return !stopped && "Dev".equalsIgnoreCase(plugin.getConfig().getString(ConfigKeys.API_SERVER_ID));
    }

    /** @param player 本人用の登録リンクを受け取るビルダー */
    public void sendUploadLink(Player player) {
        URI page = uploadPage;
        if (page == null) {
            if (currentOwner(player) != null) notify(player, PlayerMsgId.P_7765);
            return;
        }
        submit(player, owner -> repository.issueTicket(owner), token -> {
            String url = page + "?token=" + token;
            player.closeInventory();
            PlayerMessageService.getInstance().sendComponent(player,
                    PlayerMsgResource.formatComponent(PlayerMsgId.P_7760.getId()).clickEvent(ClickEvent.openUrl(url)));
        }, token -> { });
    }

    /** @param player 登録済み一覧を要求するビルダー
     * @param display メインスレッド上で本人の一覧を描画する処理 */
    public void list(Player player, Consumer<List<Entry>> display) {
        submit(player, owner -> repository.registered(owner), display, entries -> { });
    }

    /** @param player 読み込むビルダー
     * @param id 本人の登録済み建築 ID
     * @param onLoaded メインスレッド上で貼り付け画面へ進む処理 */
    public void load(Player player, UUID id, Runnable onLoaded) {
        if (!enabled() || !BuilderService.hasAccess(player)) return;
        if (!player.hasPermission(BuilderOperation.PASTE.permission)) {
            notify(player, PlayerMsgId.P_7702);
            return;
        }
        try {
            WorldEditGateway.SchematicCodec codec = gateway.schematicCodec();
            if (codec == null) {
                notify(player, PlayerMsgId.P_7701);
                return;
            }
            int maxBlocks = maxBlocks();
            submit(player, owner -> {
                byte[] bytes = repository.read(id, owner);
                return codec.read(bytes, SchematicImportValidator.validate(bytes, maxBlocks));
            }, clipboard -> {
                boolean installed = false;
                try {
                    if (!player.hasPermission(BuilderOperation.PASTE.permission)) {
                        notify(player, PlayerMsgId.P_7702);
                        return;
                    }
                    installed = gateway.installClipboard(player, codec, clipboard);
                    if (!installed) {
                        notify(player, PlayerMsgId.P_7761);
                        return;
                    }
                    notify(player, PlayerMsgId.P_7763);
                    onLoaded.run();
                } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
                    notify(player, PlayerMsgId.P_7761);
                } finally {
                    if (!installed) closeClipboard(clipboard);
                }
            }, BuilderSchematicImport::closeClipboard);
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            notify(player, PlayerMsgId.P_7761);
        }
    }

    /** 停止後の検証結果と GUI の反映を破棄します。 */
    void stop() {
        stopped = true;
        if (pollTask != null) pollTask.cancel();
        requests.clear();
    }

    private static URI uploadPage(String baseUrl) {
        if (baseUrl.isBlank()) return null;
        try {
            URI uri = URI.create(baseUrl);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) return null;
            return URI.create(uri.toASCIIString().replaceAll("/+$", "") + "/Builder/Schematics");
        } catch (IllegalArgumentException invalid) {
            return null;
        }
    }

    private int maxBlocks() {
        return Math.max(1, plugin.getConfig().getInt("builder.maxBlocks", 100_000));
    }

    private void poll() {
        if (!enabled() || repository == null || !polling.compareAndSet(false, true)) return;
        try {
            WorldEditGateway.SchematicCodec codec = gateway.schematicCodec();
            if (codec == null) {
                polling.set(false);
                return;
            }
            int maxBlocks = maxBlocks();
            plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> validatePending(codec, maxBlocks));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException failure) {
            polling.set(false);
            warnPoll(failure);
        }
    }

    private void validatePending(WorldEditGateway.SchematicCodec codec, int maxBlocks) {
        try {
            for (Entry entry : repository.pending()) {
                if (stopped) return;
                byte[] bytes = repository.read(entry.id(), null);
                if (bytes == null) continue;
                String error = validationError(codec, bytes, maxBlocks);
                if (!stopped) repository.decide(entry.id(), error);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (IOException | RuntimeException failure) {
            warnPoll(failure);
        } finally {
            polling.set(false);
        }
    }

    private static String validationError(WorldEditGateway.SchematicCodec codec, byte[] bytes, int maxBlocks) {
        SchematicImportValidator.Validation validation;
        try {
            validation = SchematicImportValidator.validate(bytes, maxBlocks);
        } catch (IOException invalid) {
            return "invalid_schematic";
        }
        try {
            closeClipboard(codec.read(bytes, validation));
            return null;
        } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            return "incompatible_schematic";
        }
    }

    private void warnPoll(Throwable failure) {
        long now = System.nanoTime();
        if (lastPollWarning == 0 || now - lastPollWarning >= Duration.ofMinutes(1).toNanos()) {
            lastPollWarning = now;
            Logger.log(LogId.E_7702, failure);
        }
    }

    private <T> void submit(Player player, OwnerRequest<T> operation, Consumer<T> apply, Consumer<T> discard) {
        UUID owner = currentOwner(player);
        if (owner == null) return;
        if (repository == null) {
            notify(player, PlayerMsgId.P_7765);
            return;
        }
        if (requests.size() >= MAX_GUI_REQUESTS || !requests.add(player.getUniqueId())) {
            notify(player, PlayerMsgId.P_7762);
            return;
        }
        UUID playerId = player.getUniqueId();
        Inventory inventory = player.getOpenInventory().getTopInventory();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            T result = null;
            try {
                result = operation.perform(owner);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } catch (IOException | ReflectiveOperationException | LinkageError | RuntimeException failure) {
                // 詳細には API やファイル内の値が含まれ得るため、画面へは固定メッセージを返す。
            }
            T response = result;
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    requests.remove(playerId);
                    if (!owner.equals(currentOwner(player)) || !inventory.equals(player.getOpenInventory().getTopInventory())) {
                        if (response != null) discard.accept(response);
                        return;
                    }
                    if (response == null) notify(player, PlayerMsgId.P_7761);
                    else apply.accept(response);
                });
            } catch (IllegalPluginAccessException disabled) {
                if (response != null) discard.accept(response);
            }
        });
    }

    private UUID currentOwner(Player player) {
        if (!enabled() || !player.isOnline() || !BuilderService.hasAccess(player)) return null;
        AstPlayer astPlayer = AstPlayerCache.get(player);
        if (astPlayer == null || astPlayer.getUser() == null) return null;
        return astPlayer.getUser().getUuid();
    }

    private static void closeClipboard(Object clipboard) {
        if (clipboard instanceof Closeable closeable) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // WorldEdit と FAWE で解放 API が異なる。読み込み結果は既に破棄している。
            }
        }
    }

    private static void notify(Player player, PlayerMsgId id) {
        PlayerMessageService.getInstance().send(player, id);
    }

    @FunctionalInterface
    private interface OwnerRequest<T> {
        T perform(UUID owner) throws IOException, InterruptedException, ReflectiveOperationException;
    }
}
