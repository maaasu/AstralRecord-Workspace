using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface ILootRepository
{
    IReadOnlyList<LootPoolResponse> GetAllPools();

    LootPoolResponse? GetPoolById(string poolId);

    IReadOnlyList<LootTableResponse> GetAllTables();

    LootTableResponse? GetTableById(string tableId);
}
