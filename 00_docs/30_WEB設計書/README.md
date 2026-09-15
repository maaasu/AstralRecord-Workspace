# 30_WEB設計書

AstralRecord Web (`30_web/AstralRecordWeb`) の画面、認証、画面遷移を管理する設計書です。

## feature 一覧

| feature | 内容 |
|---|---|
| `feature/01-web-auth` | Minecraft で発行したコードを使用する Web ログイン、ログアウト、マイページ、プロフィール公開・検索 |
| `feature/02-release-note` | Markdownリリースノートの一覧・詳細表示とAPI同期 |

API 契約は `00_docs/20_API設計書`、DB 定義は `00_docs/40_Database設計書` を正として参照します。

## ホームのサーバー状況

参加情報と同じ `PublicSite:JavaServerAddress` / `JavaServerPort` へJava Edition Server List Pingを送り、公開中・参加人数・定員・最終確認時刻を表示する。接続拒否は閉鎖中、DNS/タイムアウト/不正応答など確認不能は別表示とし人数は「—」。ホーム初期表示でも取得し、表示中は30秒ごとに更新、サーバー側は15秒キャッシュを共有する。接続先はWebの要求パラメーターから変更できない。

ホーム以外ではヘッダーの公式Discord左側にホームリンクを表示する。プレイヤー一覧とマーケットはログイン中のメニューへ配置する。
