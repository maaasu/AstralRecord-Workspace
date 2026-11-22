using System.Text.Json.Nodes;

namespace SkillTreeEditor.Server.Services;

/// <summary>Plugin source revisions used to validate the bounded combat model.</summary>
internal static class BalanceCombatParity
{
    private const string Root = "10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/";

    // The same values are recorded in tests/plugin-parity/combat-golden.json.
    internal static readonly IReadOnlyDictionary<string, string> SourceHashes = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase)
    {
        [Root + "combat/service/DamageCalculator.java"] = "63ec5708e4b1cd5b63eebe1fbcde7019850f4e3040fcf9b5c28bcb5740bd9970",
        [Root + "combat/service/DamageService.java"] = "e5fab95224fe9f2295cdd89f70b5214f4465e16ff485c4371c595c0691685e6f",
        [Root + "combat/service/CombatTimingCalculator.java"] = "3db39ff5873027083c5996c0c0143e94576698dae1b9a2d53025e8753c3736b6",
        [Root + "combat/service/NormalAttackDegradationService.java"] = "75cb7c64076830ea8bc484a254b9b88624815d04690a164c054a87421366f4e3",
        [Root + "status/service/StatusService.java"] = "912960098cd88226d036484f26029bae8ff49abf2a701e0d22d038aae2e3a2eb",
        [Root + "status/service/StatusRegenTask.java"] = "bcc9678dc96fca0c2acd261f64e7a6fbeaa66a85b965c0005627693051810ccb",
        [Root + "skill/service/SkillService.java"] = "9a30e18b4a962107797397442ee6987d798f7c73ce88b2d1330db396eccdd8fb",
        [Root + "item/service/ItemWeaponAttackService.java"] = "233dd1d0e27225a15ec7c8684363d40ab9341f578cedca6f6cffe6fec5525bb1",
        [Root + "item/service/EquipmentStatContributionCalculator.java"] = "c102685949acd11e5bced0d5b693013d55e338014daa6a74851cf47cc77b80f3",
        [Root + "status/model/StatusValue.kt"] = "d19f14ab276a28c2171d0f07dbfa908375f539c89fce71dd84f7012c0275fd6f",
        [Root + "item/service/EquipmentRequirementService.java"] = "8bd7f45b6443b6bc504fe58037cf8a26674bb392109176207b7e0c9312f90f8a",
        [Root + "mob/service/MobCombatService.java"] = "9361948e9f4c84cd083c2e7d682424b0e5170afb0a1cf7868fa49b60b5c9d55b",
        [Root + "playerclass/PlayerClassService.kt"] = "44843e0e0f38f75fb2179a6eb615cd972f8e0b0715d1cfccd0a2d4cf34aec952",
        [Root + "skill/executor/active/wizard/WizardSelfHealExecutor.java"] = "3b36abfd312851f695b37f90696792e2fbc9211acfb1372161783e657b099bdf",
        [Root + "skill/executor/active/adventurer/AdventurerQuickShotExecutor.java"] = "69af27237cbe95277022bebeef0639412a5011f762334bc72dedaa14c3333713",
        [Root + "skill/executor/active/adventurer/AdventurerManaBurstExecutor.java"] = "1fbde13e80a902798aa39058e5617597c88f9b75ad8898739cd8ea0259c9b6ea",
        [Root + "skill/executor/active/adventurer/AdventurerSmashExecutor.java"] = "01b5042ce2a11c97a05fa2eb14f96d7ec6506cb9f98fee0b9c03f19dbf2097c1",
        [Root + "mob/repository/MobRepository.java"] = "7f5c2140773c5a6612cb26ddc0fe8e5ef45fe620f5330660effc3ccf3bca072b",
        [Root + "mob/model/MobTemplate.java"] = "594ccd90b29b9fc03445201778f1057477d683a0b0103d4a7d973ce9022c30e2",
        [Root + "mob/model/MobShieldConfig.java"] = "fc8c86bc97627d1f1c633024a9b110f624057a2216af0e593f0609fdbe1d7779",
        [Root + "item/model/ItemEquipment.kt"] = "0d88fa31eddc0606a46ecf14175ecc2af96456f105a8eb5194c7705af01036be",
        [Root + "item/model/EquipmentInstance.kt"] = "00fb321f4d124b8c8da7dc0d90a6f8e40debeae49510d1ea95bc535d78b3cb06",
        [Root + "item/model/ItemRune.kt"] = "528cf44fdb5cc3b0ce68b0a52850767fd5bc5b62bd781de8e0e5d257a747ed67"
    };

    internal static IReadOnlyList<string> Mismatches(JsonObject catalog)
    {
        var available = new Dictionary<string, string>(StringComparer.OrdinalIgnoreCase);
        foreach (var row in BalanceCombatData.Array(catalog["sources"]).OfType<JsonObject>())
        {
            var path = BalanceCombatData.Text(row["path"]).Replace('\\', '/');
            var hash = BalanceCombatData.Text(row["hash"]);
            if (path.Length > 0) available[path] = hash;
        }
        return SourceHashes.Where(pair => !available.TryGetValue(pair.Key, out var hash)
            || !hash.Equals(pair.Value, StringComparison.OrdinalIgnoreCase))
            .Select(pair => pair.Key).ToArray();
    }
}
