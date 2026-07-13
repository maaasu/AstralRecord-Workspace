# Bundle Particle YAML スキーマ定義

bundle 使用時パーティクルの実体定義です。`bundle.onUse.particle` にはこの ID を指定します。

## スキーマ定義

| キー | 型 | 必須 | 既定値 | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | 1 | スキーマバージョン |
| `id` | String | ○ | - | bundle particle ID |
| `particle` | String | ○ | - | Minecraft の Particle 名 |
| `count` | Integer | × | 24 | 表示個数 |
| `originOffsetX` | Double | × | 0.0 | 発生原点 X オフセット |
| `originOffsetY` | Double | × | 1.0 | 発生原点 Y オフセット |
| `originOffsetZ` | Double | × | 0.0 | 発生原点 Z オフセット |
| `offsetX` | Double | × | 0.4 | 拡散 X |
| `offsetY` | Double | × | 0.5 | 拡散 Y |
| `offsetZ` | Double | × | 0.4 | 拡散 Z |
| `extra` | Double | × | 0.0 | `spawnParticle` の extra |

## YAML 例

以下は架空の記述例であり、現行マスタに定義された ID ではありません。利用時は `bundle_particle` マスタを作成し、その実在 ID を Bundle 側から参照してください。

```yaml
schemaVersion: 1
id: example_bundle_particle
particle: totem_of_undying
count: 24
originOffsetY: 1.0
offsetX: 0.35
offsetY: 0.45
offsetZ: 0.35
extra: 0.0
```
