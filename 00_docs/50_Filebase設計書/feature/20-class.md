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
- クラスは `usableSkills` で現在クラスにおける使用許可だけを定義し、スキルの習得・レベル・所持個体は変更しません。
- `swordsman` / `hunter` / `mage` は対応する8スキルを許可し、運営検証用 `administrator` は全実装スキルを許可します。
- チャットのプレイヤー情報には `shortName` の3文字短縮名、タブのプレイヤーリストには `name` の正式名を表示します。`shortName` は色コードを除いてちょうど3文字かつクラス間で一意にします。

## order

`order` はクラス一覧の表示順を表す数値です。値が小さいクラスから順に表示し、同値の場合はクラス ID の昇順とします。プレイヤー情報画面の全クラスレベル一覧もこの順序を使用します。

## progression

クラスそのものの解放段階を記載します。初期選択可能なクラスは同じ progression を基準とし、上位・派生クラスは解放条件となるクラスやコンテンツより後に置きます。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\20.features.class\docs.class.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
