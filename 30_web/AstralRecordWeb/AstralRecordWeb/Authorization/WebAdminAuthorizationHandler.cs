using System.Security.Claims;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;

namespace AstralRecordWeb.Authorization;

/// <summary>ManagementDB の Web 管理フラグで管理画面の利用可否を判定します。</summary>
public sealed class WebAdminAuthorizationHandler(WebAuthApiClient webAuthApiClient)
    : AuthorizationHandler<WebAdminRequirement>
{
    protected override async Task HandleRequirementAsync(
        AuthorizationHandlerContext context,
        WebAdminRequirement requirement)
    {
        var userUuidText = context.User.FindFirstValue(ClaimTypes.NameIdentifier);
        if (!Guid.TryParse(userUuidText, out var userUuid))
            return;

        if (await webAuthApiClient.IsWebAdminAsync(userUuid, CancellationToken.None))
            context.Succeed(requirement);
    }
}

/// <summary>Web 管理者であることを要求します。</summary>
public sealed class WebAdminRequirement : IAuthorizationRequirement;
