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
}

public class WebLoginChallengeConsumeResponse
{
    public Guid UserUuid { get; set; }
    public string Mcid { get; set; } = string.Empty;
    public int Permission { get; set; }
    public Guid? CurrentAccountId { get; set; }
    public IReadOnlyList<Guid> AccountIds { get; set; } = [];
}
