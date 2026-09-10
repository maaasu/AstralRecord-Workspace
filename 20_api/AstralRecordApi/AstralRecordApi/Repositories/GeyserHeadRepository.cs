using System.Text.Json;
using AstralRecordApi.Data;
using AstralRecordApi.Models;
using Microsoft.EntityFrameworkCore;

namespace AstralRecordApi.Repositories;

/// <summary>MasterDataDB とユーザーDBから Geyser 用の最小起動データを集約する。</summary>
public sealed class GeyserHeadRepository(
    MasterDataDbContext masterDataDbContext,
    AstralRecordDbContext astralRecordDbContext) : IGeyserHeadRepository
{
    private const string PlayerHead = "PLAYER_HEAD";

    public async Task<GeyserHeadsResponse> GetHeadsAsync(CancellationToken cancellationToken = default)
    {
        var payloads = await masterDataDbContext.Entries
            .AsNoTracking()
            .Where(entry => !entry.IsDeleted
                && (entry.MasterType == "item"
                    || entry.MasterType == "class"
                    || entry.MasterType == "skill"
                    || entry.MasterType == "mob.boss"
                    || entry.MasterType == "mob.enemy"
                    || entry.MasterType == "mob.npc"))
            .OrderBy(entry => entry.MasterType)
            .ThenBy(entry => entry.MasterId)
            .Select(entry => new { entry.MasterType, entry.PayloadJson })
            .ToListAsync(cancellationToken);

        var textures = new SortedSet<string>(StringComparer.Ordinal);
        foreach (var payload in payloads)
            AddTextures(payload.MasterType, payload.PayloadJson, textures);

        var playerUuids = await astralRecordDbContext.Users
            .AsNoTracking()
            .Where(user => !user.IsDeleted)
            .OrderBy(user => user.Uuid)
            .Select(user => user.Uuid)
            .ToListAsync(cancellationToken);

        return new GeyserHeadsResponse
        {
            Textures = textures.ToArray(),
            PlayerUuids = playerUuids,
        };
    }

    private static void AddTextures(string masterType, string payloadJson, ISet<string> textures)
    {
        if (masterType.StartsWith("mob.", StringComparison.Ordinal))
        {
            var mob = MasterDataPayloadJson.Deserialize<MobResponse>(payloadJson);
            if (mob is null)
                return;

            AddTexture(mob.Icon, mob.IconTexture, textures);
            foreach (var level in mob.Levels)
                AddMobLevelTexture(level, mob.Icon, mob.IconTexture, textures);
            return;
        }

        switch (masterType)
        {
            case "item":
                var item = MasterDataPayloadJson.Deserialize<ItemResponse>(payloadJson);
                if (item is not null)
                    AddTexture(item.Icon, item.IconTexture, textures);
                break;
            case "class":
                var cls = MasterDataPayloadJson.Deserialize<ClassResponse>(payloadJson);
                if (cls is not null)
                    AddTexture(cls.Icon, cls.IconTexture, textures);
                break;
            case "skill":
                var skill = MasterDataPayloadJson.Deserialize<SkillResponse>(payloadJson);
                if (skill is not null)
                    AddTexture(skill.Icon, skill.IconTexture, textures);
                break;
        }
    }

    private static void AddMobLevelTexture(
        JsonElement level,
        string? baseIcon,
        string? baseIconTexture,
        ISet<string> textures)
    {
        if (level.ValueKind is not JsonValueKind.Object)
            return;

        var icon = level.TryGetProperty("icon", out var iconValue)
            && iconValue.ValueKind is JsonValueKind.String
            ? iconValue.GetString()
            : baseIcon;
        var iconTexture = level.TryGetProperty("iconTexture", out var textureValue)
            && textureValue.ValueKind is JsonValueKind.String
            ? textureValue.GetString()
            : baseIconTexture;
        AddTexture(icon, iconTexture, textures);
    }

    private static void AddTexture(string? icon, string? iconTexture, ISet<string> textures)
    {
        if (string.Equals(icon, PlayerHead, StringComparison.OrdinalIgnoreCase)
            && GeyserIconTextureValidator.IsValid(iconTexture))
            textures.Add(iconTexture!);
    }
}
