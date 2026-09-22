using System.Globalization;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Market;

[Authorize]
public sealed class HistoryModel(MarketApiClient marketApiClient) : PageModel
{
    [BindProperty(SupportsGet = true)] public string? Category { get; set; }
    [BindProperty(SupportsGet = true)] public string? ItemId { get; set; }
    [BindProperty(SupportsGet = true)] public int PageNumber { get; set; } = 1;

    public IReadOnlyList<MarketTradeHistoryItem> Transactions { get; private set; } = [];
    public bool HasNextPage { get; private set; }
    public bool ShowPagination => PageNumber > 1 || HasNextPage;
    public string? ErrorMessage { get; private set; }

    public async Task OnGetAsync(CancellationToken cancellationToken)
    {
        Category = Category?.Trim();
        ItemId = ItemId?.Trim();
        if (PageNumber is < 1 or > 100000 || Category?.Length > 50 || ItemId?.Length > 100)
        {
            ErrorMessage = "検索条件またはページ番号を確認してください。";
            return;
        }

        try
        {
            var result = await marketApiClient.GetTradeHistoryAsync(
                Category,
                ItemId,
                PageNumber,
                cancellationToken);
            Transactions = result.Items;
            HasNextPage = result.HasNextPage;
        }
        catch (HttpRequestException exception)
        {
            ErrorMessage = exception.StatusCode is System.Net.HttpStatusCode.Unauthorized or System.Net.HttpStatusCode.Forbidden
                ? "取引履歴を取得できませんでした。運営にお問い合わせください。"
                : "取引履歴を取得できませんでした。しばらくしてから再試行してください。";
        }
        catch (OperationCanceledException) when (!cancellationToken.IsCancellationRequested)
        {
            ErrorMessage = "取引履歴の取得がタイムアウトしました。時間をおいて再試行してください。";
        }
        catch (JsonException)
        {
            ErrorMessage = "取引履歴を表示できませんでした。時間をおいて再試行してください。";
        }
    }

    public string PageLink(int pageNumber)
    {
        var values = new Dictionary<string, string?>
        {
            [nameof(Category)] = Category,
            [nameof(ItemId)] = ItemId,
            [nameof(PageNumber)] = pageNumber.ToString(CultureInfo.InvariantCulture),
        };
        return Microsoft.AspNetCore.WebUtilities.QueryHelpers.AddQueryString(Request.Path, values);
    }

    public static string CompletedAtText(DateTime completedAt) =>
        DateTime.SpecifyKind(completedAt, DateTimeKind.Utc).AddHours(9).ToString("yyyy/MM/dd HH:mm", CultureInfo.InvariantCulture);
}
