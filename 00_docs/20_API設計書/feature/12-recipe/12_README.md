# 12_README

このディレクトリは API `recipe` 機能の設計書です。  
採番・命名・参照ルールは [[README]] に従います。

YAML マスタ読み取り専用 API。プラグイン側の対応 feature は `04-item` 配下扱い。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/RecipeController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/RecipeResponse.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IRecipeRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/RecipeRepository.cs`

## マスタデータの所在

- `50_filebase/85.shared.recipe/`

## ドキュメント一覧

1. [[12_0.00-概要]]
2. [[12_1.00-モデル定義]]
3. [[12_3.00-索引]]
4. [[12_5.00-例外・ログ・運用]]
