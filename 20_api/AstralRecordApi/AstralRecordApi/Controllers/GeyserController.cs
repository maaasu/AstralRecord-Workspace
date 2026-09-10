using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>Geyser Extension の起動時データ API。</summary>
[ApiController]
[Route("api/geyser")]
public sealed class GeyserController(IGeyserHeadRepository geyserHeadRepository) : ControllerBase
{
    /// <summary>カスタムヘッドテクスチャと未削除登録ユーザー UUID をまとめて取得します。</summary>
    [HttpGet("heads")]
    [ProducesResponseType<GeyserHeadsResponse>(StatusCodes.Status200OK)]
    public async Task<IActionResult> GetHeads(CancellationToken cancellationToken)
        => Ok(await geyserHeadRepository.GetHeadsAsync(cancellationToken));
}
