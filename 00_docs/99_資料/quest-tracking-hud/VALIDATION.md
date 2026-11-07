# クエスト追跡HUDの検証記録

作業基点: `8ddf6eab097d0434c43e7a30d922d539cd9d7a7a`。配備・本番変更、プラグイン採番は実施しない。採番は開始側でリベース後に行う。

## 描画経路

- Java: `QuestHudGlyphs` が専用compact字形の正の送り幅を負spaceで相殺する。絶対x・行・字形幅をRGBへ符号化し、GUI・射影・負ascent領域・色の複合ガードを満たす頂点だけを左上へ再構成する。既存ActionBar合成後の末尾に追加するため、戦闘行の幅や中央寄せを変更しない。
- Java 75/84/88/97.1: 公式クライアント資産をSHA-1照合し、4つの頂点シェーダーの通常本文を保持。GLSL/SPIR-VのGUI/WORLD/SEE_THROUGH計8経路をコンパイル。CPU 14,400頂点、独立EGL llvmpipe 2,160頂点で位置と非対象ガードを確認した。97.1のEGL試験だけは頂点index名をGLのものへaliasし、原文はSPIR-Vとして別途コンパイルした。
- Bedrock: 専用TIPキー `geyseropt:astralrecord_quest_hud:` をJSON UIへ保持する。chat_stackの既存ペーパードール・座標・経過日数の後、チャットの前へ28px欄を挿入し、文字を左揃えで表示する。通常TIP・ActionBar・Title・Sidebarは更新しない。
- Bedrockは互換マーカーと必須資産、接続別パック登録、強制配布設定、TIP送信APIを確認した場合だけ有効。バックエンド接続の同一性を照合し、切替・hide・更新途絶で解除する。

## 再現コマンド

リポジトリルートで実行する。Java 21の完全なJDK、Maven、PowerShellが必要。プレビューに限りPillowを使用する。

```sh
mvn -f 10_plugin/AstralRecord/pom.xml test
mvn -f 10_plugin/AstralRecordProxy/pom.xml test
mvn -f 10_plugin/AstralRecordGeyserExtension/pom.xml test
dotnet build 20_api/AstralRecordApi/AstralRecordApi/AstralRecordApi.csproj
python3 .codex/skills/astralrecord-code/scripts/check_plugin_resources.py --repo-root .
python3 .codex/skills/astralrecord-plugin-test/scripts/validate_test_traceability.py
python3 50_resourcepack/scripts/generate-quest-hud-java.py --check --preview /tmp/quest-java-static.png
python3 50_resourcepack/scripts/preview-quest-hud-bedrock.py --preview /tmp/quest-bedrock-static.png
pwsh -NoProfile -File 50_resourcepack/scripts/build-resourcepack.ps1 -OutputDirectory /tmp/quest-pack-first
pwsh -NoProfile -File 50_resourcepack/scripts/build-resourcepack.ps1 -OutputDirectory /tmp/quest-pack-second
```

`deploy-debug.ps1` は配備入口を呼ばず、ASTから `Publish-ResourcePacks` 関数だけをロードしてローカル出力先へ実行し、生成manifestの `java.questTrackingHud = "1"` を確認した。両版アーカイブは同じ入力で2回生成し、SHA-256一致、JSON解析、重複entryなし、専用資産収録、保管用資産なしを確認した。

| 配布物 | SHA-256 | entry数 |
|---|---|---|
| Java zip | `bba8703dd8e1b154dad4e01f563f361cb009a558e3e4dc3b56251c398306a14f` | 2356 |
| Bedrock mcpack | `1c92939d1dd732a9116c5db4fce1a76ac058934c626fb80c1424861ba0e7b202` | 1793 |

## テストと既存制限

- 本体JUnit 1,049件が成功（失敗・エラー・skipなし）。固定fixtureによる追跡選択・進捗変化・内部IDの代替・報酬処理中の一時除去と巻き戻し・再受領・セッション切替・account破棄の一時診断も成功し、ソースとコンパイル済みクラスを除去した。
- API buildは警告・エラー0。旧manifestと新capabilityのJSON往復を確認した。Proxy 74件・Geyser 36件が警告なしで成功。消去失敗→復旧、設定変更後のhide、再接続identityの分離、消去待ちより新しい表示の優先を確認した。
- 本体compileの既存 `GuideCompletionRewardRepository.RewardUnavailableException` のserialVersionUID警告は、同じJDK・基点のソースでも再現した。作業起因の警告はない。
- 設計トレーサビリティ検査は、基点でも変更後でも `MobSkillRegistryTest` の19行・32行が参照する `feature/12-Mob/12_6-Mobスキル追加ガイド.md` が見つからない同一の既存2件で失敗する。今回のテストソースや設計入力への新規指摘はない。
- 新Bedrock UIと挿入定義は公開JSON UIスキーマを通過した。既存 `hud_screen.json` 全体のslash overrideとflip_bookは同スキーマの非対応構文が残る。

第1回独立レビューの解除再試行指摘 `AR-CODE-001` は修正済み。修正担当から独立した第2回レビューで修正と全体差分を再確認し、新規指摘なし。正規レビュー記録は完了状態で形式検証を通過した。

## プレビューと未確認範囲

両スクリプトの画像は配布パック資産と描画定義から生成した静的プレビューであり、実プレイ画像や実クライアント撮影ではない。Javaはcompact bitmapの実字形、Bedrockは同bitmapをネイティブ書体の代用品にした配置図で確認する。画像生成AIは使っていない。

Java・統合版の実クライアント起動・撮影は未実施。実クライアント固有の書体、GUI scale、各解像度、端末のセーフエリア、チャット展開、シェーダー併用、Geyser実接続の表示を確認済みとは扱わない。Mojangのコアシェーダー上書きは非サポート機能のため、対象クライアント追加時に公式資産との差分とコンパイル・描画を再検証する。
