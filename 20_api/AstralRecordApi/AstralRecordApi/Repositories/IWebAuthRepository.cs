using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebAuthRepository
{
    Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request);

    Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request);
}
