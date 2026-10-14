# E02統合静的検証

原30ddcc27d7998b5ce0fd5ac891041181925df1f1をa29a83392へcherry-pick。enemy2/material2/batch資料4の所有範囲を確認。eriva_grassboarに10a00302、eriva_sporecap_beastに10a00303を35%×1、COMMON、幸運対象、表示ありとして追加。素材saleValue0。既存確率・個数・lootTable・EXP・AI・ステータスは保持。

validate_integrated_batches.pyの許可既存素材集合へこの2種だけを追加。E01/E02の計4種・15実効levelについて、新素材1件除去後のroot全体と全実効profileをe2058adeと比較する。2種の計7levelが新素材を1件ずつ継承する。直接items配列とexp-only overrideを区別し、参照・全体ID・既存spawn保持も確認。

結果: 全880 YAML、namespace IDs1142、既存素材追加4種、15実効profileの保持PASS。中央8技/新32profile/12新素材もPASS。EXISTING_MATERIAL_ASSIGNMENTS.csvのE02確定値と状態を更新。

本番コード・中央Registryに追加変更はない。Mavenは親指定に従いB05/B06到着後にまとめる。直前のB01〜B04/E01 Plugin全1022成功はf4ea3ba64時点、E02統合後の重い検証を完了済みとしない。実server/抽選/図鑑、配置、用途実装は未確認。

E02元の静的検証・独立レビュー記録はbatches/E02に保持。統合時は限定diffと中央の実効比較を再確認。develop/push/version/deploy/server/DB/port操作なし、専用worktreeを保持。
