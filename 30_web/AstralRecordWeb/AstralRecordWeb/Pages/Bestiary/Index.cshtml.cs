using System.ComponentModel.DataAnnotations;
using System.Net;
using System.Security.Claims;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Bestiary;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(BestiaryApiClient bestiary) : PageModel
{
    [BindProperty(SupportsGet = true)] public Guid? AccountId { get; set; }
    [BindProperty(SupportsGet = true), StringLength(100)] public string? Query { get; set; }
    [BindProperty(SupportsGet = true)] public string? Category { get; set; }
    [BindProperty(SupportsGet = true)] public string? Sort { get; set; }
    [BindProperty(SupportsGet = true), Range(1, 100000)] public int PageNumber { get; set; } = 1;
    public WebBestiaryListResponse? Record { get; private set; }
    public IReadOnlyList<WebBestiaryMobSummaryResponse> Mobs { get; private set; } = [];
    public int MatchingCount { get; private set; }
    public int PageCount => Math.Max(1, (MatchingCount + 23) / 24);
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out var viewer)) return Challenge();
        Sort ??= "recent";
        if (!ModelState.IsValid) return BadRequest(ModelState);
        if (Category is not (null or "" or "ENEMY" or "BOSS") || Sort is not ("recent" or "count" or "name")) return BadRequest();
        var result = await bestiary.GetListAsync(viewer, AccountId, ct);
        if (result.Status == HttpStatusCode.NotFound) return NotFound();
        if (!result.Succeeded)
        {
            ErrorMessage = "冒険記録を取得できませんでした。時間をおいて再読み込みしてください。";
            return Page();
        }
        Record = result.Value!;
        AccountId = Record.AccountId;
        var filtered = Record.Mobs.Where(mob => (string.IsNullOrWhiteSpace(Query) || mob.Name.Contains(Query.Trim(), StringComparison.OrdinalIgnoreCase))
            && (string.IsNullOrEmpty(Category) || mob.Category == Category));
        var sorted = Sort switch
        {
            "count" => filtered.OrderByDescending(mob => mob.DefeatCount),
            "name" => filtered.OrderBy(mob => mob.Name, StringComparer.CurrentCulture),
            _ => filtered.OrderByDescending(mob => mob.LastDefeatedAt),
        };
        var matches = sorted.ThenBy(mob => mob.MobId, StringComparer.Ordinal).ToList();
        MatchingCount = matches.Count;
        PageNumber = Math.Min(PageNumber, PageCount);
        Mobs = matches.Skip((PageNumber - 1) * 24).Take(24).ToList();
        return Page();
    }
}
