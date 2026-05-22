# アイテム共通 YAML スキーマ定義

ITEMの基本的なスキーマ定義。

## スキーマ定義

| キー                | 型            | 必須 | デフォルト     | 説明                                                     |
|:------------------|:-------------|:--:|-----------|:-------------------------------------------------------|
| `schemaVersion`   | Integer      | ○  | -         | スキーマのバージョン（2026-01-17時点は `1`）                          |
| `id`              | String       | ○  | -         | テンプレートID。（例: `iron_ingot`）※大文字小文字の区別あり。カテゴリ関係なく、同id禁止。 |
| `category`        | String       | ○  | -         | カテゴリを入力。ファイルが適切なフォルダに配置されているかの確認                       |
| `name`            | String       | ○  | -         | ゲーム内に表示される名前                                           |
| `icon`            | String       | ○  | -         | Bukkit Material名（例: `IRON_INGOT`）                      |
| `rarity`          | String       | ○  | -         | rarityヘッダ参照                                            |
| `saleValue`       | Integer      | ×  | 0         | 売却した際に得られるお金                                           |
| `customModelData` | Integer      | ×  | Null      | クライアント側リソースパック用のモデルデータID (未実装予定)                       |
| `lore`            | List<String> | ×  | emptyList | アイテムの説明文（§または、&を使用した色コード利用可能）                          |
| `unTradeable`     | Boolean      | ×  | false     | trueでトレード不可                                            |
| `unSellable`      | Boolean      | ×  | false     | trueで売却不可                                              |

#### rarity
以下のいずれかの値を指定します。
- COMMON (C, c, 0)
- UNCOMMON (U, u, 1)
- RARE (R, r, 2)
- EPIC (E, e, 3)
- LEGENDARY (L, l, 4)
- MYTHIC (M, m, 5)


## YAML 例

```yaml
schemaVersion: 1
id: magic_iron_ingot
name: &b魔法の鉄鉱石
icon: IRON_INGOT
rarity: UNCOMMON
customModelData: 10001
lore:
  - &7魔力を帯びた珍しい鉄。
  - &7武器の強化に使用できる。
unTradeable: false
unSellable: false
```