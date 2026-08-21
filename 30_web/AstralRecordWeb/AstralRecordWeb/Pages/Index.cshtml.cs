using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages
{
    public class IndexModel : PageModel
    {
        public string JavaServerAddress => "mc.astralrecord.com";

        public int JavaServerPort => 25565;

        public void OnGet()
        {
        }
    }
}
