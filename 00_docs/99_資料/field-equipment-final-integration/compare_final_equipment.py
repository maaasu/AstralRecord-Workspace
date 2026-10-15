"""統合後の実YAMLから同種・同強化の装備比較を再生成する分析資料。"""
from pathlib import Path
import csv,io,json,runpy
HERE=Path(__file__).resolve().parent
MODEL=runpy.run_path(str(HERE.parent/'eriva-lv30-astra/compare_balance.py'),run_name='final_analysis_helpers')
ROOT=HERE.parents[2]
item=MODEL['item'];stat=MODEL['stat'];reference=MODEL['reference'];optimize=MODEL['optimize'];compact=MODEL['compact']
old_ids=[73,74,75,77,78,79,80]+list(range(86,98))
series=[('eriva',old_ids),('lapis',list(range(400,419))),('lumina',list(range(419,438))),('astra',list(range(438,457)))]
def generate():
 rows=[]
 for index in range(19):
  datas=[(name,item(ROOT,f'20a{ids[index]:05d}')) for name,ids in series]
  primary=datas[0][1]['equipment']['stats'][0]['status']
  for name,data in datas:
   equip=data['equipment']
   assert equip['slot']==datas[0][1]['equipment']['slot']
   if index<7:assert equip['tag']==datas[0][1]['equipment']['tag']
   for level in (0,15,30):
    rows.append(dict(series=name,item_id=data['id'],slot=equip['slot'],tag=equip.get('tag',''),level=level,primary_status=primary,
                     minimum=stat(data,primary,level,'min'),expected=stat(data,primary,level,'mean'),maximum=stat(data,primary,level,'max'),enchant_slots=equip['enchant']['maxSlots']))
  for level in (0,15,30):
   expected=[stat(d,primary,level,'mean') for n,d in datas]
   assert expected==sorted(expected),(index,level,expected)
 buffer=io.StringIO(newline='');writer=csv.DictWriter(buffer,fieldnames=list(rows[0]),lineterminator='\n');writer.writeheader();writer.writerows(rows)
 (HERE/'CROSS_SERIES_EQUIPMENT.csv').write_bytes(buffer.getvalue().encode('utf-8-sig'))
 residual=MODEL['LV25_RESIDUAL'];mob=MODEL['MOB25'];base={};upper={}
 for name,ids in series:
  weapon=item(ROOT,f'20a{ids[0]:05d}');armor=[item(ROOT,f'20a{i:05d}') for i in ids[7:11]]
  attack={'eriva':6,'lapis':6,'lumina':8,'astra':10}[name];slots=weapon['equipment']['enchant']['maxSlots']
  base[name]={str(lv):compact(reference(weapon,armor,lv,'mean',attack,residual,mob)) for lv in (0,15,30)}
  upper[name]=optimize(weapon,armor,attack,residual,mob,slots)
 for level in ('0','15','30'):
  ttks=[base[name][level]['TTK'] for name,ids in series]
  assert ttks==sorted(ttks,reverse=True),ttks
 report=dict(model='固定したLv25仮想残差・同じ敵・剣+近接4防具、実YAMLの期待値、無属性',
             residual=residual,mob=mob,baseline=base,optimized=upper,
             assumptions=MODEL['run'].__doc__ or '無刻印基準:会心なし/命中92%/最終1.3/skill込み係数1.6。極値:上級刻印の非重複effect組合せ/基礎会心5.5%・会心倍率150%/通常60%・skill40%/ルーンATTACK5。',
             limits=['旧エリヴァの二重min/max区間の平均中心と新規保存scalarの分布は異なる。','3属性は別属性で無属性に足さない。','全職・全アクセサリ・攻撃劣化・shield・操作時間・実入手量・100戦は未確認。','極値は指定刻印候補と構成内の探索結果で、全ビルドの上限保証ではない。'])
 (HERE/'CROSS_SERIES_COMBAT.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8',newline='\n')
 print(json.dumps({'rows':len(rows),'ttk_at_30':[base[n]['30']['TTK'] for n,ids in series],'optimized_ttk':[upper[n]['TTK'] for n,ids in series]},ensure_ascii=False))
if __name__=='__main__':generate()
