namespace AstralRecordApi.Options;

public class WebAuthOptions
{
    public const string SectionName = "WebAuth";

    public string LoginUrl { get; set; } = "https://astralrecord.example.com/Login";
    public int ChallengeMinutes { get; set; } = 5;
}
