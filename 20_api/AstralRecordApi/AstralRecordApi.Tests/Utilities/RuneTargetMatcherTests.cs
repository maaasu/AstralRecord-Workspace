using AstralRecordApi.Models;
using AstralRecordApi.Utilities;
using Xunit;

namespace AstralRecordApi.Tests.Utilities;

public class RuneTargetMatcherTests
{
    [Fact]
    public void MatchesSlotAndOptionalEquipmentTagAsSeparateDimensions()
    {
        var sword = new ItemEquipmentResponse { Slot = "WEAPON", Tag = "SWORD" };
        var bow = new ItemEquipmentResponse { Slot = "WEAPON", Tag = "BOW" };
        var lowerCaseBow = new ItemEquipmentResponse { Slot = "WEAPON", Tag = "bow" };
        var lowerCaseWeapon = new ItemEquipmentResponse { Slot = "weapon", Tag = "SWORD" };
        var allWeapons = new ItemRuneResponse { TargetSlots = ["WEAPON"] };
        var swordsOnly = new ItemRuneResponse
        {
            TargetSlots = ["WEAPON"],
            TargetTags = ["SWORD"],
        };
        var anySlotSwords = new ItemRuneResponse
        {
            TargetSlots = ["ANY"],
            TargetTags = ["SWORD"],
        };
        var bowOnly = new ItemRuneResponse
        {
            TargetSlots = ["WEAPON"],
            TargetTags = ["BOW"],
        };

        Assert.True(RuneTargetMatcher.Matches(sword, allWeapons));
        Assert.True(RuneTargetMatcher.Matches(bow, allWeapons));
        Assert.True(RuneTargetMatcher.Matches(lowerCaseWeapon, allWeapons));
        Assert.True(RuneTargetMatcher.Matches(sword, swordsOnly));
        Assert.False(RuneTargetMatcher.Matches(bow, swordsOnly));
        Assert.True(RuneTargetMatcher.Matches(sword, anySlotSwords));
        Assert.False(RuneTargetMatcher.Matches(lowerCaseBow, bowOnly));
    }
}
