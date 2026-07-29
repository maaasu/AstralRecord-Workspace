package io.github.maaasu.astralarchitect.worldedit;

import com.sk89q.worldedit.IncompleteRegionException;
import com.sk89q.worldedit.LocalSession;
import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldedit.regions.CuboidRegion;
import com.sk89q.worldedit.regions.Region;
import com.sk89q.worldedit.world.World;
import io.github.maaasu.astralarchitect.ticket.BlockPosition;
import io.github.maaasu.astralarchitect.ticket.TicketBounds;
import org.bukkit.FluidCollisionMode;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;

/**
 * FAWE/WorldEditのプレイヤー選択をチケット用の不変値へ変換します。
 */
public final class WorldEditSelectionProvider {

    /**
     * プレイヤーの現在選択と照準先ブロックを取得します。
     * Bukkitのプレイヤー・ワールドAPIへ触れるためメインスレッドで呼び出してください。
     *
     * @param player 対象プレイヤー
     * @param maxBlockCount 許容総ブロック数
     * @param targetDistance 基準ブロックを探す最大距離
     * @return 確定した選択情報
     * @throws SelectionException 選択、範囲、基準ブロックが不正な場合
     */
    public WorldEditSelection capture(Player player, long maxBlockCount, int targetDistance)
            throws SelectionException {
        LocalSession session = com.sk89q.worldedit.WorldEdit.getInstance()
                .getSessionManager()
                .get(BukkitAdapter.adapt(player));

        World world = session.getSelectionWorld();
        if (world == null) {
            throw new SelectionException("WorldEditのPos1とPos2を先に指定してください。");
        }
        World playerWorld = BukkitAdapter.adapt(player.getWorld());
        if (!world.equals(playerWorld)) {
            throw new SelectionException("選択したワールドへ移動してからチケットを作成してください。");
        }

        Region selected;
        try {
            selected = session.getSelection(world);
        } catch (IncompleteRegionException exception) {
            throw new SelectionException("WorldEditのPos1とPos2を先に指定してください。", exception);
        }
        if (!(selected instanceof CuboidRegion)) {
            throw new SelectionException("初期版では直方体のWorldEdit選択だけを使用できます。");
        }

        BlockVector3 minimum = selected.getMinimumPoint();
        BlockVector3 maximum = selected.getMaximumPoint();
        CuboidRegion region = new CuboidRegion(world, minimum, maximum);
        TicketBounds bounds = new TicketBounds(toPosition(minimum), toPosition(maximum));
        long volume;
        try {
            volume = bounds.volume();
        } catch (ArithmeticException exception) {
            throw new SelectionException("選択範囲が大きすぎます。", exception);
        }
        if (bounds.width() > 65_535 || bounds.height() > 65_535 || bounds.length() > 65_535) {
            throw new SelectionException("Schematic v3の各軸サイズは65535ブロック以下にしてください。");
        }
        if (volume > maxBlockCount) {
            throw new SelectionException("選択範囲は" + volume + "ブロックです。上限は"
                    + maxBlockCount + "ブロックです。");
        }

        Block target = player.getTargetBlockExact(targetDistance, FluidCollisionMode.NEVER);
        if (target == null) {
            throw new SelectionException("基準にするブロックへ照準を合わせてください。");
        }
        BlockPosition anchor = new BlockPosition(target.getX(), target.getY(), target.getZ());
        if (!bounds.contains(anchor)) {
            throw new SelectionException("基準ブロックはWorldEdit選択範囲内に置いてください。");
        }
        return new WorldEditSelection(
                world,
                player.getWorld().getUID().toString(),
                player.getWorld().getName(),
                region,
                bounds,
                anchor,
                target.getBlockData().getAsString());
    }

    private static BlockPosition toPosition(BlockVector3 vector) {
        return new BlockPosition(vector.x(), vector.y(), vector.z());
    }
}
