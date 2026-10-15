# B05中央統合・静的検証

原2bd3134bd41045dcb48815dcbcb49c6106978c41→7d0177187へcherry-pick。自種13fileを確認、中央/既存master/他batchを変更しない成果を受入。BarkBeetle/MossFox各1Executorを保持・登録し、同じ参照へ既存destroy listener clearCasterStateとonDisable nullguard stopを合成。既存callback順番とB01〜B04の接続を維持。

validate_integrated_batches.py: 全885YAML/1147namespace IDs/10技/40新profile/15新素材、E01/E02既存4種15実効profile保持PASS。resource check359log/969message PASS。JDK構文解析は中央+2Executorの3fileでPASS（semantic compileなし）。

B05素材をNEW_MATERIALS.csv final列へ反映: 木色の殻35%×1/sale180、コケ色の毛35%×1/sale180、森の香り袋8%×1/sale500。通常35%は既存グラスボア毛皮例、希少8%は初期10%より抑えた設定を維持。用途実装は装備担当。

COMBAT_COMPARISON.csv/mdは10種40profileに更新。キノカブトは通常攻撃なし（normal interval0/normal DPS0）で散弾140%/CD3秒。コケ煙はMagic40%×3pulseを総倍率1.2/CD8秒として単体の保守的上限へ加算。近接/遠隔/魔法別のPlayer防御は同じ抽象Dと仮定し、実装備/職業で別途確認。煙中の通常攻撃停止・移動回避・追加AoE対象を含まない実戦未確認の値。

バッチ独立レビュー記録はB05に保持。中央接続・比較スクリプトにも独立レビューを実施。MavenはB06到着後にまとめる。前回1022成功はB01〜B04/E01時点で、現在E02/B05の全体コンパイルは未実施。実server/client、Registryの本番masterロード、spawn配置も未実施。forestの実world/位置/密度は根拠確認待ちでspawner未変更。

重いbuild枠は使わず、develop/push/version/deploy/server/DB/port操作なし。中央資料・管理表の所有を保持。
