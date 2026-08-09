# Dungeon YAML スキーマ定義

開始要求ごとに BSP で一時ワールドへ生成する、1階層ダンジョンの静的マスタです。一つの YAML に一つのダンジョンを定義します。部屋座標や分割木は seed から実行時生成するため定義しません。

## スキーマ定義

| キー | 型 | 必須 | 制約・説明 |
|:--|:--|:--:|:--|
| `schemaVersion` | Integer | ○ | 現在は `1` |
| `id` | String | ○ | ダンジョン ID。ファイル間で一意 |
| `displayName` | String | ○ | プレイヤー表示名 |
| `worldRef` | String | ○ | `world:` 参照。`DUNGEON`, `instanceEnabled=true`, `autoLoad=false` |
| `party.min/max` | Integer | ○ | `1..6`、min <= max。World の maxPlayers 以下 |
| `generation.area.width/depth` | Integer | ○ | 各 `32..256`、積は 65,536 以下 |
| `generation.baseY` | Integer | ○ | 部屋床 Y。部屋天井を含め `-60..316` 内に収める |
| `generation.roomCount.min/max` | Integer | ○ | `3..64`。範囲から seed 抽選 |
| `generation.roomSize.min/max` | Integer | ○ | `7..64`。各葉区画内の幅・奥行き抽選範囲 |
| `generation.roomHeight` | Integer | ○ | `5..32`。床・天井を含む高さ |
| `generation.corridorWidth` | Integer | ○ | `1..7` の奇数 |
| `generation.corridorHeight` | Integer | ○ | `2..roomHeight-2`。歩行空間の高さ |
| `generation.splitRatio.min/max` | Double | ○ | 順序を保った `0.25..0.50` |
| `generation.roomShapes[]` | List | ○ | 一件以上 |
| `generation.roomShapes[].type` | Enum | ○ | `RECTANGLE` / `CYLINDER` |
| `generation.roomShapes[].weight` | Integer | ○ | 正の相対 weight |
| `theme.floor/wall/ceiling/corridor[]` | List | ○ | 各一件以上の重み付き Material |
| `theme.*[].material` | String | ○ | AIR ではない solid block Material |
| `theme.*[].weight` | Integer | ○ | 正の相対 weight。合計 100 は不要 |
| `theme.gateMaterial` | String | ○ | 通路を閉じる solid block Material |
| `theme.pillar.enabled` | Boolean | ○ | 中央柱を抽選するか |
| `theme.pillar.chance` | Double | ○ | 部屋ごとの生成率 `0.0..1.0` |
| `theme.pillar.material` | String | ○ | 柱本体の solid block Material |
| `theme.pillar.stairMaterial` | String | ○ | 末尾 `_STAIRS` の Material |
| `encounter.normalMobPool[]` | List | ○ | 一件以上の ENEMY Mob 参照 |
| `encounter.normalMobPool[].mobId` | String | ○ | `mob:` 参照 |
| `encounter.normalMobPool[].weight` | Integer | ○ | 正の相対 weight |
| `encounter.mobsPerRoom.min/max` | Integer | ○ | `1..16`。通常部屋ごとの出現数 |
| `encounter.firstCombatRoomMaxMobLevel` | Integer | ○ | 正数。開始部屋の候補上限。条件内候補が最低一体必要 |
| `encounter.bossMobId` | String | ○ | BOSS category の `mob:` 参照 |

## YAML 例

これはスキーマ例であり、本番投入する個別ダンジョンではありません。Material の weight は相対値として扱います。

```yaml
schemaVersion: 1
id: example_stone_ruins
displayName: "石造遺跡（例）"
worldRef: world:example_dungeon_instance

party:
  min: 1
  max: 4

generation:
  area:
    width: 128
    depth: 128
  baseY: 64
  roomCount:
    min: 7
    max: 11
  roomSize:
    min: 11
    max: 23
  roomHeight: 8
  corridorWidth: 3
  corridorHeight: 4
  splitRatio:
    min: 0.35
    max: 0.50
  roomShapes:
    - type: RECTANGLE
      weight: 70
    - type: CYLINDER
      weight: 30

theme:
  floor:
    - material: STONE_BRICKS
      weight: 60
    - material: CRACKED_STONE_BRICKS
      weight: 25
    - material: MOSSY_STONE_BRICKS
      weight: 15
  wall:
    - material: STONE_BRICKS
      weight: 80
    - material: MOSSY_STONE_BRICKS
      weight: 20
  ceiling:
    - material: STONE_BRICKS
      weight: 100
  corridor:
    - material: STONE_BRICKS
      weight: 85
    - material: MOSSY_STONE_BRICKS
      weight: 15
  gateMaterial: IRON_BARS
  pillar:
    enabled: true
    chance: 0.35
    material: CHISELED_STONE_BRICKS
    stairMaterial: STONE_BRICK_STAIRS

encounter:
  normalMobPool:
    - mobId: mob:example_skeleton
      weight: 70
    - mobId: mob:example_guard
      weight: 30
  mobsPerRoom:
    min: 2
    max: 5
  firstCombatRoomMaxMobLevel: 10
  bossMobId: mob:example_ruins_boss
```
