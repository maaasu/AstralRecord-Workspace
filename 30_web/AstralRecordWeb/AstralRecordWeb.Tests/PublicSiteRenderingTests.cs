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
    public async Task Home_OpenAlpha_ShowsOneParticipationNoticeAndOneCopyAction()
    {
        await using var factory = new PublicSiteWebApplicationFactory("OpenAlpha");
        using var client = CreateClient(factory);

        var response = await client.GetAsync("/");
        response.EnsureSuccessStatusCode();
        var body = await response.Content.ReadAsStringAsync();
        var hero = ExtractSection(body, "<section class=\"ar-home-hero\"");
        var discord = ExtractSection(body, "<section class=\"ar-discord-section\" id=\"discord\"");
        var join = ExtractSection(body, "<section class=\"ar-home-section ar-join-section\" id=\"join\"");

        var cssResponse = await client.GetAsync("/css/site.css");
        cssResponse.EnsureSuccessStatusCode();
        var css = await cssResponse.Content.ReadAsStringAsync();

        Assert.Contains("data-public-phase=\"open-alpha\"", body);
        Assert.Contains("href=\"#join\"", hero);
        Assert.Contains("今すぐ参加する", hero);
        Assert.DoesNotContain("mc.astralrecord.com", hero);
        Assert.DoesNotContain("data-copy-server", hero);
        Assert.DoesNotContain("ar-quick-connect", body);
        Assert.DoesNotContain("<noscript", body);

        Assert.Single(Regex.Matches(body, "class=\"ar-alpha-notice\"").Cast<Match>());
        Assert.Single(Regex.Matches(body, @"data-copy-server(?:\s|>)").Cast<Match>());
        Assert.Contains("mc.astralrecord.com", join);
        Assert.Contains("急な再起動や停止が頻繁に発生", join);
        Assert.Contains("個別の復旧や補償の対象外", join);
        Assert.Contains("正式リリース時に引き継がれません", join);
        Assert.Contains("予告なく変更されることがあります", join);
        Assert.Contains("Minecraft Bedrock Edition", join);
        Assert.Contains("対応中", join);
        Assert.Contains("統合版にも対応しています", join);
        Assert.Contains("IPアドレスはJava版と同じ", join);
        Assert.Contains(
            "IPアドレスはJava版と同じ <strong>mc.astralrecord.com</strong>、ポートは <strong>19132</strong>",
            join);
        Assert.Contains("Java版でのプレイを前提としているため", join);
        Assert.Contains("公式Discordの改善案チャンネル", join);
        Assert.Contains("修正される可能性があります", join);
        Assert.DoesNotContain("discord.gg", join, StringComparison.OrdinalIgnoreCase);

        Assert.Contains("冒険の続きは、公式Discordで。", discord);
        Assert.Contains("公式Discordに参加する", discord);
        Assert.Contains("https://discord.gg/Fja6zmpjGX", discord);
        Assert.Matches(
            "<a[^>]*class=\"nav-link ar-nav-link ar-nav-discord\"[^>]*href=\"https://discord\\.gg/Fja6zmpjGX\"[^>]*>公式Discord</a>",
            body);

        foreach (var featureImage in new[] { "combat", "adventure", "community" })
        {
            Assert.Contains($"/images/feature-{featureImage}-800.webp", css);
            Assert.Contains($"/images/feature-{featureImage}-1600.webp", css);
        }

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
        Assert.DoesNotContain("ar-alpha-notice", body);
        Assert.DoesNotContain("正式リリース時に引き継がれません", body);
        Assert.Single(Regex.Matches(body, @"data-copy-server(?:\s|>)").Cast<Match>());
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
        Assert.Contains("自動で連携されることもありません", privacy);
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
