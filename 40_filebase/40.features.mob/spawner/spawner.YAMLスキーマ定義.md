# Mob Spawner YAML スキーマ定義

Mob スポナーの静的マスタ定義です。

この定義にはスポーン座標を含めません。スポーン座標はプラグイン実行環境のデータフォルダ配下 `mob_spawners.yml` に、スポナー ID とブロック座標の組として保存します。

---

## スキーマ定義

| キー | 型 | 必須 | デフォルト | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | - | スキーマバージョン |
| `id` | String | ○ | - | スポナー ID |
| `type` | String | × | `MOB_SPAWNER` | マスタ種別 |
| `radiusMeters` | Double | ○ | - | 登録座標から半径何 m をスポーン範囲にするか |
| `spawnMobs[]` | List | ○ | - | スポーン対象 Mob と抽選重み |
| `spawnMobs[].mobId` | String | ○ | - | スポーン対象 Mob ID（`mob:` 参照） |
| `spawnMobs[].weight` | Integer | ○ | - | 抽選重み。全 weight の比率で抽選します |
| `spawnTimes[]` | List | × | 終日 | スポーン可能な Minecraft ワールド時間帯 |
| `spawnTimes[].startTick` | Long | ○ | - | 開始 tick（0-23999） |
| `spawnTimes[].endTick` | Long | ○ | - | 終了 tick（0-23999）。開始より小さい場合は日跨ぎ |
| `itemMaterial` | String | ○ | - | スポナーアイテムの見た目 Bukkit Material。ブロック Material を指定します |
| `spawnIntervalTicks` | Long | × | `100` | スポーン判定間隔 |
| `spawnLimit.maxAlivePerSpawner` | Integer | × | `8` | このスポナー由来の同時存在上限 |
| `spawnLimit.maxNearbyMobs` | Integer | × | `18` | 他スポナー由来を含む周辺 Mob 上限 |
| `spawnLimit.spawnPerPlayer` | Integer | × | `1` | 範囲内プレイヤー 1 人あたりの目標スポーン数。最大 6 人分まで加算 |

---

## YAML 例

```yaml
schemaVersion: 1
id: goblin_training_spawner
type: MOB_SPAWNER
radiusMeters: 18

spawnMobs:
  - mobId: mob:goblin_warrior
    weight: 80
  - mobId: mob:ai_nearest_wander
    weight: 20

spawnTimes:
  - startTick: 0
    endTick: 23999

itemMaterial: SPAWNER
spawnIntervalTicks: 100

spawnLimit:
  maxAlivePerSpawner: 8
  maxNearbyMobs: 18
  spawnPerPlayer: 1
```
