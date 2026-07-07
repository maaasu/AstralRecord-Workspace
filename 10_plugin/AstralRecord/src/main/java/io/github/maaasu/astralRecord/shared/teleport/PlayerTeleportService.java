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
}
