# Enemy 設計

## 役割

Enemy は、通常の探索・移動・採集に戦闘上の判断と報酬を与える戦闘 mob です。

## 設計方針

- 戦闘距離、攻撃方法、耐久、生存行動のうち主役を明確にします。
- targeting、combat style、skill、drop を同じ役割へ揃えます。
- 同じ progression では役割の異なる enemy を用意し、数値差だけで種類を増やしません。
- 通常 drop と loot table の責務を重複させません。

## progression

標準的に遭遇し対処できる段階を記載します。通常 drop は同値、更新報酬は同値から `+1` を基準にします。

## 正本参照

- 共通 YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\docs.mob.YAMLスキーマ定義.md`
- enemy YAML: `E:\AstralRecord-Workspace\40_filebase\40.features.mob\enemy\docs.enemy.YAMLスキーマ定義.md`
