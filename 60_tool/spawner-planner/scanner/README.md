# Region Terrain Scanner

既存の Paper 1.21.11 ワールドから地形だけを読み、`terrain.json` を書き出す単独プラグインです。AstralRecord 本体は必要ありません。

## 実行

1回の解析は一辺1024ブロック以下に制限します。`safeColumnY` は各セル内をZ→X順に展開した最初の非木材固体上の足元Yの配列で、支持面・足・頭を検査できない列はnullです。地表の接続判定に使い、区画内の欄干と通路の高さを混同しないようにします。`walkableBelowSurface` は同じ判定を通った下層の歩行面を `{x,y,z}` で列挙し、`safeColumnY` の地表候補とは重複しません。屋根下など上下に床がある場所の探索に使います。通常床に加えて道・耕地・階段・ハーフブロックを支持候補に含めます。

1. `mvn package` で `target/region-terrain-scanner-1.0.0.jar` を作る。
2. 解析専用サーバーの `plugins/` へ JAR を置き、対象ワールドの既存フォルダー（`level.dat` を含む）をサーバールートへコピーする。
3. サーバーを起動する。既定では自動スキャンし、`analysis/terrain.json` に出力する。

`config.yml` の領域は `minX/minZ` を含み `minX+size/minZ+size` を含みません。`minX`、`minZ`、`size` は 16 の倍数、`cellSize` は 16 の約数にしてください。1 tick に開始するチャンクは最大 1 個です。未生成チャンクを生成しません。プラグインがロードしたチャンクは解析後に解放します。

出力は UTF-8 の JSON です。`cells` はチャンク Z→X、チャンク内セル Z→X の順で、全セルを一度ずつ含みます。各セルの `safeCount` は 4×4 列の地表安全候補数です。`candidateX/Y/Z` と `y` は地表安全候補がある場合だけ数値です。`surface` は上面の代表材質、`ground` は木の葉・幹を除いた最初の固体の代表材質です。`minY/maxY` は地表安全候補の足元 Y の範囲です。`waterCount/treeCount` は上面に水・木がある列数です。下層の調査は各列の上面から最大32ブロック下までに限るため、さらに深い床は含まれません。未生成・取得失敗チャンクのセルは `status: "unknown"` と `reason` を持ち、他の測定値を `null` にします。書込中は一時ファイルを使い、完了時だけ置き換えます。
