# 防衛戦 API

チャンネル別の設定、日次または手動開催、Plugin報告、段階報酬を管理する。防衛戦のゲーム内規則は `00_docs/10_Plugin設計書/feature/42-defense/` を参照する。運営が未決数値と報酬を入力するまでは開始しない。

## 認証

全経路で通常の `X-Api-Key` を要する。管理経路は追加で `Defense:WebKey` と一致する `X-Defense-Web-Key`、`actor_user_uuid`、要求ごとのManagementDB `web_admin` 再認可を要する。Web側の本人確認・CSRFはWebで行う。Plugin経路は `Defense:RuntimeKey` と一致する `X-Defense-Runtime-Key` と、登録済みゲームチャンネル `serverId` を要する。専用キーは通常APIキーと分けて設定し、未設定時は503。レスポンスは `Cache-Control: no-store`。

## 管理経路

| 操作 | 経路 | 要求・応答 |
|---|---|---|
| 取得 | GET `/api/defense/admin/channels/{serverId}?actor_user_uuid={uuid}` | `DefenseChannelResponse` |
| 保存 | PUT 同経路 | `DefenseChannelUpdateRequest` → `DefenseChannelResponse`。`expectedRevision`不一致は409 |
| 手動開始 | POST 同経路`/start` | `{operationId,expectedRevision}` → `DefenseRunResponse`。最新30秒の受付状態がOPENかつ登録者1人以上でなければ409 |
| 停止 | POST 同経路`/stop` | `{operationId,runId,reason}` → `DefenseRunResponse`。進行中はSTOP_REQUESTEDにしPluginのABORTED報告で終端。開始前は直ちにABORTED |
| 履歴 | GET 同経路`/runs?limit=20` | `DefenseRunResponse[]`。limit 1～100 |

`DefenseChannelResponse` は `serverId,revision,dailyEnabled,dailyStartTimeJst,configuration,missingSettings,nextScheduledAtUtc,activeRun,latestRun,registrationStatus,registeredCount` を返す。時刻はJSTの `HH:mm`。受付報告が30秒より古い場合は `registrationStatus:"UNKNOWN",registeredCount:null`。初回設定のrevisionは0、保存後は1から増える。設定変更は既存開催の`configuration`スナップショットを変更しない。

`configuration` は以下を保持し、未設定や不正な値は `missingSettings` にJSONパスで列挙する。PUT時には空欄をnullとして保存できる。開始時には一覧が空である必要がある。

| 項目 | 型・条件 |
|---|---|
| `worldName` | string、専用ワールド |
| `coreX/Y/Z`, `entryX/Y/Z` | 有限double |
| `spawnPoints` | `{x,y,z}` の非空配列、座標は有限double |
| `coreMaxHealth`, `coreDefense` | double。前者>0、後者>=0 |
| `baseSpawnCount`, `spawnIntervalSeconds`, `maxSpawnsPerWave`, `maxSpawnsPerTick`, `maxAliveEnemies` | 正整数。tick<=wave<=alive |
| `retaliationSeconds`, `retaliationRange` | 正整数・正double |
| `playerSpawnCoefficient`, `playerHealthCoefficient`, `playerDamageCoefficient`, `postMaxLevelHealthCoefficient`, `postMaxLevelDamageCoefficient` | 正double |
| `registrationLeadSeconds`, `reconnectGraceSeconds` | 正整数 |
| `reviveProtectionSeconds`, `reviveShieldMode` | 非負整数、`EMPTY`または`FULL` |
| `rewardEveryLevels`, `minimumActiveSeconds` | 正整数、非負整数 |
| `rewardTiers` | `{requiredDefenseLevel,orbItemId,orbQuantity}` の非空配列。Lv1からの一意な段階下限、登録済みorb item、正数量 |

適格なENEMY MobがMasterDataDBに0件なら `eligibleMobs` を不足として返す。防衛レベルはLv1から戦闘開始後30秒ごとに+1、最大30人、1～10人は倍率1固定。これら確定値はチャンネル設定にしない。

## Plugin経路

| 操作 | 経路 | 要求・応答 |
|---|---|---|
| 指示取得 | GET `/api/defense/runtime/{serverId}?instanceId={guid}` | `{serverId,configuration,pendingRun,activeRun,missingSettings,serverTimeUtc}`。各run内のconfigurationが開催固定値 |
| 受付報告 | POST `/api/defense/runtime/{serverId}/registration` | `{instanceId,operationId,status,registeredCount}`。statusはCLOSED/OPEN/PREPARING、人数0～30 |
| 開催報告 | POST `/api/defense/runtime/{serverId}/runs/{runId}/report` | `{instanceId,operationId,event,participants,defenseLevel,reason}` → `DefenseRunResponse` |
| 段階報酬 | POST `/api/defense/runtime/{serverId}/runs/{runId}/rewards` | `{instanceId,operationId,rewards}` → `DefenseRewardReceipt[]` |

`DefenseRunResponse` は `runId,serverId,source,status,scheduledAtUtc,createdAtUtc,startedAtUtc,endedAtUtc,instanceId,configuration,defenseLevel,participantCount,reason`。sourceはMANUAL/DAILY。statusはPENDING/RUNNING/STOP_REQUESTED/ENDED/ABORTED/SKIPPED。管理startはPENDINGを受理しただけで、実際の開始成功はSTARTED報告時に確定する。

日次開催は設定時刻の `registrationLeadSeconds` 前からPENDINGを発行し、Pluginは事前受付と準備を行う。STARTEDは定刻以降、本人とアカウントの組が実在する異なる1～30人を固定する。0人と途中参加は拒否。Pluginは同じ起動世代 `instanceId` を使う。`HEARTBEAT` では防衛レベルと各参加者の `activeSeconds,isAfk,eliminated` を送る。停止要求はruntime GETでSTOP_REQUESTEDとして見える。ENDED/ABORTEDで終端し、旧世代の確定済み段階報酬の再送は終端後も受ける。報告が途絶えたrunは復帰猶予以上かつ最低60秒でABORTEDにし、過去戦闘を新世代へ再開させない。

報酬の `rewards` 要素は `{userUuid,accountId,requiredDefenseLevel,activeSeconds,isAfk}`。`requiredDefenseLevel` は実際の到達段階Lvで、`1 + k × rewardEveryLevels` だけ許可する。固定参加者、報告済み防衛レベル以下、必要参加秒数、非AFKを検証する。各段階では最大の `requiredDefenseLevel <= 到達段階` のtierを選び、run/account/段階の一意キーで一度だけ保存する。受取メールIDとオーブ種類・数量を固定し、再送で再抽選しない。通常Mob報酬はPluginで抑止する。

確定報酬はManagementDBに保存後、既存の `player_mail_delivery` に期限なしの実item添付メールを固定IDで配送する。配送失敗はバックグラウンドで再試行し、切断・満杯で報酬を消さない。メールの既存の未受取・受取原子処理を利用する。

## 競合と失敗

要求不正は400、権限不足は403、対象チャンネル/run不存在は404、設定不足・revision・run状態・operationId衝突は409。専用キー未設定は503。手動開催の同じoperationIdは同じrunを返す。runtime report/rewardの同じoperationIdは内容ハッシュも一致した場合だけ冪等として扱う。日次はチャンネルとJST日付で一意、同チャンネルのactive runは最大1。サーバーが定刻までに接続しなかった日次分はSKIPPEDを記録し、後から遡って開始しない。
