using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Releases;

public sealed class DetailsModel(ReleaseNoteCatalog catalog) : PageModel
{
    public ReleaseNoteDocument? ReleaseNote { get; private set; }

    public async Task<IActionResult> OnGetAsync(string slug, CancellationToken cancellationToken)
    {
        var releases = await catalog.GetPublishedAsync(cancellationToken);
        ReleaseNote = releases.SingleOrDefault(note =>
            string.Equals(note.Slug, slug, StringComparison.OrdinalIgnoreCase));

        return ReleaseNote is null ? NotFound() : Page();
    }
}
