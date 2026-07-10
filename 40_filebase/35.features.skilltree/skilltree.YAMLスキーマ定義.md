# SkillTree YAML スキーマ定義

`40_filebase/35.features.skilltree/*.yml` に配置するスキルツリーノード定義の schema です。
ノードごとに次の 2 系統の効果を持てます。

- `skillIds`: ノード解放時にプレイヤーが恒常的に得るスキル
- `statuses`: ノード解放時に永続プレイヤーへ付与されるステータス

進行データの責務:

- プレイヤー単位の動的進行状態（解放済み `nodeId` 一覧）は plugin ローカル YAML には保持せず、`account-skilltree` API と AstralRecord DB を正本とする。CP/PP はクラスレベル・プレイヤーレベルから算出する
- `positionId` の参照先である `skilltree_structure.yml` は `skill_tree` ワールド上の座標配置を表す plugin データファイルであり、サーバローカルで管理する
- 現行 plugin は `skilltree_structure.yml` の `edges[]` を参照し、`root` tag を起点に隣接ノードを解放する単一連結ツリーとして扱う
- `skilltree_structure.yml` が plugin data folder に存在しない場合は、plugin jar に同梱した序盤用の既定レイアウトを初回起動時に展開する

`skilltree_structure.yml` の契約:

- 配置場所: plugin data folder 直下の `skilltree_structure.yml`
- 初回配布元: `10_plugin/AstralRecord/src/main/resources/skilltree_structure.yml`
- 管理対象: `positions[]` と `edges[]`
- `positions[]`: `{ id, world, x, y, z }`
- `edges[]`: `{ left, right }`
- 用途: filebase ノード定義の `positionId` と、Purpur サーバ上の `skill_tree` ワールド座標を結び付ける
- 非対象: プレイヤーごとの解放状態、所持CP/PP、監査履歴

仕様を簡潔に先に整理すると次のようになります。

## スキーマ定義

| キー | 型 | 必須 | 既定値 | 説明 |
| --- | --- | --- | --- | --- |
| `schemaVersion` | Integer | 必須 | - | 現在は `1` |
| `id` | String / Integer | 必須 | - | ノードID。数値 scalar でもよい |
| `positionId` | String / Integer | 必須 | - | `skilltree_structure.yml` の position ID。数値 scalar でもよい |
| `name` | String | 必須 | - | 表示名 |
| `icon` | String | 任意 | `NETHER_STAR` | Material 名 |
| `lore` | List<String> | 任意 | `[]` | ノード説明 |
| `tags` | List<String> | 任意 | `[]` | 任意タグ |
| `pointType` | String | 任意 | `PP` | 解放に消費するポイント種別。`CP` / `CLASS_POINT` はクラスポイント、`PP` / `PASSIVE_POINT` はパッシブポイント |
| `pointCost` | Integer | 任意 | root tag は `0`、それ以外は `1` | 解放に必要なポイント数。0 以上 |
| `skillIds` | List<String> | 任意 | `[]` | 解放時に得るスキル ID 一覧 |
| `statuses` | List<Map> | 任意 | `[]` | 解放時に付与するステータス一覧 |
| `statuses[].status` | String | 必須 | - | `StatusType` 名 |
| `statuses[].type` | String | 任意 | `FLAT` | `FLAT` または `SCALAR` |
| `statuses[].value` | Number | 必須 | - | 付与値。`SCALAR` の場合は `0.10 = 10%` |

## 補足

- `positionId` は filebase node 定義と、サーバローカル `skilltree_structure.yml` の位置定義をつなぐキーである
- `id` / `positionId` / `edges[].left` / `edges[].right` は YAML 上で数値として書いてよく、plugin 側では文字列化して扱う
- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: false` の場合、ノード解放だけで恒常効果になる
- `skillIds` に含まれるスキルがパッシブで `passive.bindRequired: true` の場合、ノード解放後にパッシブスロットへ設定された時だけ効果になる
- `statuses` はパッシブスキルを実装せず、ノード解放状態から直接プレイヤーステータスへ反映される
- `statuses.type` は schema 上の受理値で、実装時は `FLAT` として扱います
- `pointType` / `pointCost` を変更した結果、既存の解放済みノードの必要ポイントが現在算出できる所持CP/PPを超えた場合、plugin は解放済みノード ID の数値が高い順に「解放済みだが効果停止中」として扱い、スキル・ステータス反映から除外します。

## YAML 例

### スキル付与ノード

```yaml
schemaVersion: 1
id: 1001
positionId: 1001
name: "&c&l始まりの力"
icon: IRON_SWORD
lore:
  - "&7スキルツリーの最初のノード。"
skillIds:
  - iron_will
tags:
  - starter
```

### ステータス付与ノード

```yaml
schemaVersion: 1
id: 1002
positionId: 1002
name: "&d生命強化"
icon: AMETHYST_SHARD
statuses:
  - status: MAX_HEALTH
    type: FLAT
    value: 10
  - status: DEFENSE
    type: SCALAR
    value: 0.05
tags:
  - starter
  - status
```

### 混合ノード

```yaml
schemaVersion: 1
id: 1101
positionId: 1101
name: "&b守りの構え"
icon: SHIELD
skillIds:
  - iron_will
statuses:
  - status: MAX_HEALTH
    type: FLAT
    value: 15
tags:
  - hybrid
```

## 表示ルール

- `statuses` のみを持つ基礎ステータス上昇ノードは、名前色を `&d` で統一し、`root` ノードを除くアイコンは `AMETHYST_SHARD` を基本とする。
- `skillIds` を持つパッシブスキルノードは、系統タグに応じた色付き太字の名前を使用する。
- `melee` 系パッシブは `&c&l`、`ranged` 系は `&a&l`、`magic` 系は `&b&l`、`support` 系は `&e&l` を基準にする。
- ノード座標に対応するワールド上のブロックには、視認性確保のため `LIGHT` ブロック `level=15` を設置する。
- `ADMIN` account mode では、移動に応じて `skilltree_structure.yml` の position marker と接続ラインを再同期し、表示距離内のノード配置確認に使う。
- ワールド上のノード名は、解放済みのみ `name` に定義した色を使い、未解放は色コードを外した灰色表示を基本とする。
- ワールド上のノード接続ラインは、未解放同士を灰色、片側のみ解放を黄色、両側解放を緑色で表示する。
