namespace AstralRecordApi.Models;

public class SetEffectResponse
{
    public required string Id { get; init; }

    public required string Name { get; init; }

    public IReadOnlyList<SetEffectPieceResponse> Pieces { get; init; } = [];
}

public class SetEffectPieceResponse
{
    public int Count { get; init; }

    public IReadOnlyList<SetEffectStatResponse> Stats { get; init; } = [];

    public IReadOnlyList<string> Skills { get; init; } = [];
}

public class SetEffectStatResponse
{
    public required string Status { get; init; }

    public required string Type { get; init; }

    public required string Value { get; init; }
}
