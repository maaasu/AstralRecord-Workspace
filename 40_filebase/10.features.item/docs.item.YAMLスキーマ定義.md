# アイテム共通 YAML スキーマ定義

ITEMの基本的なスキーマ定義。

## スキーマ定義

| キー                | 型            | 必須 | デフォルト     | 説明                                                     |
|:------------------|:-------------|:--:|-----------|:-------------------------------------------------------|
| `schemaVersion`   | Integer      | ○  | -         | スキーマのバージョン（2026-01-17時点は `1`）                          |
| `id`              | String       | ○  | -         | カテゴリ番号2桁 + 英字1桁 + 連番5桁のID（例: `10a00001`）。カテゴリ関係なく同id禁止。 |
| `category`        | String       | ○  | -         | 論理カテゴリを入力。採番済みカテゴリフォルダとの一致を確認する                       |
| `name`            | String       | ○  | -         | ゲーム内に表示される名前                                           |
| `icon`            | String       | ○  | -         | Bukkit Material名（例: `IRON_INGOT`）                      |
| `rarity`          | String       | ○  | -         | rarityヘッダ参照                                            |
| `saleValue`       | Integer      | ×  | 0         | 売却した際に得られるお金                                           |
| `customModelData` | Integer      | ×  | Null      | クライアント側リソースパック用のモデルデータID (未実装予定)                       |
| `appearance`      | Map          | ×  | Null      | 同じ `icon` Material 内のバニラ外見差分を固定する設定。革装備色、ポーション色/種別などに使用する |
| `lore`            | List<String> | ×  | emptyList | アイテムの説明文（§または、&を使用した色コード利用可能）                          |
| `unTradeable`     | Boolean      | ×  | false     | trueでトレード不可                                            |
| `unSellable`      | Boolean      | ×  | false     | trueで売却不可                                              |

## ID・ファイル名・カテゴリフォルダ

アイテムIDは、管理画面・マスタ一覧でカテゴリと入手順を識別できる固定長形式とします。

```text
{カテゴリ番号2桁}{英字1桁}{連番5桁}
```

- 英字は `a` から始め、連番 `99999` 到達後に `b`、`c` と繰り上げます。連番は `00001` から `99999` です。
- `z` はデバッグアイテム専用です。通常アイテムの繰り上げでは `z` を使用しません。
- カテゴリ番号は `10=material`、`20=equipment`、`30=consumable`、`40=orb`、`50=bundle`、`60=rune`、`70=sigil`、`99=currency` です。
- カテゴリフォルダ名は `10.material`、`20.equipment` のように番号と論理カテゴリ名を連結します。YAMLの `category` には論理カテゴリ名だけを記載します。
- ファイル名は `v<schemaVersion>.<id>.<管理用slug>.yml` とし、例は `v1.20a00002.nox_sword.yml` です。slugは人間が旧来の名称を検索するための補助名で、正本IDはYAMLの `id` です。
- ゲーム内進行に依存しないPlugin組み込み通貨は `99a00001` 以降を予約します。

既存マスタの新規IDは、同一カテゴリ内で `progression` の昇順を基本とし、同値の場合は入手用途が近いものをまとめて採番します。

`sigil` カテゴリは習得済みスキル個体へ消費装着する専用アイテムです。

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `sigil.equipGroupId` | String | ○ | - | 1～128文字。同じスキル個体へ重複装着できないグループ ID。シジル ID とは独立して定義する |
| `sigil.modifiers[]` | List | × | `[]` | 対象スキルの計算中だけ加算するステータス補正 |
| `sigil.modifiers[].status` | String | ○ | - | 共有ステータス ID |
| `sigil.modifiers[].value` | Double | ○ | - | 加算値。同一ステータスは装着シジル間で合算する |

ロジック変更型シジルは `modifiers` を空にし、シジルのアイテム ID を個別スキル executor の `hasSigil` 判定と一致させます。取り外し・汎用優先順位・矛盾解決は定義しません。

シジルのitem IDはインベントリ列へ格納できる100文字以内とします。`modifiers[].status` は共有ステータスカタログに存在するID、`value`は有限値でなければなりません。

#### rarity
以下のいずれかの値を指定します。
- COMMON (C, c, 0)
- UNCOMMON (U, u, 1)
- RARE (R, r, 2)
- EPIC (E, e, 3)
- LEGENDARY (L, l, 0)
- MYTHIC (M, m, 0)

#### appearance

`appearance` は Bukkit `Material` だけでは表せないバニラの細分化を指定します。未対応の Material に指定されたキーは無視されます。

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `appearance.color` | String | × | Null | 色指定。`#RRGGBB` または `R,G,B`。`LEATHER_*` 装備と `POTION` / `SPLASH_POTION` / `LINGERING_POTION` / `TIPPED_ARROW` の表示色に使用する |
| `appearance.potionType` | String | × | Null | Bukkit `PotionType` 名。`HEALING`, `STRONG_HEALING`, `SWIFTNESS` など。PotionMeta を持つ Material に使用する |


## YAML 例

```yaml
schemaVersion: 1
id: 10a00001
category: material
name: &b魔法の鉄鉱石
icon: IRON_INGOT
rarity: UNCOMMON
customModelData: 10001
appearance:
  color: "#7A5A3A"
lore:
  - &7魔力を帯びた珍しい鉄。
  - &7武器の強化に使用できる。
unTradeable: false
unSellable: false
```
