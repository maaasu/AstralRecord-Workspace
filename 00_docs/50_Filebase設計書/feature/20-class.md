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
- `swordsman` / `hunter` / `mage` は、冒険者から引き継ぐ初期攻撃 skill だけを `usableSkills` に定義する。ソードマンは `adventurer_astral_edge` / `adventurer_smash`、ハンターは `adventurer_blast_arrow` / `adventurer_quick_shot`、メイジは `adventurer_mana_burst` / `adventurer_lightning_bolt` とする。
- 上記以外の skill は class の `usableSkills` に追加せず、skilltree の `skill` 効果で使用許可を付与する。運営検証用 `administrator` は実装済みスキルだけを許可する。
- クラスの短縮表示には `shortName`、タブのプレイヤーリストには `name` の正式名を使用します。`shortName` の形式は、下記の `shortName` 定義に従います。

## shortName

`shortName` はクラスの短縮表示名として保持する項目です。色・装飾コードと前後空白を除いた表示文字を、ASCII の英大文字 `A-Z` だけで構成したちょうど3文字（正規表現 `^[A-Z]{3}$`）にします。クラス間で同じ短縮名を定義してはいけません。タブのプレイヤーリストでは `shortName` ではなく正式な `name` を表示します。

## status の成長

`baseStats` は、StatusService の共有基礎値へ加算する現在クラスの初期補正です。`growthPerLevel` はクラスレベルが1上がるごとの追加補正です。現在クラスのステータス補正は、次の式で求めます。

```text
class status bonus = baseStats + growthPerLevel × (classLevel - 1)
```

クラス補正は `StatusType` ごとに加算し、未定義のステータスは `0.0` とします。これはクラスレベル1の `baseStats` を含む補正値であり、共有基礎値をクラス値で置き換えません。

## order

`order` はクラス一覧の表示順を表す数値です。値が小さいクラスから順に表示し、同値の場合はクラス ID の昇順とします。プレイヤー情報画面の全クラスレベル一覧もこの順序を使用します。

## progression

クラスそのものの解放段階を記載します。初期選択可能なクラスは同じ progression を基準とし、上位・派生クラスは解放条件となるクラスやコンテンツより後に置きます。

## 正本参照

- YAML: `E:\AstralRecord-Workspace\40_filebase\20.features.class\docs.class.YAMLスキーマ定義.md`
- status: `E:\AstralRecord-Workspace\40_filebase\75.shared.status\v1.status_types.yml`
