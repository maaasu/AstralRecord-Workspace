# Dungeon YAML スキーマ定義

開始要求ごとに BSP で一時ワールドへ生成する、1階層ダンジョンの静的マスタです。一つの YAML に一つのダンジョンを定義します。生成調整値には安全な既定値があり、最小構成では受付地点と Mob 参照だけを記述します。

## 必須項目

| キー | 型 | 制約・説明 |
|:--|:--|:--|
| `schemaVersion` | Integer | 現在は `1` |
| `id` | String | ダンジョン ID。ファイル間で一意 |
| `displayName` | String | プレイヤー表示名 |
| `entry.worldRef` | String | 挑戦受付地点を置く既存 `world:` 参照 |
| `entry.x/y/z` | Double | 挑戦受付地点 |
| `encounter.normalMobPool[]` | List | 一件以上の ENEMY Mob 参照 |
| `encounter.normalMobPool[].mobId` | String | `mob:` 参照 |
| `encounter.bossMobId` | String | BOSS category の `mob:` 参照 |

## 省略可能な調整項目

| キー | 型 | 既定値・制約 |
|:--|:--|:--|
| `entry.yaw/pitch` | Double | `0.0`。門型パーティクルの向きにも使用 |
| `entry.radius` | Double | `2.0`、`0.5..16.0` |
| `party.min/max` | Integer | `1/4`、`1..6`、min <= max |
| `challenge.deathLimit` | Integer | `5`、`0` 以上。設定回数までは死亡可能で、次の死亡時にセッション終了 |
| `challenge.reviveDelaySeconds` | Integer | `5`、`1` 以上。許容回数内の死亡後に開始地点へ復帰するまでの秒数 |
| `generation.area.width/depth` | Integer | `128/128`。各 `32..256`、積 65,536 以下 |
| `generation.baseY` | Integer | `64`。天井を含め `-60..316` 内 |
| `generation.roomCount.min/max` | Integer | `7/11`、`3..64`。seed 抽選 |
| `generation.roomSize.min/max` | Integer | `11/23`、`7..64` |
| `generation.roomHeight` | Integer | `8`、`5..32` |
| `generation.corridorWidth` | Integer | `3`、`1..7` の奇数 |
| `generation.corridorHeight` | Integer | `4`、`2..roomHeight-2` |
| `generation.splitRatio.min/max` | Double | `0.35/0.50`、`0.25..0.50` |
| `generation.roomShapes[]` | List | `RECTANGLE:3`, `CYLINDER:1`。type は両列挙値、weight 省略時 `1` |
| `theme.floor/wall/ceiling[]` | List | 各 `STONE_BRICKS:1` |
| `theme.corridor[]` | List | `COBBLESTONE:1` |
| `theme.*[].material` | String | AIR ではない solid block Material |
| `theme.*[].weight` | Integer | `1`。正の相対 weight |
| `theme.gateMaterial` | String | `IRON_BARS` |
| `theme.pillar.enabled` | Boolean | `false` |
| `theme.pillar.chance` | Double | `0.35`、`0.0..1.0` |
| `theme.pillar.material` | String | `CHISELED_STONE_BRICKS` |
| `theme.pillar.stairMaterial` | String | `STONE_BRICK_STAIRS` |
| `encounter.normalMobPool[].weight` | Integer | `1`。正の相対 weight |
| `encounter.mobsPerRoom.min/max` | Integer | `2/4`、`1..16` |
| `encounter.firstCombatRoomMaxMobLevel` | Integer | `10`。開始部屋候補の level 上限 |
| `clearRewards.items[]` | List | プレイヤーごとにクリア時点で独立抽選する直接報酬 |
| `clearRewards.items[].itemId` | String | `item:` 参照 |
| `clearRewards.items[].rate` | Double | `100.0`、有限値の `0.0..100.0`（%） |
| `clearRewards.items[].amount` | String / Integer | `"1"`。1以上の固定整数または、1以上かつ min <= max の `"1~3"` 形式 |
| `clearRewards.lootTable` | String | 任意の `loot_table:` 参照。直接報酬と独立抽選して結合 |

ダンジョンごとの DUNGEON World マスタは不要です。Plugin が共通インスタンスルートと保護設定から実行時 World 定義を生成します。

既定値はキーを省略した場合だけ適用します。キーを明示した場合、非数値、境界外、負数、不正な数量範囲を既定値へ補正せず、マスタの load／公開を失敗させます。

## 最小 YAML 例

```yaml
schemaVersion: 1
id: example_ruins
displayName: "石造遺跡（例）"
entry:
  worldRef: world:example_overworld
  x: 10.5
  y: 64.0
  z: -20.5
encounter:
  normalMobPool:
    - mobId: mob:example_skeleton
    - mobId: mob:example_guard
  bossMobId: mob:example_ruins_boss
challenge:
  deathLimit: 5
  reviveDelaySeconds: 5
clearRewards:
  items:
    - itemId: item:example_ruins_fragment
      rate: 100.0
      amount: "1~2"
```

生成範囲、部屋数、Material、柱、Mob 数を個別調整したいダンジョンだけ、省略可能項目を追加します。
