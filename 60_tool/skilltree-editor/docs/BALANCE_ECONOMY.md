# 統合バランスラボ: 経済・育成計算

`BalanceEconomy.Estimate(catalog, request, combatSummary, token)` は、固定されたマスタスナップショットからドロップ、強化、経験値、売却収入、移動を含む時間を推定する。入出力は `JsonObject`。ファイルや稼働サーバーは参照しない。

## 入力

`catalog.masters[]` は `{path, kind, revision, content}`。`kind` は `MasterDataService.Kind` と同じ `mob`、`item`、`loot_table`、`loot_pool`、`class`、`recipe` 等を使う。`request.enemy={mobId,level}`、`request.mode=strict|approximate`、`request.economy` へ次の値を指定できる。

| 項目 | 意味 |
| --- | --- |
| `targetItemId`, `quantity` | 討伐で集めるアイテムと個数。省略時は装備を所有済みとして強化だけ計算する |
| `enhanceItemId`, `fromEnhanceLevel`, `targetEnhanceLevel`, `transcendenceRank`, `orbItemId`, `orbOwned` | 強化対象と試行条件。オーブのスロット・ランク適格性も確認する。`orbOwned` は既存の所持数 |
| `dropRatePercent`, `luck` | ドロップ増加率（100が等倍）と直接ドロップだけに効く幸運値。未指定は戦闘ビルドの値、明示指定は設定値 |
| `sellItemIds` | 討伐後に売却するアイテム。収集対象と同一なら売却を除外する |
| `moveSeconds`, `waitSeconds`, `resupplySeconds`, `killSeconds`, `timeBudgetSeconds` | 戦闘1回ごとの移動・待機、固定補給時間、戦闘時間の上書き |
| `accountUuid`, `playerLevel`, `targetPlayerLevel`, `classId`, `classLevel`, `targetClassLevel` | 必要経験値の計算条件 |
| `recipeId` | レシピ原稿の診断。SHOP参照経路を指定できないため総費用には含めない |

`combatSummary.successRate` は0〜1の勝率、`meanElapsedSeconds` は1戦闘試行あたりの平均秒数。収集の `expectedKills`、`medianKills`、`p90Kills` は**勝利討伐数ではなく試行回数**を表す。負け・時間切れの試行では報酬と経験値を得ない。`request.economy.timeBudgetSeconds` を明示した場合だけ予算内の目標収集確率を `collection.probabilityWithinBudget` に返す。

## 計算根拠

- `MobRepository.java` のレベルプロファイルと同様に、基礎 `drops` にレベル固有 `drops` を再帰的に重ねる。配列は丸ごと置換する。直接 `drops.items` は行ごとの独立確率。`luckAffected` の既定値は true で、有効率は `clamp(rate × (1+max(0,luck)/100) × max(0,dropRatePercent)/100, 0, 100)`。
- ルートテーブルは `rolls` 回、各プールは行を独立確率 `clamp(rate × dropRatePercent/100,0,100)` で判定した後、成功行数が `pick` を超えると一様に間引く。`pick` は重みではない。抽選数量は両端を含む整数一様分布。1プール12行以下では成功部分集合と選択組合せを列挙して厳密に求める。`rolls` は20以下。超過は `strict` で `unsupported`、プール超過だけは `approximate` でseed固定の標本抽選とする。
- アイテム数量200以下では1戦闘あたりの数量分布から期待試行数を再帰式で、中央値/p90を分布の畳み込みの二分探索で求める。数量200超は `strict` で `unsupported`、`approximate` で正規近似を出す。到達率0は `unreachable`。
- 強化は装備の次レベルの `successRate`（0〜1）を使い、失敗時は `NONE`、`DECREASE_ONE`、`SET_LEVEL` のマスタ値で遷移する。期待試行数は吸収Markov連立方程式、中央値/p90は遷移分布で求める。試行ごとにオーブを1個消費する。`orb.effect.chargeSaleValue=true` の場合だけ `max(0,orb.saleValue)` ゴールドを消費する。オーブ未指定では適格性と費用が未確定であると診断し、費用と収支をnullで返す。超越は実定義の`overrides.enhance`をランク順に重ねた強化上限・レベル定義を使う。
- `growth` は `MasterAnalyticsService.PlayerExperienceForNextLevel` と `ClassExperienceForNextLevel` を再利用する。Pluginのレベル差補正（1Lv差ごと5%、最低10%）を適用して丸め、その後にビルドの経験値率を適用して再び丸める。プレイヤーLvが上がるたびに獲得量を更新し、余剰EXPを持ち越して必要勝利数を求め、勝率で割って期待戦闘試行数とする。装備・勝率・討伐時間は開始時の固定条件で、育成途中の戦闘性能変化は再シミュレーションしない。転生、途中経験値、職業履歴、クエスト報酬、死亡時のEXP損失は扱わない。必要EXPは現在Lv到達直後から目標Lv到達直後までの値である。`classPath` は職業の解放Lv、前提職、管理者・コマンド制限を示す。前提職の進捗は `economy.classProgress` で指定できる。
- 売却額は `SellService` と同じく、売却可能品の非負 `saleValue × 数量`。`gold.income` は `収集試行数 ×（勝率 × money平均 + 指定した売却品の期待額）`、`expense` は `強化期待試行数 × オーブ使用時のゴールド費用`。`gold.scope=directGoldOnly`、各 `lines[].basis` と `completeness` は推定の根拠・未算入を示す。ショップ価格はオーブ調達費と混同しない。
- `time.totalSeconds` は**収集対象の期待戦闘試行数**について、戦闘・移動・待機・補給を加えた値。収集対象とオーブが同じ場合はその個数の調達時間を含み、`orbStockCompletionProbability` で収集数と既存所持数以内の強化達成率を表示する。最小必要数未満なら `unreachable`。別アイテムのオーブ調達は同時ドロップ相関や他入手元を確定できないため加えず、既存所持数がp90試行数未満なら `ORB_SUPPLY_SEPARATE` を返す。

`recipe` マスタを実行時に読むのは現在 `ShopRecipeRepository` の `category: SHOP` に限定され、ショップ項目が `recipeId` で参照した場合だけ `ShopService` が追加通貨・素材費を適用する。現行の `85.shared.recipe` の `ALCHEMY` はこの経路では実行されず、`requiredLevel`、`successRate`、`failAction` も現在のショップ実行では評価されない。ラボはこれを調達可能な制作経路として数えない。

## 実在マスタの例

`midgard_grassboar` Lv.1 は直接 `10a00001` を80%、数量2〜3で落とし、さらに `normal_enemy_common_table_tier_1` を1回抽選する。同テーブルの強化プールでは `40a00003` が4%、`40a00001` が6%、`40a00002` が2%、`pick:1`。等倍条件で `40a00003` の実効採用率は約3.8416%となる。プールには別の支援プールもあり、両プールは独立である。`40a00003`（テュールのオーブ）の売却価格は80だが、`chargeSaleValue` がないため使用時の追加ゴールドは0。これはオーブ自体の調達費が0である意味ではない。

強化済み装備のスタート状態、所持オーブ、ショップでの代替入手、他の敵の討伐、移動ルート、湧き待ちの相関は入力マスタだけでは確定できない。表示では診断と仮定を添え、未算入の調達費を総額0として扱わない。
