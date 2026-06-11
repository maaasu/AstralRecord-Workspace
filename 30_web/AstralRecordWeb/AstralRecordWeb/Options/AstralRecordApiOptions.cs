namespace AstralRecordWeb.Options;

public class AstralRecordApiOptions
{
    public const string SectionName = "AstralRecordApi";

    public string BaseUrl { get; set; } = "https://localhost:5001";
    public string ApiKey { get; set; } = string.Empty;
}
