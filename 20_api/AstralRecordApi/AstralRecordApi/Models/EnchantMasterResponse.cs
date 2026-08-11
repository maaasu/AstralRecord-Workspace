using System.ComponentModel.DataAnnotations;

namespace AstralRecordApi.Models;

public class EnchantMasterResponse
{
    public int SchemaVersion { get; init; }

    public required string Id { get; init; }

    public IReadOnlyList<EnchantTargetResponse> Targets { get; init; } = [];
}

public class EnchantTargetResponse
{
    public required string EquipmentType { get; init; }

    public IReadOnlyList<EnchantEntryResponse> Entries { get; init; } = [];
}

public class EnchantEntryResponse
{
    public required string EffectId { get; init; }

    public required string Status { get; init; }

    public required string Type { get; init; }

    public required string Value { get; init; }

    [Range(1, int.MaxValue)]
    public int Weight { get; init; } = 1;
}
