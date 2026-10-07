package io.github.maaasu.astralRecord.feature.builder.model;

import java.util.UUID;

/**
 * 確認画面のために確定した操作。実行時に再検証し、一度だけ消費します。
 * @param id 要求の一意な識別子
 * @param playerId 確認を行ったプレイヤー
 * @param worldId 確認時のBukkitワールド
 * @param operation 固定の編集操作
 * @param command 素材と数値を確定したWorldEditコマンド
 * @param snapshot 確認時のWorldEdit状態
 * @param x 確認時のプレイヤーブロックX座標
 * @param y 確認時のプレイヤーブロックY座標
 * @param z 確認時のプレイヤーブロックZ座標
 * @param estimatedBlocks 保守的な対象ブロック数。履歴操作は不明を表す-1
 */
public record BuilderRequest(UUID id, UUID playerId, UUID worldId, BuilderOperation operation,
                             String command, WorldEditSnapshot snapshot, int x, int y, int z,
                             long estimatedBlocks) { }
