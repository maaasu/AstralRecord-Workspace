# Bundle Sound YAML スキーマ定義

bundle 使用時サウンドの実体定義です。`bundle.onUse.sound` にはこの ID を指定します。

## スキーマ定義

| キー | 型 | 必須 | 既定値 | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | 1 | スキーマバージョン |
| `id` | String | ○ | - | bundle sound ID |
| `sound` | String | ○ | - | 再生する Minecraft sound key |
| `volume` | Double | × | 1.0 | 再生音量 |
| `pitch` | Double | × | 1.0 | 再生ピッチ |

## YAML 例

以下は架空の記述例であり、現行マスタに定義された ID ではありません。利用時は `bundle_sound` マスタを作成し、その実在 ID を Bundle 側から参照してください。

```yaml
schemaVersion: 1
id: example_bundle_sound
sound: block.chest.open
volume: 1.0
pitch: 1.0
```
