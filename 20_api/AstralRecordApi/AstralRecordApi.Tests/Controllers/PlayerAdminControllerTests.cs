using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class PlayerAdminControllerTests
{
    private static readonly Guid Actor = Guid.NewGuid();
    private static readonly Guid Account = Guid.NewGuid();

    [Fact]
    public async Task WebEditRequiresWebAdminActorWithoutDedicatedKey()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository { IsAdmin = true };
        var request = new PlayerAdminEditStartRequest(Guid.NewGuid(), "maintenance");
        var noActor = MakeController(edits, auth);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await noActor.Start(Account, Guid.Empty, request)).StatusCode);
        Assert.Equal(0, edits.StartCalls);

        auth.IsAdmin = false;
        var noAdmin = MakeController(edits, auth);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await noAdmin.Start(Account, Actor, request)).StatusCode);
        Assert.Equal(0, edits.StartCalls);
        Assert.Equal(1, auth.AdminChecks);

        auth.IsAdmin = true;
        var allowed = MakeController(edits, auth);
        var response = Assert.IsType<ObjectResult>(await allowed.Start(Account, Actor, request));
        Assert.Equal(201, response.StatusCode);
        Assert.Equal(1, edits.StartCalls);
        Assert.Equal(Account, edits.LastAccount);
        Assert.Equal(Actor, edits.LastActor);
        Assert.Same(request, edits.LastRequest);
    }

    [Fact]
    public async Task ActiveSessionRequiresWebAdminWithoutDedicatedKey()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository();
        var controller = MakeController(edits, auth);

        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.GetActive(Account, Guid.Empty)).StatusCode);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.GetActive(Account, Actor)).StatusCode);
        Assert.Equal(0, edits.GetActiveCalls);

        auth.IsAdmin = true;
        var response = Assert.IsType<ObjectResult>(await controller.GetActive(Account, Actor));
        Assert.Equal(200, response.StatusCode);
        Assert.Equal(1, edits.GetActiveCalls);
        Assert.Equal(Account, edits.LastAccount);
        Assert.Equal(Actor, edits.LastActor);
    }

    [Fact]
    public async Task ApplyAndCancelRequireWebAdminBeforeRepositoryMutation()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository { IsAdmin = true };
        var apply = new PlayerAdminEditOperationRequest(Guid.NewGuid(), 1, new string('a', 64),
            new string('b', 64), null, null, []);
        var cancel = new PlayerAdminEditCancelRequest(Guid.NewGuid(), 1);
        var session = Guid.NewGuid();

        auth.IsAdmin = false;
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await MakeController(edits, auth)
            .Apply(session, Actor, apply)).StatusCode);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await MakeController(edits, auth)
            .Cancel(session, Actor, cancel)).StatusCode);
        Assert.Equal(0, edits.ApplyCalls);
        Assert.Equal(0, edits.CancelCalls);

        auth.IsAdmin = true;
        var controller = MakeController(edits, auth);
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.Apply(session, Actor, apply)).StatusCode);
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.Cancel(session, Actor, cancel)).StatusCode);
        Assert.Equal(1, edits.ApplyCalls);
        Assert.Equal(1, edits.CancelCalls);
    }

    [Fact]
    public async Task RepositoryConflictRemainsConflictResponse()
    {
        var edits = new EditRepository { StartResult = new(409, Error: "User already has an edit lock.") };
        var result = await MakeController(edits, new WebAuthRepository { IsAdmin = true })
            .Start(Account, Actor, new(Guid.NewGuid(), "maintenance"));

        var response = Assert.IsType<ObjectResult>(result);
        Assert.Equal(409, response.StatusCode);
        var problem = Assert.IsType<ProblemDetails>(response.Value);
        Assert.Equal(409, problem.Status);
        Assert.Equal("User already has an edit lock.", problem.Title);
    }

    [Fact]
    public async Task RuntimeRoutesDoNotRequireDedicatedCredentialOrWebActor()
    {
        var edits = new EditRepository();
        var auth = new WebAuthRepository();
        var registration = new PlayerAdminRuntimeRegistrationRequest(Guid.NewGuid(), "PROXY", null, null);
        var session = Guid.NewGuid();
        var acknowledgement = new PlayerAdminDrainAckRequest("proxy-1", registration.ServerSessionId,
            Account, Guid.NewGuid(), true, true, Guid.NewGuid());
        var controller = MakeController(edits, auth);

        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.RegisterServer("proxy-1", registration)).StatusCode);
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.GetDrains("proxy-1", registration.ServerSessionId)).StatusCode);
        Assert.Equal(200, Assert.IsType<ObjectResult>(await controller.AcknowledgeDrain(session, acknowledgement)).StatusCode);
        Assert.Equal(1, edits.RegisterCalls);
        Assert.Equal(1, edits.GetDrainsCalls);
        Assert.Equal(1, edits.AcknowledgeCalls);
        Assert.Same(acknowledgement, edits.LastAcknowledgement);
        Assert.Equal(session, edits.LastEditSession);
        Assert.Equal(0, auth.AdminChecks);
        Assert.Equal(0, edits.StartCalls);
        Assert.Equal(0, edits.ApplyCalls);
        Assert.Equal(0, edits.CancelCalls);
    }

    private static PlayerAdminController MakeController(EditRepository edits, WebAuthRepository auth) =>
        new(edits, auth)
        {
            ControllerContext = new ControllerContext { HttpContext = new DefaultHttpContext() },
        };

    private sealed class EditRepository : IPlayerAdminEditRepository
    {
        public int StartCalls { get; private set; }
        public int GetActiveCalls { get; private set; }
        public int ApplyCalls { get; private set; }
        public int CancelCalls { get; private set; }
        public int RegisterCalls { get; private set; }
        public int GetDrainsCalls { get; private set; }
        public int AcknowledgeCalls { get; private set; }
        public PlayerAdminDrainAckRequest? LastAcknowledgement { get; private set; }
        public Guid LastEditSession { get; private set; }
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
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetActiveAsync(Guid accountId, Guid actor)
        {
            GetActiveCalls++;
            LastAccount = accountId;
            LastActor = actor;
            return Task.FromResult(StartResult with { StatusCode = 200 });
        }
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> GetByIdAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> RefreshAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminEditorResponse>> GetEditorAsync(Guid editSessionId, Guid actor) => throw new NotSupportedException();
        public Task<PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>> RegisterServerAsync(string serverId, PlayerAdminRuntimeRegistrationRequest request)
        {
            RegisterCalls++;
            return Task.FromResult(new PlayerAdminResult<PlayerAdminRuntimeRegistrationResponse>(200,
                new(serverId, request.ServerSessionId, request.Role)));
        }
        public Task<PlayerAdminResult<IReadOnlyList<PlayerAdminDrainResponse>>> GetDrainsAsync(string serverId, Guid serverSessionId)
        {
            GetDrainsCalls++;
            return Task.FromResult(new PlayerAdminResult<IReadOnlyList<PlayerAdminDrainResponse>>(200, []));
        }
        public Task<PlayerAdminResult<PlayerAdminEditSessionResponse>> AcknowledgeDrainAsync(Guid editSessionId, PlayerAdminDrainAckRequest request)
        {
            AcknowledgeCalls++;
            LastEditSession = editSessionId;
            LastAcknowledgement = request;
            return Task.FromResult(StartResult with { StatusCode = 200 });
        }
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
