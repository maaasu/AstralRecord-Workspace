using System.Net;
using System.Security.Claims;
using System.Text.Json;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.SkillTree;

[Authorize]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class IndexModel(SkillTreeEditorApiClient editor) : PageModel
{
    [BindProperty(SupportsGet = true)] public Guid AccountId { get; set; }
    public JsonElement? EditorState { get; private set; }
    public WebSkillTreeProfileResponse? Tree { get; private set; }
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (!ModelState.IsValid || AccountId == Guid.Empty) return BadRequest();
        var result = await editor.GetStateAsync(actor, AccountId, ct);
        if (result.Status is HttpStatusCode.NotFound or HttpStatusCode.Forbidden) return NotFound();
        if (result.Succeeded)
        {
            EditorState = result.Value;
            if (result.Value.TryGetProperty("tree", out var tree) && tree.ValueKind == JsonValueKind.Object)
            {
                try { Tree = tree.Deserialize<WebSkillTreeProfileResponse>(new JsonSerializerOptions(JsonSerializerDefaults.Web)); }
                catch (JsonException) { ErrorMessage = "サーバーの更新待ちです。最新の状態を再取得してください。"; }
            }
            Tree ??= new WebSkillTreeProfileResponse { StructureId = "", Name = "", RootNodeId = "", Nodes = [], Edges = [] };
        }
        else ErrorMessage = "現在の状態を取得できませんでした。時間をおいて再取得してください。";
        return Page();
    }

    public async Task<IActionResult> OnGetStateAsync(CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (!ModelState.IsValid || AccountId == Guid.Empty) return BadRequest();
        return ApiResult(await editor.GetStateAsync(actor, AccountId, ct));
    }

    public async Task<IActionResult> OnPostOperationAsync([FromBody] SkillTreeOperationInput input, CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (!ModelState.IsValid || AccountId == Guid.Empty || input.OperationId == Guid.Empty) return BadRequest();
        return ApiResult(await editor.SubmitAsync(actor, AccountId, input, ct));
    }

    public async Task<IActionResult> OnGetOperationAsync(Guid operationId, CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (!ModelState.IsValid || AccountId == Guid.Empty || operationId == Guid.Empty) return BadRequest();
        return ApiResult(await editor.GetOperationAsync(actor, AccountId, operationId, ct));
    }

    public async Task<IActionResult> OnPostCancelAsync(Guid operationId, CancellationToken ct)
    {
        if (!TryActor(out var actor)) return Challenge();
        if (!ModelState.IsValid || AccountId == Guid.Empty || operationId == Guid.Empty) return BadRequest();
        return ApiResult(await editor.CancelAsync(actor, AccountId, operationId, ct));
    }

    private bool TryActor(out Guid actor) => Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out actor);

    private IActionResult ApiResult(ProfileApiResult<JsonElement> result) => result.Succeeded
        ? new JsonResult(result.Value)
        : new JsonResult(new { message = result.Status == HttpStatusCode.Conflict
            ? "最新の状態を再取得してください。条件が変わったため、変更は確定していません。"
            : "操作結果を確認できませんでした。再取得して状態を確認してください。" }) { StatusCode = (int)result.Status };
}
