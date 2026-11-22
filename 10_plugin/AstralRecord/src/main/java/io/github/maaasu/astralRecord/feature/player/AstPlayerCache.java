package io.github.maaasu.astralRecord.feature.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * オンラインプレイヤーの {@link AstPlayer} インスタンスをキャッシュするクラス。
 * <p>
 * プレイヤーのログイン時に {@link #put(AstPlayer)} でキャッシュに追加し、
 * ログアウト時に {@link #remove(UUID)} で削除してください。
 */
public final class AstPlayerCache {

    private static final Map<UUID, AstPlayer> CACHE = new ConcurrentHashMap<>();
    private static final Map<UUID, AstPlayer> BY_ACCOUNT = new HashMap<>();

    private AstPlayerCache() {
        // utility class
    }

    /**
     * {@link AstPlayer} をキャッシュに追加します。
     *
     * @param astPlayer キャッシュに追加するプレイヤー
     */
    public static synchronized void put(@NotNull AstPlayer astPlayer) {
        AstPlayer previous = CACHE.put(astPlayer.getBukkit().getUniqueId(), astPlayer);
        if (previous != null) BY_ACCOUNT.remove(accountId(previous), previous);
        UUID accountId = accountId(astPlayer);
        if (accountId != null) BY_ACCOUNT.put(accountId, astPlayer);
    }

    /**
     * 指定 UUID のプレイヤーがキャッシュ済みか判定します。
     *
     * @param uuid プレイヤーの UUID
     * @return キャッシュ済みの場合は true
     */
    public static boolean contains(@NotNull UUID uuid) {
        return CACHE.containsKey(uuid);
    }

    /**
     * UUID に対応する {@link AstPlayer} をキャッシュから取得します。
     *
     * @param uuid プレイヤーの UUID
     * @return {@link AstPlayer}、存在しない場合は {@code null}
     */
    @Nullable
    public static AstPlayer get(@NotNull UUID uuid) {
        return CACHE.get(uuid);
    }

    /**
     * アカウント ID に対応するオンラインセッションを取得します。
     *
     * @param accountId アカウントの UUID
     * @return 対応するセッション。未登録なら {@code null}
     */
    @Nullable
    public static synchronized AstPlayer getByAccount(@NotNull UUID accountId) {
        return BY_ACCOUNT.get(accountId);
    }

    /**
     * Bukkit {@link Player} に対応する {@link AstPlayer} をキャッシュから取得します。
     *
     * @param player Bukkit プレイヤー
     * @return {@link AstPlayer}、存在しない場合は {@code null}
     */
    @Nullable
    public static AstPlayer get(@NotNull Player player) {
        return CACHE.get(player.getUniqueId());
    }

    /**
     * UUID に対応するプレイヤーをキャッシュから削除します。
     *
     * @param uuid プレイヤーの UUID
     */
    public static synchronized void remove(@NotNull UUID uuid) {
        AstPlayer removed = CACHE.remove(uuid);
        if (removed != null) BY_ACCOUNT.remove(accountId(removed), removed);
    }

    /**
     * 指定したセッションが現在も登録されている場合だけキャッシュから削除します。
     *
     * @param uuid プレイヤーの UUID
     * @param expectedPlayer 削除対象として期待するプレイヤーセッション
     * @return 対象セッションを削除できた場合は {@code true}
     */
    public static synchronized boolean remove(@NotNull UUID uuid, @NotNull AstPlayer expectedPlayer) {
        if (!CACHE.remove(uuid, expectedPlayer)) return false;
        BY_ACCOUNT.remove(accountId(expectedPlayer), expectedPlayer);
        return true;
    }

    /**
     * キャッシュされているすべての {@link AstPlayer} を返します。
     *
     * @return {@link AstPlayer} のコレクション（変更不可）
     */
    @NotNull
    public static Collection<AstPlayer> getAll() {
        return Collections.unmodifiableCollection(CACHE.values());
    }

    /**
     * キャッシュをすべてクリアします。
     * プラグインのシャットダウン時に呼び出してください。
     */
    public static synchronized void clear() {
        CACHE.clear();
        BY_ACCOUNT.clear();
    }

    /** セッションに読み込まれているアカウントのIDを返します。 */
    private static @Nullable UUID accountId(AstPlayer player) {
        var account = player.getAccount();
        return account == null ? null : account.getUuid();
    }
}
