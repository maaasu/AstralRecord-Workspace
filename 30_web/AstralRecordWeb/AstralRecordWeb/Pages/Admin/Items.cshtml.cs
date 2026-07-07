using System.Net;
using System.Text;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Admin;

[Authorize(Policy = "AdminOnly")]
public class ItemsModel(ItemMasterApiClient itemMasterApiClient) : PageModel
{
    private const string IconBaseUrl = "https://assets.mcasset.cloud/1.21.11/assets/minecraft/textures/item/";

    public IReadOnlyList<ItemMasterResponse> Items { get; private set; } = [];

    public IReadOnlyList<string> Categories { get; private set; } = [];

    public IReadOnlyList<string> Rarities { get; private set; } = [];

    public IReadOnlyList<string> Slots { get; private set; } = [];

    public string? ErrorMessage { get; private set; }

    [BindProperty(SupportsGet = true)]
    public string? Query { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Category { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Rarity { get; set; }

    [BindProperty(SupportsGet = true)]
    public string? Slot { get; set; }

    [BindProperty(SupportsGet = true)]
    public bool TradableOnly { get; set; }

    [BindProperty(SupportsGet = true)]
    public bool SellableOnly { get; set; }

    public async Task OnGetAsync(CancellationToken cancellationToken)
    {
        IReadOnlyList<ItemMasterResponse> allItems;
        try
        {
            allItems = await itemMasterApiClient.GetAllAsync(cancellationToken);
        }
        catch (HttpRequestException ex)
        {
            ErrorMessage = ex.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden
                ? "API の認証に失敗しました。Web 側の API キー設定を確認してください。"
                : "アイテムマスタの取得に失敗しました。API と MasterDataDB の状態を確認してください。";
            allItems = [];
        }

        Categories = allItems
            .Select(item => item.Category)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(value => value, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        Rarities = allItems
            .Select(item => item.Rarity)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(value => RaritySort(value))
            .ThenBy(value => value, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        Slots = allItems
            .Select(item => item.Equipment?.Slot)
            .Where(value => !string.IsNullOrWhiteSpace(value))
            .Select(value => value!)
            .Distinct(StringComparer.OrdinalIgnoreCase)
            .OrderBy(value => value, StringComparer.OrdinalIgnoreCase)
            .ToArray();

        Items = ApplyFilters(allItems).ToArray();
    }

    public string IconUrl(ItemMasterResponse item)
    {
        var iconFile = item.Icon
            .Trim()
            .ToLowerInvariant()
            .Replace(' ', '_');

        return IconBaseUrl + Uri.EscapeDataString(iconFile) + ".png";
    }

    public string FormatMinecraftText(string? text)
    {
        if (string.IsNullOrEmpty(text))
            return string.Empty;

        var builder = new StringBuilder();
        var currentClass = "mc-white";
        var openSpan = false;

        void OpenSpan()
        {
            if (!openSpan)
            {
                builder.Append("<span class=\"");
                builder.Append(currentClass);
                builder.Append("\">");
                openSpan = true;
            }
        }

        for (var index = 0; index < text.Length; index++)
        {
            var current = text[index];
            if ((current is '&' or '§') && index + 1 < text.Length)
            {
                var next = char.ToLowerInvariant(text[index + 1]);
                var cssClass = MinecraftColorClass(next);
                if (cssClass is not null)
                {
                    if (openSpan)
                    {
                        builder.Append("</span>");
                        openSpan = false;
                    }

                    currentClass = cssClass;
                    index++;
                    continue;
                }
            }

            OpenSpan();
            builder.Append(WebUtility.HtmlEncode(current.ToString()));
        }

        if (openSpan)
            builder.Append("</span>");

        return builder.ToString();
    }

    public string RarityClass(string? rarity)
        => "rarity-" + (rarity ?? "common").Trim().ToLowerInvariant();

    public static string StatValueText(ItemEquipmentStatValueResponse? value)
    {
        if (value is null)
            return string.Empty;

        if (string.Equals(value.Min, value.Max, StringComparison.OrdinalIgnoreCase))
            return value.Min;

        return $"{value.Min} - {value.Max}";
    }

    private IEnumerable<ItemMasterResponse> ApplyFilters(IEnumerable<ItemMasterResponse> items)
    {
        var filtered = items;

        if (!string.IsNullOrWhiteSpace(Query))
        {
            var query = Query.Trim();
            filtered = filtered.Where(item =>
                Contains(item.Id, query)
                || Contains(StripMinecraftCodes(item.Name), query)
                || Contains(item.Icon, query)
                || item.Lore.Any(line => Contains(StripMinecraftCodes(line), query)));
        }

        if (!string.IsNullOrWhiteSpace(Category))
            filtered = filtered.Where(item => EqualsIgnoreCase(item.Category, Category));

        if (!string.IsNullOrWhiteSpace(Rarity))
            filtered = filtered.Where(item => EqualsIgnoreCase(item.Rarity, Rarity));

        if (!string.IsNullOrWhiteSpace(Slot))
            filtered = filtered.Where(item => EqualsIgnoreCase(item.Equipment?.Slot, Slot));

        if (TradableOnly)
            filtered = filtered.Where(item => !item.UnTradeable);

        if (SellableOnly)
            filtered = filtered.Where(item => !item.UnSellable);

        return filtered;
    }

    private static bool Contains(string? source, string value)
        => source?.Contains(value, StringComparison.OrdinalIgnoreCase) == true;

    private static bool EqualsIgnoreCase(string? left, string? right)
        => string.Equals(left, right, StringComparison.OrdinalIgnoreCase);

    private static int RaritySort(string rarity)
        => rarity.ToUpperInvariant() switch
        {
            "COMMON" => 0,
            "UNCOMMON" => 1,
            "RARE" => 2,
            "EPIC" => 3,
            "LEGENDARY" => 4,
            "MYTHIC" => 5,
            _ => 99,
        };

    private static string StripMinecraftCodes(string text)
    {
        var builder = new StringBuilder(text.Length);
        for (var index = 0; index < text.Length; index++)
        {
            if ((text[index] is '&' or '§') && index + 1 < text.Length)
            {
                index++;
                continue;
            }

            builder.Append(text[index]);
        }

        return builder.ToString();
    }

    private static string? MinecraftColorClass(char code)
        => code switch
        {
            '0' => "mc-black",
            '1' => "mc-dark-blue",
            '2' => "mc-dark-green",
            '3' => "mc-dark-aqua",
            '4' => "mc-dark-red",
            '5' => "mc-dark-purple",
            '6' => "mc-gold",
            '7' => "mc-gray",
            '8' => "mc-dark-gray",
            '9' => "mc-blue",
            'a' => "mc-green",
            'b' => "mc-aqua",
            'c' => "mc-red",
            'd' => "mc-light-purple",
            'e' => "mc-yellow",
            'f' or 'r' => "mc-white",
            _ => null,
        };
}
