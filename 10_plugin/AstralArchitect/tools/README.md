# AstralArchitect companion ticket CLI

このディレクトリは、Codexがチケットを調査し、`candidate.schem`だけを安全に編集するための標準ライブラリのみのPython CLIです。Minecraftワールドへの適用、チケット削除、ロールバックは行いません。

## 実行例

```text
python ticket_cli.py info <absolute-ticket-directory>
python ticket_cli.py palette <absolute-ticket-directory>
python ticket_cli.py get-block <absolute-ticket-directory> 100 64 200
python ticket_cli.py slice <absolute-ticket-directory> --y 64 --x-min 96 --x-max 127 --z-min 192 --z-max 223
python ticket_cli.py surface <absolute-ticket-directory> --x-min 96 --x-max 127 --z-min 192 --z-max 223
python ticket_cli.py diff <absolute-ticket-directory> --limit 1000
python ticket_cli.py apply-ops <absolute-ticket-directory> --ops <absolute-operations.json>
```

`--help`表示を除き、成功時も失敗時もstdoutへJSONを1件出力します。失敗時は非0の終了コードです。

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
