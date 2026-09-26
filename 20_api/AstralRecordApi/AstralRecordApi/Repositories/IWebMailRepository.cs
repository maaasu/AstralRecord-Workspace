using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IWebMailRepository
{
    Task<IReadOnlyList<MailResponse>?> ListAsync(Guid actor, Guid accountId, string? filter);
    Task<WebMailCurrencyClaimResponse> CreateAsync(Guid actor, string mailId, WebMailCurrencyClaimRequest request);
    Task<WebMailCurrencyClaimResponse?> GetAsync(Guid actor, Guid operationId);
    Task<IReadOnlyList<WebMailClaimPendingResponse>> PendingAsync(Guid accountId);
    Task<WebMailCurrencyClaimResponse?> ProcessAsync(Guid operationId, WebMailClaimProcessRequest request);
}
