using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public sealed class WebPlayerProfileAuthorizationTests
{
    [Fact]
    public async Task PrivateSearchAndDetailRejectNonAdminBeforeReadingProfiles()
    {
        var repository = new Profiles();
        var controller = new WebPlayerProfileController(repository, new Authorization());
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.Search(Guid.NewGuid(), null, null, includePrivate: true)).StatusCode);
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.GetProfile(Guid.NewGuid(), Guid.NewGuid(), includePrivate: true)).StatusCode);
        Assert.Equal(0, repository.Reads);
    }

    [Fact]
    public async Task AdminFlagIsRecheckedOnEveryRequest()
    {
        var repository = new Profiles();
        var authorization = new Authorization { Admin = true };
        var controller = new WebPlayerProfileController(repository, authorization);
        Assert.IsType<OkObjectResult>(await controller.Search(Guid.NewGuid(), null, null, includePrivate: true));
        authorization.Admin = false;
        Assert.Equal(403, Assert.IsType<StatusCodeResult>(await controller.Search(Guid.NewGuid(), null, null, includePrivate: true)).StatusCode);
        Assert.Equal(1, repository.Reads);
    }

    private sealed class Authorization : IWebAuthRepository
    {
        public bool Admin { get; set; }
        public Task<bool> IsWebAdminAsync(Guid userUuid) => Task.FromResult(Admin);
        public Task<bool> IsTrustedBrowserAsync(Guid userUuid, Guid sessionVersion, string? token) => throw new NotSupportedException();
        public Task<WebLoginChallengeCreateResponse?> CreateChallengeAsync(WebLoginChallengeCreateRequest request) => throw new NotSupportedException();
        public Task<WebLoginChallengeConsumeResponse?> ConsumeChallengeAsync(WebLoginChallengeConsumeRequest request) => throw new NotSupportedException();
        public Task<WebLoginChallengeUserResolveResult> ResolveUserByMcidAsync(string mcid) => throw new NotSupportedException();
        public Task<WebPasswordLoginResult> LoginWithPasswordAsync(WebPasswordLoginRequest request) => throw new NotSupportedException();
        public Task<WebCredentialResponse?> GetCredentialAsync(Guid userUuid) => throw new NotSupportedException();
        public Task<WebCredentialUpdateResult> UpdateCredentialAsync(Guid userUuid, WebCredentialUpdateRequest request) => throw new NotSupportedException();
    }

    private sealed class Profiles : IWebPlayerProfileRepository
    {
        public int Reads { get; private set; }
        public Task<WebPlayerProfileSearchResponse> SearchAsync(Guid viewerUserUuid, string? mcid, string? classId, string? sort, int page, int pageSize, bool includePrivate)
        {
            Reads++;
            return Task.FromResult(new WebPlayerProfileSearchResponse { Profiles = [], Classes = [], Page = page, PageSize = pageSize, TotalCount = 0 });
        }
        public Task<WebPlayerProfileResponse?> GetProfileAsync(Guid targetUserUuid, Guid viewerUserUuid, bool includePrivate, Guid? accountId = null)
        {
            Reads++;
            return Task.FromResult<WebPlayerProfileResponse?>(null);
        }
        public Task<WebPlayerProfileResponse?> GetMyProfileAsync(Guid viewerUserUuid) => throw new NotSupportedException();
        public Task<WebPlayerProfileResponse?> UpdateVisibilityAsync(Guid viewerUserUuid, bool isPublic) => throw new NotSupportedException();
    }
}
