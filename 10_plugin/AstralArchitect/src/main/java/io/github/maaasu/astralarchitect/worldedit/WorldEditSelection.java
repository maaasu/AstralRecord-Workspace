package io.github.maaasu.astralarchitect.worldedit;

import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.world.World;
import io.github.maaasu.astralarchitect.ticket.BlockPosition;
import io.github.maaasu.astralarchitect.ticket.TicketBounds;

/**
 * メインスレッドで確定したWorldEdit選択と建築基準点です。
 *
 * @param world WorldEditワールド
 * @param worldUuid BukkitワールドUUID
 * @param worldName ワールド名
 * @param region 直方体選択
 * @param bounds 永続化する絶対座標範囲
 * @param anchor 基準ブロック絶対座標
 * @param anchorBlockState 基準ブロック状態
 */
public record WorldEditSelection(
        World world,
        String worldUuid,
        String worldName,
        CuboidRegion region,
        TicketBounds bounds,
        BlockPosition anchor,
        String anchorBlockState) {
}
