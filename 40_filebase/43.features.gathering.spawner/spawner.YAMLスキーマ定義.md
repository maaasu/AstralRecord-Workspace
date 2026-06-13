# Gathering Spawner YAML スキーマ定義

採集オブジェクトのスポナー定義です。
基本構造は `41.features.mob.spawner` を踏襲しつつ、スポーン対象を採集オブジェクトに置き換え、スポーン可能な地面ブロック条件を追加します。

この定義にはスポーン座標を含めません。
座標自体はプラグイン実行環境のデータフォルダ側で管理する想定です。

## スキーマ

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | - | スキーマバージョン |
| `id` | String | ○ | - | スポナー ID |
| `type` | String | ○ | `GATHERING_SPAWNER` | スポナー種別 |
| `radiusMeters` | Double | ○ | - | スポーン候補半径 |
| `spawnGatherings[]` | List | ○ | - | スポーン対象採集オブジェクト候補 |
| `spawnGatherings[].gatheringId` | String | ○ | - | 採集オブジェクト ID。`gathering:` prefix を使用 |
| `spawnGatherings[].weight` | Integer | ○ | - | 抽選重み |
| `spawnTimes[]` | List | ＊ | 終日 | スポーン可能な Minecraft ワールド時刻帯 |
| `spawnTimes[].startTick` | Long | ○ | - | 開始 tick (`0-23999`) |
| `spawnTimes[].endTick` | Long | ○ | - | 終了 tick (`0-23999`) |
| `itemMaterial` | String | ○ | - | スポナー自体を表す Bukkit Material |
| `spawnIntervalTicks` | Long | ＊ | `100` | 再スポーン試行間隔 |
| `spawnLimit.maxAlivePerSpawner` | Integer | ＊ | `8` | このスポナーから同時に存在できる採集オブジェクト数 |
| `spawnLimit.maxNearbyGatherings` | Integer | ＊ | `18` | 周辺に存在できる採集オブジェクト上限 |
| `spawnLimit.spawnPerPlayer` | Integer | ＊ | `1` | 周辺プレイヤー 1 人あたりに追加できる数 |
| `requiredBaseBlocks[]` | List<String> | ＊ | emptyList | このブロックの上にのみスポーン可能な地面条件 |

＊: 省略可能

## requiredBaseBlocks

- 未指定または空配列の場合: 半径内の候補座標からランダムにスポーンする
- 1 件以上指定した場合: 指定ブロックの上だけを有効候補とする
- 値は Bukkit Material 名を使用する

例:

- `STONE`
- `DEEPSLATE`
- `GRASS_BLOCK`
- `DIRT`

## YAML 例

```yaml
schemaVersion: 1
id: iron_ore_field
type: GATHERING_SPAWNER
radiusMeters: 18

spawnGatherings:
  - gatheringId: gathering:iron_ore_vein
    weight: 100

spawnTimes:
  - startTick: 0
    endTick: 23999

itemMaterial: SPAWNER
spawnIntervalTicks: 100

spawnLimit:
  maxAlivePerSpawner: 12
  maxNearbyGatherings: 24
  spawnPerPlayer: 2

requiredBaseBlocks:
  - STONE
  - DEEPSLATE
```
