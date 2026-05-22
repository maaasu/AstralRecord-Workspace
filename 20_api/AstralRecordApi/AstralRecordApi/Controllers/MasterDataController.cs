using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>MasterDataDB の Seeder 管理 API</summary>
[ApiController]
[Route("api/master-data")]
public class MasterDataController(
    IMasterDataSeeder seeder,
    IMasterDataRepository repository) : ControllerBase
{
    /// <summary>filebase から MasterDataDB を同期する</summary>
    /// <remarks>
    /// filebase の YAML を走査し、差分を MasterDataDB へ反映します。
    /// <c>mode=rebuild</c> を指定すると entry/reference を全削除してから再投入します。
    /// </remarks>
    /// <param name="mode">同期モード。<c>diff</c>（既定）または <c>rebuild</c>。</param>
    /// <param name="cancellationToken">キャンセルトークン</param>
    /// <response code="200">同期成功</response>
    /// <response code="422">YAML 構文エラーや必須参照未解決により同期失敗</response>
    [HttpPost("seed")]
    [ProducesResponseType<MasterDataSeedResultResponse>(StatusCodes.Status200OK)]
    [ProducesResponseType<MasterDataSeedResultResponse>(StatusCodes.Status422UnprocessableEntity)]
    public async Task<ActionResult<MasterDataSeedResultResponse>> Seed(
        [FromQuery] string? mode,
        CancellationToken cancellationToken)
    {
        var seedMode = string.Equals(mode, "rebuild", StringComparison.OrdinalIgnoreCase)
            ? MasterDataSeedMode.Rebuild
            : MasterDataSeedMode.Diff;

        var result = await seeder.RunAsync(
            MasterDataSeedTrigger.SeederApi, seedMode, cancellationToken);

        return result.Status == "FAILED"
            ? UnprocessableEntity(result)
            : Ok(result);
    }

    /// <summary>Seeder 実行履歴を取得する</summary>
    /// <param name="limit">取得件数。1〜100。既定は 20。</param>
    /// <response code="200">実行履歴の取得成功</response>
    [HttpGet("seed-runs")]
    [ProducesResponseType<IReadOnlyList<MasterDataSeedRunResponse>>(StatusCodes.Status200OK)]
    public async Task<ActionResult<IReadOnlyList<MasterDataSeedRunResponse>>> GetSeedRuns(
        [FromQuery] int limit = 20)
    {
        var runs = await repository.GetSeedRunsAsync(Math.Clamp(limit, 1, 100));
        return Ok(runs);
    }

    /// <summary>MasterDataDB の参照可能状態を取得する</summary>
    /// <response code="200">状態の取得成功</response>
    [HttpGet("health")]
    [ProducesResponseType<MasterDataHealthResponse>(StatusCodes.Status200OK)]
    public async Task<ActionResult<MasterDataHealthResponse>> GetHealth()
    {
        var health = await repository.GetHealthAsync();
        return Ok(health);
    }
}
