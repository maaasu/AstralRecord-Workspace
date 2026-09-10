using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>Geyser Extension の起動時データを集約して取得するリポジトリ。</summary>
public interface IGeyserHeadRepository
{
    Task<GeyserHeadsResponse> GetHeadsAsync(CancellationToken cancellationToken = default);
}
