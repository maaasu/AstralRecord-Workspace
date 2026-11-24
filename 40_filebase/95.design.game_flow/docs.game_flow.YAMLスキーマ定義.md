# ゲーム進行フロー YAML スキーマ定義

ダンジョン・ショップ・アイテムなどのマスター参照をノードにして、攻略や入手の流れを矢印でつなぐ設計図です。配置先はこのディレクトリ直下の `v1.<id>.yml` です。型と必須項目は [JSON Schema](schemas/game-flow.v1.schema.json) を参照してください。Plugin / API は読み込みません。

| キー | 意味 |
| --- | --- |
| `schemaVersion` | `1` |
| `type` | `GAME_FLOW` |
| `id` | ファイル名に含める一意のID |
| `name` | フローの表示名 |
| `description` | フロー全体の目的や前提（任意） |
| `classes` | このフローで扱う職業の `class:<id>` 一覧（任意）。エディタの職業フィルタの候補。省略時は全職業 |
| `nodes` | ノードの一覧 |
| `nodes[].id` | フロー内で一意のノードID。英小文字・数字・`_` |
| `nodes[].ref` | マスター参照。`config.yml` の `referenceResolver` の接頭辞付きID（例 `dungeon:eriva_windscar_steppe`、`shop:weapon_craftsman_shop`、`item:20a00073`、`mob:eriva_grassboar`）。エディタでは章の詳細図を `game_flow:<id>`、装備・入手経路のビルドを `progression_preset:<id>` でも参照できる |
| `nodes[].role` | 参照なしで進行上の操作を表す場合に `action` を指定する。`ref` と `role` のどちらも省略したノードはメモ |
| `nodes[].label` | 操作・メモの表示名、または参照ありの場合の表示名の上書き。`ref` と `label` のどちらかは必須 |
| `nodes[].note` | 目的・条件・周回数などの補足（任意） |
| `nodes[].classes` | このノードが該当する職業の `class:<id>` 一覧（任意）。省略時は全職業共通 |
| `nodes[].x` / `nodes[].y` | エディタ上の表示位置（整数）。進行の意味は持たない |
| `edges` | 進行の矢印・注釈線の一覧 |
| `edges[].from` / `edges[].to` | 始点・終点のノードID。同じノード同士は不可。同じ始点・終点の矢印は1本だけ |
| `edges[].fromPort` / `edges[].toPort` | 始点・終点の接続面（任意）。`top`、`right`、`bottom`、`left` を指定できる。省略時はエディタが選ぶ |
| `edges[].label` | `ドロップ`、`素材`、`交換`、`開封`、`挑戦` など流れの種類（任意） |
| `edges[].note` | 補足（任意） |
| `edges[].classes` | この矢印が該当する職業の `class:<id>` 一覧（任意）。省略時は全職業共通 |

## 読み方

- 矢印は案内や操作の流れ、マスター間の関係を表します。ショップからアイテムへの矢印は購入や交換、モブから素材への矢印はドロップのように、必要に応じて `label` で種類を示します。
- 片方または両方の端点がメモの接続は、矢じりのない破線の注釈として表示します。接点を共有しない線の直線部が交差するときは、片方に小さな弧を描きます。
- 職業ごとに異なる進行は、該当するノードまたは矢印へ `classes` を付けます。ある職業の流れは、`classes` が未指定のノード・矢印と、その職業を含むノード・矢印で構成されます。矢印は両端のノードが表示される場合だけ有効です。
- 参照先の名前・アイコン・性能は参照先のマスターを正とし、フローには複製しません。`label` は補足が必要な場合だけ使います。
- 1ファイルは1つの進行テーマ（例: 序盤の武器更新、特定ダンジョンの攻略準備）にまとめます。

ノードや矢印を配置する際は、[図の作成ルール](../../00_docs/50_Filebase設計書/feature/95-game_flow.md#図の作成ルール)を参照してください。

## 記載例

```yaml
# design:
#   motif: "進行設計 / エリヴァ武器の入手"
#   progression: 0
schemaVersion: 1
type: GAME_FLOW
id: sample_eriva_weapons
name: "サンプル: エリヴァ武器を揃えて風切りの草原へ"
classes: [class:swordsman, class:hunter, class:mage]
nodes:
  - { id: weapon_craftsman, ref: mob:weapon_craftsman, label: "武器職人（NPC）", x: 600, y: -80 }
  - { id: weapon_shop, ref: shop:weapon_craftsman_shop, x: 600, y: 80 }
  - { id: exchange_conditions, label: "素材とゴールドが必要", x: 600, y: 240 }
  - { id: sword, ref: item:20a00073, classes: [class:swordsman], x: 900, y: -80 }
  - { id: short_bow, ref: item:20a00077, classes: [class:hunter], x: 900, y: 80 }
  - { id: windscar_steppe, ref: dungeon:eriva_windscar_steppe, x: 1200, y: 80 }
edges:
  - { from: weapon_craftsman, to: weapon_shop, fromPort: bottom, toPort: top, label: "店を開く" }
  - { from: exchange_conditions, to: weapon_shop, fromPort: top, toPort: bottom }
  - { from: weapon_shop, to: sword, label: "交換" }
  - { from: weapon_shop, to: short_bow, label: "交換" }
  - { from: sword, to: windscar_steppe, label: "挑戦" }
  - { from: short_bow, to: windscar_steppe, label: "挑戦" }
```

## 検証

マスターデータエディタは保存前に次を確認します。

- 保存先が `95.design.game_flow/v1.<id>.yml` であること。
- ノードIDの重複、存在しないノードを指す矢印、自己ループ、同じ始点・終点の矢印の重複がないこと。
- `nodes[].ref` と `classes` の参照先マスターが存在すること。
