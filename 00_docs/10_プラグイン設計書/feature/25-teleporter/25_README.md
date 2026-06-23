# 25-teleporter

このディレクトリは `feature/teleporter` の設計書です。
採番・命名・参照ルールは [[README]] に従います。

## 対象実装パス

- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/command/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/event/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/gui/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/model/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/repository/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/service/*`
- `src/main/java/io/github/maaasu/astralRecord/feature/teleporter/view/*`
- Plugin data folder: `waystones.yml`

## ドキュメント一覧（推奨順）

1. [[25_0.00-概要]]
2. [[25_1.00-モデル定義]]
3. [[25_2.00-ユースケース]]
4. [[25_3.00-索引]]
5. [[25_4.00-統合フロー]]
6. [[25_5.00-例外・ログ・運用]]
7. [[25_9.00-未決事項]]

## 依存 feature

- `player`: オンラインプレイヤー、選択中アカウント、権限 `permission`
- `account`: ウェイストーン解除状態のアカウント単位管理
- `currency`: ロック解除時のゴールド残高確認と消費
- `world`: 同一ワールド判定と共通テレポート処理
- `menu`: GUI 共通のホットバー閉じるボタン仕様
- API `account-waystone`: `00_docs/20_API設計書/feature/25-waystone`
- DB `dbo.account_waystone_unlock`: `00_docs/40_Database設計書/table-definitions/AstralRecord/dbo.account_waystone_unlock.md`

## 更新ルール（変更時に必ず更新する章）

- コマンド引数、権限、YAML 形式の変更:
  - [[25_1.00-モデル定義]]
  - [[25_3.03-コマンド]]
  - [[25_5.00-例外・ログ・運用]]
- ロック解除、ゴールド消費、API 契約の変更:
  - [[25_1.00-モデル定義]]
  - [[25_3.02-サービス]]
  - [[25_3.04-リポジトリ]]
  - [[25_4.00-統合フロー]]
- ワールド上の表示、packet 表示、クリック判定の変更:
  - [[25_1.00-モデル定義]]
  - [[25_3.01-イベント]]
  - [[25_3.07-GUI・View]]
  - [[25_4.00-統合フロー]]
- テレポーター GUI の表示内容、ページング、クリック動作の変更:
  - [[25_2.00-ユースケース]]
  - [[25_3.07-GUI・View]]
  - [[25_4.00-統合フロー]]

## 実装メモ

- 2026-06-23: API / DB 側には `account-waystone` 契約と `dbo.account_waystone_unlock` が存在する。Plugin 側の `feature/waystone` は削除済みのため、本 feature は Plugin 側の再実装設計として扱う。
- 本設計では、ユーザー依頼上の「プレイヤー単位の解除状態」を、既存 DB / API 契約に合わせて「選択中アカウント単位の解除状態」として扱う。Minecraft ユーザー単位で共有する場合は [[25_9.00-未決事項]] で別途判断する。
