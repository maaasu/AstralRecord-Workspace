# 既存25種への新規素材追加・13分割

親はE01〜E13を各1〜2種のdeviceスレッドへ割当できる。新規種B01〜B12と所有ファイルが交差しない。予約はEXISTING_MATERIAL_ASSIGNMENTS.csvを正本とする。既存10a00300〜00324の対応を保持し、未使用10a00325〜00399は統合担当が保留する。

共通基点は計画commit e2058ade10a682118382e70aa01c7db758ac81c6。この追加資料を読むには中央資料の追加commitを参照・cherry-pickしてよいが、全Bバッチの基点を変更する必要はない。

## 所有と仕様

各E担当はCSVのenemy_pathとmaterial_path、およびbatches/E番号/の根拠・検証資料だけを編集。既存enemyは新規drop1件追加だけ。レベル・stats・ai・skill・spawn・exp・money・既存drop・lootTableは変更しない。装備/recipe/API/Web/Editor/共通schema/中央資料を編集しない。

全種に通常素材1種類、COMMON、個数1、設定率35%、luckAffected true、hidden falseを設計案とする。既存通常素材35〜80%の下側に置き、1撃破の追加期待数量0.35個に抑える。既存ドロップを減らして補償しない。用途未実装なので製作先をLoreに断言しない。saleValueは0を初期案とし、未実装の金策導線を追加しない。progressionは既存Mob先頭コメントと同値をCSVへ記録。全levelで同素材・同率とする。

35%はMobDropServiceの直接drops.itemsに追加する独立抽選の設定値（lootTableと別）。実効値はLUCKとDROP_RATE_INCREASEで補正され、0〜100へclamp。既存抽選枠のweightを奪わない。確率・個数の最終変更が必要なら担当が根拠を記録し親と統合担当へ返す。アイコンは既存使用状況を確認し、再利用がモチーフ上自然な場合だけ理由を記録。

## レベル継承の注意

MobRepository.mergeLevelProfile/mergeJsonObjectsはobjectを再帰mergeするが配列は置換する。root drops.itemsに追記するだけで、levels[].drops.itemsを定義したprofileには追加素材が届かない。各担当は全profileの実効dropsを確認し、明示items配列があるlevelではその既存配列を一切変えず末尾に新規1件を追加。expだけのoverrideはroot itemsを継承するため重複追加しない。空配列overrideも見落とさない。各levelの新素材出現がちょうど1件、既存全item/rate/amount/luckAffected/hidden/lootTableが基点と同値であることを機械比較する。

## 分担

CSV順に2種ずつE01〜E12、最後witch_forest_hex_witchだけE13。各unitは素材定義+全level drop追加を完結させる。新規スキルは作成しない。品質を落とす一括25種編集はしない。静的検証後限定commit、SHA/所有path/実効drops比較/ID参照確認を返してworktree保持。重いbuildは不要、server/DB/port/push/develop統合/deployは禁止。

## 検証と装備依存

変更YAML parse/schema、全ID重複、item参照、上記profile継承比較、既存drop保持、名前/icon/progressionを確認。素材101件の既存一覧とは別にこの25件を追加予定として扱う。装備タスクはequipment-scalar-random / codex/equipment-scalar-randomの所有者が20aとスキーマ/API/Web/Editorを管理。E担当はそのworktreeを編集しない。素材実装後と装備反映後に用途なし一覧を再判定する。

## 統合進捗

E01〜E09を受入済み。既存25種中18種（72%）、素材00300〜00317、全37実効levelで追加と既存データ保持を確認。残り7種はE10〜E13。全体Pluginは新規23種基点で1022全成功、E09は静的のみ。
