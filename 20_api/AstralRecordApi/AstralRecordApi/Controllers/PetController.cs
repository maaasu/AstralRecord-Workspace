using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

[ApiController]
[Route("api/pet")]
public class PetController(IPetRepository repository) : ControllerBase
{
    /// <summary>全種の成長・遺伝・孵化・スキルを定義する単一ペットマスターを返します。</summary>
    [HttpGet("master")]
    public async Task<IActionResult> Master() => await repository.GetMasterAsync() is { } master ? Ok(master) : NotFound();
    /// <summary>所有個体と選択中のペットを取得します。卵の個体詳細は返しません。</summary>
    [HttpGet("accounts/{accountId:guid}")]
    public async Task<IActionResult> Account(Guid accountId) => Ok(await repository.GetByAccountAsync(accountId));
    /// <summary>所有者に限定して個体を取得します。卵の個体詳細は返しません。</summary>
    [HttpGet("instances/{instanceId:guid}")]
    public async Task<IActionResult> Instance(Guid instanceId, [FromQuery(Name = "account_id")] Guid accountId)
        => await repository.GetInstanceAsync(accountId, instanceId) is { } pet ? Ok(pet) : NotFound();
    /// <summary>冪等操作の結果と現在所有者限定のインベントリ正本を照会します。</summary>
    [HttpGet("accounts/{accountId:guid}/operations/{operationId:guid}")]
    public async Task<IActionResult> Operation(Guid accountId, Guid operationId)
        => await repository.GetOperationAsync(accountId, operationId) is { } result ? Result(result) : NotFound();
    /// <summary>討伐報酬として野生由来の卵を作成してBAGへ入れます。</summary>
    [HttpPost("accounts/{accountId:guid}/eggs")]
    public async Task<IActionResult> Egg(Guid accountId, PetEggCreateRequest request) => Result(await repository.CreateEggAsync(accountId, request));
    /// <summary>施設で素材を原子的に消費して孵化し、初めて個体詳細を公開します。</summary>
    [HttpPost("accounts/{accountId:guid}/{instanceId:guid}/hatch")]
    public async Task<IActionResult> Hatch(Guid accountId, Guid instanceId, PetFacilityRequest request) => Result(await repository.HatchAsync(accountId, instanceId, request));
    /// <summary>同種の成長済みオスとメスを配合し、譲渡不可の卵を作成します。</summary>
    [HttpPost("accounts/{accountId:guid}/breed")]
    public async Task<IActionResult> Breed(Guid accountId, PetBreedRequest request) => Result(await repository.BreedAsync(accountId, request));
    /// <summary>版を照合して経験値・HP・死亡・クールダウンを保存し、成長時のスキルを一度だけ抽選します。</summary>
    [HttpPost("accounts/{accountId:guid}/{instanceId:guid}/progress")]
    public async Task<IActionResult> Progress(Guid accountId, Guid instanceId, PetProgressRequest request) => Result(await repository.ProgressAsync(accountId, instanceId, request));
    /// <summary>施設の必要素材または専用オーブを消費して死亡個体を復活します。</summary>
    [HttpPost("accounts/{accountId:guid}/{instanceId:guid}/revive")]
    public async Task<IActionResult> Revive(Guid accountId, Guid instanceId, PetReviveRequest request) => Result(await repository.ReviveAsync(accountId, instanceId, request));
    /// <summary>所有する孵化済み個体を装備枠に設定します。死亡個体は設定できても召喚できません。</summary>
    [HttpPut("accounts/{accountId:guid}/equipped")]
    public async Task<IActionResult> Equipped(Guid accountId, PetEquipRequest request) => Result(await repository.EquipAsync(accountId, request));
    /// <summary>孵化済み個体の名前を変更します。</summary>
    [HttpPost("accounts/{accountId:guid}/{instanceId:guid}/rename")]
    public async Task<IActionResult> Rename(Guid accountId, Guid instanceId, PetRenameRequest request) => Result(await repository.RenameAsync(accountId, instanceId, request));

    private IActionResult Result(PetMutationResult result) => result.Succeeded ? Ok(result.Response)
        : result.Failure is "account_not_found" or "pet_not_found" or "egg_not_found" or "master_not_found" ? NotFound(new { failure = result.Failure })
        : Conflict(new { failure = result.Failure });
}
