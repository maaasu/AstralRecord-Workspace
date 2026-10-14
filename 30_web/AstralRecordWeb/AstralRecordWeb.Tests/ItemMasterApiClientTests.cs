using System.Globalization;
using System.Net;
using System.Text;
using AstralRecordWeb.Models;
using AstralRecordWeb.Pages.Admin;
using AstralRecordWeb.Services;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class ItemMasterApiClientTests
{
    [Fact]
    public async Task GetNamesAsync_LoadsRequestedNamesFromSingleSummaryRequest()
    {
        var handler = new ItemMasterApiHandler();
        using var httpClient = new HttpClient(handler)
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var subject = new ItemMasterApiClient(httpClient);

        var names = await subject.GetNamesAsync(
            ["debug_sword", "DEBUG_SWORD", "legacy-item", "missing-item"],
            CancellationToken.None);

        Assert.Equal("Debug Sword", Assert.Single(names).Value);
        Assert.Equal(1, handler.RequestCounts["/api/item"]);
        Assert.DoesNotContain("/api/item/debug_sword", handler.RequestCounts.Keys);
        Assert.DoesNotContain("/api/item/missing-item", handler.RequestCounts.Keys);
    }

    [Fact]
    public async Task GetAllAsync_DeserializesCurrentEquipmentAndBundleContracts()
    {
        using var httpClient = new HttpClient(new ItemMasterApiHandler())
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var subject = new ItemMasterApiClient(httpClient);

        var items = await subject.GetAllAsync(CancellationToken.None);

        var equipment = Assert.Single(items, item => item.Id == "debug_sword");
        var requiredClass = Assert.Single(equipment.Equipment!.RequiredClasses);
        Assert.Equal("swordsman", requiredClass.ClassId);
        Assert.Equal(3, requiredClass.Level);

        var bundle = Assert.Single(items, item => item.Id == "initial_bundle");
        Assert.Equal("block.chest.open", bundle.Bundle!.OnUse!.Sound!.Sound);
        Assert.Equal(0.8, bundle.Bundle.OnUse.Sound.Volume);
        Assert.Equal("TOTEM_OF_UNDYING", bundle.Bundle.OnUse.Particle!.Particle);
        Assert.Equal(24, bundle.Bundle.OnUse.Particle.Count);
    }

    [Fact]
    public async Task GetAllAsync_PreservesRandomMasterForDisplayWithoutChangingSavedInstanceRoll()
    {
        using var httpClient = new HttpClient(new ItemMasterApiHandler())
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var subject = new ItemMasterApiClient(httpClient);

        var items = await subject.GetAllAsync(CancellationToken.None);
        var equipment = Assert.Single(items, item => item.Id == "debug_sword");
        var stat = Assert.Single(equipment.Equipment!.Stats);
        var value = Assert.IsType<ItemEquipmentStatValueResponse>(stat.Value);
        var random = Assert.IsType<ItemEquipmentStatRandomResponse>(stat.Random);
        Assert.Equal("0", value.Min);
        Assert.Equal("0", value.Max);
        Assert.Equal(0.1, random.OffsetMin);
        Assert.Equal(10, random.OffsetMax);
        Assert.Equal(0.1, random.Step);
        Assert.Equal(0.95, random.ZeroChance);

        var masterDisplay = ItemsModel.StatValueText(stat);
        Assert.StartsWith("0.1 - 10", masterDisplay);
        Assert.Contains($"0の確率 {0.95.ToString("P2", CultureInfo.CurrentCulture)}", masterDisplay);
        Assert.Equal("4.6", AstralRecordWeb.Pages.Market.IndexModel.InstanceStatText(
            new MarketEquipmentStatRollResponse { Min = "4.6", Max = "4.6" }));
    }

    [Fact]
    public async Task ItemsPage_ShowsAnInPageErrorWhenItemJsonIsInvalid()
    {
        using var httpClient = new HttpClient(new FixedResponseHandler((_, _) =>
            Task.FromResult(JsonResponse("["))))
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var page = new ItemsModel(new ItemMasterApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);

        Assert.Equal("アイテムマスタの形式が不正です。API と Web の更新状態を確認してください。", page.ErrorMessage);
        Assert.Empty(page.Items);
    }

    [Fact]
    public async Task ItemsPage_ShowsAnInPageErrorWhenItemApiTimesOut()
    {
        using var httpClient = new HttpClient(new FixedResponseHandler((_, _) =>
            Task.FromCanceled<HttpResponseMessage>(new CancellationToken(canceled: true))))
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var page = new ItemsModel(new ItemMasterApiClient(httpClient));

        await page.OnGetAsync(CancellationToken.None);

        Assert.Equal("アイテムマスタの取得がタイムアウトしました。しばらくしてから再試行してください。", page.ErrorMessage);
        Assert.Empty(page.Items);
    }

    [Fact]
    public async Task ItemsPage_RethrowsUserRequestedCancellation()
    {
        using var httpClient = new HttpClient(new FixedResponseHandler((_, _) =>
            Task.FromCanceled<HttpResponseMessage>(new CancellationToken(canceled: true))))
        {
            BaseAddress = new Uri("https://api.example/"),
        };
        var page = new ItemsModel(new ItemMasterApiClient(httpClient));
        using var cancellationSource = new CancellationTokenSource();
        cancellationSource.Cancel();

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => page.OnGetAsync(cancellationSource.Token));
    }

    private sealed class ItemMasterApiHandler : HttpMessageHandler
    {
        public Dictionary<string, int> RequestCounts { get; } = new(StringComparer.OrdinalIgnoreCase);

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var path = request.RequestUri!.AbsolutePath;
            RequestCounts[path] = RequestCounts.GetValueOrDefault(path) + 1;
            var json = request.RequestUri!.AbsolutePath switch
            {
                "/api/item" => """
                    [{"id":"debug_sword","category":"equipment","name":"Debug Sword"},{"id":"initial_bundle","category":"bundle","name":"Initial Bundle"},{"id":"legacy-item","category":"material"}]
                    """,
                "/api/item/debug_sword" => """
                    {
                      "schemaVersion":1,"id":"debug_sword","category":"equipment","name":"Debug Sword",
                      "icon":"IRON_SWORD","rarity":"MYTHIC",
                      "equipment":{"slot":"WEAPON","requiredClasses":[{"classId":"swordsman","level":3}],
                        "stats":[{"status":"FIRE_DAMAGE_INCREASE","type":"FLAT","value":{"min":"0","max":"0"},
                          "random":{"offsetMin":0.1,"offsetMax":10,"step":0.1,"zeroChance":0.95}}]}
                    }
                    """,
                "/api/item/initial_bundle" => """
                    {
                      "schemaVersion":1,"id":"initial_bundle","category":"bundle","name":"Initial Bundle",
                      "icon":"CHEST","rarity":"RARE",
                      "bundle":{"onUse":{"sound":{"sound":"block.chest.open","volume":0.8,"pitch":1.15},"particle":{"particle":"TOTEM_OF_UNDYING","count":24}}}
                    }
                    """,
                _ => null,
            };

            return Task.FromResult(json is null
                ? new HttpResponseMessage(HttpStatusCode.NotFound)
                : new HttpResponseMessage(HttpStatusCode.OK)
                {
                    Content = new StringContent(json, Encoding.UTF8, "application/json"),
                });
        }
    }

    private sealed class FixedResponseHandler(
        Func<HttpRequestMessage, CancellationToken, Task<HttpResponseMessage>> sendAsync) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken) => sendAsync(request, cancellationToken);
    }

    private static HttpResponseMessage JsonResponse(string json) => new(HttpStatusCode.OK)
    {
        Content = new StringContent(json, Encoding.UTF8, "application/json"),
    };
}
