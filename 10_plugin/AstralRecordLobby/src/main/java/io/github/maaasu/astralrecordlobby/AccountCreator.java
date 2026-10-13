package io.github.maaasu.astralrecordlobby;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** 参加時の保有キャッシュを使い、未保有者だけ初回アカウントを作成します。状態はメインスレッド専用です。 */
final class AccountCreator {
    private final AstralRecordLobbyPlugin plugin;
    private final LobbyNpc npc;
    private final PlayerMessageService messages;
    private final Map<UUID, Session> sessions = new HashMap<>();
    // 退出しても進行中の書込は取り消せないため、完了まではUUID単位のガードを保持します。
    private final Set<UUID> creating = new HashSet<>();

    AccountCreator(AstralRecordLobbyPlugin plugin) {
        this(plugin, new PlayerMessageService());
    }

    AccountCreator(AstralRecordLobbyPlugin plugin, PlayerMessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        npc = new LobbyNpc(plugin, "accountCreator.npc", "account_creator", "アカウント作成", false);
    }

    void start() { npc.start(); }
    void stop() { npc.stop(); sessions.clear(); }
    boolean isNpc(Entity entity) { return npc.matches(entity); }
    double raycastDistance() { return npc.raycastDistance(); }

    /** 参加ごとに一覧を一度だけ取得します。旧接続や作成前の遅延応答は採用しません。 */
    void onJoin(Player player) {
        if (!npc.enabled()) return;
        UUID playerId = player.getUniqueId();
        Session session = new Session();
        sessions.put(playerId, session);
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            List<LobbyApiClient.AccountSummary> accounts;
            try {
                accounts = plugin.api().getAccounts(playerId);
            } catch (RuntimeException failure) {
                LobbyLog.warning(plugin, LobbyLog.Id.ACCOUNT_LOAD_FAILED, failure, playerId);
                accounts = null;
            }
            List<LobbyApiClient.AccountSummary> result = accounts;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (sessions.get(playerId) != session || session.state != State.LOADING) return;
                session.accounts = result == null ? List.of() : result;
                session.state = result == null ? State.FAILED : State.READY;
            });
        });
    }

    void onQuit(UUID playerId) { sessions.remove(playerId); }

    /** 既存アカウント・取得失敗・作成中の場合は、追加API通信をせず終了します。 */
    void create(Player player) {
        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        if (session != null && session.state == State.READY && !session.accounts.isEmpty()) {
            messages.send(player, PlayerMsgId.ACCOUNT_ALREADY_EXISTS, session.accounts.getFirst().accountName());
            return;
        }
        if (creating.contains(playerId)) {
            messages.send(player, PlayerMsgId.ACCOUNT_CREATING);
            return;
        }
        if (session == null || session.state == State.FAILED) {
            messages.send(player, PlayerMsgId.ACCOUNT_UNAVAILABLE);
            return;
        }
        if (session.state == State.LOADING) {
            messages.send(player, PlayerMsgId.ACCOUNT_LOADING);
            return;
        }
        if (plugin.isEditing(playerId)) {
            messages.send(player, PlayerMsgId.ACCOUNT_EDITING);
            return;
        }
        creating.add(playerId);
        messages.send(player, PlayerMsgId.ACCOUNT_CREATING);
        String minecraftName = player.getName();
        String globalIp = player.getAddress() == null || player.getAddress().getAddress() == null
            ? "" : player.getAddress().getAddress().getHostAddress();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LobbyApiClient.AccountSummary created = null;
            boolean selected = false;
            try {
                plugin.api().ensureUser(playerId, minecraftName, globalIp);
                created = plugin.api().createAccount(playerId, minecraftName);
                try {
                    plugin.api().selectAccount(playerId, created.uuid());
                    selected = true;
                } catch (RuntimeException failure) {
                    LobbyLog.warning(plugin, LobbyLog.Id.ACCOUNT_SELECT_FAILED, failure, created.uuid(), playerId);
                }
            } catch (RuntimeException failure) {
                LobbyLog.warning(plugin, LobbyLog.Id.ACCOUNT_CREATE_FAILED, failure, playerId);
            }
            LobbyApiClient.AccountSummary result = created;
            boolean selectionComplete = selected;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                creating.remove(playerId);
                Session current = sessions.get(playerId);
                if (current == null) return;
                // 旧接続の結果不明は、再参加時に取得した新しい保有情報を消しません。
                if (result == null && current != session) return;
                // 新接続の読込より作成結果を優先し、後着の一覧応答も無効化します。
                current.accounts = result == null ? List.of() : List.of(result);
                current.state = result == null ? State.FAILED : State.READY;
                Player recipient = plugin.getServer().getPlayer(playerId);
                if (recipient == null) return;
                if (result == null) {
                    messages.send(recipient, PlayerMsgId.ACCOUNT_CREATE_FAILED);
                } else {
                    messages.send(recipient, selectionComplete ? PlayerMsgId.ACCOUNT_CREATED
                        : PlayerMsgId.ACCOUNT_CREATED_SELECTION_FAILED, result.accountName());
                }
            });
        });
    }

    private enum State { LOADING, READY, FAILED }
    private static final class Session {
        private State state = State.LOADING;
        private List<LobbyApiClient.AccountSummary> accounts = List.of();
    }
}
