using System.Security.Cryptography;
using AstralRecordApi.Models;

namespace AstralRecordApi.Services;

/// <summary>基礎個体値だけを遺伝させ、成長増分と潜在倍率を別に計算する。</summary>
public static class PetGenetics
{
    private static readonly string[] CollarColors =
    [
        "WHITE", "ORANGE", "MAGENTA", "LIGHT_BLUE", "YELLOW", "LIME", "PINK", "GRAY",
        "LIGHT_GRAY", "CYAN", "PURPLE", "BLUE", "BROWN", "GREEN", "RED", "BLACK",
    ];
    private static readonly string[] CatTypes =
    [
        "ALL_BLACK", "BLACK", "BRITISH_SHORTHAIR", "CALICO", "JELLIE", "PERSIAN",
        "RAGDOLL", "RED", "SIAMESE", "TABBY", "WHITE",
    ];

    public static PetDetailsResponse HatchWild(PetMasterResponse master, PetSpeciesMaster species, Random random)
    {
        var details = NewDetails(master, species, random);
        foreach (var (id, stat) in species.Stats)
        {
            var tier = Weighted(stat.Tiers.Where(t => t.Tier <= master.Rules.WildMaxTier).ToArray(), t => t.Weight, random);
            details.Stats[id] = new PetStatResponse
            {
                Tier = tier.Tier, BaseValue = RollValue(tier, stat.ValueWeightPower, random),
                Potential = random.NextDouble() < master.Rules.WildPotentialChance,
            };
        }
        var first = Weighted(species.Skills.Where(s => s.Tier == 0).ToArray(), s => s.Weight, random);
        details.Skills.Add(new PetSkillResponse { Id = first.Id, Tier = first.Tier, SlotIndex = 0 });
        RefreshValues(master, species, details);
        return details;
    }

    public static PetDetailsResponse Breed(PetMasterResponse master, PetSpeciesMaster species,
        PetDetailsResponse male, PetDetailsResponse female, Random random)
    {
        var details = NewDetails(master, species, random);
        foreach (var (id, definition) in species.Stats)
        {
            var left = male.Stats[id];
            var right = female.Stats[id];
            var inherited = left.Tier > right.Tier || (left.Tier == right.Tier && left.BaseValue >= right.BaseValue) ? left : right;
            var tier = inherited.Tier;
            var value = inherited.BaseValue;
            if (tier < master.Rules.BreedMaxTier && random.NextDouble() < master.Rules.MutationChance)
            {
                var next = definition.Tiers.Single(t => t.Tier == tier + 1);
                tier = next.Tier;
                value = Math.Max(value, RollValue(next, definition.ValueWeightPower, random));
            }
            details.Stats[id] = new PetStatResponse { Tier = tier, BaseValue = value };
        }
        var inheritedSkills = male.Skills.Concat(female.Skills).DistinctBy(s => s.Id).ToArray();
        var first = inheritedSkills[random.Next(inheritedSkills.Length)];
        details.Skills.Add(new PetSkillResponse { Id = first.Id, Tier = first.Tier, SlotIndex = 0 });
        var count = Math.Max(male.Stats.Values.Count(s => s.Potential), female.Stats.Values.Count(s => s.Potential));
        if (count == 0)
        {
            foreach (var stat in details.Stats.Values)
                stat.Potential = random.NextDouble() < master.Rules.WildPotentialChance;
        }
        else
        {
            var offsets = new[] { -1, 0, 1 };
            var offset = Weighted(offsets, o => master.Rules.PotentialCountWeights[o + 1], random);
            count = Math.Clamp(count + offset, 0, details.Stats.Count);
            var candidates = details.Stats.Keys.ToList();
            for (var index = 0; index < count; index++)
            {
                var id = Weighted(candidates, id => (male.Stats[id].Potential ? 4 : 1) + (female.Stats[id].Potential ? 4 : 1), random);
                details.Stats[id].Potential = true;
                candidates.Remove(id);
            }
        }
        RefreshValues(master, species, details);
        return details;
    }

    public static void Grow(PetMasterResponse master, PetSpeciesMaster species, PetDetailsResponse details, long experience, Random random)
    {
        details.Experience = checked(details.Experience + checked((long)Math.Floor(experience * master.Experience.ActivityRate)));
        while (details.Level < master.Rules.MaxLevel)
        {
            var required = RequiredExperience(master.Experience, details.Level);
            if (details.Experience < required) break;
            details.Experience -= required;
            details.Level++;
        }
        if (details.Level >= master.Rules.MaxLevel) details.Experience = 0;
        UnlockSkills(master, species, details, random);
        RefreshValues(master, species, details);
    }

