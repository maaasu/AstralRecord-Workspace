package io.github.maaasu.astralRecord.feature.player;

import io.github.maaasu.astralRecord.feature.player.model.AstPlayer;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Collections;
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

    private AstPlayerCache() {
        // utility class
    }

    /**
     * {@link AstPlayer} をキャッシュに追加します。
     *
     * @param astPlayer キャッシュに追加するプレイヤー
     */
    public static void put(@NotNull AstPlayer astPlayer) {
        CACHE.put(astPlayer.getBukkit().getUniqueId(), astPlayer);
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
    public static void remove(@NotNull UUID uuid) {
        CACHE.remove(uuid);
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
    public static void clear() {
        CACHE.clear();
    }
}
