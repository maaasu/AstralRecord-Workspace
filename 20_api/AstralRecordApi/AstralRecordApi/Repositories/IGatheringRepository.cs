using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>採集オブジェクト master data 取得リポジトリです。</summary>
public interface IGatheringRepository
{
    /// <summary>
    /// 採集オブジェクト一覧を取得します。
    /// <paramref name="category"/> が指定された場合は <c>gathering.mining</c> / <c>gathering.harvesting</c>
    /// のいずれかに対応する master_type のみを返します。
    /// </summary>
    IReadOnlyList<GatheringSummaryResponse> GetAllSummaries(string? category = null);

    /// <summary>指定 ID の採集オブジェクトを取得します。存在しない場合は <c>null</c> を返します。</summary>
    GatheringResponse? GetById(string gatheringId);
}
