using System.Net;
using System.Security.Claims;
using System.Text;
using System.Text.Encodings.Web;
using System.Text.Json;
using System.Text.RegularExpressions;
using AstralRecordWeb.Models;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.AspNetCore.TestHost;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class MarketRenderingTests
{
    private static readonly Guid Actor = Guid.Parse("11111111-1111-1111-1111-111111111111");
    private static readonly Guid Buyer = Guid.Parse("22222222-2222-2222-2222-222222222222");
    private static readonly Guid Seller = Guid.Parse("33333333-3333-3333-3333-333333333333");

    [Theory]
    [InlineData(100L, 100L, "{\"SuggestedUnitPrice\":500}", 101L)]
    [InlineData(null, 100L, "{\"SuggestedUnitPrice\":100}", 101L)]
    [InlineData(null, 0L, "{\"suggestedUnitPrice\":0}", 1L)]
    [InlineData(null, 100L, "{\"SuggestedUnitPrice\":200}", 200L)]
    [InlineData(150L, 100L, "{\"SuggestedUnitPrice\":200}", 150L)]
    [InlineData(null, long.MaxValue, "{\"SuggestedUnitPrice\":9223372036854775807}", null)]
    public void RecommendedPrice_StaysAboveListingPriceFloor(long? reference, long floor, string snapshot, long? expected)
    {
        var listing = new MarketListingResponse
        {
            ReferenceUnitPrice = reference,
            PriceFloor = floor,
            ValuationSnapshotJson = snapshot,
        };

        Assert.Equal(expected, new MarketListingItem(listing, null).RecommendedUnitPrice);
    }

    [Fact]
    public async Task MarketRendersWalletVisibleSellerAndOneSharedBuyerForEveryPurchase()
    {
        await using var factory = new MarketRenderingFactory();
        using var client = factory.CreateClient(new WebApplicationFactoryClientOptions { BaseAddress = new Uri("https://localhost") });
        var response = await client.GetAsync($"/Market?BuyerAccountId={Buyer}");
        response.EnsureSuccessStatusCode();
        var html = WebUtility.HtmlDecode(await response.Content.ReadAsStringAsync());

        Assert.Contains("128,500", html);
        Assert.Contains("現在の所持金", html);
        Assert.Contains("保存済み残高", html);
        Assert.Equal(3, Regex.Matches(html, "class=\"market-recommended-price\"").Count);
        Assert.Contains("出品価格（1個あたり）", html);
        Assert.Equal(6, Regex.Matches(html, "おすすめ価格（出品時点の参考価格）").Count);
        Assert.Matches("market-recommended-price[^>]*><span>おすすめ価格[^<]*</span><strong>11,000</strong>", html);
        Assert.Matches("market-recommended-price[^>]*><span>おすすめ価格[^<]*</span><strong>701</strong>", html);
        Assert.Matches("おすすめ価格（出品時点の参考価格）</dt><dd>701\\s+Gold</dd>", html);
        Assert.Matches("market-recommended-price[^>]*><span>おすすめ価格[^<]*</span><strong>データなし</strong>", html);
        var firstCard = html[html.IndexOf("<article", StringComparison.Ordinal)..];
        var detailsIndex = firstCard.IndexOf("<details", StringComparison.Ordinal);
        Assert.True(firstCard.IndexOf("出品者", StringComparison.Ordinal) < detailsIndex);
        Assert.True(firstCard.IndexOf("Luna", StringComparison.Ordinal) < detailsIndex);
        Assert.Contains($"accountId={Seller}", firstCard);
        var forms = Regex.Matches(html, "<form[^>]*data-market-purchase[\\s\\S]*?</form>");
        Assert.Equal(3, forms.Count);
        foreach (Match form in forms)
        {
            Assert.Contains($"name=\"BuyerAccountId\" value=\"{Buyer}\"", form.Value);
            Assert.Contains("data-gold=\"128500\"", form.Value);
            Assert.Contains("name=\"__RequestVerificationToken\"", form.Value);
            Assert.DoesNotContain("<select", form.Value);
        }
        Assert.Contains("readonly=\"readonly\"", forms[0].Value);
    }

    private sealed class MarketRenderingFactory : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder) => builder.WithIsolatedWebDependencies().ConfigureTestServices(services =>
        {
            services.AddAuthentication(options =>
            {
                options.DefaultAuthenticateScheme = "MarketRendering";
                options.DefaultChallengeScheme = "MarketRendering";
            }).AddScheme<AuthenticationSchemeOptions, MarketAuthentication>("MarketRendering", _ => { });
            services.AddHttpClient<MarketApiClient>().ConfigurePrimaryHttpMessageHandler(() => new RenderingHandler());
            services.AddHttpClient<PlayerProfileApiClient>().ConfigurePrimaryHttpMessageHandler(() => new RenderingHandler());
            services.AddHttpClient<WebAuthApiClient>().ConfigurePrimaryHttpMessageHandler(() => new RenderingHandler());
        });
    }

    private sealed class MarketAuthentication(IOptionsMonitor<AuthenticationSchemeOptions> options,
        ILoggerFactory logger, UrlEncoder encoder) : AuthenticationHandler<AuthenticationSchemeOptions>(options, logger, encoder)
    {
        protected override Task<AuthenticateResult> HandleAuthenticateAsync() => Task.FromResult(AuthenticateResult.Success(
            new AuthenticationTicket(new ClaimsPrincipal(new ClaimsIdentity(
                [new Claim(ClaimTypes.NameIdentifier, Actor.ToString()), new Claim(ClaimTypes.Name, "Kaede")],
                Scheme.Name)), Scheme.Name)));
    }

    private sealed class RenderingHandler : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(HttpRequestMessage request, CancellationToken cancellationToken)
        {
            var path = request.RequestUri!.AbsolutePath;
            object? value = path switch
            {
                "/api/account" => new[] {
                    new { uuid = Buyer, userId = Actor, accountName = "Kaede", slotIndex = 1, isDeleted = false },
                    new { uuid = Guid.Parse("44444444-4444-4444-4444-444444444444"), userId = Actor, accountName = "Kaede Adventure", slotIndex = 2, isDeleted = false },
                },
                "/api/market/listings" => new[] {
                    Listing("blade", 12500, 1, true, 0), Listing("crystal", 850, 24, false, 1), Listing("potion", 120, 64, false, 2),
                },
                "/api/item/blade" => new { schemaVersion = 1, id = "blade", name = "星霜のロングソード", category = "EQUIPMENT", rarity = "EPIC", icon = "DIAMOND_SWORD",
                    lore = new[] { "星の光を宿した刀身。旅路を切り拓く、冒険者の一振り。" }, equipment = new { slot = "MAIN_HAND", requiredLevel = 25, stats = Array.Empty<object>() } },
                "/api/item/crystal" => new { schemaVersion = 1, id = "crystal", name = "澄んだアストラル結晶", category = "MATERIAL", rarity = "RARE", icon = "AMETHYST_SHARD", lore = new[] { "装備の強化に用いる、淡い輝きを帯びた結晶。" } },
                "/api/item/potion" => new { schemaVersion = 1, id = "potion", name = "ヒーリングポーション", category = "CONSUMABLE", rarity = "COMMON", icon = "POTION", lore = new[] { "長い冒険のお供に。体力を回復する薬。" } },
                _ when path.StartsWith("/api/web-profiles/", StringComparison.Ordinal) => new {
                    userUuid = Actor, mcid = "Kaede", accounts = Array.Empty<object>(),
                    currentAccount = new {
                        accountId = Buyer, accountName = "Kaede", slotIndex = 1, playerLevel = 32, classId = "warrior", className = "Warrior", classLevel = 12,
                        classProgresses = Array.Empty<object>(), gold = 128500, updatedAt = DateTime.UtcNow,
                        skillTree = new { structureId = "test", name = "test", rootNodeId = "root", nodes = Array.Empty<object>(), edges = Array.Empty<object>() },
                    },
                },
                _ => null,
            };
            return Task.FromResult(value is null ? new HttpResponseMessage(HttpStatusCode.NotFound)
                : new HttpResponseMessage(HttpStatusCode.OK) { Content = new StringContent(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json") });
        }

        private static object Listing(string itemId, long price, int quantity, bool equipment, int order) => new {
            listingId = Guid.NewGuid(), sellerAccountId = Seller, sellerUserUuid = Guid.Parse("55555555-5555-5555-5555-555555555555"),
            sellerAccountName = order == 0 ? "Luna" : order == 1 ? "Aster" : "Mizuki", sellerAccountSlotIndex = 1,
            itemCategory = equipment ? "EQUIPMENT" : "MATERIAL", itemId, quantity, remainingQuantity = quantity,
            unitPrice = price, totalPrice = price * quantity, currencyId = "gold", status = "ACTIVE",
            priceFloor = order == 1 ? 700L : 0L,
            referenceUnitPrice = order == 0 ? 11000L : (long?)null,
            valuationSnapshotJson = order switch { 0 => "{\"SuggestedUnitPrice\":10000}", 1 => "{\"suggestedUnitPrice\":700}", _ => "invalid-json" },
            listedAt = DateTime.UtcNow.AddMinutes(-order * 30), expiresAt = DateTime.UtcNow.AddDays(2),
            instanceId = equipment ? Guid.NewGuid() : (Guid?)null, instanceType = equipment ? "EQUIPMENT" : null,
            equipmentInstance = equipment ? new {
                equipmentInstanceId = Guid.NewGuid(), itemId, enhanceLevel = 5, transcendenceRank = 1,
                runeMaxSlots = 3, durabilityValue = 98, durabilityMax = 100,
                statRolls = new[] { new { status = "physical_attack", min = "32", max = "40", sortOrder = 0 } },
                enchants = Array.Empty<object>(), runes = Array.Empty<object>(),
            } : null,
        };
    }
}
