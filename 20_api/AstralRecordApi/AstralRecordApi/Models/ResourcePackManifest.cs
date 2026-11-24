namespace AstralRecordApi.Models;

public sealed record ResourcePackManifest(JavaResourcePack Java, BedrockResourcePack Bedrock);
public sealed record JavaResourcePack(
    string Sha1,
    string? ActionRingPanel = null,
    string? GuiTheme = null,
    string? GameplaySounds = null,
    string? SunbirdPresentation = null,
    string? ItemTooltips = null,
    string? WeaponAttackProjectiles = null,
    string? ProfileHud = null,
    string? WideCombatMeter = null,
    string? SkillEffects = null,
    string? SeleneaPresentation = null,
    string? CombatRewardPlaque = null,
    string? QuestTrackingHud = null,
    string? ReceptionDisplay = null,
    string? ChallengeCountdown = null,
    string? ArchmagePhoenixPresentation = null,
    string? LocationHud = null,
    int[]? WornHelmetModels = null,
    string? FishingRodTip = null,
    string? NyxeronPresentation = null,
    string? MossZombiePresentation = null,
    string? ActionRingCooldownHud = null,
    string? SkillTooltips = null);
public sealed record BedrockResourcePack(string Sha256, Guid Uuid);
