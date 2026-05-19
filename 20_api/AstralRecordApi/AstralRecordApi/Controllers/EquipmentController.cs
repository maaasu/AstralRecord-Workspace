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

    [HttpPost("enchant")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Enchant([FromBody] EquipmentEnchantRequest request)
    {
        var instance = await equipmentService.EnchantAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

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

    [HttpPost("enhance")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Enhance([FromBody] EquipmentEnhanceRequest request)
    {
        var instance = await equipmentService.EnhanceAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    [HttpPost("transcendence")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> Transcend([FromBody] EquipmentTranscendenceRequest request)
    {
        var instance = await equipmentService.TranscendAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    /// <summary>ルーン装着</summary>
    /// <response code="200">ルーン装着成功</response>
    /// <response code="404">対象装備または対象ルーンが存在しない、あるいは装着条件を満たさない</response>
    [HttpPost("rune")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> AttachRune([FromBody] EquipmentRuneAttachRequest request)
    {
        var instance = await equipmentService.AttachRuneAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }

    /// <summary>ルーン脱着</summary>
    /// <response code="200">ルーン脱着成功</response>
    /// <response code="404">対象装備または対象スロットのルーンが存在しない</response>
    [HttpDelete("rune")]
    [ProducesResponseType(StatusCodes.Status200OK)]
    [ProducesResponseType(StatusCodes.Status404NotFound)]
    public async Task<IActionResult> DetachRune([FromBody] EquipmentRuneDetachRequest request)
    {
        var instance = await equipmentService.DetachRuneAsync(request);
        if (instance is null)
            return NotFound();

        return Ok(instance);
    }
}
