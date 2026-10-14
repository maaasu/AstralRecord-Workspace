# B02 検証記録

対象branch: `codex/field-mobs-lv50-b02`。共通基点: `e2058ade10a682118382e70aa01c7db758ac81c6`。実装・検証日は2026-10-14 UTC。

## 実施した軽い検証

Python 3.14 / PyYAML 6.0.3、JDK21、ローカルMaven cacheのPaper API 1.21.11を使用。新しいdependency取得、server、port、DB、Maven/dotnet/npm buildは起動していない。

```powershell
& 'C:\Users\kaede\AppData\Local\Python\pythoncore-3.14-64\python.exe' '<task-root>\00_docs\99_資料\field-mobs-lv50\batches\B02\verify_batch.py' `
  --paper-jar 'C:\Users\kaede\.m2\repository\io\papermc\paper\paper-api\1.21.11-R0.1-SNAPSHOT\paper-api-1.21.11-R0.1-SNAPSHOT.jar' `
  --javac 'C:\Program Files\Java\jdk-21.0.10\bin\javac.exe' --java 'C:\Program Files\Java\jdk-21.0.10\bin\java.exe'
```

```text
PASS: 860 YAML parsed; 857 namespace IDs unique; 2 mobs/8 profiles/2 item refs/2 manifests; owned diff only
PASS: 12 fan/hitbox cases and 4 Paper Material enums
```

全filebaseのYAML重複キーとnamespace ID重複、種内幅6/最大34、全レベルの成長、通常攻撃、素材35%×1個と幸運・図鑑表示、item ref、Mob対応stat/tag、予約Mob skill IDとmanifest、所有diffを確認。既存ドロップを含む既存ファイルは変更していない。共有loot・全spawner・中央登録・POMは差分にない。

扇の実コードだけを一時コンパイルして確認した境界: 正面、背後、真横、角度外、扇端を跨ぐhitbox、中心は射程外でも体が内側、全身射程外、原点重なり、高所、下階、90度回転した正面と側面。MaterialはRABBIT_SPAWN_EGG/SILVERFISH_SPAWN_EGG/RABBIT_HIDE/TURTLE_SCUTE。専用検証はProject全体やExecutor全体のコンパイルを保証しない。

```powershell
& '<python>' '<task-root>\.codex\skills\astralrecord-code\scripts\check_plugin_resources.py' --repo-root '<task-root>'
```

```text
Plugin resource validation passed: 359 log IDs, 969 player message IDs.
```

stdout/stderrを確認し、上記成功実行に警告なし。初回の幾何検証はOS sandboxの既定Tempへの書込権限で失敗したため、所有worktree内の99_workに一時出力を限定して再実行・成功した。これは実装のコンパイルエラーではない。一時生成物はGit対象外で、専用検証の終了時に削除する。

## 独立レビュー

第1回Solレビューは2Executor、2enemy、2素材、仕様・manifest・検証の読み取り専用確認。AR-CODE-001 [中] はカゼトカゲのwindタグがSKILLTREE_NODE専用でMob対象外という指摘。タグを削除し、専用validatorにもappliesTo:MOBの検証を追加して再実行した。コードの衝突、方向固定、対象制限、キャンセル、終了処理には他の確証ある指摘なし。

第2回は修正担当と異なる独立Solが読み取り専用レビューを実施。windタグ除去とMOB適用先の検証、2Executorの継続・解除、MobSkillService/ConditionService/DamageService契約、4レベルの継承、素材参照、destroy/shutdown合成manifest、SPEC数値を照合し、新たな指摘なし。初回指摘は修正済み、レビュー記録の更新・検証ツールは成功。

通常のレビュー記録保存先は依頼の独占所有範囲から外れるため、同じ記録形式をbatches/B02内に保存した。管理表も統合担当所有のため本バッチで更新していない。

## 親へ残す検証

- Executorの中央登録、destroy callbackの合成、onDisableのstop接続と全skill IDのRegistry解決。
- Plugin全体コンパイルと通常 `mvn -q test`。本バッチは重い検証枠を使用しない。
- MobRepository/APIで8profileの読込、通常攻撃と固有技、素材の継承を照合。
- 実server/clientで跳躍の壁・角・半ブロック・崖、扇境界、予告の回避、条件中断、召喚ペット、複数個体、Plugin停止を確認。
- 実戦TTK/耐久を確認し、Lv30超の共有基準確定時にLv32/34の暫定値を再評価。
- 実地域/worldを確認した後のspawner定義と配置。他batch統合後のID・参照再検証。装備担当による素材消費先。

中央未登録の技を実行確認済み・完成済みとは扱わない。統合manifestが接続要求と残検証の正本。
