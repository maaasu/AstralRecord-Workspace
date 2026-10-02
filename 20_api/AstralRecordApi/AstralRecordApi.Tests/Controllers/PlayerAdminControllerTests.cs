using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.Extensions.Configuration;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class PlayerAdminControllerTests
{
    private static readonly Guid Actor = Guid.NewGuid();
    private static readonly Guid Account = Guid.NewGuid();

    [Fact]
    public async Task WebEditRequiresDistinctDedicatedKeyAndWebAdminActor()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository { IsAdmin = true };
        var request = new PlayerAdminEditStartRequest(Guid.NewGuid(), "maintenance");
        var missing = MakeController(edits, auth);
        Assert.IsType<UnauthorizedResult>(await missing.Start(Account, Actor, request));
        Assert.Equal(0, edits.StartCalls);
        Assert.Equal(0, auth.AdminChecks);

        var commonOnly = MakeController(edits, auth, "common-secret");
        Assert.IsType<UnauthorizedResult>(await commonOnly.Start(Account, Actor, request));
        Assert.Equal(0, edits.StartCalls);

        var reusedCommon = MakeController(edits, auth, "admin-secret", "admin-secret");
        Assert.IsType<UnauthorizedResult>(await reusedCommon.Start(Account, Actor, request));
        Assert.Equal(0, edits.StartCalls);

        var wrong = MakeController(edits, auth, "wrong-secret");
        Assert.IsType<UnauthorizedResult>(await wrong.Start(Account, Actor, request));
        Assert.Equal(0, edits.StartCalls);

        var noActor = MakeController(edits, auth, "admin-secret");
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await noActor.Start(Account, Guid.Empty, request)).StatusCode);
        Assert.Equal(0, edits.StartCalls);

        auth.IsAdmin = false;
        var noAdmin = MakeController(edits, auth, "admin-secret");
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await noAdmin.Start(Account, Actor, request)).StatusCode);
        Assert.Equal(0, edits.StartCalls);
        Assert.Equal(1, auth.AdminChecks);

        auth.IsAdmin = true;
        var allowed = MakeController(edits, auth, "admin-secret");
        var response = Assert.IsType<ObjectResult>(await allowed.Start(Account, Actor, request));
        Assert.Equal(201, response.StatusCode);
        Assert.Equal(1, edits.StartCalls);
        Assert.Equal(Account, edits.LastAccount);
        Assert.Equal(Actor, edits.LastActor);
        Assert.Same(request, edits.LastRequest);
    }

    [Fact]
    public async Task ApplyAndCancelGuardCredentialsBeforeRepositoryMutation()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository { IsAdmin = true };
        var apply = new PlayerAdminEditOperationRequest(Guid.NewGuid(), 1, new string('a', 64),
            new string('b', 64), null, null, []);
        var cancel = new PlayerAdminEditCancelRequest(Guid.NewGuid(), 1);
        var session = Guid.NewGuid();

        Assert.IsType<UnauthorizedResult>(await MakeController(edits, auth)
            .Apply(session, Actor, apply));
        Assert.IsType<UnauthorizedResult>(await MakeController(edits, auth)
            .Cancel(session, Actor, cancel));
        auth.IsAdmin = false;
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await MakeController(edits, auth, "admin-secret")
            .Apply(session, Actor, apply)).StatusCode);
        Assert.Equal(0, edits.ApplyCalls);
        Assert.Equal(0, edits.CancelCalls);

        auth.IsAdmin = true;
        var controller = MakeController(edits, auth, "admin-secret");
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.Apply(session, Actor, apply)).StatusCode);
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.Cancel(session, Actor, cancel)).StatusCode);
        Assert.Equal(1, edits.ApplyCalls);
        Assert.Equal(1, edits.CancelCalls);
    }

    [Fact]
    public async Task RepositoryConflictRemainsConflictResponse()
    {
        var edits = new EditRepository { StartResult = new(409, Error: "User already has an edit lock.") };
        var result = await MakeController(edits, new WebAuthRepository { IsAdmin = true }, "admin-secret")
            .Start(Account, Actor, new(Guid.NewGuid(), "maintenance"));

        var response = Assert.IsType<ObjectResult>(result);
        Assert.Equal(409, response.StatusCode);
        var problem = Assert.IsType<ProblemDetails>(response.Value);
        Assert.Equal(409, problem.Status);
        Assert.Equal("User already has an edit lock.", problem.Title);
    }

    [Fact]
    public async Task RuntimeRoutesRequireDedicatedKeyDistinctFromWebAndCommon()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository();
        var registration = new PlayerAdminRuntimeRegistrationRequest(Guid.NewGuid(), "PROXY", null, null);
        Assert.IsType<UnauthorizedResult>(await MakeController(edits, auth)
            .RegisterServer("proxy-1", registration));
        Assert.IsType<UnauthorizedResult>(await MakeController(edits, auth, runtimeProvided: "common-secret")
            .RegisterServer("proxy-1", registration));
        Assert.IsType<UnauthorizedResult>(await MakeController(edits, auth, runtimeProvided: "runtime-secret",
                runtimeConfigured: "admin-secret")
            .GetDrains("proxy-1", registration.ServerSessionId));
        Assert.Equal(0, edits.RegisterCalls);

        var allowed = MakeController(edits, auth, runtimeProvided: "runtime-secret");
        Assert.Equal(200, Assert.IsType<ObjectResult>(await allowed.RegisterServer("proxy-1", registration)).StatusCode);
        Assert.Equal(1, edits.RegisterCalls);
    }

    private static PlayerAdminController MakeController(EditRepository edits, WebAuthRepository auth,
        string? provided = null, string common = "common-secret",
        string? runtimeProvided = null, string runtimeConfigured = "runtime-secret")
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["PlayerAdmin:WebKey"] = "admin-secret",
            ["PlayerAdmin:RuntimeKey"] = runtimeConfigured,
            ["ApiKey:Key"] = common,
        }).Build();
        var controller = new PlayerAdminController(edits, auth, config)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };
        if (provided is not null)
            controller.Request.Headers["X-Player-Admin-Web-Key"] = provided;
        if (runtimeProvided is not null)
            controller.Request.Headers["X-Player-Admin-Runtime-Key"] = runtimeProvided;
        return controller;
    }

    private sealed class EditRepository : IPlayerAdminEditRepository
    {
        public int StartCalls { get; private set; }
        public int ApplyCalls { get; private set; }
        public int CancelCalls { get; private set; }
        public int RegisterCalls { get; private set; }
        public Guid LastAccount { get; private set; }
        public Guid LastActor { get; private set; }
        public PlayerAdminEditStartRequest? LastRequest { get; private set; }
        public PlayerAdminResult<PlayerAdminEditSessionResponse> StartResult { get; set; } =
            new(201, new(Guid.NewGuid(), Account, Guid.NewGuid(), Actor, "maintenance",
                "DRAINING", 1, DateTime.UtcNow, DateTime.UtcNow,
                DateTime.UtcNow.AddMinutes(30), null, 3, 0));

        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> StartAsync(Guid accountId,
            Guid actor, PlayerAdminEditStartRequest request)
        {
            StartCalls++;
            LastAccount = accountId;
            LastActor = actor;
            LastRequest = request;
            return Task.FromResult(StartResult);
        }

        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> ApplyAsync(Guid editSessionId,
            Guid actor, PlayerAdminEditOperationRequest request)
        {
            ApplyCalls++;
            return Task.FromResult(StartResult with { StatusCode = 200 });
        }

        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> CancelAsync(Guid editSessionId,
            Guid actor, PlayerAdminEditCancelRequest request)
        {
            CancelCalls++;
            return Task.FromResult(StartResult with { StatusCode = 200 });
        }

        public Task<bool> IsUserLockedAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetActiveAsync(Guid accountId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetByIdAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> RefreshAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditorResponse>> GetEditorAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>> RegisterServerAsync(string serverId, PlayerAdminRuntimeRegistrationRequest request)
        {
            RegisterCalls++;
            return Task.FromResult(new PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>(200,
                new(serverId, request.ServerSessionId, request.Role)));
        }
        public Task<PlayerAdminResult<IReadOnlyList<PlayerAdminDrainResponse>>> GetDrainsAsync(string serverId, Guid serverSessionId) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> AcknowledgeDrainAsync(Guid editSessionId, PlayerAdminDrainAckRequest request) => throw new NotSupportedException();
    }

    private sealed class WebAuthRepository : IWebAuthRepository
    {
        public bool IsAdmin { get; set; }
        public int AdminChecks { get; private set; }
        public Task<bool> IsWebAdminAsync(Guid userUuid)
        {
            AdminChecks++;
            return Task.FromResult(IsAdmin);
        }
        public Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request) => throw new NotSupportedException();
        public Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request) => throw new NotSupportedException();
        public Task<bool> IsTrustedBrowserAsync(Guid userUuid, Guid sessionVersion, string? token) => throw new NotSupportedException();
        public Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid) => throw new NotSupportedException();
        public Task<WebPasswordLoginResult> LoginWithPasswordAsync(WebPasswordLoginRequest request) => throw new NotSupportedException();
        public Task<WebCredentialResponse?> GetCredentialAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<WebCredentialUpdateResult> UpdateCredentialAsync(Guid userUuid, WebCredentialUpdateRequest request) => throw new NotSupportedException();
    }
}
