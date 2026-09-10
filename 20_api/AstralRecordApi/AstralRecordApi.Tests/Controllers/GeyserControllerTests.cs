using AstralRecordApi.Controllers;
using AstralRecordApi.Models;
using AstralRecordApi.Repositories;
using Microsoft.AspNetCore.Mvc;
using Xunit;

namespace AstralRecordApi.Tests.Controllers;

public class GeyserControllerTests
{
    /// <summary>
    /// 設計入力: 00_docs/20_API設計書/feature/99-system/3-エンドポイント仕様/99_3.01-取得系.md
    /// 章・見出し: # 99_3.01-取得系 > ### Geyser ヘッド起動データ取得
    /// 検証契約: Geyser 用のテクスチャと未削除ユーザー UUID の集約結果を 200 OK でそのまま返す。
    /// </summary>
    [Fact]
    public async Task GetHeads_ReturnsRepositoryBootstrapPayload()
    {
        var expected = new GeyserHeadsResponse
        {
            Textures = ["base64-texture"],
            PlayerUuids = [Guid.Parse("11111111-1111-1111-1111-111111111111")],
        };
        var controller = new GeyserController(new FakeGeyserHeadRepository(expected));

        var result = await controller.GetHeads(CancellationToken.None);

        var ok = Assert.IsType<OkObjectResult>(result);
        Assert.Same(expected, ok.Value);
    }

    private sealed class FakeGeyserHeadRepository(GeyserHeadsResponse response) : IGeyserHeadRepository
    {
        public Task<GeyserHeadsResponse> GetHeadsAsync(CancellationToken cancellationToken = default)
            => Task.FromResult(response);
    }
}
