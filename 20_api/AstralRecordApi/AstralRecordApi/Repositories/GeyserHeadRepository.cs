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
        // categoryなどDB列由来のDTO必須値に依存せず、必要なアイコン情報だけを読む。
        using var document = JsonDocument.Parse(payloadJson);
        var root = document.RootElement;
        if (root.ValueKind != JsonValueKind.Object)
            return;
        var icon = ReadString(root, "icon");
        var texture = ReadString(root, "iconTexture");
        AddTexture(icon, texture, textures);
        if (masterType.StartsWith("mob.", StringComparison.Ordinal)
            && root.TryGetProperty("levels", out var levels) && levels.ValueKind == JsonValueKind.Array)
            foreach (var level in levels.EnumerateArray())
                AddMobLevelTexture(level, icon, texture, textures);
    }

    private static string? ReadString(JsonElement value, string key)
        => value.TryGetProperty(key, out var property) && property.ValueKind == JsonValueKind.String
            ? property.GetString() : null;

    private static void AddMobLevelTexture(
        JsonElement level,
        string? baseIcon,
        string? baseIconTexture,
        ISet<string> textures)
    {
        if (level.ValueKind is not JsonValueKind.Object)
            return;

        var icon = level.TryGetProperty("icon", out _)
            ? ReadString(level, "icon")
            : baseIcon;
        var iconTexture = level.TryGetProperty("iconTexture", out _)
            ? ReadString(level, "iconTexture")
            : baseIconTexture;
        AddTexture(icon, iconTexture, textures);
    }

    private static void AddTexture(string? icon, string? iconTexture, ISet<string> textures)
    {
        var normalizedTexture = iconTexture?.Trim();
        if (string.Equals(icon?.Trim(), PlayerHead, StringComparison.OrdinalIgnoreCase)
            && GeyserIconTextureValidator.IsValid(normalizedTexture))
            textures.Add(normalizedTexture!);
    }
}
