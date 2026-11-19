# AstralArchitect companion ticket CLI

このディレクトリは、Codexがチケットを調査し、`candidate.schem`だけを安全に編集するための標準ライブラリのみのPython CLIソースです。Minecraftワールドへの適用、チケット削除、ロールバックは行いません。実チケットを扱うCodexは、このファイルを直接起動せず、`$astralarchitect-builder`の`invoke_ticket_cli.py`から信頼済みパスとして呼び出します。

## CLI開発・手動検証用の実行例

```text
python ticket_cli.py info <absolute-ticket-directory>
python ticket_cli.py palette <absolute-ticket-directory>
python ticket_cli.py get-block <absolute-ticket-directory> 100 64 200
python ticket_cli.py slice <absolute-ticket-directory> --y 64 --x-min 96 --x-max 127 --z-min 192 --z-max 223
python ticket_cli.py surface <absolute-ticket-directory> --x-min 96 --x-max 127 --z-min 192 --z-max 223
python ticket_cli.py diff <absolute-ticket-directory> --limit 1000
python ticket_cli.py preview <absolute-ticket-directory> --ops <absolute-operations.json> --eye 128 88 160 --target 112 68 195 --output <absolute-new-png>
python ticket_cli.py apply-ops <absolute-ticket-directory> --ops <absolute-operations.json>
```

`--help`表示を除き、成功時も失敗時もstdoutへJSONを1件出力します。失敗時は非0の終了コードです。`live` は起動時にJSONを出力し、停止するまで稼働します。

## 3Dプレビュー

`preview` は候補をローカルでPNGに描画します。`--source` は元データを読み、`--ops` は `apply-ops` と同じ操作をメモリ上で仮適用します。`--eye` と `--target` はワールド座標の XYZ、`--clip-y` は描画する最大ブロックYです。PNGはチケットデータ外の新しいパスに保存します。見た目は簡易描画で、ゲームのテクスチャ・光量・衝突や更新は再現しません。

採用した操作と、プレビューの応答に含まれる `baseCandidateSha256` を `apply-ops --expect-candidate-sha256 <hash>` に渡すと、仮適用後に候補が更新された場合は反映前に拒否されます。

## ライブ3Dビューアー

`live <ticket> --session <new-absolute-session-json>` が返す URL を同じPCのWebGL 2対応ブラウザーで開きます。別端末から `live-ops <ticket> --session <session-json> --ops <absolute-operations-json>` を実行するたびに、検証済みの変更がブラウザーに届きます。ドラッグで周回、右ドラッグで平行移動、ホイールでズームできます。候補へ反映する操作は `live-export <ticket> --session <session-json> --output <new-absolute-operations-json>` で書き出し、返された候補ハッシュとともに `apply-ops` に渡します。

標準インストールや Windows の `%LOCALAPPDATA%/AstralArchitect/minecraft/1.21.11.jar` にある Minecraft Java 1.21.11 のクライアント JAR を検出すると、モデルとテクスチャを使います。別の配置先は `live` に `--minecraft-jar <クライアントJARの絶対パス>` を追加します。画面で `Java 1.21.11` と `簡易表示` を確認できます。

HTTP は `127.0.0.1` の空きポートだけで待ち受け、Web画面は閲覧専用です。セッションファイルは作業者の手元に置いてください。停止は `live-stop <ticket> --session <session-json>` または起動端末で Ctrl+C です。

## 編集操作

`apply-ops`はJSON配列、`{"operations": [...]}`、単一操作、NDJSONを受け付けます。座標はすべてワールド座標です。

```json
{
  "operations": [
    {
      "op": "set",
      "x": 100,
      "y": 64,
      "z": 200,
      "block": "minecraft:stone_bricks",
      "expect": "minecraft:stone"
    },
    {
      "op": "fill",
      "from": {"x": 101, "y": 64, "z": 200},
      "to": {"x": 105, "y": 68, "z": 203},
      "block": "minecraft:air"
    },
    {
      "op": "replace",
      "from": {"x": 100, "y": 64, "z": 200},
      "to": {"x": 110, "y": 70, "z": 210},
      "match": "minecraft:stone",
      "block": "minecraft:mossy_stone_bricks"
    },
    {
      "op": "line",
      "from": {"x": 100, "y": 64, "z": 200},
      "to": {"x": 110, "y": 70, "z": 210},
      "block": "minecraft:oak_log[axis=y]"
    }
  ]
}
```

