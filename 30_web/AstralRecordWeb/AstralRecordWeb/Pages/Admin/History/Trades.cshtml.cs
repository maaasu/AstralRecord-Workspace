using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordWeb.Pages.Admin.History;

[Authorize(Policy = "WebAdminOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class TradesModel(
    ActivityHistoryApiClient api,
    ItemMasterApiClient itemMasterApi,
    ILogger<TradesModel> logger) : HistoryPageModel
{
    [BindProperty(SupportsGet = true)] public Guid? UserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? OtherUserUuid { get; set; }
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    public PagedPlayerActivityResponse<PlayerTradeActivityResponse>? Results { get; private set; }
    public IReadOnlyDictionary<string, string> ItemNames { get; private set; }
        = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);

    public string FormatItemName(PlayerTradeItemResponse item)
        => MinecraftTextFormatter.ToHtml(ItemNames.GetValueOrDefault(item.ItemId, item.ItemName));

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Prepare(out var actor)) return Page();
        var result = await api.GetAsync<PagedPlayerActivityResponse<PlayerTradeActivityResponse>>("trades", actor, FromUtc, ToUtc, PageNumber, Query, ct,
            ("userUuid", UserUuid?.ToString()), ("otherUserUuid", OtherUserUuid?.ToString()), ("accountId", AccountId?.ToString()));
        if (result.Status is System.Net.HttpStatusCode.Forbidden or System.Net.HttpStatusCode.Unauthorized) return Forbid();
        Results = result.Value;
        if (!result.Succeeded) ErrorMessage = "トレード履歴を取得できません。時間をおいて再試行してください。";
        else if (Results is { Items.Count: > 0 })
        {
            try
            {
                ItemNames = await itemMasterApi.GetNamesAsync(
                    Results.Items.SelectMany(trade => trade.Items).Select(item => item.ItemId),
                    ct);
            }
            catch (OperationCanceledException) when (ct.IsCancellationRequested)
            {
                throw;
            }
            catch (Exception ex) when (ex is HttpRequestException or TaskCanceledException or System.Text.Json.JsonException or NotSupportedException)
            {
                logger.LogWarning(ex, "Item master names could not be loaded for trade history.");
                ErrorMessage = "アイテムマスタ名を取得できなかったため、履歴保存時の名称を表示しています。";
            }
        }
        return Page();
    }
}
