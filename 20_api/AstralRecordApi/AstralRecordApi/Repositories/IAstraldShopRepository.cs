using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IAstraldShopRepository
{
    Task<AstraldShopCatalogResponse> GetCatalogAsync();
    Task<AstraldShopPurchaseResponse> CreateAsync(Guid actor, AstraldShopPurchaseRequest request);
    Task<AstraldShopPurchaseResponse?> GetAsync(Guid actor, Guid operationId);
    Task<IReadOnlyList<AstraldShopPendingResponse>> PendingAsync(Guid accountId);
    Task<AstraldShopPurchaseResponse?> ProcessAsync(Guid operationId, AstraldShopProcessRequest request);
}
