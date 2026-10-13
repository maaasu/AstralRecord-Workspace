package io.github.maaasu.astralRecord.shared.teleport;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;

/**
 * プレイヤーの向きを保持する共通テレポート処理です。
 */
public final class PlayerTeleportService {

    private PlayerTeleportService() {
    }

    /**
     * 現在のXYZを維持して、DBに保存した視線を参加transaction内で同期復元します。
     * 操作状態の復元は通常移動の方向保持規則の例外です。
     * @param player 復元対象。Bukkitメインスレッドから呼びます
     * @param yaw 保存済みyaw
     * @param pitch 保存済みpitch
     * @return 転送がキャンセルされず方向を適用できた場合true
     */
    public static boolean restoreLookDirection(@NotNull Player player, float yaw, float pitch) {
        Location current = player.getLocation();
        current.setYaw(yaw);
        current.setPitch(pitch);
        return player.teleport(current);
    }

    /**
     * テレポート先の座標へ、移動前のプレイヤーの yaw / pitch を反映した Location を作成します。
     * 元の Location は変更せず、ワールド未設定などの検証は呼び出し側で行います。
     *
     * @param player 向きを引き継ぐ対象プレイヤー
     * @param targetLocation テレポート先座標
     * @return プレイヤーの現在向きを反映したテレポート先座標
     */
    @NotNull
    public static Location withCurrentLookDirection(
            @NotNull Player player,
            @NotNull Location targetLocation
    ) {
        Location orientedLocation = targetLocation.clone();
        Location currentLocation = player.getLocation();
        orientedLocation.setYaw(currentLocation.getYaw());
        orientedLocation.setPitch(currentLocation.getPitch());
        return orientedLocation;
    }

    /**
     * 移動前のプレイヤーの yaw / pitch を保持して同期テレポートします。
     *
     * @param player テレポート対象プレイヤー
     * @param targetLocation テレポート先座標
     * @return テレポートに成功した場合は {@code true}
     */
    public static boolean teleport(
            @NotNull Player player,
            @NotNull Location targetLocation
    ) {
        return player.teleport(withCurrentLookDirection(player, targetLocation));
    }

    /**
     * 移動前のプレイヤーの yaw / pitch を保持して、原因付きで同期テレポートします。
     *
     * @param player テレポート対象プレイヤー
     * @param targetLocation テレポート先座標
     * @param cause テレポート原因
     * @return テレポートに成功した場合は {@code true}
     */
    public static boolean teleport(
            @NotNull Player player,
            @NotNull Location targetLocation,
            @NotNull PlayerTeleportEvent.TeleportCause cause
    ) {
        return player.teleport(withCurrentLookDirection(player, targetLocation), cause);
    }

    /**
     * 移動前のプレイヤーの yaw / pitch を保持して非同期テレポートします。
     *
     * @param player テレポート対象プレイヤー
     * @param targetLocation テレポート先座標
     * @return テレポート結果を返す Future
     */
    @NotNull
    public static CompletableFuture<Boolean> teleportAsync(
            @NotNull Player player,
            @NotNull Location targetLocation
    ) {
        return player.teleportAsync(withCurrentLookDirection(player, targetLocation));
    }

    /**
     * 行き先が明示した向きで非同期転送します。通常の移動はteleportAsyncを使用します。
     * サーバーレコード入場の指定スポーン方向と、保存した帰還時の方向を復元する例外経路です。
     * @param player 移動対象プレイヤー
     * @param targetLocation 明示的なyaw/pitchを含む転送先
     * @return 転送結果
     */
    public static @NotNull CompletableFuture<Boolean> teleportAsyncWithTargetDirection(
            @NotNull Player player, @NotNull Location targetLocation
    ) {
        return player.teleportAsync(targetLocation.clone());
    }
}
