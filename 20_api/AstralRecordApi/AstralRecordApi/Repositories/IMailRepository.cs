using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IMailRepository
{
    Task<IReadOnlyList<MailResponse>> GetAvailableByUserIdAsync(Guid userId, string? filter);

    Task<MailResponse?> MarkReadAsync(string mailId, MailActionRequest request);

    Task<bool> DeleteAsync(string mailId, MailActionRequest request);
}
