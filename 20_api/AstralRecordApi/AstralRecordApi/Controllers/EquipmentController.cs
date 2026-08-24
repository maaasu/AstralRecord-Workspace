using AstralRecordApi.Models;
using AstralRecordApi.Services;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>装備インスタンス API</summary>
[ApiController]
[Route("api/equipment")]
public class EquipmentController(IEquipmentService equipmentService) : ControllerBase
{
    /// <summary>装備インスタンス作成</summary>
    /// <remarks>
    /// マスタデータ（YAML）をもとに装備の個別動的データを生成して DB に保存します。
    /// ルーンスロット数・ステータス乱数ロールの解決を含む作成処理をサーバー側で行います。
    /// </remarks>
    /// <param name="request">作成リクエスト</param>
    /// <response code="201">装備インスタンス作成成功</response>
    /// <response code="404">指定した equipmentId または accountId が存在しない、または equipment カテゴリではない</response>
    [HttpPost("instances")]
    [ProducesResponseType(StatusCodes.Status201Created)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Create([FromBody] EquipmentCreateRequest request)
    {
        var created = await equipmentService.CreateAsync(request);
        if (created is null)
            return NotFound();

        return CreatedAtAction(nameof(GetByInstanceId),
            new { instanceId = created.EquipmentInstanceId },
            created);
    }

    /// <summary>装備インスタンス取得</summary>
    /// <param name="instanceId">装備インスタンス ID</param>
    /// <response code="200">装備インスタンス取得成功</response>
    /// <response code="404">指定した装備インスタンスが存在しない、または論理削除済み</response>
    [HttpGet("instances/{instanceId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> GetByInstanceId(Guid instanceId)
    {
        var instance = await equipmentService.GetByInstanceIdAsync(instanceId);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    /// <summary>オーブ支払いと装備更新を冪等かつ原子的に実施</summary>
    /// <param name="request">operationId、所有者、オーブitem、対象装備</param>
    /// <response code="200">確定済みの業務結果。同一 operationId は同じ結果を再生する</response>
    /// <response code="400">識別子が空、または orbItemId が128 UTF-16 code unitを超える</response>
    /// <response code="409">同じ operationId が異なる要求内容で使用済み</response>
    [HttpPost("orb-operations")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    [ProducesResponseType(StatusCodes.Status409Conflict)]
    public async Task<IActionResult> ApplyOrb([FromBody] EquipmentOrbOperationRequest request)
    {
        if (request.OperationId == Guid.Empty
            || request.AccountId == Guid.Empty
            || request.EquipmentInstanceId == Guid.Empty
            || request.OrbInventoryEntryId == Guid.Empty
            || string.IsNullOrWhiteSpace(request.OrbItemId)
            || request.OrbItemId.Trim().Length > EquipmentOrbOperationRequest.OrbItemIdMaxLength)
            return BadRequest();
        var result = await equipmentService.ApplyOrbAsync(request);
        return result.Result == "OPERATION_CONFLICT" ? Conflict(result) : Ok(result);
    }

    /// <summary>通信結果が不明なオーブ操作の台帳結果を取得</summary>
    [HttpGet("orb-operations/{operationId:guid}")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    [ProducesResponseType(StatusCodes.Status400BadRequest)]
    public async Task<IActionResult> GetOrbOperation(Guid operationId, [FromQuery(Name = "account_id")] Guid accountId)
    {
        if (operationId == Guid.Empty || accountId == Guid.Empty)
            return BadRequest();

        var result = await equipmentService.FindOrbOperationAsync(operationId, accountId);
        return result is null ? NotFound() : Ok(result);
    }

    /// <summary>指定スロットのエンチャントを削除</summary>
    /// <param name="request">対象装備、スロット番号、所有アカウントを含むリクエスト</param>
    /// <response code="200">削除後の装備インスタンス</response>
    /// <response code="404">対象装備・スロットが存在しない、または所有者が一致しない</response>
    [HttpDelete("enchant")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DeleteEnchant([FromBody] EquipmentEnchantDeleteRequest request)
    {
        var instance = await equipmentService.DeleteEnchantAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    /// <summary>装備インスタンス耐久値更新</summary>
    /// <param name="request">耐久値更新リクエスト</param>
    /// <response code="200">耐久値更新成功</response>
    /// <response code="404">対象装備インスタンスまたは対象アカウントが存在しない</response>
    [HttpPost("durability")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> UpdateDurability([FromBody] EquipmentDurabilityUpdateRequest request)
    {
        var instance = await equipmentService.UpdateDurabilityAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    [HttpDelete("instances/{instanceId:guid}")]
    [ProducesResponseType(StatusCodes.Status204NoContent)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Delete(Guid instanceId)
    {
        var deleted = await equipmentService.DeleteAsync(instanceId);
        if (!deleted)
            return NotFound();

        return NoContent();
    }

}
