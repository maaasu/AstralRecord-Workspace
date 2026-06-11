# docs-review レビュー結果

- レビュー対象: `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord`
- skill: `docs-review`
- 指摘修正数 / 指摘数: `1 / 3`
- 完了状態: 未完了

## 指摘一覧

### AR-DOC-001 [高] 記録更新契約の入力・保存先・失敗時責務が確定していない
- 種別: `不足` / `未確定事項`
- 対象: `00_docs/10_プラグイン設計書/feature/21-adventurerecord/3-メソッド仕様/21_3.00-メソッド仕様.md:21`
- 関連箇所: `00_docs/10_プラグイン設計書/feature/21-adventurerecord/0-概要/21_0.00-概要.md:11` / `00_docs/10_プラグイン設計書/feature/21-adventurerecord/2-ユースケース/21_2.00-ユースケース.md:15` / `00_docs/10_プラグイン設計書/feature/21-adventurerecord/4-統合フロー/21_4.00-統合フロー.md:10` / `00_docs/10_プラグイン設計書/feature/21-adventurerecord/5-例外・ログ・運用/21_5.00-例外・ログ・運用.md:7`
- 根拠: 概要では「他 feature が記録値を更新する場合も、更新契約は本 feature で管理する」としているが、メソッド仕様の `recordEvent` は「イベント種別と対象 account」「保存またはキャッシュ」「失敗を返す」までしか定義していない。ユースケースも `mob / player など` と例示するだけで、対象イベント種別、入力項目、集計項目への対応、永続化かキャッシュか、重複反映の扱い、呼び出し元が失敗をどう扱うべきかを確定していない。
- 問題: 本 feature が正本とするはずの更新契約が、呼び出し側 feature から見て実装可能な API 契約になっていない。
- 影響: Mob 撃破数、死亡回数、プレイ時間などの更新元が feature ごとにばらつき、二重加算・未反映・障害時の主処理継続判断の不一致が起きる可能性がある。
- 修正方針: `記録イベント反映` に、イベント種別一覧、必須入力、更新対象フィールド、保存先、冪等性キーまたは重複防止方針、失敗戻り値、呼び出し元の推奨扱いを追加する。必要なら `9-未決事項` を作り、未確定のイベント範囲を集約する。
- 修正対象候補: `21_2.00-ユースケース.md` / `21_3.00-メソッド仕様.md` / `21_4.00-統合フロー.md` / `21_5.00-例外・ログ・運用.md`
- 修正可否: `設計判断待ち`
- 修正状態: `未修正`

### AR-DOC-002 [中] 他プレイヤー閲覧の許可条件と表示制限が仕様化されていない
- 種別: `不足` / `未確定事項`
- 対象: `00_docs/10_プラグイン設計書/feature/21-adventurerecord/2-ユースケース/21_2.00-ユースケース.md:9`
- 関連箇所: `00_docs/10_プラグイン設計書/feature/21-adventurerecord/3-メソッド仕様/21_3.00-メソッド仕様.md:8` / `00_docs/10_プラグイン設計書/feature/21-adventurerecord/5-例外・ログ・運用/21_5.00-例外・ログ・運用.md:5`
- 根拠: ユースケースは「許可された導線」と「閲覧可能な項目だけ」を前提にしているが、許可判定の主体、導線、対象 account 解決方法、閲覧可能項目の範囲が定義されていない。メソッド仕様も `targetAccountId` 検証と基本情報取得のみで、閲覧者 `viewerUuid` と対象 account の関係、非公開項目のフィルタ、対象未解決時の表示メッセージを扱っていない。
- 問題: 他プレイヤーの冒険記録を誰がどの範囲で見られるかが設計書から確定できない。
- 影響: GUI で個人情報に近いプレイ統計を過剰表示したり、逆に本来表示すべき公開項目を実装者判断で隠したりする可能性がある。対象 account 解決失敗時の UX もばらつく。
- 修正方針: 他プレイヤー閲覧ユースケースに、許可導線、権限または関係性、公開フィールド、非公開フィールド、対象未解決時の処理を明記する。`AdventureRecordGuiState.viewerUuid` を使った許可判定が必要なら、メソッド仕様にも判定条件を追加する。
- 修正対象候補: `21_2.00-ユースケース.md` / `21_3.00-メソッド仕様.md` / `21_5.00-例外・ログ・運用.md`
- 修正可否: `設計判断待ち`
- 修正状態: `未修正`

