namespace AstralRecordWeb.Models;

public class WebLoginChallengeConsumeRequest
{
    public string LoginCode { get; set; } = string.Empty;
    public Guid? ExpectedUserUuid { get; set; }
    public bool IssueTrustedBrowser { get; set; }
}

public class WebLoginChallengeConsumeResponse
{
    public string? CodeAuthenticationProof { get; set; }
    public DateTimeOffset? CodeAuthenticatedAt { get; set; }
    public string? TrustedBrowserToken { get; set; }
    public Guid SessionVersion { get; set; }
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public int Permission { get; set; }
    public bool WebAdmin { get; set; }
    public Guid? CurrentAccountId { get; set; }
    public IReadOnlyList<Guid> AccountIds { get; set; } = [];
}

public sealed class WebTrustedBrowserValidationRequest
{
    public Guid SessionVersion { get; set; }
    public string? Token { get; set; }
}

public sealed class WebTrustedBrowserValidationResponse
{
    public bool Trusted { get; set; }
}

public sealed class WebCredentialState
{
    public string? CodeAuthenticationProof { get; set; }
    public DateTimeOffset? CodeAuthenticatedAt { get; set; }
    public string? LoginId { get; set; }
    public bool Enabled { get; set; }
    public Guid SessionVersion { get; set; }
}

public sealed class WebCredentialUpdateRequest
{
    public Guid SessionVersion { get; set; }
    public string Action { get; set; } = string.Empty;
    public string? CurrentPassword { get; set; }
    public string? NewPassword { get; set; }
    public string? CodeAuthenticationProof { get; set; }
}

public sealed record WebCredentialUpdateResult(WebCredentialState? State, bool Unavailable, bool Stale);

public class WebAuthorizationResponse
{
    public bool WebAdmin { get; set; }
}

public enum WebLoginChallengeConsumeStatus
{
    Success,
    Invalid,
    ServiceUnavailable,
}

public class WebLoginChallengeConsumeResult
{
    public WebLoginChallengeConsumeStatus Status { get; private init; }
    public WebLoginChallengeConsumeResponse? Response { get; private init; }

    public static WebLoginChallengeConsumeResult Success(WebLoginChallengeConsumeResponse response) =>
        new()
        {
            Status = WebLoginChallengeConsumeStatus.Success,
            Response = response,
        };

    public static WebLoginChallengeConsumeResult Invalid() =>
        new()
        {
            Status = WebLoginChallengeConsumeStatus.Invalid,
        };

    public static WebLoginChallengeConsumeResult ServiceUnavailable() =>
        new()
        {
            Status = WebLoginChallengeConsumeStatus.ServiceUnavailable,
        };
}
