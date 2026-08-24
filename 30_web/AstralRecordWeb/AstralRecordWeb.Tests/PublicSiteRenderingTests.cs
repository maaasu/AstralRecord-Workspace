using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.Configuration;
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
        var join = ExtractSection(body, "<section class=\"ar-home-section ar-join-section\" id=\"join\"");

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

        var listResponse = await client.GetAsync("/releases");
        listResponse.EnsureSuccessStatusCode();
        var listBody = await listResponse.Content.ReadAsStringAsync();

        Assert.Contains("リリースノート公開機能を導入しました", listBody);
        Assert.Contains("0.1.0", listBody);
        Assert.Contains("/releases/release-management", listBody);

        var detailResponse = await client.GetAsync("/releases/release-management");
        detailResponse.EnsureSuccessStatusCode();
        var detailBody = await detailResponse.Content.ReadAsStringAsync();

        Assert.Contains("Markdownで管理するリリースノート", detailBody);
        Assert.Contains("通知に失敗した場合は、APIがOutboxへ保持して再試行します", detailBody);
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

    private sealed class PublicSiteWebApplicationFactory(string phase) : WebApplicationFactory<Program>
    {
        protected override void ConfigureWebHost(IWebHostBuilder builder)
        {
            builder.ConfigureAppConfiguration((_, configurationBuilder) =>
            {
                configurationBuilder.AddInMemoryCollection(new Dictionary<string, string?>
                {
                    ["PublicSite:Phase"] = phase,
                });
            });
        }
    }
}