### AR-DOC-003 [中] メソッド仕様が処理条件・取得項目・失敗時挙動の必須粒度を満たしていない
- 種別: `形式・命名` / `不足`
- 対象: `00_docs/10_プラグイン設計書/feature/21-adventurerecord/3-メソッド仕様/21_3.00-メソッド仕様.md:8`
- 関連箇所: `00_docs/10_プラグイン設計書/README.md:98` / `00_docs/10_プラグイン設計書/feature/21-adventurerecord/5-例外・ログ・運用/21_5.00-例外・ログ・運用.md:3`
- 根拠: ルート README は、メソッド仕様の処理内容について「判定条件・取得項目・委譲先・例外時挙動をまとめて記載する」と定めている。しかし `冒険記録取得` は `targetAccountId` 検証、基本情報取得、記録データ集計の抽象記述に留まり、どの feature から何を取得するか、account 未解決・基本情報未取得・集計失敗時にどの戻り値やログになるかを記載していない。`冒険記録 GUI 表示` も GUI スロット配置の内訳、戻る先未指定時の扱い、取得失敗時のプレイヤー向け表示がない。
- 問題: メソッド仕様が実装者の判断に依存し、例外・ログ・運用の方針をメソッド単位に落とし込めていない。
- 影響: GUI 表示、取得失敗、戻る操作、ログ出力の実装が feature 間で統一されず、後続の docs-fix や実装作業で設計意図を補う必要が出る。
- 修正方針: 各メソッドの処理番号内に、判定条件、取得元 feature、取得項目、委譲先、戻り値、失敗時ログまたはプレイヤー向けメッセージを追加する。
- 修正対象候補: `21_3.00-メソッド仕様.md` / `21_5.00-例外・ログ・運用.md`
- 修正可否: `自動修正可`
- 修正状態: `修正済み`

## 未確認・質問

### Q-DOC-001
- 関連指摘: `AR-DOC-001`
- 確認事項: 冒険記録の更新イベント種別は、初期設計として `mobKillCount` / `deathCount` / `totalPlayTimeSeconds` の 3 系統だけを扱うのか、職業・クエスト・取引など将来項目も含む汎用イベントとして定義するのか。
- 判断が必要な理由: `recordEvent` の入力モデル、冪等性、保存先、呼び出し元 feature の責務が変わるため。

### Q-DOC-002
- 関連指摘: `AR-DOC-002`
- 確認事項: 他プレイヤーの冒険記録は一般プレイヤーにも公開するのか、パーティー・フレンド・管理者などの限定導線だけにするのか。
- 判断が必要な理由: 許可判定、公開項目、GUI 導線、対象 account 解決失敗時のメッセージが変わるため。

## 修正スキル入力サマリ

- 自動修正候補: `AR-DOC-003`
- 要確認: `AR-DOC-001`, `AR-DOC-002`, `Q-DOC-001`, `Q-DOC-002`
- 推奨修正順: `AR-DOC-001` -> `AR-DOC-002` -> `AR-DOC-003`
- 対象範囲: `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord`

## 確認した範囲

- 読んだ設計書:
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\README.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\21_README.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\0-概要\21_0.00-概要.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\1-モデル定義\21_1.00-モデル定義.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\2-ユースケース\21_2.00-ユースケース.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\3-メソッド仕様\21_3.00-メソッド仕様.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\4-統合フロー\21_4.00-統合フロー.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord\5-例外・ログ・運用\21_5.00-例外・ログ・運用.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\02-account\02_README.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\02-account\1-モデル定義\02_1.00-モデル定義.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\03-player\03_README.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\03-player\1-モデル定義\03_1.00-モデル定義.md`
  - `E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\09-menu\09_README.md`
- 実行した検査: `docs_structure_audit.py E:\AstralRecord-Workspace\00_docs\10_プラグイン設計書\feature\21-adventurerecord`
- 検査結果: 構造監査の指摘なし

## ソース参照

ソースコードは参照していません。
