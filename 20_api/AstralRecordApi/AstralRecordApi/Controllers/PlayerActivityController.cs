using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>ゲーム進行から切り離した管理用プレイヤー行動履歴を保存・検索します。</summary>
[ApiController]
public sealed class PlayerActivityController(IPlayerActivityRepository repository, IWebAuthRepository authorization) : ControllerBase
{
    /// <summary>Plugin の非同期キューから行動履歴を冪等に一括保存します。</summary>
    [HttpPost("api/history/activity/batch")]
    public async Task<IActionResult> RecordBatch([FromBody] PlayerActivityBatchRequest request)
    { try { return Ok(await repository.RecordBatchAsync(request)); } catch (ArgumentException exception) { return BadRequest(new { message = exception.Message }); } }

    /// <summary>同一IPを観測した別ユーザーのペアを管理者向けに返します。IP文字列は返しません。</summary>
    [HttpGet("api/admin/player-activity/same-ip")]
    public async Task<IActionResult> GetSameIp([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetSameIpAsync(query));
    /// <summary>プレイヤー間の単方向トレード履歴を管理者向けに返します。</summary>
    [HttpGet("api/admin/player-activity/trades")]
    public async Task<IActionResult> GetTrades([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetTradesAsync(query));
    /// <summary>参加者、攻略時間、移動距離を含むダンジョン踏破履歴を返します。</summary>
    [HttpGet("api/admin/player-activity/dungeons")]
    public async Task<IActionResult> GetDungeons([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetDungeonsAsync(query));
    /// <summary>期間内のダンジョン踏破回数をプレイヤー別に集計します。</summary>
    [HttpGet("api/admin/player-activity/dungeons/players")]
    public async Task<IActionResult> GetDungeonPlayers([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetDungeonPlayersAsync(query));
    /// <summary>Mob別のプレイヤー死亡数と対プレイヤー実ダメージ集計を返します。</summary>
    [HttpGet("api/admin/player-activity/mobs")]
    public async Task<IActionResult> GetMobs([FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetMobsAsync(query));
    /// <summary>指定Mobの被害プレイヤー別集計を返します。</summary>
    [HttpGet("api/admin/player-activity/mobs/{mobId}/players")]
    public async Task<IActionResult> GetMobPlayers(string mobId, [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetMobPlayersAsync(mobId, query));
    /// <summary>指定Mobがプレイヤーを倒した時系列履歴を返します。</summary>
    [HttpGet("api/admin/player-activity/mobs/{mobId}/kills")]
    public async Task<IActionResult> GetMobDeaths(string mobId, [FromQuery(Name = "actor_user_uuid")] Guid actor, [FromQuery] PlayerActivityQuery query) => await Admin(actor, () => repository.GetMobDeathsAsync(mobId, query));

    private async Task<IActionResult> Admin<T>(Guid actor, Func<Task<T>> operation)
    { if (actor == Guid.Empty || !await authorization.IsWebAdminAsync(actor)) return StatusCode(StatusCodes.Status403Forbidden); try { return Ok(await operation()); } catch (ArgumentException exception) { return BadRequest(new { message = exception.Message }); } }
}
