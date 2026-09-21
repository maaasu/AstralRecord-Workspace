using System.Globalization;
using System.Security.Claims;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin.History;

/// <summary>履歴検索の期間とページ送りを共通化します。時刻は UTC で扱います。</summary>
public abstract class HistoryPageModel : PageModel
{
    [BindProperty(SupportsGet = true)] public DateOnly? From { get; set; }
    [BindProperty(SupportsGet = true)] public DateOnly? To { get; set; }
    [BindProperty(SupportsGet = true)] public string? Query { get; set; }
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;
    public string? ErrorMessage { get; protected set; }
    public const int PageSize = 50;
    public DateTimeOffset FromUtc => new(From!.Value.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc));
    public DateTimeOffset ToUtc => new(To!.Value.AddDays(1).ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc));

    protected bool Prepare(out Guid actor)
    {
        actor = default;
        if (!ModelState.IsValid)
        {
            ErrorMessage = "日付・ページ番号・検索条件を確認してください。";
            return false;
        }
        var today = DateOnly.FromDateTime(DateTime.UtcNow);
        From ??= today.AddDays(-29);
        To ??= today;
        Query = Query?.Trim();
        if (From > To || To == DateOnly.MaxValue || To.Value.DayNumber - From.Value.DayNumber >= 366
            || Query?.Length > 100 || PageNumber is < 1 or > 100000)
        {
            ErrorMessage = "期間は開始日から終了日まで最大366日、検索語は100文字以内で指定してください。";
            return false;
        }
        return Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);
    }

    public string PageLink(int pageNumber)
    {
        var values = Request.Query.ToDictionary(p => p.Key, p => (string?)p.Value.ToString(), StringComparer.OrdinalIgnoreCase);
        values[nameof(PageNumber)] = pageNumber.ToString(CultureInfo.InvariantCulture);
        values[nameof(From)] = From?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        values[nameof(To)] = To?.ToString("yyyy-MM-dd", CultureInfo.InvariantCulture);
        return Microsoft.AspNetCore.WebUtilities.QueryHelpers.AddQueryString(Request.Path, values);
    }

    public static string Timestamp(DateTime value) => value.ToString("yyyy-MM-dd HH:mm:ss", CultureInfo.InvariantCulture);
    public static string Duration(double seconds)
    {
        var duration = TimeSpan.FromSeconds(Math.Clamp(seconds, 0, 315360000));
        return duration.TotalHours >= 1
            ? $"{(long)duration.TotalHours}時間 {duration.Minutes}分 {duration.Seconds}秒"
            : $"{duration.Minutes}分 {duration.Seconds}秒";
    }
}
