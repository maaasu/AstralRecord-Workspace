using System.Text.Json.Nodes;
namespace SkillTreeEditor.Server.Services;

// Source revisions of the economic runtime contracts used by this model.
internal static class BalanceEconomyParity
{
    internal static readonly IReadOnlyDictionary<string, string> SourceHashes = new Dictionary<string, string>(StringComparer.Ordinal)
    {
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/loot/service/LootRollService.java"] = "d9232aea2bd77e74b412d46db564f804c015c96bac2930df141642727648b28e",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/service/MobDropService.java"] = "9da6de209b6c574e94f861baea5b742c53a28359655fc7dedd2217ae523794f3",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/repository/MobRepository.java"] = "7f5c2140773c5a6612cb26ddc0fe8e5ef45fe620f5330660effc3ccf3bca072b",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/mob/model/MobTemplate.java"] = "594ccd90b29b9fc03445201778f1057477d683a0b0103d4a7d973ce9022c30e2",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/OrbLocalMutationCalculator.java"] = "585a111a356f66a5f5fd6c85f481b76a2f93b5601b4e6f2baf65cdff43779e90",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/OrbEligibility.java"] = "e450d7b36cbf0d24cabd8da889e0bcc036ddcea9f08b5b428eee841a8bed696a",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/repository/ItemRepository.kt"] = "d0257358f7bcb22a09dfb69fb5602bfbc5c4c5c7274223312d7ae979bacc9548",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/sell/service/SellService.java"] = "06578cb7d159403b4e1e1bc91b906691f89d3bb84eb4c667ec2290f2bec031bf",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/status/service/StatusRateCalculator.java"] = "a0c15c8d4455cde418271274b33ab7297967f2cd49d86cd15ecb9d477abc34af",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/combat/service/LevelDifferenceCalculator.java"] = "848aaeb53991bdea9ae91c40edb139627eca28721e6ff5c5745dd9c7a1255b7e",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/account/service/AccountService.java"] = "9552fcf4372d1eff573a8a2cb62a2e89a36396ed6a91a59eb2ee9bbde2b036f7",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/playerclass/PlayerClassService.kt"] = "44843e0e0f38f75fb2179a6eb615cd972f8e0b0715d1cfccd0a2d4cf34aec952",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/shop/repository/ShopRecipeRepository.java"] = "73f577ca28132ad49bf07a149f5d87702d457335bf7702d6c0c6b3d96eceee91",
        ["10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/item/service/EquipmentEnhanceCalculator.java"] = "940af9ce115bab9c854253ef86cc98b8f6b6e869122ec7f2d674cc6ba30fcd15",
    };
    internal static string[] Mismatches(JsonObject catalog)
    {
        var actual = (catalog["sources"] as JsonArray ?? []).OfType<JsonObject>().ToDictionary(value => value["path"]!.ToString(), value => value["hash"]!.ToString(), StringComparer.Ordinal);
        return SourceHashes.Where(pair => !actual.TryGetValue(pair.Key, out var hash) || hash != pair.Value).Select(pair => pair.Key).ToArray();
    }
}
