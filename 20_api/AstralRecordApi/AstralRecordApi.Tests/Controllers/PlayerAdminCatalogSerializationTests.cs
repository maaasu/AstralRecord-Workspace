using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Http;
using Microsoft.AspNetCore.Mvc;
using Microsoft.AspNetCore.Mvc.Abstractions;
using Microsoft.AspNetCore.Routing;
using Microsoft.Extensions.DependencyInjection;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

/// <summary>
/// RPG hashes the raw detail-response bytes. The admin catalog hash serializes
/// those same DTOs with MVC JsonOptions, so keep the MVC wire bytes
/// aligned when changing JSON configuration.
/// </summary>
public sealed class PlayerAdminCatalogSerializationTests
{
    [Fact]
    public async Task ItemDetail_WireBodyMatchesCatalogFingerprintInput()
    {
        var item = new ItemResponse
        {
            SchemaVersion = 1, Id = "test_sword", Category = "equipment",
            Name = "星の剣", Icon = "IRON_SWORD", Rarity = "COMMON", MaxStack = 1,
            Equipment = new ItemEquipmentResponse
            {
                Slot = "MAIN_HAND", Rune = new ItemEquipmentRuneResponse { MaxSlots = "2" },
                Stats = [new ItemEquipmentStatResponse
                {
                    Status = "attack", Type = "RANDOM",
                    Value = new ItemEquipmentStatValueResponse { Min = "1.25", Max = "2.75" },
                }],
            },
        };
        var response = Assert.IsType<OkObjectResult>(new ItemController(new ItemStub(item)).GetById(item.Id));
        Assert.Equal(JsonSerializer.Serialize(item, CatalogOptions()),
            await WriteMvcResponseAsync(response));
    }

    [Fact]
    public async Task ClassDetail_WireBodyMatchesCatalogFingerprintInput()
    {
        var definition = new ClassResponse
        {
            SchemaVersion = 1, Id = "mage", Type = "MAGIC", Name = "魔導士",
            Order = 1, ShortName = "Mage", Role = "DAMAGE", BaseStats = [],
            UnlockClassLevel = [new ClassUnlockClassLevelResponse { ClassId = "novice", Level = 10 }],
        };
        var response = Assert.IsType<OkObjectResult>(new ClassController(new ClassStub(definition))
            .GetById(definition.Id));
        Assert.Equal(JsonSerializer.Serialize(definition, CatalogOptions()),
            await WriteMvcResponseAsync(response));
    }

    private static async Task<string> WriteMvcResponseAsync(OkObjectResult response)
    {
        var services = new ServiceCollection();
        services.AddLogging();
        services.AddControllers();
        using var provider = services.BuildServiceProvider();
        var context = new DefaultHttpContext { RequestServices = provider };
        await using var output = new MemoryStream();
        context.Response.Body = output;
        await response.ExecuteResultAsync(new ActionContext(context, new RouteData(), new ActionDescriptor()));
        return Encoding.UTF8.GetString(output.ToArray());
    }

    private static JsonSerializerOptions CatalogOptions() => new(JsonSerializerDefaults.Web)
    {
        Encoder = JavaScriptEncoder.UnsafeRelaxedJsonEscaping,
    };

    private sealed class ItemStub(ItemResponse item) : IItemRepository
    {
        public IReadOnlyList<ItemSummaryResponse> GetAllSummaries() => [];
        public ItemResponse? GetById(string itemId) => itemId == item.Id ? item : null;
    }

    private sealed class ClassStub(ClassResponse definition) : IClassRepository
    {
        public IReadOnlyList<ClassSummaryResponse> GetAllSummaries() => [];
        public ClassResponse? GetById(string classId) => classId == definition.Id ? definition : null;
    }
}
