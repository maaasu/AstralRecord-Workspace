# 26-login-bonus

API `login-bonus` 機能の設計をまとめる。

## 目次

| 区分 | ドキュメント |
|:--|:--|
| 概要 | [[26_0.00-概要]] |
| モデル定義 | [[26_1.00-モデル定義]] |
| エンドポイント仕様 | [[26_3.00-索引]] |

## 変更時の同期対象

- API: `LoginBonusController` / `LoginBonusClaimRepository` / `LoginBonusClaimModels`
- DB: `dbo.login_bonus_claim`
- Plugin: `feature/loginbonus` の GUI 表示・受取処理
