# エリヴァ超大陸のスポナー配置プレビュー

`continent.py` はコピーしたワールドの全域16m地表スキャンと既存Filebaseを読み、配置候補をJSONへ出力する。既存の設置一覧を読まず、実サーバーの配置、共有マスタ、Filebaseを変更しない。出力は `previewOnly: true` であり、公開用の完成マスタではない。

## 西側道路沿い Lv.15～45 モード

`--policy 60_tool/spawner-planner/policies/eriva-westroad.json` を指定すると、半径64mだけの道路進行プレビューになる。指定しなければ従来の全大陸ポリシーを使う。開始地点と最低推奨レベルは引き続きワールドマスタから読み、道路モードは最低推奨レベルが15でなければ拒否する。`fieldSpawnerIds` は草原・森林それぞれ Lv.15,18,21,24,27,30,33,36,39,42,45 の `eriva_westroad_*_spawner` を厳密に参照する。全IDの実在と対応するEnemyレベルを検証し、未登録Enemyからのdraftは作らない。

開始地点を含む16m区画から、`groundCounts` に `minecraft:dirt_path` が1列以上ある区画だけを4近傍でたどる。他の道路成分は開始地点からの経路とみなさない。経路の最短距離を16m単位で求め、到達した道路区画の最大距離を11段階に分ける。各区画を最も近い道路区画へ割り当て、その道路距離で目標レベルを決める。森林・草原の地域IDは `eriva-westroad-stageNN-lvLL-<habitat>`、地域名は仮称であり正式な地理区分ではない。枝道も同じ最短道路距離で段階を得る。

道路区画の中心から224mを今回の範囲とし、範囲外の解析済み非水域には `reserved_future` を付けて配置しない。未生成・計測失敗・水域は元の理由を保持する。候補中心は道路区画中心から160m以内、かつ `224m - 出現半径` 以内とする。出現円が `reserved_future` 区画へ触れる候補も拒否する。半径64mと開始地点48m保護の双方を満たす提案だけを出す。北東などの後続地域、将来のLv.100以上の設計範囲は今回の候補に含まれない。

`manifest.progressionPolicy.mode` は `road`、`road.nodes` は16m道路区画中心と最短道路距離、`road.edges` は4近傍辺のノード索引である。`road.maxDistanceMeters`、`road.corridorMeters`、`road.candidateCenterMeters`、段階 `levels` を同じオブジェクトへ出す。`cells.json` の `roadDistanceMeters` と `distanceToRoadMeters` は解析済みの道路範囲だけに入り、範囲外・未計測はnullとなる。提案にもこの二つの距離を記録する。トップレベルの `mode: continent-redesign`、schema 2とファイル間revision一致は維持する。

`groundCounts` は256列の材質個数であり、道の列座標を保存しない。4近傍で隣り合う道含有区画が実際につながる保証はなく、橋、段差、障害物、Mobの経路探索も証明しない。道線と段階は配置前にコピーしたワールドで現地確認する。半径64mのスポナーで異環境や隣の段階へMobが出現し得る点も別途検証する。

## 実行

```powershell
python 60_tool/spawner-planner/continent.py `
  --terrain C:/server/AstralRecord-CodexDev/analysis/continent-terrain.json `
  --filebase C:/AstralRecord-Workspace/40_filebase `
  --output C:/server/AstralRecord-CodexDev/analysis/continent-plan
