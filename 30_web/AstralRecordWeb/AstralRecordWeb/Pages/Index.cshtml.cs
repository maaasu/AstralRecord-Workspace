using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Pages
{
    public class IndexModel(IOptions<PublicSiteOptions> publicSiteOptions, MinecraftServerStatusService statusService) : PageModel
    {
        public PublicSiteOptions PublicSite { get; } = publicSiteOptions.Value;

        public MinecraftServerStatus ServerStatus { get; private set; } = new("unknown", null, null, DateTimeOffset.MinValue);

        public async Task OnGetAsync(CancellationToken ct) => ServerStatus = await statusService.GetAsync(ct);

        public async Task<JsonResult> OnGetServerStatusAsync(CancellationToken ct)
        {
            Response.Headers.CacheControl = "no-store";
            return new JsonResult(await statusService.GetAsync(ct));
        }
    }
}
