namespace AstralRecordApi.Options;

public class MasterDataOptions
{
    public const string SectionName = "MasterData";

    /// <summary>API 起動時に Seeder を自動実行するか。false でも MasterDataDB が空なら実行する。</summary>
    public bool AutoSeedOnStartup { get; set; }

    /// <summary>Seeder が created_by / updated_by に記録する system UUID。</summary>
    public Guid SystemUserId { get; set; } = new("00000000-0000-0000-0000-000000000001");
}
