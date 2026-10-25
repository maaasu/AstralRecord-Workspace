# Region Terrain Scanner

既存の Paper 1.21.11 ワールドから地形だけを読み、`terrain.json` を書き出す単独プラグインです。AstralRecord 本体は必要ありません。

## 実行

1. `mvn package` で `target/region-terrain-scanner-1.0.0.jar` を作る。
2. 解析専用サーバーの `plugins/` へ JAR を置き、対象ワールドの既存フォルダー（`level.dat` を含む）をサーバールートへコピーする。
3. サーバーを起動する。既定では自動スキャンし、`analysis/terrain.json` に出力する。

`config.yml` の領域は `minX/minZ` を含み `minX+size/minZ+size` を含みません。`minX`、`minZ`、`size` は 16 の倍数、`cellSize` は 16 の約数にしてください。1 tick に開始するチャンクは最大 1 個です。未生成チャンクを生成しません。プラグインがロードしたチャンクは解析後に解放します。

出力は UTF-8 の JSON です。`cells` はチャンク Z→X、チャンク内セル Z→X の順で、全セルを一度ずつ含みます。各セルの `safeCount` は 4×4 列の安全候補数です。`candidateX/Y/Z` と `y` は安全候補がある場合だけ数値です。`surface` は上面の代表材質、`ground` は木の葉・幹を除いた支持面の代表材質です。`minY/maxY` は安全候補の足元 Y の範囲です。`waterCount/treeCount` は上面に水・木がある列数です。未生成・取得失敗チャンクのセルは `status: "unknown"` と `reason` を持ち、他の測定値を `null` にします。書込中は一時ファイルを使い、完了時だけ置き換えます。
