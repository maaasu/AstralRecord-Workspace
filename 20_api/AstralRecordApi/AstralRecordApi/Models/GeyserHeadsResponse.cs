namespace AstralRecordApi.Models;

/// <summary>Geyser Extension 起動時に必要なカスタムヘッドテクスチャと登録プレイヤー UUID の集合。</summary>
public sealed class GeyserHeadsResponse
{
    public IReadOnlyList<string> Textures { get; init; } = [];

    public IReadOnlyList<Guid> PlayerUuids { get; init; } = [];
}
