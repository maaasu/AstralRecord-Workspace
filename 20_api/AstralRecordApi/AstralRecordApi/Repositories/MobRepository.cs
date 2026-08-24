using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;
using System.Text.Json;

namespace AstralRecordApi.Repositories;

/// <summary>
/// MasterDataDB の <c>master_data_entry</c>（<c>master_type IN ('mob.boss', 'mob.enemy', 'mob.npc')</c>）から
/// Mob マスタを取得する。
/// </summary>
public class MobRepository(MasterDataDbContext dbContext) : IMobRepository
{
    private const string MasterTypeBoss = "mob.boss";
    private const string MasterTypeEnemy = "mob.enemy";
    private const string MasterTypeNpc = "mob.npc";

    private static readonly IReadOnlyList<string> AllMasterTypes =
        [MasterTypeBoss, MasterTypeEnemy, MasterTypeNpc];

    public IReadOnlyList<MobSummaryResponse> GetAllSummaries(string? category = null)
    {
        var masterTypes = ResolveMasterTypes(category);

        var payloads = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted && masterTypes.Contains(entry.MasterType))
            .OrderBy(entry => entry.MasterType)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => entry.PayloadJson)
            .ToArray();

        return payloads
            .Select(MasterDataPayloadJson.Deserialize<MobResponse>)
            .Where(mob => mob is not null)
            .Select(mob => new MobSummaryResponse
            {
                Id = mob!.Id,
                Category = mob.Category,
                Name = mob.Name,
                Level = ResolveDefaultLevel(mob),
                EntityType = mob.EntityType,
                Icon = mob.Icon,
                Tags = mob.Tags,
            })
            .ToArray();
    }

    private static int ResolveDefaultLevel(MobResponse mob)
    {
        var fallback = Math.Max(1, mob.Level);
        return (mob.Levels ?? [])
            .Select(level => level.TryGetProperty("level", out var value) && value.TryGetInt32(out var number)
                ? number
                : int.MaxValue)
            .Where(level => level > 0 && level < int.MaxValue)
            .DefaultIfEmpty(fallback)
            .Min();
    }

    public MobResponse? GetById(string mobId)
    {
        var payload = dbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && AllMasterTypes.Contains(entry.MasterType)
                && entry.MasterId == mobId)
            .Select(entry => entry.PayloadJson)
            .FirstOrDefault();

        return payload is null
            ? null
            : MasterDataPayloadJson.Deserialize<MobResponse>(payload);
    }

    /// <summary>
    /// <paramref name="category"/> から検索対象 master_type のリストを決定する。
    /// 不正値は <see cref="ArgumentException"/> を送出する（呼び出し元で 400 を返すこと）。
    /// </summary>
    private static IReadOnlyList<string> ResolveMasterTypes(string? category)
    {
        if (string.IsNullOrWhiteSpace(category))
            return AllMasterTypes;

        return category.Trim().ToUpperInvariant() switch
        {
            "BOSS" => [MasterTypeBoss],
            "ENEMY" => [MasterTypeEnemy],
            "NPC" => [MasterTypeNpc],
            _ => throw new ArgumentException(
                $"Unsupported category: {category}. Expected one of BOSS, ENEMY, NPC.",
                nameof(category)),
        };
    }
}
