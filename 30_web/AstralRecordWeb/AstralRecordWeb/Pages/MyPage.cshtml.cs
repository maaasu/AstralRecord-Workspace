using System.Security.Claims;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages;

[Authorize]
public class MyPageModel : PageModel
{
    public string Mcid { get; private set; } = string.Empty;
    public string UserUuid { get; private set; } = string.Empty;
    public string Permission { get; private set; } = string.Empty;
    public string CurrentAccountId { get; private set; } = "-";

    public void OnGet()
    {
        Mcid = User.Identity?.Name ?? string.Empty;
        UserUuid = User.FindFirstValue(ClaimTypes.NameIdentifier) ?? string.Empty;
        Permission = User.FindFirstValue("permission") ?? "0";
        CurrentAccountId = User.FindFirstValue("currentAccountId") ?? "-";
    }
}
