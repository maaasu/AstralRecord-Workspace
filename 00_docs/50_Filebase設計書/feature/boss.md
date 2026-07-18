# Boss 設計

## 役割

Boss は、準備、戦闘理解、協力、周回などを確認する節目の戦闘対象です。

## 設計方針

- 通常 mob と異なる判断を要求する主要ギミックを定めます。
- 前提となる装備、消耗品、skill の役割を確認できる戦闘にします。
- 高HPだけで難度を作らず、予兆、対処、失敗時の結果を設計します。
- 専用報酬には周回理由を持たせますが、必須装備を極端な低確率だけに依存させません。

## progression

- boss の progression は標準攻略段階です。
- 攻略準備は `P-1` から `P`、標準報酬は `P`、更新報酬は `P+1` を基準にします。

## 正本参照

- 共通 YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\mob.YAMLスキーマ定義.md`
- boss YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\boss\boss.YAMLスキーマ定義.md`
