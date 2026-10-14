# B08 統合受入

固定原commit 494526a2325a156ad3c1b5b247f7796b675eaf85 → a800f79ef、所有13新規ファイルだけを受入。基点3444b74b2から他の差分は取り込んでいない。

アカサソリ/スナヤモリ Lv38/40/42/44を各1回登録。既存destroy listenerへhandleMobDestroyed、onDisable冒頭へstopを接続。素材270/271/272は35%/35%/8%、各1個、売却550/420/620。赤蠍の毒針をアカサソリの毒針へ平易化しYAML/SPEC/manifest/PLAN/CSVを同期。

904 YAML/1166 namespace ID/16 executor/64新規profile/23新素材、既存8種19実効profile保持の中央静的検証PASS。resource359log/969message PASS。Plugin mvn -o testは1022件失敗0/エラー0/skip0、BUILD SUCCESS、01:12。ログC:/Users/kaede/AppData/Local/Temp/field-mobs-lv50-b08-plugin-test.log。独立2レビューを実施。比較表は直接倍率0.85/0.35、毒SUPPORT_POWERと盲目の実戦影響は表外。

B08専用validatorは元基点から所有差分だけというガードを持つため、他batch/中央変更を含む統合treeでは所有範囲assertで拒否する。原batchの静的結果を保持し、統合時は中央validatorを正本とした。実server/client/DB/port操作なし、メサのworld/座標根拠なしで実配置保留。

管理表の既知9milestoneメモを再確認し、全体をTemp field-mobs-management-preserved-20261015-B08.mdへ保全。SHA256 43A02A85A325F32B262E25B1582E57DD9025DA068FA616513930D65EA9CC3082。全体再生成や他行削除なし。更新前の未観測行との完全一致は証明できない。完了後中央メモのみ追記する。develop統合/push/版更新/deployなし、worktree保持、ビルド枠解放。