    public static void SetLevel(PetMasterResponse master, PetSpeciesMaster species, PetDetailsResponse details, int targetLevel, Random random)
    {
        details.Level = targetLevel;
        details.Experience = 0;
        UnlockSkills(master, species, details, random);
        RefreshValues(master, species, details);
    }

    private static void UnlockSkills(PetMasterResponse master, PetSpeciesMaster species, PetDetailsResponse details, Random random)
    {
        foreach (var (unlockLevel, slot) in master.Rules.SkillSlotLevels.Select((value, index) => (value, index)))
        {
            if (details.Level < unlockLevel || details.Skills.Any(s => s.SlotIndex == slot)) continue;
            var maxTier = details.Skills[0].Tier >= 2 ? 3 : slot;
            var candidates = species.Skills.Where(s => s.Tier <= maxTier && details.Skills.All(owned => owned.Id != s.Id)).ToArray();
            var skill = Weighted(candidates, s => s.Weight, random);
            details.Skills.Add(new PetSkillResponse { Id = skill.Id, Tier = skill.Tier, SlotIndex = slot });
        }
    }

    public static long RequiredExperience(PetExperienceMaster curve, int level)
    {
        var milestone = level % curve.MilestoneInterval == 0 ? curve.MilestoneBase + level * curve.MilestoneLinear : 0;
        // 主人のUUIDによる小さな個体差は使わず、プレイヤーと同じ基礎曲線を使う。
        return checked((long)(curve.Base + level * (double)level * curve.Quadratic
            + level / curve.TierInterval * curve.TierBonus + curve.Wave[(level - 1) % curve.Wave.Length] + milestone));
    }

    public static void RefreshValues(PetMasterResponse master, PetSpeciesMaster species, PetDetailsResponse details)
    {
        foreach (var (id, stat) in details.Stats)
            stat.CurrentValue = stat.BaseValue + (details.Level - 1) * species.Stats[id].GrowthPerLevel
                * (stat.Potential ? master.Rules.PotentialGrowthMultiplier : 1);
    }

    private static PetDetailsResponse NewDetails(PetMasterResponse master, PetSpeciesMaster species, Random random) => new()
    {
        Name = species.Names[random.Next(species.Names.Length)],
        Sex = random.NextDouble() < master.Rules.MaleChance ? "MALE" : "FEMALE",
        Size = Math.Round(species.SizeMin + random.NextDouble() * (species.SizeMax - species.SizeMin), 3),
        Appearance = RollAppearance(species.Id, random),
    };

    private static PetAppearanceResponse? RollAppearance(string speciesId, Random random) => speciesId switch
    {
        "wolf" => new PetAppearanceResponse { CollarColor = CollarColors[random.Next(CollarColors.Length)] },
        "cat" => new PetAppearanceResponse
        {
            CollarColor = CollarColors[random.Next(CollarColors.Length)],
            CatType = CatTypes[random.Next(CatTypes.Length)],
        },
        _ => null,
    };

    /// <summary>外見情報を持たない既存個体だけに、個体IDから再現可能な外見を与える。</summary>
    public static PetAppearanceResponse? LegacyAppearance(string speciesId, Guid instanceId)
    {
        var hash = SHA256.HashData(instanceId.ToByteArray());
        return speciesId switch
        {
            "wolf" => new PetAppearanceResponse { CollarColor = CollarColors[hash[0] % CollarColors.Length] },
            "cat" => new PetAppearanceResponse
            {
                CollarColor = CollarColors[hash[0] % CollarColors.Length],
                CatType = CatTypes[hash[1] % CatTypes.Length],
            },
            _ => null,
        };
    }

    private static double RollValue(PetTierMaster tier, double power, Random random)
    {
        var values = Enumerable.Range((int)tier.Min, checked((int)tier.Max - (int)tier.Min + 1)).ToArray();
        return Weighted(values, value => Math.Pow(tier.Max - value + 1, power), random);
    }

    private static T Weighted<T>(IReadOnlyList<T> values, Func<T, double> weight, Random random)
    {
        if (values.Count == 0) throw new InvalidOperationException("Pet master contains no eligible candidates.");
        var remaining = random.NextDouble() * values.Sum(weight);
        foreach (var value in values)
        {
            remaining -= weight(value);
            if (remaining < 0) return value;
        }
        return values[^1];
    }
}
