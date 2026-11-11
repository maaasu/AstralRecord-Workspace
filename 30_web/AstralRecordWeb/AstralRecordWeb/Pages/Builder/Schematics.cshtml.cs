using System.Net;
using System.Security.Claims;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.RazorPages;

namespace AstralRecordWeb.Pages.Builder;

[Authorize]
[RequestSizeLimit(MaxRequestBytes)]
[RequestFormLimits(MultipartBodyLengthLimit = MaxRequestBytes, ValueLengthLimit = 1024, ValueCountLimit = 8)]
public sealed class SchematicsModel(BuilderSchematicApiClient api) : PageModel
{
    public const int MaxFileBytes = 1024 * 1024;
    private const int MaxRequestBytes = MaxFileBytes + 64 * 1024;
    private const string LinkInstructions = "開発サーバーで /builder を開き、「.schemを登録」からアップロード用リンクを発行してください。";

    [BindProperty] public string? Token { get; set; }
    [BindProperty] public IFormFile? SchematicFile { get; set; }
    [TempData] public string? StatusMessage { get; set; }
    public bool CanUpload { get; private set; }
    public bool ShowList { get; private set; }
    public Guid? StatusId { get; private set; }
    public IReadOnlyList<BuilderSchematicUpload> Uploads { get; private set; } = [];
    public BuilderSchematicUpload? Upload { get; private set; }
    public string? ErrorMessage { get; private set; }

    public async Task<IActionResult> OnGetAsync(Guid? id, string? token, CancellationToken ct)
    {
        if (!TryGetPlayer(out var playerId)) return Challenge();

        if (!ModelState.IsValid || id == Guid.Empty)
        {
            ErrorMessage = "登録情報が見つかりませんでした。";
            Response.StatusCode = StatusCodes.Status404NotFound;
        }
        else if (id is { } uploadId)
        {
            var (code, upload) = await api.GetAsync(playerId, uploadId, ct);
            if (code == HttpStatusCode.NotFound)
            {
                ErrorMessage = "登録情報が見つかりませんでした。";
                Response.StatusCode = StatusCodes.Status404NotFound;
            }
            else if (upload?.Id == uploadId && upload.Status is "pending" or "registered" or "rejected")
            {
                Upload = upload;
                StatusId = uploadId;
            }
            else
            {
                ErrorMessage = "登録状況を取得できませんでした。しばらくしてから更新してください。";
                StatusId = uploadId;
            }
        }
        else if (token is not null)
        {
            Token = token;
            CanUpload = IsUploadToken(Token);
            if (!CanUpload) ErrorMessage = LinkInstructions;
        }
        else
        {
            ShowList = true;
            var uploads = await api.ListAsync(playerId, ct);
            if (uploads is not null)
                Uploads = uploads.Where(upload => upload is { Id: var entryId, Status: "pending" or "registered" } && entryId != Guid.Empty).ToArray();
            else
                ErrorMessage = "建築の一覧を取得できませんでした。しばらくしてから更新してください。";
        }
        return Page();
    }

    public async Task<IActionResult> OnPostAsync(CancellationToken ct)
    {
        if (!TryGetPlayer(out var playerId)) return Challenge();
        CanUpload = IsUploadToken(Token);
        if (!CanUpload)
            ErrorMessage = LinkInstructions;
        else if (SchematicFile is null || SchematicFile.Length == 0)
            ErrorMessage = ".schem ファイルを選択してください。";
        else if (SchematicFile.Length > MaxFileBytes)
            ErrorMessage = "1 MiB 以下のファイルを選択してください。";
        else if (!string.Equals(Path.GetExtension(SchematicFile.FileName), ".schem", StringComparison.OrdinalIgnoreCase))
            ErrorMessage = ".schem ファイルを選択してください。";
        else
        {
            var (code, upload) = await api.UploadAsync(playerId, Token!, SchematicFile, ct);
            if (upload is { Id: var uploadId, Status: "pending" } && uploadId != Guid.Empty)
                return RedirectToPage("/Builder/Schematics", new { id = uploadId });

            CanUpload = code != HttpStatusCode.Forbidden;
            ErrorMessage = code switch
            {
                HttpStatusCode.Forbidden => "このリンクでは送信できません。発行したMinecraftアカウントでログインし、新しいリンクを発行してください。",
                HttpStatusCode.BadRequest => ".schem ファイルの形式またはファイル名を確認してください。",
                HttpStatusCode.RequestEntityTooLarge => "1 MiB 以下のファイルを選択してください。",
                HttpStatusCode.TooManyRequests => "登録の上限に達しました。不要なファイルを一覧から削除するか、確認中のファイルの処理が終わってからお試しください。",
                _ => "送信結果を確認できませんでした。しばらくしてから開発サーバーの「登録済み建築」を確認してください。",
            };
        }
        return Page();
    }

    public async Task<IActionResult> OnPostDeleteAsync(Guid id, CancellationToken ct)
    {
        if (!TryGetPlayer(out var playerId)) return Challenge();
        if (id == Guid.Empty) return NotFound();

        var code = await api.DeleteAsync(playerId, id, ct);
        if (code == HttpStatusCode.NoContent)
        {
            StatusMessage = "アップロードを削除しました。";
            return RedirectToPage("/Builder/Schematics");
        }
        if (code == HttpStatusCode.NotFound)
        {
            StatusMessage = "指定したアップロードは一覧にありません。";
            return RedirectToPage("/Builder/Schematics");
        }

        ErrorMessage = "削除の結果を確認できませんでした。登録状況を更新してください。";
        StatusId = id;
        return Page();
    }

    public static string RejectionMessage(string? error) => error switch
    {
        "unsupported_format" => "対応している .schem 形式で保存してください。",
        "too_large" => "ファイル内の建築が大きすぎます。範囲を小さくして保存してください。",
        "unsafe_content" => "登録できないデータが含まれています。ファイルを確認してください。",
        "incompatible_schematic" => "開発サーバーの WorldEdit で読み込めませんでした。対応するバージョンで保存してください。",
        _ => ".schem ファイルを確認し、保存し直してください。",
    };

    private bool TryGetPlayer(out Guid id) =>
        Guid.TryParse(User.FindFirstValue(ClaimTypes.NameIdentifier), out id) && id != Guid.Empty;

    private static bool IsUploadToken(string? token) => !string.IsNullOrWhiteSpace(token) && token.Length <= 256;
}
