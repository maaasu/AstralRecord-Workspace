# Class 設計

## 役割

Class は、プレイヤーの戦闘上の役割、成長傾向、利用できる skill と装備傾向をまとめるテンプレートです。

## 設計方針

- クラスごとに主な戦闘距離、攻撃方法、生存方法、リソース運用を定めます。
- 主に伸ばすステータスと、弱点として残すステータスを明確にします。
- class 単体で全役割を満たさず、equipment と skill に選択余地を残します。
- 使用可能なステータス一覧は複製せず `StatusType.kt` を参照します。
- 初期職は `adventurer`、通常の一次派生職は `swordsman` / `hunter` / `mage` とします。3 職はいずれも `adventurer` Lv.10 を転職条件とし、ここからさらに枝分かれできる構造にします。
- `acolyte` は現行クラス定義に含めません。
- 現行クラスマスタには初期習得・レベル習得スキルを定義しません。成長値と転職条件は各クラス YAML を正本とします。

## progression

クラスそのものの解放段階を記載します。初期選択可能なクラスは同じ progression を基準とし、上位・派生クラスは解放条件となるクラスやコンテンツより後に置きます。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\20.features.class\docs.class.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\10_plugin\AstralRecord\src\main\java\io\github\maaasu\astralRecord\feature\status\model\StatusType.kt`
