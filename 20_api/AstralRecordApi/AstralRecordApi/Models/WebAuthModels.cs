namespace AstralRecordApi.Models;

public class WebLoginChallengeCreateRequest
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public string ServerId { get; set; } = string.Empty;
    public DateTime RequestedAt { get; set; }
}

public class WebLoginChallengeCreateResponse
{
    public Guid ChallengeId { get; set; }
    public string LoginCode { get; set; } = string.Empty;
    public DateTime ExpiresAt { get; set; }
    public string LoginUrl { get; set; } = string.Empty;
}

public class WebLoginChallengeConsumeRequest
{
    public string LoginCode { get; set; } = string.Empty;
    public Guid? ExpectedUserUuid { get; set; }
}

public class WebLoginChallengeConsumeResponse
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public int Permission { get; set; }
    public bool WebAdmin { get; set; }
    public Guid? CurrentAccountId { get; set; }
    public IReadOnlyList<Guid> AccountIds { get; set; } = [];
    public Guid SessionVersion { get; set; }
}

public class WebPasswordLoginRequest
{
    public string LoginId { get; set; } = string.Empty;
    public string Password { get; set; } = string.Empty;
}

public class WebCredentialResponse
{
    public string? LoginId { get; set; }
    public bool Enabled { get; set; }
    public Guid SessionVersion { get; set; }
}

public class WebCredentialUpdateRequest
{
    public Guid SessionVersion { get; set; }
    public string Action { get; set; } = string.Empty;
    public string? CurrentPassword { get; set; }
    public string? NewPassword { get; set; }
    public DateTimeOffset? CodeAuthenticatedAt { get; set; }
}

public enum WebPasswordLoginStatus
{
    Succeeded,
    Invalid,
    Throttled,
}

public sealed class WebPasswordLoginResult
{
    public WebPasswordLoginStatus Status { get; init; }
    public WebLoginChallengeConsumeResponse? Response { get; init; }
}

public enum WebCredentialUpdateStatus
{
    Succeeded,
    Invalid,
    Stale,
    Throttled,
    NotFound,
}

public sealed class WebCredentialUpdateResult
{
    public WebCredentialUpdateStatus Status { get; init; }
    public WebCredentialResponse? Response { get; init; }
}

public class WebLoginChallengeUserResolveResponse
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
}

public enum WebLoginChallengeUserResolveStatus
{
    Found,
    NotFound,
    Ambiguous,
}

public class WebLoginChallengeUserResolveResult
{
    public WebLoginChallengeUserResolveStatus Status { get; private init; }
    public WebLoginChallengeUserResolveResponse? Response { get; private init; }

    public static WebLoginChallengeUserResolveResult Found(WebLoginChallengeUserResolveResponse response) =>
        new() { Status = WebLoginChallengeUserResolveStatus.Found, Response = response };

    public static WebLoginChallengeUserResolveResult NotFound() =>
        new() { Status = WebLoginChallengeUserResolveStatus.NotFound };

    public static WebLoginChallengeUserResolveResult Ambiguous() =>
        new() { Status = WebLoginChallengeUserResolveStatus.Ambiguous };
}

public class WebAuthorizationResponse
{
    public bool WebAdmin { get; set; }
}
