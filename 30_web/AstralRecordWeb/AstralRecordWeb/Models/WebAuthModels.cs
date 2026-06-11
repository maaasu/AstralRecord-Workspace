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
