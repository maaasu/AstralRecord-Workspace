using System.Security.Claims;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authorization;

namespace AstralRecordWeb.Authorization;

/// <summary>ManagementDB の Web 管理フラグと本人確認状態で管理機能の可否を判定します。</summary>
public sealed class WebAdminAuthorizationHandler(WebAuthApiClient webAuthApiClient, TimeProvider clock)
    : AuthorizationHandler<WebAdminRequirement>
{
    protected override async Task HandleRequirementAsync(
        AuthorizationHandlerContext context,
        WebAdminRequirement requirement)
    {
        var userUuidText = context.User.FindFirstValue(ClaimTypes.NameIdentifier);
        if (!Guid.TryParse(userUuidText, out var userUuid))
            return;

        if (!await webAuthApiClient.IsWebAdminAsync(userUuid, CancellationToken.None)) return;
        if (!requirement.RequireRecentCode || WebSession.RecentCodeTime(context.User, clock).HasValue)
            context.Succeed(requirement);
        else if (context.Resource is HttpContext httpContext)
            httpContext.Items[WebSession.NeedsCodeItem] = true;
    }
}

/// <summary>Web 管理者であることを要求します。</summary>
public sealed class WebAdminRequirement(bool requireRecentCode = true) : IAuthorizationRequirement
{
    public bool RequireRecentCode { get; } = requireRecentCode;
}
