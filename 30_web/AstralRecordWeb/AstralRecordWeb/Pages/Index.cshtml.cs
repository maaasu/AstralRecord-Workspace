using AstralRecordWeb.Options;
using Microsoft.AspNetCore.Mvc.RazorPages;
using Microsoft.Extensions.Options;

namespace AstralRecordWeb.Pages
{
    public class IndexModel(IOptions<PublicSiteOptions> publicSiteOptions) : PageModel
    {
        public PublicSiteOptions PublicSite { get; } = publicSiteOptions.Value;

        public void OnGet()
        {
        }
    }
}
