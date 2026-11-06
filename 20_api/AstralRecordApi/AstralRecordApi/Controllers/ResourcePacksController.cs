using System.Text.Json;
using AstralRecordApi.Models;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>配布中の Minecraft リソースパックを取得します。</summary>
[ApiController]
[Route("api/resource-packs")]
[ResponseCache(NoStore = true, Location = ResponseCacheLocation.None)]
public sealed class ResourcePacksController(IWebHostEnvironment environment) : ControllerBase
{
    private static readonly JsonSerializerOptions ManifestJson = new(JsonSerializerDefaults.Web);
    private readonly string directory = Path.Combine(environment.ContentRootPath, "resource-packs");

    /// <summary>配布中の両版パックのハッシュ、Bedrock UUID、Java版の表示機能（個人情報HUD、横長の戦闘ゲージ、アイテムツールチップ、通常攻撃の飛翔演出、スキル演出など）と効果音の互換情報を取得します。</summary>
    [HttpGet("manifest")]
    public async Task<ActionResult<ResourcePackManifest>> GetManifest(CancellationToken cancellationToken)
    {
        var manifest = await ReadManifestAsync(cancellationToken);
        if (manifest is null)
            return NotFound();

        return manifest;
    }

    /// <summary>指定された SHA-1 に一致する Java パックを取得します。</summary>
    [HttpGet("java/{sha1}.zip")]
    public IActionResult GetJava(string sha1) =>
        IsLowerHex(sha1, 40)
            ? GetArchive($"{sha1}.zip", "application/zip")
            : NotFound();

    /// <summary>指定された SHA-256 に一致する Bedrock パックを取得します。</summary>
    [HttpGet("bedrock/{sha256}.mcpack")]
    public IActionResult GetBedrock(string sha256) =>
        IsLowerHex(sha256, 64)
            ? GetArchive($"{sha256}.mcpack", "application/octet-stream")
            : NotFound();

    private async Task<ResourcePackManifest?> ReadManifestAsync(CancellationToken cancellationToken)
    {
        var path = Path.Combine(directory, "manifest.json");
        if (!System.IO.File.Exists(path))
            return null;

        await using var stream = System.IO.File.OpenRead(path);
        return await JsonSerializer.DeserializeAsync<ResourcePackManifest>(stream, ManifestJson, cancellationToken);
    }

    private IActionResult GetArchive(string fileName, string contentType)
    {
        var path = Path.Combine(directory, fileName);
        return System.IO.File.Exists(path) ? PhysicalFile(path, contentType) : NotFound();
    }

    private static bool IsLowerHex(string value, int length) =>
        value.Length == length && value.All(c => c is >= '0' and <= '9' or >= 'a' and <= 'f');
}
