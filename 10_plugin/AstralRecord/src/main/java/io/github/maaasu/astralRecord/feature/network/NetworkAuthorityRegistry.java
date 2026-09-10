package io.github.maaasu.astralRecord.feature.network;

import org.jetbrains.annotations.Nullable;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Proxy設定から同期したサーバー最高権限UUIDを保持します。 */
public final class NetworkAuthorityRegistry {
    public static final int AUTHORITY_PERMISSION = 99;
    static final long AUTHORITY_TTL_NANOS = TimeUnit.SECONDS.toNanos(30L);
    private static volatile Set<UUID> authorityUsers = Set.of();
    private static volatile long updatedAtNanos;

    private NetworkAuthorityRegistry() {
    }

    /**
     * 指定UUIDがProxyの最高権限ユーザーか判定します。
     *
     * @param playerId 判定対象UUID
     * @return 最高権限ユーザーならtrue
     */
    public static boolean isAuthority(@Nullable UUID playerId) {
        return isAuthority(playerId, System.nanoTime());
    }

    /**
     * Proxy最高権限を反映した実効permissionを返します。
     *
     * @param playerId 判定対象UUID
     * @param storedPermission APIに保存されたpermission
     * @return Proxy最高権限の場合は99、それ以外は保存値
     */
    public static int effectivePermission(@Nullable UUID playerId, int storedPermission) {
        return isAuthority(playerId) ? Math.max(AUTHORITY_PERMISSION, storedPermission) : storedPermission;
    }

    /** 取得済み最高権限UUID一覧を全置換する。 */
    static void replace(Set<UUID> users) {
        authorityUsers = Set.copyOf(users);
        updatedAtNanos = System.nanoTime();
    }

    /** 保持中の最高権限UUIDを破棄する。 */
    static void clear() {
        authorityUsers = Set.of();
        updatedAtNanos = 0L;
    }

    /** 指定時刻でTTL内にある最高権限UUIDか判定する。 */
    static boolean isAuthority(@Nullable UUID playerId, long nowNanos) {
        long ageNanos = nowNanos - updatedAtNanos;
        return playerId != null && updatedAtNanos != 0L
            && ageNanos >= 0L && ageNanos <= AUTHORITY_TTL_NANOS
            && authorityUsers.contains(playerId);
    }
}
