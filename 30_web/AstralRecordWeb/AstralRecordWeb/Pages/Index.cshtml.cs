using AstralRecordWeb.Models;
using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Pages
{
    public class IndexModel(
        IOptions<PublicSiteOptions> publicSiteOptions,
        MinecraftServerStatusService statusService,
        ReleaseNoteCatalog releaseNoteCatalog) : PageModel
    {
        public PublicSiteOptions PublicSite { get; } = publicSiteOptions.Value;

        public MinecraftServerStatus ServerStatus { get; private set; } = new("unknown", null, null, DateTimeOffset.MinValue);

        public ReleaseNoteDocument? LatestRelease { get; private set; }

        public async Task OnGetAsync(CancellationToken ct)
        {
            var statusTask = statusService.GetAsync(ct);
            var releasesTask = releaseNoteCatalog.GetPublishedAsync(ct);
            await Task.WhenAll(statusTask, releasesTask);

            ServerStatus = await statusTask;
            LatestRelease = (await releasesTask).FirstOrDefault();
        }

        public async Task<JsonResult> OnGetServerStatusAsync(CancellationToken ct)
        {
            Response.Headers.CacheControl = "no-store";
            return new JsonResult(await statusService.GetAsync(ct));
        }
    }
}
