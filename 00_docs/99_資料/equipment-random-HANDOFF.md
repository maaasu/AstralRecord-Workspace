# 装備上位シリーズ・生成時乱数 HANDOFF

## 担当境界

スキーマ担当の統合先は `codex/equipment-scalar-random`、作業ツリーは `E:\AstralRecord-Worktrees\equipment-scalar-random`。Plugin/API/Web/Editor・契約文書・共通回帰テストを所有する。装備定義、ショップ、素材・レシピ、worktree管理表は編集しない。分担用の `equipment-random-api-web` と `equipment-random-editor` の差分は統合先へ適用済み。

## 予約済み装備ID

既存developと確認時点のworktree群に同番の装備ファイルがないことを確認し、上位57品用に `20a00400`～`20a00456` を予約した。管理表への登録は親の単独担当が行う。素材IDの `10a00400` などとは別カテゴリである。

| 装備Lv | 名前候補 | エンチャント枠 | 武器7品 | 防具12品 |
|:--|:--|--:|:--|:--|
| 20 | ラピス | 2 | 20a00400～20a00406 | 20a00407～20a00418 |
| 25 | ルミナ | 3 | 20a00419～20a00425 | 20a00426～20a00437 |
| 30 | アストラ | 4 | 20a00438～20a00444 | 20a00445～20a00456 |

各武器範囲はソード、ハンマー、スピア、ショートボウ、ロングボウ、ワンド、スタッフの順。各防具範囲は近接・遠隔・魔法の3系列、各系列内は頭・胸・脚・足の順とする。各段階の防具セット3件は独立した文字列IDであり、この57品の採番には含めない。具体的なセットIDは装備担当が親の登録情報と照合して決める。

既存エリヴァは装備Lv15・エンチャント1枠、武器7種、防具3系列×4部位。上位防具は各系列の2部位・4部位セット効果を必須とし、既存の2部位主攻撃+6／4部位命中+2を基準に、本体・個体差・増枠と合わせて抑制する。防具の個体差は武器より狭くする。シリーズ名と数値の最終調整は装備担当が行える。

## 確定random契約

正本は [装備YAMLスキーマ](../../40_filebase/10.features.item/20.equipment/docs.equipment.YAMLスキーマ定義.md) と [Pluginモデル定義](../10_Plugin設計書/feature/04-item/04_1-モデル定義.md)。対象は `equipment.stats[]` の基礎ステータスだけで、強化差分・ルーン・セット効果へ自動拡張しない。

```yaml
equipment:
  stats:
    - status: MELEE_ATTACK
      type: FLAT
      value: 100
      random: {offsetMin: -8, offsetMax: 8, step: 1}
    - status: FIRE_DAMAGE_INCREASE
      type: FLAT
      value: 0
      random: {offsetMin: 0.1, offsetMax: 10, step: 0.1, zeroChance: 0.95}
```

- `random` なしは既存scalar/rangeの解釈を完全維持する。既存固定値の一括ランダム化はしない。
- マスターは単一の数値 `value`（数値文字列も可）と明示 `random` を併記する。`value` のrangeオブジェクト・範囲文字列（同値範囲も含む）・行直下min/maxとの併記は禁止。offset・step・確率は数値型で指定する。
- `offsetMin`、`offsetMax`、`step` は必須。`zeroChance` は任意、既定0。下端以下上端、stepは正、差はstepの整数倍、両端を含む候補数は最大100万。
- 基準値・両offset・stepは有限かつ数値として小数4桁以内。末尾ゼロは許容する。API/Editorでは.NET decimalで表せる値を使う。各入力と基準値＋offsetの両端はdoubleとの十進往復で値が変化せず、候補が複数ある場合stepは最大絶対端点のdouble隣接間隔以上でなければならない。
- `zeroChance` は有限の0～1。先にこの確率で0を選び、残りで `value + offsetMin + n * step` の各候補を等確率抽選する。zeroChanceが正なら通常区間に0を含めない。上記属性例は95%で0、残る5%で0.1～10.0を0.1刻みに抽選する。
- Pluginが新規個体生成時に一度だけ抽選し、BigDecimalの十進候補をdoubleへ変換せず同じ文字列を既存の個体min/maxへ保存する。snapshot再送、再ログイン、マスター再読込、表示、戦闘でこの抽選を再実行しない。既存個体は保存済みmin/maxを維持する。
- APIはマスター基準値を既存同様 `value: {min: "100", max: "100"}` へ正規化し、randomを別項目で保持する。PluginはこのAPI応答の同値numeric形式を許容する。マスター入力のrange許容を意味しない。
- ショップ・Webのマスター表示とEditor分析は抽選しない。個体の主値は保存済み値を表示する。Editorは0偏重を含む期待値を計算し、保存往復でrandom・未知キー・コメントを保持する。

## 属性の単位

実在する属性増加キーは `FIRE_DAMAGE_INCREASE`、`ICE_DAMAGE_INCREASE`、`LIGHTNING_DAMAGE_INCREASE`。`type: FLAT` の数値10は10%増加で、戦闘倍率は `1 + increase / 100`。`SCALAR` は従来どおり係数であり、この表示単位と混同しない。

## 素材・ショップの後続依存

素材・レシピIDはこの資料で新規確定しない。モブ統合担当の最新 `field-mobs-lv50-integration` 内 `PLAN.md` / `NEW_MATERIALS.csv` と実装結果を受領してから接続する。上位防具は既存鉱石素材を優先し、Lv30は胸を最多として採集素材の大量消費先にする。数量案は調査報告を初期値とし、装備担当が産出・必要時間と照合して調整する。

ショップの商品領域は論理slot0～27（7列×4行）、操作は実slot45/53。防具は左3列・中央空き1列・右3列で1ページ2シリーズ、武器はslot0～6と14～20へ各7種を配置し、各直下の1行を将来オフハンド用に空ける。1ページ目はエリヴァ＋Lv20、2ページ目はLv25＋Lv30が初期案。

## 検証と完了処理

統合先でPlugin/API/Web/Editorを全体直列で検証し、最終ソースで以下を確認した。実サーバー・DB・ポート操作、push、develop統合、deployは行っていない。

- Plugin: Maven全体テスト1,022件成功。既存の未変更クラスのserialVersionUID警告1件のみ。
- API: solution build成功、全体テスト875件成功。SQL Server統合テスト実行フラグは無効。既存依存パッケージのNU1902/NU1903警告あり。
- Web: solution build成功（警告0）、全体テスト159件成功。
- Editor Client: npm ci（脆弱性0）、lint、全体テスト270件、production build成功。
- Editor Server: 全体テスト303件、solution build成功（警告0）。
- Pluginテスト追跡・リソース整合性、レビュー記録2件の構造検証、Git差分・staged文字化け検査を実施。独立レビューの全3指摘を修正し、再確認済み。最後のEditorテスト構文・期待値修正も独立レビューで確認した。

ローカルcommit hashは最終報告を参照。装備・ショップ・素材・レシピYAML、worktree管理表はこの変更に含めない。
