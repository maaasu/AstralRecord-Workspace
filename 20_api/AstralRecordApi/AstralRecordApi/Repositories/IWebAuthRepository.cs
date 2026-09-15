using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebAuthRepository
{
    Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request);

    Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request);

    Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid);

    Task<bool> IsWebAdminAsync(Guid userUuid);
}
