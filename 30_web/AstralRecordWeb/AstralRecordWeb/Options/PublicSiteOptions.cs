namespace AstralRecordWeb.Options;

public enum PublicSitePhase
{
    OpenAlpha,
    Release,
}

public sealed class PublicSiteOptions
{
    public const string SectionName = "PublicSite";

    public PublicSitePhase Phase { get; set; } = PublicSitePhase.OpenAlpha;

    public string JavaServerAddress { get; set; } = "mc.astralrecord.com";

    public int JavaServerPort { get; set; } = 25565;

    public bool IsOpenAlpha => Phase == PublicSitePhase.OpenAlpha;
}
