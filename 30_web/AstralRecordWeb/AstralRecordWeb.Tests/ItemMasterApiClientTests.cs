using System.Net;
using System.Text;
using AstralRecordWeb.Pages.Admin;
using AstralRecordWeb.Services;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class ItemMasterApiClientTests
{
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
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            var json = request.RequestUri!.AbsolutePath switch
            {
                "/api/item" => """
                    [{"id":"debug_sword","category":"equipment"},{"id":"initial_bundle","category":"bundle"}]
                    """,
                "/api/item/debug_sword" => """
                    {
                      "schemaVersion":1,"id":"debug_sword","category":"equipment","name":"Debug Sword",
                      "icon":"IRON_SWORD","rarity":"MYTHIC",
                      "equipment":{"slot":"WEAPON","requiredClasses":[{"classId":"swordsman","level":3}]}
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
