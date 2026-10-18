using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordControl.Web.Pages;

[Authorize(Policy = "OwnerOnly")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class TransfersModel : PageModel
{
    public void OnGet() { }
}
