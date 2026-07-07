using System.Net;
using System.Net.Http.Json;
using AstralRecordWeb.Models;

namespace AstralRecordWeb.Services;

public class ItemMasterApiClient(HttpClient httpClient)
{
    public async Task<IReadOnlyList<ItemMasterResponse>> GetAllAsync(CancellationToken cancellationToken)
    {
        var summaries = await httpClient.GetFromJsonAsync<IReadOnlyList<ItemSummaryResponse>>(
            "api/item",
            cancellationToken) ?? [];

        var tasks = summaries
            .Select(summary => GetByIdAsync(summary.Id, cancellationToken))
            .ToArray();

        var items = await Task.WhenAll(tasks);
        return items
            .Where(item => item is not null)
            .Select(item => item!)
            .OrderBy(item => item.Category, StringComparer.OrdinalIgnoreCase)
            .ThenBy(item => item.Id, StringComparer.OrdinalIgnoreCase)
            .ToArray();
    }

    private async Task<ItemMasterResponse?> GetByIdAsync(string itemId, CancellationToken cancellationToken)
    {
        using var response = await httpClient.GetAsync(
            $"api/item/{Uri.EscapeDataString(itemId)}",
            cancellationToken);

        if (response.StatusCode == HttpStatusCode.NotFound)
            return null;

        response.EnsureSuccessStatusCode();
        return await response.Content.ReadFromJsonAsync<ItemMasterResponse>(cancellationToken);
    }
}
