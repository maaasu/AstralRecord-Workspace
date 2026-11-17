package io.github.maaasu.astralrecordlobby;

import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** ロビーで初回アカウントを準備し、本人にWebログインコードを渡します。状態はメインスレッド専用です。 */
final class WebLoginDesk {
    private final AstralRecordLobbyPlugin plugin;
    private final LobbyNpc npc;
    private final PlayerMessageService messages;
    private final Map<UUID, Session> sessions = new HashMap<>();
    // 退出しても進行中の書込は取り消せないため、発行完了まではUUID単位のガードを保持します。
    private final Set<UUID> processing = new HashSet<>();

    WebLoginDesk(AstralRecordLobbyPlugin plugin) {
        this(plugin, new PlayerMessageService());
    }

    WebLoginDesk(AstralRecordLobbyPlugin plugin, PlayerMessageService messages) {
        this.plugin = plugin;
        this.messages = messages;
        npc = new LobbyNpc(plugin, "accountCreator.npc", "account_creator", "Webログイン受付", false, "アカウント作成");
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
                session.state = result == null || (result.isEmpty() && session.ignoreEmptySnapshot)
                    ? State.FAILED : State.READY;
            });
        });
    }

    void onQuit(UUID playerId) { sessions.remove(playerId); }

    /** 既存アカウントは変更せず、未保有が確認できた場合だけ初回作成します。 */
    void requestLoginCode(Player player) {
        UUID playerId = player.getUniqueId();
        Session session = sessions.get(playerId);
        if (processing.contains(playerId)) {
            messages.send(player, PlayerMsgId.WEB_LOGIN_IN_PROGRESS);
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
        boolean needsAccount = session.accounts.isEmpty();
        processing.add(playerId);
        messages.send(player, needsAccount ? PlayerMsgId.ACCOUNT_CREATING : PlayerMsgId.WEB_LOGIN_IN_PROGRESS);
        String minecraftName = player.getName();
        String serverId = plugin.serverId();
        var address = needsAccount ? player.getAddress() : null;
        String globalIp = address == null || address.getAddress() == null ? "" : address.getAddress().getHostAddress();
        plugin.getServer().getScheduler().runTaskAsynchronously(plugin, () -> {
            LobbyApiClient.AccountSummary created = null;
            boolean selected = false;
            if (needsAccount) {
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
            }
            LobbyApiClient.WebLoginCode code = null;
            if (!needsAccount || created != null) {
                try {
                    code = plugin.api().issueWebLoginCode(playerId, minecraftName, serverId);
                } catch (RuntimeException failure) {
                    // API応答や解析例外に含まれ得るコードは記録しません。
                    LobbyLog.warning(plugin, LobbyLog.Id.WEB_LOGIN_CODE_FAILED, null, playerId);
                }
            }
            LobbyApiClient.WebLoginCode issued = code;
            LobbyApiClient.AccountSummary result = created;
            boolean selectionComplete = selected;
            plugin.getServer().getScheduler().runTask(plugin, () -> {
                processing.remove(playerId);
                Session current = sessions.get(playerId);
                if (current == null) return;
                if (result != null) {
                    // 新接続の読込より確定した作成結果を優先し、後着の一覧応答も無効化します。
                    current.accounts = List.of(result);
                    current.state = State.READY;
                }
                if (current != session) {
                    if (needsAccount && result == null && current.accounts.isEmpty()) {
                        // 結果不明の書込と重なった空一覧では、未作成を確認できません。
                        current.ignoreEmptySnapshot = true;
                        if (current.state == State.READY) current.state = State.FAILED;
                    }
                    // ログインコードは要求元の接続にだけ渡します。
                    return;
                }
                if (needsAccount && result == null) {
                    current.state = State.FAILED;
                    messages.send(player, PlayerMsgId.ACCOUNT_CREATE_FAILED);
                    return;
                }
                if (result != null) {
                    messages.send(player, selectionComplete ? PlayerMsgId.ACCOUNT_CREATED
                        : PlayerMsgId.ACCOUNT_CREATED_SELECTION_FAILED, result.accountName());
                }
                if (issued == null) messages.send(player, PlayerMsgId.WEB_LOGIN_CODE_FAILED);
                else messages.sendWebLoginCode(player, issued);
            });
        });
    }

    private enum State { LOADING, READY, FAILED }
    private static final class Session {
        private State state = State.LOADING;
        private List<LobbyApiClient.AccountSummary> accounts = List.of();
        private boolean ignoreEmptySnapshot;
    }
}
