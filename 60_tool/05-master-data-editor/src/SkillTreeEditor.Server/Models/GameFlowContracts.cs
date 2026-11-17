namespace SkillTreeEditor.Server.Models;

/// <summary>ショップの商品。<paramref name="Items"/> は接頭辞なしのアイテムIDを掲載順に重複なく並べる。</summary>
public sealed record GameFlowShop(string Id, string Path, IReadOnlyList<string> Items);

/// <summary>
/// マスター同士の進行上のつながり。<paramref name="From"/> / <paramref name="To"/> は `kind:id`
/// （kind は config.yml の正規の参照接頭辞）で、フロー図の矢印と同じ向き。
/// </summary>
public sealed record GameFlowLink(string From, string To, string Label);

/// <summary>ゲーム進行フローの編集補助。<paramref name="ItemClasses"/> は装備の requiredClasses（クラスID）。</summary>
public sealed record GameFlowContext(IReadOnlyList<GameFlowShop> Shops, IReadOnlyDictionary<string, IReadOnlyList<string>> ItemClasses,
    IReadOnlyList<GameFlowLink> Links);
