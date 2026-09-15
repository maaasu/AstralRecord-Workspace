package io.github.maaasu.astralRecord.feature.network;

import org.jetbrains.annotations.Nullable;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/** Network APIから取得したチャンネル単位ロールを短時間だけ保持します。 */
public final class NetworkChannelAccessRegistry {
    static final long ACCESS_TTL_NANOS = TimeUnit.SECONDS.toNanos(5L);
    private static final Map<UUID, CachedAccess> accessByUser = new ConcurrentHashMap<>();

    private NetworkChannelAccessRegistry() {
    }

    /**
     * API応答を現在のロールスナップショットとして保存します。
     *
     * @param access 保存するチャンネルロール応答
     */
    public static void replace(NetworkChannelAccess access) {
        accessByUser.put(access.userUuid(), new CachedAccess(access, System.nanoTime()));
    }

    /**
     * 指定UUIDの期限内チャンネルロールを返します。
     *
     * @param userUuid 照会対象のプレイヤーUUID
     * @return 期限内のロール。未取得または失効時は{@code null}
     */
    public static @Nullable NetworkChannelAccess get(@Nullable UUID userUuid) {
        if (userUuid == null) return null;
        CachedAccess cached = accessByUser.get(userUuid);
        if (cached == null || System.nanoTime() - cached.updatedAtNanos() > ACCESS_TTL_NANOS) {
            return null;
        }
        return cached.access();
    }

    /**
     * 指定UUIDが期限内のチャンネルdebugロールを持つか返します。
     *
     * @param userUuid 照会対象のプレイヤーUUID
     * @return debugロールを持つ場合は{@code true}
     */
    public static boolean isDebugUser(@Nullable UUID userUuid) {
        NetworkChannelAccess access = get(userUuid);
        return access != null && access.channelKnown() && access.debugUser();
    }

    /**
     * 指定UUIDが期限内のglobal authorityロールを持つか返します。
     *
     * @param userUuid 照会対象のプレイヤーUUID
     * @return global authorityの場合は{@code true}
     */
    public static boolean isAuthority(@Nullable UUID userUuid) {
        NetworkChannelAccess access = get(userUuid);
        return access != null && access.channelKnown() && access.authority();
    }

    /**
     * 指定UUIDが期限内のチャンネル接続許可を持つか返します。
     *
     * @param userUuid 照会対象のプレイヤーUUID
     * @return 接続許可を持つ場合は{@code true}
     */
    public static boolean isAllowed(@Nullable UUID userUuid) {
        NetworkChannelAccess access = get(userUuid);
        return access != null && access.channelKnown() && access.allowed();
    }

    /**
     * 指定UUIDのチャンネル応答を反映した実効permissionを返します。
     *
     * @param userUuid 照会対象のプレイヤーUUID
     * @param storedPermission Game userに保存されたpermission
     * @return API応答と保存値の大きい方のpermission
     */
    public static int effectivePermission(@Nullable UUID userUuid, int storedPermission) {
        NetworkChannelAccess access = get(userUuid);
        if (access == null || !access.channelKnown()) return storedPermission;
        return Math.max(storedPermission, access.permission());
    }

    /** Plugin停止時にロールスナップショットを破棄します。 */
    public static void clear() {
        accessByUser.clear();
    }

    private record CachedAccess(NetworkChannelAccess access, long updatedAtNanos) {
    }
}
