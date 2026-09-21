# 20_README

このディレクトリは API `account-skilltree` 機能の設計をまとめる。
アカウント単位のスキルツリー進行状態を API / DB で永続化する。Web編集は状態全置換APIを呼ばず、実ロード定義のPluginが既存ゲーム内操作と同じ検証・player-state snapshot transactionで確定する。

CP / PP の残高は永続化しない。API は解放ノードごとに CP の消費元クラスを保持し、Plugin がプレイヤーレベル・クラス別レベルから残高を導出する。

ノード定義と配置・接続構造は、それぞれ filebase `40_filebase/35.features.skilltree/nodes/*.json` と `structures/*.json` を正本とします。これらは Plugin が直接読み込む静的マスタです。成功して公開した関連定義の完全snapshotはAstralRecord DBに世代別保存し、Webはその世代のPlugin評価viewを使用します。MasterDataDBへのノード定義追加は行いません。

skill effectはスキル個体を習得させず、現在クラス条件を満たしてノード効果が有効な間だけ使用許可を追加する。所持はスキルマネージャーが skill master の `learnRequiredItems` / `levelUpRequiredItems` に従って作成・強化する習得済み個体を正本とする。

## 対象実装パス

- `20_api/AstralRecordApi/AstralRecordApi/Controllers/AccountSkillTreeController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Models/AccountSkillTreeStateModels.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/IAccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/AccountSkillTreeStateRepository.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeStateEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Data/Entities/AccountSkillTreeUnlockedNodeEntity.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Controllers/SkillTreeEditorController.cs`
- `20_api/AstralRecordApi/AstralRecordApi/Repositories/SkillTreeOperationRepository.cs`

## 世代・Web編集の安全境界

- Pluginはロード成功後だけ、正規化済み完全snapshotのSHA-256を`definitionGenerationId`としてserver session、起動fence、互換性情報とともに登録する。reload失敗時に新世代を登録しない。
- Pluginはプレイヤー別に評価済み`tree`/`points`/`relockGoldCost` viewを登録する。WebはPlugin評価viewから変更案の接続とポイントを事前検証する。表示効果と固定費用はPluginの公開値を使い、最終判定はPluginが既存ルールで再検証する。
- Webは対象serverが実際に登録したgeneration、skilltree state version、Plugin評価fingerprintで操作を要求する。online時は同じ接続serverかつPluginが拠点・スキルツリーワールドと判定した場合だけ要求を作成する。offline時は同一target serverへ編集案を保存し、次回参加時にPluginが世代・state・fingerprint・位置を再検証する。
- 旧generationへのtarget固定案を別serverへ読み替えない。参加先が異なる場合は旧案を`RECONFIRMATION_REQUIRED`または`CANCELED`として結果照会可能にする。
- `account_skilltree_state.definition_generation_id`が`NULL`のlegacy状態は明示移行までWeb確定・自動補修対象外である。世代不一致は保留し、構造不整合だけが既存補修対象である。ノード廃止は別途明示移行で返還を検証してから適用する。
- 明示移行はruntime-keyでoffline accountにのみ実行する。operation ID、expected state version、旧node集合baseline、from/to世代、明示remove node ID、APIが保存済み世代snapshotからgraph・保持nodeの条件/コストを照合する。APIは新node付与、CP元付替え、Gold変更を行わず、除去nodeの既存spent返還はPlugin既存規則に委ねる。

## 関連 feature

- plugin 側の skilltree: `00_docs/10_Plugin設計書/feature/13-skill/`
- account レベル進行: `00_docs/10_Plugin設計書/feature/02-account/`
- DB テーブル: `00_docs/40_Database設計書/table-definitions/AstralRecord/`
- filebase JSON: `40_filebase/35.features.skilltree/docs.skilltree.JSONスキーマ定義.md`

## ドキュメント

1. [[20_3.00-索引]]

## 参加権限と保存

各accountはランダムな`accountSessionId`と秘密lease tokenで参加先を1つだけ取得する。45秒の有効期限をview/更新で延長する。閉鎖または期限切れのsession IDは再利用できず、履歴を削除しない。同じserverでもbootが変われば旧bootは保存できない。全更新はaccount行のUPDLOCK/HOLDLOCKを起点として直列化し、本番のEF Core再試行strategy内でtransactionを開始する。

Web操作のclaim leaseは30秒、編集案は7日で期限切れとなる。有効な案はaccountあたり1件。取消・期限切れ・別参加先への移動を確定済み結果と区別し、古い案を再開しない。処理結果はoperation ID、player-stateの通信結果はsnapshot IDで照会する。確定済みsnapshotの再送は元のACKを返すだけで二重更新しない。

世代記録済み、または参加session履歴があるaccountは全player-state snapshotで`runtimeAuthority`を必須とする。Goldやinventoryだけの変更も対象となる。旧PUTはこのaccountを拒否する。GETも世代と参加権限のheaderがなければ409を返し、旧Pluginが状態を誤解して修復する前に止める。

オフラインは応答切れから推測しない。Pluginの全退出保存ACK後にcloseされた最終viewだけを保存済み残高として扱う。closeできない場合、またはその後に正本状態/残高が変わった場合は確認不能となり編集案を作成できない。初回導入直後や非空legacy accountは、移行・対応Pluginでの正常な参加と退出を終えるまで編集不可。

導入・明示移行は [[20_5.00-安全編集の導入と検証]] を参照する。

## 一括編集v2

`skilltree-operation-v1`は従来単件のみ、`skilltree-operation-v2`は単件とBATCHを受理する。新Webは`supportsBatch`を確認して一括操作を送る。BATCHは親operation一件の`changes_json`に順序付き1〜512変更を保持し、同じnodeの重複を拒否する。旧Pluginへ先頭の単件として配信しない。receipt、世代・版数、lease、取消、再送、offline案は全体で一件として扱う。

Webプロフィールの`currentAccount.connection`は本人閲覧時だけ含め、公開プロフィールと管理者の他人閲覧ではnullにする。

一括対応の`view.editorVersion=2`も確認する。旧Pluginのオフライン保存viewは、server本体をv2へ更新しただけで一括対応済みと扱わず、対応Pluginへの参加で評価情報を更新してから利用する。

明示退出済みのv2保存viewがあれば、server停止中もoffline案を保存できる。稼働情報の期限切れで即時確定は許可せず、次回参加時のlive runtime・所有session・世代・条件のclaim検証を必須とする。
