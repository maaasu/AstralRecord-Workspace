using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebPlayerProfileRepository
{
    Task<WebPlayerProfileResponse?> GetMyProfileAsync(Guid viewerUserUuid);
    Task<WebPlayerProfileResponse?> GetProfileAsync(Guid targetUserUuid, Guid viewerUserUuid, bool includePrivate, Guid? accountId = null);
    Task<WebPlayerProfileSearchResponse> SearchAsync(
        Guid viewerUserUuid, string? mcid, string? classId, string? sort, int page, int pageSize, bool includePrivate);
    Task<WebPlayerProfileResponse?> UpdateVisibilityAsync(Guid viewerUserUuid, bool isPublic);
}
