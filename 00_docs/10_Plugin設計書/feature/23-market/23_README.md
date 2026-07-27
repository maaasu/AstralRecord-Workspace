# 23_README

`market` feature は Market API の出品一覧・詳細・account summary・価格見積・出品・購入・cancel を型付き model と短期 cache で集約する plugin 内 API facade である。

## 対象実装パス

- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/feature/market/*`
- `10_plugin/AstralRecord/src/main/java/io/github/maaasu/astralRecord/infrastructure/util/ApiRequestUtil.kt`

## ドキュメント一覧（推奨順）

1. [[23_0.00-概要]]
2. [[23_1.00-モデル定義]]
3. [[23_3.00-メソッド仕様]]
4. [[23_4.00-統合フロー]]
5. [[23_5.00-例外・ログ・運用]]

## 依存 feature

| feature | 依存内容 |
|:--|:--|
| infrastructure / API | HTTP request builder、Market API の業務判定・transaction |
| Gson / Java time | JSON model 変換、期限・cache TTL |

現在の plugin market 実装は GUI、command、player inventory、item、currency を直接呼ばない。これらは将来の呼び出し側であり、実装済み依存として扱わない。

## 更新ルール（変更時に必ず更新する章）

- Market API request／response field を変更した場合は [[23_1.00-モデル定義]]、[[23_3.00-メソッド仕様]] と API 設計書を同時更新する。
- endpoint／status code を変更した場合は [[23_3.00-メソッド仕様]] と [[23_5.00-例外・ログ・運用]]を更新する。
- cache TTL／invalidation を変更した場合は [[23_4.00-統合フロー]] と [[23_5.00-例外・ログ・運用]]を更新する。
- GUI／inventory 統合を追加した場合は新しいユースケース章と transaction 補償設計を追加する。
