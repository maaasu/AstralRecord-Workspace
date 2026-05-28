# Bundle Sound YAML スキーマ定義

bundle 使用時サウンドの実体定義です。`bundle.onUse.sound` にはこの ID を指定します。

| キー | 型 | 必須 | 既定値 | 説明 |
|:--|:--|:--:|:--|:--|
| `schemaVersion` | Integer | ○ | 1 | スキーマバージョン |
| `id` | String | ○ | - | bundle sound ID |
| `sound` | String | ○ | - | 再生する Minecraft sound key |
| `volume` | Double | × | 1.0 | 再生音量 |
| `pitch` | Double | × | 1.0 | 再生ピッチ |

```yaml
schemaVersion: 1
id: bundle_chest_open
sound: block.chest.open
volume: 1.0
pitch: 1.0
```
