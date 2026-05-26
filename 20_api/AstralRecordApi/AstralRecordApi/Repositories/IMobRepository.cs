using AstralRecordApi.Models;

namespace AstralRecordApi.Repositories;

/// <summary>Mob マスタ参照リポジトリ。</summary>
public interface IMobRepository
{
    /// <summary>
    /// すべての Mob テンプレートの主要項目を取得する。
    /// <paramref name="category"/> が指定された場合は <c>mob.boss</c> / <c>mob.enemy</c> / <c>mob.npc</c>
    /// のうち該当する master_type のみを返す。
    /// </summary>
    /// <param name="category">フィルタするカテゴリ。<c>null</c> なら全件。</param>
    IReadOnlyList<MobSummaryResponse> GetAllSummaries(string? category = null);

    /// <summary>指定 ID の Mob テンプレートを取得する。未定義なら <c>null</c>。</summary>
    MobResponse? GetById(string mobId);
}
