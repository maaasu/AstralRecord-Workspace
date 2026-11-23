using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

public interface IMailRepository
{
    Task<IReadOnlyList<MailResponse>> GetAvailableByAccountIdAsync(Guid accountId, string? filter);

    Task<int> CountUnreadByAccountIdAsync(Guid accountId);

    /// <summary>指定アカウントの初回チュートリアルメールの受取状態を取得する。</summary>
    /// <param name="accountId">状態を確認するアカウント ID。</param>
    /// <returns>受取状態を表す API の文字列。</returns>
    Task<string> GetWelcomeTutorialStatusAsync(Guid accountId);

    Task<MailResponse?> MarkReadAsync(string mailId, MailActionRequest request);

    Task<bool> DeleteAsync(string mailId, MailActionRequest request);
}
