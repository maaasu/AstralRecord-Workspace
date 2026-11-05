using System.Text.RegularExpressions;
using System.Net;
using AstralRecordWeb.Options;
using AstralRecordWeb.Services;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using Xunit;

namespace AstralRecordWeb.Tests;

public sealed class PublicSiteRenderingTests
{
    [Fact]
    public async Task Home_OpenAlpha_KeepsStatusAndConnectionInfoSeparateFromTheHero()
    {
        await using var factory = new PublicSiteWebApplicationFactory("OpenAlpha");
        using var client = CreateClient(factory);

        var response = await client.GetAsync("/");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync();
        var hero = ExtractSection(body, "<section class=\"ar-home-hero\"");
        var connect = ExtractSection(body, "<section class=\"ar-home-connect\"");
        var discord = ExtractSection(body, "<section class=\"ar-home-community\" id=\"discord\"");
        var join = ExtractSection(body, "<section class=\"ar-home-join\" id=\"join\"");

        Assert.Contains("data-public-phase=\"open-alpha\"", body);
        Assert.Contains("href=\"#join\"", hero);
        Assert.Contains("参加方法を見る", hero);
        Assert.DoesNotContain("mc.astralrecord.com", hero);
        Assert.DoesNotContain("data-copy-server", hero);
        Assert.Contains("data-server-status", connect);
        Assert.Contains("data-server-players>17", connect);
        Assert.Contains("data-server-maximum>100", connect);
        Assert.Contains("data-copy-value=\"mc.astralrecord.com\"", connect);
        Assert.Single(Regex.Matches(body, "class=\"ar-home-alpha-notice\"").Cast<Match>());
        Assert.Equal(2, Regex.Matches(body, @"data-copy-server(?:\s|>)").Count);
        Assert.Contains("mc.astralrecord.com", join);
        Assert.Contains("予告なく再起動や停止が発生することがあります", join);
        Assert.Contains("個別の復旧や補償の対象外", join);
        Assert.Contains("正式リリース時に引き継がれません", join);
        Assert.Contains("予告なく変更されることがあります", join);
        Assert.Contains("Java版ポート</dt><dd>25565", join);
        Assert.Contains("統合版ポート</dt><dd>19132", join);
        Assert.Contains("name=\"minecraft-edition\" value=\"java\"", join);
        Assert.Contains("name=\"minecraft-edition\" value=\"bedrock\"", join);
        Assert.Contains("一部の機能や操作に支障が出る場合があります", join);
        Assert.Contains("href=\"#discord\"", join);
        Assert.DoesNotContain("discord.gg", join, StringComparison.OrdinalIgnoreCase);

        Assert.Contains("公式Discordに参加する", discord);
        Assert.Contains("https://discord.gg/Fja6zmpjGX", discord);
        Assert.Contains("href=\"/#features\"", body);
        Assert.Contains("href=\"/#join\"", body);
        Assert.Contains("/images/bg-town-640.webp", body);
        Assert.Contains("/images/feature-combat-800.webp", body);

        foreach (var forbiddenPhrase in new[]
                 {
                     "開発対象",
                     "完成前",
                     "正式リリースまで",
                     "under construction",
                     "まだ途中の冒険",
                     "物語を紡ぐ",
                 })
        {
            Assert.DoesNotContain(forbiddenPhrase, body, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task Home_Release_SuppressesAllOpenAlphaSpecificPresentation()
    {
        await using var factory = new PublicSiteWebApplicationFactory("Release");
        using var client = CreateClient(factory);

        var response = await client.GetAsync("/");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync();

        Assert.Contains("data-public-phase=\"release\"", body);
        Assert.DoesNotContain("OPEN ALPHA", body, StringComparison.OrdinalIgnoreCase);
        Assert.DoesNotContain("ar-home-alpha-notice", body);
        Assert.DoesNotContain("正式リリース時に引き継がれません", body);
        Assert.Equal(2, Regex.Matches(body, @"data-copy-server(?:\s|>)").Count);
        Assert.Contains("mc.astralrecord.com", body);

        foreach (var path in new[] { "/Privacy", "/Terms" })
        {
            var legalResponse = await client.GetAsync(path);
            legalResponse.EnsureSuccessStatusCode();
            var legalBody = await legalResponse.Content.ReadAsStringAsync();

            Assert.DoesNotContain("OPEN ALPHA", legalBody, StringComparison.OrdinalIgnoreCase);
            Assert.DoesNotContain("オープンアルファ", legalBody);
            Assert.DoesNotContain("正式リリース時に引き継がれません", legalBody);
        }
    }

    [Fact]
    public async Task LegalPages_ReturnSuccessAndUseSharedLegalPresentation()
    {
        await using var factory = new PublicSiteWebApplicationFactory("OpenAlpha");
        using var client = CreateClient(factory);

        var termsResponse = await client.GetAsync("/Terms");
        var privacyResponse = await client.GetAsync("/Privacy");
        termsResponse.EnsureSuccessStatusCode();
        privacyResponse.EnsureSuccessStatusCode();

        var terms = await termsResponse.Content.ReadAsStringAsync();
        var privacy = await privacyResponse.Content.ReadAsStringAsync();

        Assert.Contains("ar-legal-page", terms);
        Assert.Contains("ar-legal-shell", terms);
        Assert.Contains("マクロ、自動操作、自動入力ツールは原則として使用禁止", terms);
        Assert.Contains("VPN・プロキシ", terms);
        Assert.Contains("XRay", terms);
        Assert.Contains("公式のお知らせで個別に禁止", terms);

        Assert.Contains("ar-legal-page", privacy);
        Assert.Contains("ar-legal-shell", privacy);
        Assert.Contains("Minecraftサーバー接続時のIPアドレス", privacy);
        Assert.Contains("広告配信やアクセス解析を目的とするCookieは使用していません", privacy);
        Assert.Contains("Discord連携は本人の明示的な操作と認可によって行い", privacy);
        Assert.Contains("ギフトカード番号または送金URL", privacy);
        Assert.Contains("受領を確認して承認した金額1円につき", terms);
        Assert.DoesNotContain("Webサイトへのアクセスログ", privacy);
        Assert.DoesNotContain("プレイ時間", privacy);
    }

    [Fact]
    public async Task ReleaseNotes_RenderInitialPublishedNote()
    {
        await using var factory = new PublicSiteWebApplicationFactory("OpenAlpha");
        using var client = CreateClient(factory);

        var catalog = factory.Services.GetRequiredService<ReleaseNoteCatalog>();
        var configuredPath = factory.Services.GetRequiredService<IOptions<ReleaseNoteOptions>>().Value.ContentRootRelativePath;
        Assert.True(Directory.Exists(configuredPath), configuredPath);
        Assert.NotEmpty(await catalog.GetPublishedAsync(CancellationToken.None));

        var listResponse = await client.GetAsync("/releases");
        listResponse.EnsureSuccessStatusCode();
        var listBody = await listResponse.Content.ReadAsStringAsync();
        var decodedListBody = WebUtility.HtmlDecode(listBody);

        Assert.Contains("リリースノート公開機能を導入しました", decodedListBody);
        Assert.Contains("0.1.0", decodedListBody);
        Assert.Contains("/releases/release-management", decodedListBody);
        Assert.Contains("Astral Recordのアップデート情報をWebサイトと公式Discordで確認できるようになりました", decodedListBody);

        var detailResponse = await client.GetAsync("/releases/release-management");
        detailResponse.EnsureSuccessStatusCode();
        var detailBody = await detailResponse.Content.ReadAsStringAsync();

        var decodedDetailBody = WebUtility.HtmlDecode(detailBody);
        Assert.Contains("2026年8月24日 21:00", decodedDetailBody);
        Assert.Contains("datetime=\"2026-08-24T21:00:00+09:00\"", decodedDetailBody);
        Assert.Contains("Astral Recordのアップデート情報を、Webサイトで確認できるようになりました", decodedDetailBody);
        Assert.Contains("公式Discordでもお知らせします", decodedDetailBody);
        var visibleDetailText = Regex.Replace(decodedDetailBody, "<[^>]+>", " ");

        foreach (var internalTerm in new[]
                 {
                     "00_docs/",
                     "front matter",
                     "デプロイ",
                     "Markdown",
                     "Webコード",
                     "API",
                     "Outbox",
                 })
        {
            Assert.DoesNotContain(internalTerm, visibleDetailText, StringComparison.OrdinalIgnoreCase);
        }
    }

    [Fact]
    public async Task ReleaseNotes_DisplayPublishedAtInJapanStandardTime()
    {
        var temporaryDirectory = Path.Combine(
            Path.GetTempPath(),
            $"astralrecord-release-note-jst-{Guid.NewGuid():N}");
        Directory.CreateDirectory(temporaryDirectory);

        try
        {
            await File.WriteAllTextAsync(
                Path.Combine(temporaryDirectory, "utc-release.md"),
                """
                ---
                slug: utc-release
                version: 1.0.0
                title: 日本時間表示テスト
                summary: 公開日時の表示確認
                publishedAt: 2026-08-23T18:00:00+00:00
                status: published
                notifyDiscord: false
                ---

                公開日時を日本標準時で表示します。
                """);

            await using var factory = new PublicSiteWebApplicationFactory("OpenAlpha", temporaryDirectory);
            using var client = CreateClient(factory);
            var listResponse = await client.GetAsync("/releases");
            listResponse.EnsureSuccessStatusCode();
            var listBody = WebUtility.HtmlDecode(await listResponse.Content.ReadAsStringAsync());

            Assert.Contains("2026年8月24日", listBody);
            Assert.Contains("datetime=\"2026-08-24T03:00:00+09:00\"", listBody);

            var homeBody = WebUtility.HtmlDecode(await client.GetStringAsync("/"));
            Assert.Contains("日本時間表示テスト", homeBody);
            Assert.Contains("/releases/utc-release", homeBody);

            var detailResponse = await client.GetAsync("/releases/utc-release");
            detailResponse.EnsureSuccessStatusCode();
            var detailBody = WebUtility.HtmlDecode(await detailResponse.Content.ReadAsStringAsync());

            Assert.Contains("2026年8月24日 03:00", detailBody);
            Assert.Contains("datetime=\"2026-08-24T03:00:00+09:00\"", detailBody);
        }
        finally
        {
            Directory.Delete(temporaryDirectory, recursive: true);
        }
    }

    private static HttpClient CreateClient(WebApplicationFactory<Program> factory) =>
        factory.CreateClient(new WebApplicationFactoryClientOptions
        {
            BaseAddress = new Uri("https://localhost"),
            AllowAutoRedirect = false,
        });

    private static string ExtractSection(string html, string startMarker)
    {
        var start = html.IndexOf(startMarker, StringComparison.Ordinal);
        Assert.True(start >= 0, $"Section marker was not found: {startMarker}");

        var end = html.IndexOf("</section>", start, StringComparison.Ordinal);
        Assert.True(end >= 0, $"Section end was not found for: {startMarker}");
        return html[start..(end + "</section>".Length)];
    }

    private sealed class PublicSiteWebApplicationFactory(string phase, string? releaseNotesPath = null) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.WithIsolatedWebDependencies();
            builder.ConfigureServices(services => services.AddSingleton<IMinecraftStatusProbe, FixedOnlineStatusProbe>());
            builder.ConfigureAppConfiguration((_, configurationBuilder) =>
            {
                configurationBuilder.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["PublicSite:Phase"] = phase,
                    ["ReleaseNotes:ContentRootRelativePath"] = releaseNotesPath
                        ?? Path.Combine(AppContext.BaseDirectory, "release-notes"),
                });
            });
        }
    }
}

internal sealed class FixedOnlineStatusProbe : IMinecraftStatusProbe
{
    public Task<MinecraftServerStatus> QueryAsync(string host, int port, CancellationToken cancellationToken)
        => Task.FromResult(new MinecraftServerStatus("online", 17, 100, DateTimeOffset.UtcNow));
}
