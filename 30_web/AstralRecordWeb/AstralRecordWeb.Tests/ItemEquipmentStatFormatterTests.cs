using AstralRecordWeb.Models;
using Xunit;

namespace AstralRecordWeb.Tests;

public class ItemEquipmentStatFormatterTests
{
    [Fact]
    public void MasterRandomDisplaysRollRangeAndZeroChance()
    {
        var stat = new ItemEquipmentStatResponse
        {
            Value = new ItemEquipmentStatValueResponse { Min = "10", Max = "10" },
            Random = new ItemEquipmentStatRandomResponse
            {
                OffsetMin = -2.5,
                OffsetMax = 1.5,
                Step = 0.5,
                ZeroChance = 0.2,
            },
        };

        var display = ItemEquipmentStatFormatter.Format(stat);
        Assert.Contains("7.5 - 11.5", display);
        Assert.Contains("0の確率", display);
    }

    [Fact]
    public void LegacyMasterAndInstanceDisplayRemainIndependent()
    {
        var stat = new ItemEquipmentStatResponse
        {
            Value = new ItemEquipmentStatValueResponse { Min = "2", Max = "4" },
        };

        Assert.Equal("2 - 4", ItemEquipmentStatFormatter.Format(stat));
        Assert.Equal("3", AstralRecordWeb.Pages.Market.IndexModel.InstanceStatText(new MarketEquipmentStatRollResponse { Min = "3", Max = "3" }));
    }
}
