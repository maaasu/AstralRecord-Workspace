namespace SkillTreeEditor.Server.Models;

/// <summary>ショップの商品。<paramref name="Items"/> は接頭辞なしのアイテムIDを掲載順に重複なく並べる。</summary>
public sealed record GameFlowShop(string Id, string Path, IReadOnlyList<string> Items);

/// <summary>ゲーム進行フローの編集補助。<paramref name="ItemClasses"/> は装備の requiredClasses（クラスID）。</summary>
public sealed record GameFlowContext(IReadOnlyList<GameFlowShop> Shops, IReadOnlyDictionary<string, IReadOnlyList<string>> ItemClasses);
