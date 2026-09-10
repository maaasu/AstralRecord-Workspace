using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IMailRepository
{
    Task<IReadOnlyList<MailResponse>> GetAvailableByAccountIdAsync(Guid accountId, string? filter);

    Task<int> CountUnreadByAccountIdAsync(Guid accountId);

    Task<MailResponse?> MarkReadAsync(string mailId, MailActionRequest request);

    Task<bool> DeleteAsync(string mailId, MailActionRequest request);
}
