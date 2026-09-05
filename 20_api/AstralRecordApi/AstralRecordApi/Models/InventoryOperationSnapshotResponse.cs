namespace AstralRecordApi.Models;

/// <summary>操作応答時の所有者限定インベントリ正本。covered内でentriesにないIDは削除・所有外を表す。</summary>
public class InventoryOperationSnapshotResponse
{
    public Guid AccountId { get; init; }
    public IReadOnlyList<Guid> CoveredEntryIds { get; init; } = [];
    public IReadOnlyList<InventoryEntryResponse> Entries { get; init; } = [];
    public Guid? CurrencyInventoryId { get; init; }
    public IReadOnlyList<InventoryEntryResponse> CurrencyEntries { get; init; } = [];
}
