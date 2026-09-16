using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebBestiaryRepository
{
    Task<WebBestiaryListResponse?> GetListAsync(Guid viewerUserUuid, Guid? accountId);
    Task<WebBestiaryDetailResponse?> GetDetailAsync(Guid viewerUserUuid, Guid? accountId, string mobId);
}
