# NPC 設計

## 役割

NPC は、案内、会話、shop、交換、強化など、プレイヤーへ機能や導線を提供する非戦闘 mob です。

## 設計方針

- NPC ごとに提供機能を明確にし、無関係な機能を集約しすぎません。
- 表示名、役職、会話は機能を推測できる内容にします。
- ワールド固有の人物設定は個別マスタまたは個別ワールド資料で管理し、共通設計へ固定しません。
- 参照する shop、quest、guide などが先に存在することを確認します。

## progression

NPC の機能を標準的に利用可能になる段階を記載します。外見上の登場時期ではなく、提供機能の最速利用段階を基準にします。

## 正本参照

- 共通 YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\mob.YAMLスキーマ定義.md`
- NPC YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\npc\npc.YAMLスキーマ定義.md`
