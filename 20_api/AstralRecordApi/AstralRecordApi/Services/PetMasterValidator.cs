using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

public static class PetMasterValidator
{
    public static void Validate(PetMasterResponse master)
    {
        var rules = master.Rules;
        Require(master.Id == "pets" && master.Species.Count > 0 && master.Species.Select(s => s.Id).Distinct().Count() == master.Species.Count, "species IDs");
        Require(rules.MaxLevel >= 50 && rules.WildMaxTier >= 0 && rules.BreedMaxTier > rules.WildMaxTier && rules.BreedMaxTier <= 100, "tier/level bounds");
        Require(rules.SkillSlotLevels.Length == 3 && rules.SkillSlotLevels[0] == 1
            && rules.SkillSlotLevels[1] > 1 && rules.SkillSlotLevels[2] > rules.SkillSlotLevels[1] && rules.SkillSlotLevels[2] <= rules.MaxLevel
            && rules.BreedMinLevel >= rules.SkillSlotLevels[1] && rules.BreedMinLevel <= rules.MaxLevel, "slot/breed levels");
        Require(rules.BreedCooldownHours > 0 && !string.IsNullOrWhiteSpace(rules.FacilityId) && !string.IsNullOrWhiteSpace(rules.ReviveOrbItemId), "facility/cooldown");
        Require(new[] { rules.MutationChance, rules.WildPotentialChance, rules.MaleChance, rules.EggDropChance }.All(v => double.IsFinite(v) && v >= 0 && v <= 1)
            && rules.PotentialGrowthMultiplier == 2 && rules.PotentialCountWeights.Length == 3 && rules.PotentialCountWeights.All(w => w >= 0)
            && rules.PotentialCountWeights.Sum() > 0, "probabilities/potential");
        var curve = master.Experience;
        Require(new[] { curve.Base, curve.Quadratic, curve.TierBonus, curve.MilestoneBase, curve.MilestoneLinear, curve.ActivityRate }.All(double.IsFinite)
            && curve.Base > 0 && curve.Quadratic >= 0 && curve.TierInterval > 0 && curve.MilestoneInterval > 0
            && curve.Wave.Length > 0 && curve.Wave.All(v => v >= 0) && curve.ActivityRate > 0, "experience curve");
        foreach (var species in master.Species)
        {
            Require(!string.IsNullOrWhiteSpace(species.Id) && !string.IsNullOrWhiteSpace(species.EggItemId) && !string.IsNullOrWhiteSpace(species.PetItemId)
                && species.EggItemId != species.PetItemId && species.Names.Length > 0 && species.Names.All(n => n.Length is > 0 and <= 24)
                && species.SizeMin > 0 && species.SizeMax >= species.SizeMin && species.DropWeight > 0, "species metadata");
            Require(species.Stats.Keys.Order().SequenceEqual(new[] { "DEFENSE", "EVASION", "POWER", "SUPPORT", "VITALITY" }), "stat keys");
            foreach (var stat in species.Stats.Values)
            {
                var tiers = stat.Tiers.OrderBy(t => t.Tier).ToArray();
                Require(tiers.Length == rules.BreedMaxTier + 1 && tiers.Select(t => t.Tier).SequenceEqual(Enumerable.Range(0, tiers.Length))
                    && tiers.All(t => double.IsFinite(t.Min) && double.IsFinite(t.Max) && t.Min >= 0 && t.Max >= t.Min && t.Max <= 100000
                        && t.Min == Math.Floor(t.Min) && t.Max == Math.Floor(t.Max) && double.IsFinite(t.Weight) && t.Weight >= 0)
                    && tiers.Zip(tiers.Skip(1)).All(pair => pair.First.Max < pair.Second.Min)
                    && tiers.Where(t => t.Tier <= rules.WildMaxTier).Sum(t => t.Weight) > 0, "stat tiers");
                Require(new[] { stat.ValueWeightPower, stat.GrowthPerLevel, stat.Scale, stat.InheritanceMin, stat.InheritanceMax }.All(double.IsFinite)
                    && stat.ValueWeightPower is >= 0 and <= 16 && stat.GrowthPerLevel >= 0 && stat.Scale > 0
                    && stat.InheritanceMin >= 0 && stat.InheritanceMax >= stat.InheritanceMin, "stat growth/coefficients");
            }
            Require(species.Skills.Count == 8 && species.Skills.Select(s => s.Id).Distinct().Count() == 8
                && Enumerable.Range(0, 4).All(t => species.Skills.Count(s => s.Tier == t) == 2)
                && species.Skills.All(s => new[] { s.Weight, s.CooldownSeconds, s.Value, s.Chance, s.DurationSeconds }.All(double.IsFinite)
                    && s.Weight > 0 && s.CooldownSeconds > 0 && s.Value >= 0 && s.Chance is >= 0 and <= 1
                    && s.DurationSeconds >= 0 && s.HitCount > 0 && s.AttackCount > 0), "skills");
            if (species.BasicAttack is { } attack)
                Require(new[] { attack.DamageRatio, attack.CooldownSeconds, attack.Range }.All(double.IsFinite)
                    && attack.DamageRatio > 0 && attack.CooldownSeconds > 0 && attack.Range > 0, "basic attack");
            ValidateMaterials(species.HatchMaterials);
        }
        ValidateMaterials(rules.BreedMaterials); ValidateMaterials(rules.ReviveMaterials);
    }

    private static void ValidateMaterials(IReadOnlyList<PetMaterialMaster> materials)
        => Require(materials.Count > 0 && materials.All(m => !string.IsNullOrWhiteSpace(m.ItemId) && m.Quantity > 0), "materials");
    private static void Require(bool valid, string area)
    {
        if (!valid) throw new InvalidOperationException("Pet master is invalid: " + area);
    }
}
