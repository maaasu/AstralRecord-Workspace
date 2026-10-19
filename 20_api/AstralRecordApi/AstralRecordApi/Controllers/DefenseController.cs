using System.Security.Cryptography;
using System.Text;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;

namespace AstralRecordApi.Controllers;

/// <summary>防衛戦の管理設定、開催指示、ゲームサーバー報告を受け付けます。</summary>
[ApiController]
[Route("api/defense")]
public sealed class DefenseController(IDefenseRepository repository, IWebAuthRepository authorization,
    IConfiguration configuration) : ControllerBase
{
    /// <summary>管理者へチャンネルの設定、不足項目、進行中と直近開催を返します。</summary>
    [HttpGet("admin/channels/{serverId}")]
    public Task<IActionResult> GetChannel(string serverId, [FromQuery(Name = "actor_user_uuid")] Guid actor) =>
        Admin(actor, () => repository.GetChannelAsync(serverId));

    /// <summary>期待revisionが一致する場合に日次時刻と必須設定を保存します。</summary>
    [HttpPut("admin/channels/{serverId}")]
    public Task<IActionResult> UpdateChannel(string serverId, [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] DefenseChannelUpdateRequest request) => Admin(actor, () => repository.UpdateChannelAsync(serverId, request, actor));

    /// <summary>操作IDで冪等に即時開催指示を作ります。実際の戦闘開始はPlugin報告で確定します。</summary>
    [HttpPost("admin/channels/{serverId}/start")]
    public Task<IActionResult> Start(string serverId, [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] DefenseStartRequest request) => Admin(actor, () => repository.StartAsync(serverId, request));

    /// <summary>指定開催へ停止を要求します。Pluginの中断報告で終了します。</summary>
    [HttpPost("admin/channels/{serverId}/stop")]
    public Task<IActionResult> Stop(string serverId, [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromBody] DefenseStopRequest request) => Admin(actor, () => repository.StopAsync(serverId, request));

    /// <summary>管理者へチャンネルの開催履歴を返します。</summary>
    [HttpGet("admin/channels/{serverId}/runs")]
    public Task<IActionResult> Runs(string serverId, [FromQuery(Name = "actor_user_uuid")] Guid actor,
        [FromQuery] int limit = 20) => Admin(actor, () => repository.RunsAsync(serverId, limit));

    /// <summary>実行サーバーへ設定と開始待ちまたは進行中の開催を返します。</summary>
    [HttpGet("runtime/{serverId}")]
    public Task<IActionResult> Runtime(string serverId, [FromQuery] Guid instanceId) =>
        RuntimeCall(() => repository.RuntimeAsync(serverId, instanceId));

    /// <summary>実行サーバーが現在の受付開閉と人数を管理画面へ報告します。</summary>
    [HttpPost("runtime/{serverId}/registration")]
    public Task<IActionResult> Registration(string serverId, [FromBody] DefenseRegistrationReport request) =>
        RuntimeCall(() => repository.RegistrationAsync(serverId, request));

    /// <summary>開始、進行、終了、中断を起動世代と操作IDで確定します。</summary>
    [HttpPost("runtime/{serverId}/runs/{runId:guid}/report")]
    public Task<IActionResult> Report(string serverId, Guid runId, [FromBody] DefenseRunReportRequest request) =>
        RuntimeCall(() => repository.ReportAsync(serverId, runId, request));

    /// <summary>確定参加者の段階報酬を一度だけ保存し、既存メール経路へ配送します。</summary>
    [HttpPost("runtime/{serverId}/runs/{runId:guid}/rewards")]
    public Task<IActionResult> Rewards(string serverId, Guid runId, [FromBody] DefenseRewardReportRequest request) =>
        RuntimeCall(() => repository.RewardsAsync(serverId, runId, request));

    private async Task<IActionResult> Admin<T>(Guid actor, Func<Task<T>> operation)
    {
        Response.Headers.CacheControl = "no-store";
        var expected = configuration["Defense:WebKey"];
        if (string.IsNullOrWhiteSpace(expected)) return StatusCode(503, new { message = "防衛戦Webキーが未設定です。" });
        if (actor == Guid.Empty || !KeyMatches(expected, Request.Headers["X-Defense-Web-Key"].ToString())
            || !await authorization.IsWebAdminAsync(actor)) return StatusCode(403);
        return await Execute(operation);
    }

    private Task<IActionResult> RuntimeCall<T>(Func<Task<T>> operation)
    {
        Response.Headers.CacheControl = "no-store";
        var expected = configuration["Defense:RuntimeKey"];
        if (string.IsNullOrWhiteSpace(expected)) return Task.FromResult<IActionResult>(StatusCode(503,
            new { message = "防衛戦runtimeキーが未設定です。" }));
        if (!KeyMatches(expected, Request.Headers["X-Defense-Runtime-Key"].ToString()))
            return Task.FromResult<IActionResult>(StatusCode(403));
        return Execute(operation);
    }

    private async Task<IActionResult> Execute<T>(Func<Task<T>> operation)
    {
        try { return Ok(await operation()); }
        catch (DefenseConflictException ex) { return Conflict(new { message = ex.Message }); }
        catch (ArgumentException ex) { return BadRequest(new { message = ex.Message }); }
        catch (KeyNotFoundException) { return NotFound(); }
    }

    private static bool KeyMatches(string expected, string supplied) =>
        CryptographicOperations.FixedTimeEquals(Encoding.UTF8.GetBytes(expected), Encoding.UTF8.GetBytes(supplied));
}
