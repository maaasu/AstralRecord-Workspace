using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Releases;

public sealed class IndexModel(ReleaseNoteCatalog catalog) : PageModel
{
    public IReadOnlyList<ReleaseNoteDocument> Releases { get; private set; } = [];

    public async Task OnGetAsync(CancellationToken cancellationToken)
    {
        Releases = await catalog.GetPublishedAsync(cancellationToken);
    }
}
