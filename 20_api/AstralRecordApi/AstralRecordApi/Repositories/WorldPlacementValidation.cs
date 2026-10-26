using AstralRecordApi.Models;
using System.Globalization;
using System.Text.RegularExpressions;

namespace AstralRecordApi.Repositories;

public static class WorldPlacementValidation
{
    public static void Validate(IReadOnlyList<WorldPlacement>? placements)
    {
        if (placements is null || placements.Count > 100_000)
            throw new ArgumentException("Placement list is missing or too large.");
        var ids = new HashSet<string>(StringComparer.Ordinal);
        var waystoneIds = new HashSet<string>(StringComparer.Ordinal);
        foreach (var placement in placements)
        {
            if (placement is null || !ValidId(placement.PlacementId) || !ids.Add(placement.PlacementId)
                || placement.Kind is not ("spawner" or "npc" or "waystone")
                || !ValidWorld(placement.WorldId) || !ValidWorld(placement.WorldName)
                || (placement.Kind is "spawner" or "npc" && !ValidId(placement.TemplateId))
                || (placement.Kind == "waystone" && placement.TemplateId is not null)
                || !double.IsFinite(placement.X) || !double.IsFinite(placement.Y) || !double.IsFinite(placement.Z)
                || !float.IsFinite(placement.Yaw) || !float.IsFinite(placement.Pitch)
                || Math.Abs(placement.X) > 30_000_000 || Math.Abs(placement.Z) > 30_000_000
                || Math.Abs(placement.Y) > 100_000 || Math.Abs(placement.Pitch) > 90)
                throw new ArgumentException("Invalid world placement.");
            if (placement.Kind == "waystone")
            {
                var waystone = placement.Waystone;
                if (waystone is null || !ValidId(waystone.Id) || !waystoneIds.Add(waystone.Id)
                    || string.IsNullOrWhiteSpace(waystone.Name) || waystone.Name.Length > 128
                    || waystone.UnlockGold < 0 || waystone.Icon?.Length > 256
                    || (waystone.CreatedAt is not null && (waystone.CreatedAt.Length > 64
                        || !Regex.IsMatch(waystone.CreatedAt,
                            @"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$")
                        || !DateTimeOffset.TryParse(waystone.CreatedAt, CultureInfo.InvariantCulture,
                            DateTimeStyles.None, out _)))
                    || waystone.CreatedBy?.Length > 128)
                    throw new ArgumentException("Invalid waystone placement.");
            }
            else if (placement.Waystone is not null)
                throw new ArgumentException("Only waystone placements may contain waystone metadata.");
        }
    }

    private static bool ValidId(string? value) => !string.IsNullOrWhiteSpace(value) && value.Length <= 128
        && value.All(c => char.IsLetterOrDigit(c) || c is '_' or '-' or '.' or ':');

    private static bool ValidWorld(string? value) => !string.IsNullOrWhiteSpace(value) && value.Length <= 512
        && value.All(c => !char.IsControl(c));
}
