namespace AstralRecordWeb.Models;

public class WebLoginChallengeConsumeRequest
{
    public string LoginCode { get; set; } = string.Empty;
}

public class WebLoginChallengeConsumeResponse
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public int Permission { get; set; }
    public Guid? CurrentAccountId { get; set; }
    public IReadOnlyList<Guid> AccountIds { get; set; } = [];
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
