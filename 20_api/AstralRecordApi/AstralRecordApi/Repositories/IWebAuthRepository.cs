using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebAuthRepository
{
    Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request);

    Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request);

    Task<bool> IsTrustedBrowserAsync(Guid userUuid, Guid sessionVersion, string? token);

    Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid);

    Task<bool> IsWebAdminAsync(Guid userUuid);

    Task<WebPasswordLoginResult> LoginWithPasswordAsync(WebPasswordLoginRequest request);

    Task<WebCredentialResponse?> GetCredentialAsync(Guid userUuid);

    Task<WebCredentialUpdateResult> UpdateCredentialAsync(Guid userUuid, WebCredentialUpdateRequest request);
}
