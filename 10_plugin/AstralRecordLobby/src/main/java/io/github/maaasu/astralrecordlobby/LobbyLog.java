package io.github.maaasu.astralrecordlobby;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.logging.Level;

/** ロビーNPC・アカウント処理のログを例外とともに記録します。 */
final class LobbyLog {
    enum Id { NPC_WORLD_UNAVAILABLE, ACCOUNT_LOAD_FAILED, ACCOUNT_CREATE_FAILED, ACCOUNT_SELECT_FAILED }
    private static final ResourceBundle MESSAGES = ResourceBundle.getBundle("lobby-logger", Locale.ROOT);

    private LobbyLog() { }

    static void warning(AstralRecordLobbyPlugin plugin, Id id, Throwable failure, Object... arguments) {
        plugin.getLogger().log(Level.WARNING, MessageFormat.format(MESSAGES.getString(id.name()), arguments), failure);
    }
}