```

Python 3.11以上とPyYAMLが必要。地形JSONはscanner schema 2で、全長方形区画を `ok` / `absent` / `failed` として列挙する。`failed` は割当済みでも未生成・読込失敗・計測失敗だった区画、`absent` はMCAに未割当の区画であり、どちらも配置しない。解析矩形の外側にも配置しない。中断時の `.part` ではなく完成したJSONを使う。

出力ファイルは `manifest.json`、`cells.json`、`regions.json`、`proposals-32.json`、`proposals-48.json`、`proposals-64.json`。いずれもschema 2で、同じ `exportRevision` と `terrainFingerprint` を持つ。`manifest.json` を最後に置換する。読取側は各ファイルのrevisionとmanifestの一致を確認する。`exportRevision` は地形、ポリシー、参照するワールド/Enemy/Spawnerマスタ、計算器版から決まる。

## 進行・地域の決め方

ワールドマスタの最低推奨はLv.15。`policies/eriva-continent.json` に開始地点からの平面距離に対するLv.15～42の**提案上の目標帯**を定義する。地形経路の距離や実際にそこへ到達できる順序を示すものではない。地域名は方角、距離帯、生息環境をつないだ仮称であり、既存の正式地名ではない。海、山、橋、孤立した島は現地で別途確認する。

野外適合が確認できるエリヴァの既存スポナーを草原・森林へ使う。さらに `field_*` の既存Enemyについて、モチーフと既存の定義レベルが合う草原・森林・雪原・乾燥地の敵を明示的に選び、未登録スポナー案として示す。Enemyの架空レベルや能力値は作らない。他ワールド固有、ダンジョン専用、BossおよびBoss召喚物は自動選定しない。未登録案の `draftDefinition` はモブID、実レベル、地域、半径のみを提示し、出現間隔と上限などは別途設計が必要。既存マスタ案と未登録案は `definitionStatus` で区別する。

対象環境にある定義レベルを、目標帯と同値、次いで2レベル以内の低い値、最後に2レベル以内の高い値から選ぶ。選べない区画は `unsupported_level`。このため森林にLv.15/17/18の近郊案とLv.24以降の遠域案があり、間の空白は可視化される。雪原はLv.32、乾燥地はLv.38が最初の既存Enemyレベルなので、それより早い帯をLv.15などで埋めない。個々の候補には目標帯 `targetLevel` と実際のEnemyレベル `level` の双方を記録する。

## 地形・被覆の判断

- 16m区画の256列中、安全な地表が128列以上、高低差4以下の区画を候補にする。水が半数以上の区画、自然地面でない区画、未生成・読込失敗区画は除く。森林/雪原/乾燥地の分類はバイオーム、地面、樹木数などの推定であり、建造物の完全判定ではない。
- 開始地点の48m以内を保護する。スポーン円全体が保護円と既知スキャン矩形から外れる中心だけを許す。円が未生成・失敗区画と交差する中心も許さない。
- 地表の非水域区画を4近傍でグループ化し、`landComponentId` を付ける。水域や未解析区画を隔てた離れ地形は別グループとなるが、この接続は16m単位の粗い地表推定であり、橋・段差・断崖を歩ける証明ではない。
- 区画全体をカバーしたとするには四隅が出現円内で、足場の高度が `中心Y-3` ～ `中心Y+半径` に入り、同じ提案地域と `landComponentId` に属することを求める。これは実際の全Mob出現点を証明しない。
- 各半径で未カバー区画を最も多く覆う候補から貪欲に採用する。同点は区画の行優先順で決定する。数学的な最小配置数は保証しない。半径を大きくした案でも既存スポナーIDを使う場合はマスタ半径変更が別途必要となり、同IDの全設置へ影響し得る。

全セルには `reasonCode` と日本語の `reason` を付ける。半径別ファイルの `reasonCodes` は `cells.json` のセル順に対応し、`coveredBy` は提案IDへ対応する。`coveredCells / targetCells` は安全で対応マスタが選べる地表だけのカバー率で、ワールド全体の網羅率ではない。区画ごとの現地到達性、洞窟・屋内、水中、Mobの経路探索、異環境への実スポーン、負荷、戦闘バランスは未検証。

同時存在数の上限は、既存マスタだけなら `maxAliveUpperBound` に各設定値の単純合計を示す。未登録案を1件でも含む場合は未設計の上限を0として数えず、`maxAliveUpperBound: null` とする。`knownMaxAliveTotal` と `unknownLimitProposalCount` に内訳を記録する。いずれも実機負荷の測定値ではない。
