package io.github.maaasu.astralarchitect.worldedit;

import com.sk89q.worldedit.world.block.BlockState;
import io.github.maaasu.astralarchitect.ticket.BlockPosition;

/**
 * sourceとcandidate間の単一ブロック差分です。
 *
 * @param relativePosition 選択最小点からの相対座標
 * @param sourceState 元ブロック状態
 * @param candidateState 候補ブロック状態
 */
public record SchematicChange(
        BlockPosition relativePosition,
        BlockState sourceState,
        BlockState candidateState) {
}