- `expect`がある場合、その操作の対象が1か所でも一致しなければファイルを変更しません。
- 全操作をメモリ上で検証してから、同じディレクトリの一時ファイルを`os.replace`で原子的に置換します。
- 編集後にどのブロックも参照しないPalette項目を除去し、使用するIDを0からの連番に振り直してDataの参照も更新します。旧ブロックIDを置換しても未使用項目がゲーム内検証を妨げないようにするためです。ブロック配置に差分がない操作でも未使用項目があれば書き込み、`written=true`、`changedBlockCount=0`を返します。
- Javaプラグインと共通の`plugins/AstralArchitect/.locks/<ticket-id>.lock`を取得し、同時編集・検証・適用・削除による更新消失を拒否します。
- `slice`と`surface`は1回16,384セルまでです。広い範囲はX/Zの座標窓を分けて読みます。
- 編集操作の全展開数は最低500万ブロック、またはチケット総ブロック数の大きい方までです。巨大な反復操作は書込み前に拒否します。
- CLI初期版が読み込む1チケットの絶対安全上限は2,000万ブロックです。縦横高さは固定しません。
- `source.schem`はSHA-256を照合するだけで、書き込みません。
- 既存Block Entityの座標に対する状態変更は拒否します。
- 編集後は必ずMinecraft内の`/architect ticket validate <ID>`を実行してください。

## テスト

```text
python -m unittest discover -s tests -v
```

## 部品を使う建築

`preview --plan <設計書の絶対パス> --stage rough` で建物全体の骨格を、`--stage detail` で全体に仕上げを加えた姿を撮影できます。従来の `preview` と同じカメラ・出力引数を使います。プレビューのたびに現在の候補から部品を展開するので、設計書で塔を移動すると以前の位置も消えます。ライブ表示では `live-ops --session <session> --plan <設計書> --stage rough` のように設計を差し替えられます。

設計書 v1 の `parts.<name>` には JSON の真偽値 `reapplyRough` を指定できます。既定値は `false` です。`true` の部品は、全配置の骨格を置いたあとの仕上げ段階で、配置・反復ごとに `rough`、`detail` の順に適用します。重複した座標は後の命令が優先され、再適用した命令も操作量の上限に算入されます。

画像確認後、`apply-ops <ticket> --plan <設計書> --expect-candidate-sha256 <baseCandidateSha256> --expect-stage-sha256 <stageSha256>` で採用した仕上げを反映します。部品、反復、鏡映を使う JSON の例は [設計書 v1](../../../.codex/skills/astralarchitect-builder/references/blueprint.md) を参照してください。

断面を `shiftX` で横に移し、`span` で先端の幅を絞る屋根は、通常の `--plan` で指定できます。指定方法と JSON 例は [断面ごとの屋根幅と横移動](../../../.codex/skills/astralarchitect-builder/references/blueprint.md#断面ごとの屋根幅と横移動) を参照してください。

### `roof_sheet` の階段材

設計書 v1 の `roof_sheet` では、任意の `stairs` にプロパティなしの名前空間付き `_stairs` ブロック名を指定できます。省略した場合は従来どおりの操作を出力します。`rough` と `detail` のどちらでも、通常の `--plan` から利用できます。

補間、`span` による切り出し、`shiftX` による移動を終えた同じ屋根片の天端・下面を参照し、次の条件を満たす列の最上部の全ブロックを階段に置換します。

- 下り隣の天端は 1 または 2 半段低く、上り隣は同じ高さか 1～2 半段高い。同じ高さなら、その先の列で 1 または 2 半段上がる。
- 斜面に直交する両側にも同じ向きの勾配が続く。対応する天端の高さの差は各点で3半段以内とし、上りの向きが複数ある場合は階段化しない。
- 斜面を横切る両隣は、対象セルの下半分または両列に屋根材があるその下の位置で面が接する。上り隣は階段の高い側の上半分と面で接する。
- 下り隣が 1 半段低い場合は、その列の最上半段が対象セルの下半分と面で接する。2 半段低い場合は、両列が対象セルの直下の半段まで達し、そこで面で接する。

既定の下面でも `undersides` を指定した場合でも、同じ接触条件を使います。既定の下面は、各列と屋根片内の前後左右の天端の最小値から半段下げた位置です。

階段は上り方向を `facing` とする `half=bottom,shape=straight` の `set` に展開します。セルの上り側の天端と下面を保ち、下り側の上半分だけを削ります。対象セルを含む `fill` は上端を短くし、階段の `set` も既存の操作上限に数えます。鏡映する部品には既存の `minecraft` 名前空間の制約が適用され、配置時に階段の座標と向きが鏡映されます。

次の部品操作では、既定の下面で X = 1、Z = 1 の天端が東向きの階段になります。

```json
{
  "op": "roof_sheet",
  "at": [0, 0, 0],
  "offsets": [0, 2],
  "sections": [
    {"z": 0, "heights": [0, 4]},
    {"z": 2, "heights": [0, 4]}
  ],
  "block": "minecraft:stone_bricks",
  "slab": "minecraft:stone_brick_slab",
  "stairs": "minecraft:stone_brick_stairs"
}
```
