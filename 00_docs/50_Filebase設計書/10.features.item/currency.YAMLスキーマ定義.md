
# CURRENCY (通貨・証) YAML スキーマ定義

ゲーム内の電子通貨とは別に、アイテムとして管理できる「通貨・証（トークン/通行証/討伐の証など）」を表現します。
システム側で通常アイテムとは別インベントリ（通貨枠）で管理する想定のため、現時点では Currency 固有キーは最小限とし、拡張しやすい粒度で定義します。

## スキーマ定義

| キー                     | 型      | 必須 | デフォルト | 説明                                                                                   |
|:-----------------------|:-------|:--:|:------|:-------------------------------------------------------------------------------------|
| `maxStack`             | Bigint | ×  | 型上限値  | 最大スタック数（未実装/実装依存）。指定する場合は `1` 以上。通常アイテムと異なるスタック上限を持たせたい場合の拡張枠。                       |
| `currency[].type`      | String | ×  | -     | 通貨の種類（運用上の分類キー）。例: `DUNGEON_TOKEN` / `BOSS_PROOF` / `PASS` など。表示や交換所/NPCなどの紐付け用途を想定。 |
| `currency[].group`     | String | ×  | -     | グルーピング用キー。例: `dungeon` / `event_2026_newyear` など。UIのタブ分けやフィルタ用に使用できる。                |
| `currency[].expiresAt` | String | ×  | -     | 失効日時（任意）。フォーマットは運用で統一する（例: ISO-8601 `2026-02-01T00:00:00+09:00`）。イベントトークン等の期限用途。     |

### currency[].type
列挙は固定しません（DB運用で追加されることを想定）。
ただし、同じ概念を別名で増やさないため、以下の指針で命名することを推奨します。

- 英大文字+アンダースコア（例: `DUNGEON_TOKEN`）
- 「入手元/用途」が分かる粒度（例: `EVENT_TOKEN` より `NEWYEAR_EVENT_TOKEN` の方が衝突しにくい）

### 参照（ref）
Currency はアイテムテンプレートの一種です。
他DBから Currency を参照する場合は、アイテム共通の参照規約に従い `item:` prefix を使用します。

- 例: `ref: item:dungeon_medal`

## YAML 例

### 例1: 最小構成（Currency固有キーなし）

```yaml
schemaVersion: 1
id: dungeon_medal
category: CURRENCY
name: "&6ダンジョンメダル"
icon: GOLD_NUGGET
rarity: RARE
lore:
  - "&7過酷なダンジョンを制した証。"
  - "&7特定のNPCと交換できる。"
unTradeable: false
unSellable: true
```

### 例2: 分類キーを付ける（交換所/イベント等の紐付け用途）

```yaml
schemaVersion: 1
id: boss_proof_golem
category: CURRENCY
name: "&dゴーレム討伐の証"
icon: AMETHYST_SHARD
rarity: EPIC
lore:
  - "&7巨像を打ち倒した者に与えられる証。"

currency:
  type: BOSS_PROOF
  group: golem
  expiresAt: 2026-02-01T00:00:00+09:00
```