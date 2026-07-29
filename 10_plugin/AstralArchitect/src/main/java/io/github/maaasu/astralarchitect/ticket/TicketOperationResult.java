package io.github.maaasu.astralarchitect.ticket;

/**
 * 非同期チケット操作の結果です。
 *
 * @param ticket 更新後チケット
 * @param affectedBlocks 変更または検証した差分ブロック数
 */
public record TicketOperationResult(TicketMetadata ticket, long affectedBlocks) {
}
