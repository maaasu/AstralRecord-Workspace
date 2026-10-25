"use strict";
const $ = id => document.getElementById(id);
const canvas = $("map"), ctx = canvas.getContext("2d");
const colors = {covered:"#91bd62",existing:"#56b6dc",uncovered:"#e4954c",budget:"#dc7849",protected:"#d3b876",water:"#345d80",unsafe:"#65625a",steep:"#9c8070",unsupported:"#9d90a9",disconnected:"#bd7ba0",unknown:"#d3505e",eligible:"#6c9453"};
const habitatColors = {grass:"#779c49",forest:"#315d35",snow:"#c4d6d6",dry:"#cbb980",water:"#365f83",other:"#8c8c7c",unknown:"#cc5763"};
const habitatNames = {grass:"草原",forest:"森林",snow:"雪・氷雪地形",dry:"乾燥地",water:"水域",other:"その他の地形",unknown:"未解析"};
let data, plan, scale=1, offsetX=0, offsetY=0, viewWidth=0, viewHeight=0, selected=null, drag=null, moved=false;
let terrainImage, reasonImage, habitatImage, indexByGrid=new Map();
function escapeHtml(value){return String(value).replace(/[&<>"']/g,c=>({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;","'":"&#39;"}[c]));}
function surfaceColor(c){
  const s=c.surface||"";
  if(c.status!=="ok") return "#be4c59";
  if(s.includes("water")) return "#345c87";
  if(s.includes("lava")) return "#d47732";
  if(s.includes("snow")||s.includes("ice")) return "#d3dfdd";
  if(s.includes("cherry")) return "#d4abc6";
  if(s.includes("leaves")) return "#3e713d";
  if(s.includes("sand")) return "#c6bb8e";
  if(s.includes("stone")||s.includes("gravel")) return "#848a7d";
  return habitatColors[c.habitat]||"#6c8053";
}
function makeImage(colorFor){
  const t=data.terrain, out=document.createElement("canvas");out.width=t.width/t.cellSize;out.height=t.height/t.cellSize;
  const paint=out.getContext("2d");
  data.cells.forEach((c,i)=>{paint.fillStyle=colorFor(c,i);paint.fillRect((c.x-t.minX)/t.cellSize,(c.z-t.minZ)/t.cellSize,1,1);});return out;
}
function terrainColor(c){
  const base=surfaceColor(c);
  if(c.candidateY==null||c.habitat==="water")return base;
  const east=data.cells[indexByGrid.get(`${c.x+4},${c.z}`)],south=data.cells[indexByGrid.get(`${c.x},${c.z+4}`)];
  const slope=((east?.candidateY??c.candidateY)-c.candidateY)+((south?.candidateY??c.candidateY)-c.candidateY);
  const shade=Math.max(.66,Math.min(1.18,1-slope*.035));
  return `rgb(${[1,3,5].map(i=>Math.min(255,Math.round(parseInt(base.slice(i,i+2),16)*shade))).join(",")})`;
}
function resize(){const r=canvas.getBoundingClientRect();viewWidth=r.width;viewHeight=r.height;canvas.width=Math.round(r.width*devicePixelRatio);canvas.height=Math.round(r.height*devicePixelRatio);draw();}
function reset(){if(!data)return;scale=Math.min((viewWidth-44)/data.terrain.width,(viewHeight-44)/data.terrain.height);offsetX=(viewWidth-data.terrain.width*scale)/2;offsetY=(viewHeight-data.terrain.height*scale)/2;draw();}
function screen(x,z){return [(x-data.terrain.minX)*scale+offsetX,(z-data.terrain.minZ)*scale+offsetY];}
function world(x,z){return [(x-offsetX)/scale+data.terrain.minX,(z-offsetY)/scale+data.terrain.minZ];}
function zoom(factor,cx=viewWidth/2,cy=viewHeight/2){const newScale=Math.min(12,Math.max(.15,scale*factor)),ratio=newScale/scale;offsetX=cx-(cx-offsetX)*ratio;offsetY=cy-(cy-offsetY)*ratio;scale=newScale;draw();}
function focus(x,z){offsetX=viewWidth/2-(x-data.terrain.minX)*scale;offsetY=viewHeight/2-(z-data.terrain.minZ)*scale;draw();}
function circle(x,y,z,r,color,fill,label){const [sx,sz]=screen(x,z);ctx.beginPath();ctx.arc(sx,sz,r*scale,0,Math.PI*2);ctx.strokeStyle=color;ctx.lineWidth=1.2;if(fill){ctx.fillStyle=fill;ctx.fill();}ctx.stroke();if(label){ctx.font="11px Segoe UI";ctx.fillStyle=color;ctx.fillText(label,sx+7,sz-7);}}
function draw(){
  ctx.setTransform(devicePixelRatio,0,0,devicePixelRatio,0,0);ctx.clearRect(0,0,viewWidth,viewHeight);if(!data)return;
  const t=data.terrain, layer=$("layer").value;ctx.imageSmoothingEnabled=false;
  ctx.drawImage(layer==="reasons"?(reasonImage||terrainImage):layer==="habitat"?habitatImage:terrainImage,offsetX,offsetY,t.width*scale,t.height*scale);
  const filter=$("reason-filter").value;
  if(filter&&plan){ctx.fillStyle="#07110bd9";data.cells.forEach((c,i)=>{if(plan.reasonCodes[i]!==filter){const [x,z]=screen(c.x,c.z);ctx.fillRect(x,z,t.cellSize*scale+.5,t.cellSize*scale+.5);}});}
  if(layer==="terrain"&&plan){ctx.fillStyle="#e8a15b65";data.cells.forEach((c,i)=>{if(["uncovered","budget"].includes(plan.reasonCodes[i])){const [x,z]=screen(c.x,c.z);ctx.fillRect(x,z,t.cellSize*scale,t.cellSize*scale);}});}
  const radii=$("show-radii").checked;
  if($("show-existing").checked)data.existing.forEach(p=>{if(radii)circle(p.x+.5,p.y,p.z+.5,p.radiusMeters,"#69cce065",null);mark(p.x+.5,p.z+.5,"#79daee",3);});
  if(plan) plan.proposals.forEach((p,i)=>{if(radii)circle(p.x+.5,p.y,p.z+.5,p.radiusMeters,"#d2f39f9a",null);mark(p.x+.5,p.z+.5,"#ddffa5",3.2);if(scale>1.5){const [x,z]=screen(p.x,p.z);ctx.fillStyle="#f4ffe4";ctx.font="10px Segoe UI";ctx.fillText(String(i+1),x+5,z-5);}});
  const s=data.world.spawnLocation;circle(s.x,s.y,s.z,data.safeRadius,"#f6d687","#dfbc4a22");mark(s.x,s.z,"#ffe5a2",6);const [sx,sz]=screen(s.x,s.z);ctx.fillStyle="#fff3c3";ctx.font="bold 12px Segoe UI";ctx.fillText("開始地点",sx+10,sz+4);
  if(selected!==null){const c=data.cells[selected], [x,z]=screen(c.x,c.z);ctx.strokeStyle="#fff";ctx.lineWidth=2;ctx.strokeRect(x,z,t.cellSize*scale,t.cellSize*scale);}
  ctx.strokeStyle="#dbe8d333";ctx.lineWidth=1;ctx.strokeRect(offsetX,offsetY,t.width*scale,t.height*scale);
  const metres=scale>3?20:scale>1?50:100;ctx.fillStyle="#edf3e2";ctx.fillRect(viewWidth-24-metres*scale,viewHeight-25,metres*scale,2);ctx.font="10px Segoe UI";ctx.fillText(`${metres} m`,viewWidth-24-metres*scale,viewHeight-31);
}
function mark(x,z,color,r){const [sx,sz]=screen(x,z);ctx.beginPath();ctx.arc(sx,sz,r,0,Math.PI*2);ctx.fillStyle=color;ctx.fill();ctx.strokeStyle="#172219";ctx.lineWidth=1;ctx.stroke();}
function selectCell(i){selected=i;const c=data.cells[i], code=plan?plan.reasonCodes[i]:c.reasonCode;$("detail").innerHTML=`<strong>X ${c.x} / Z ${c.z}</strong><p>${escapeHtml(data.reasons[code])}</p><p>環境: ${habitatNames[c.habitat]}<br>足場: ${c.safeCount??0} / 16列<br>足元Y: ${c.candidateY??"—"}<br>高度接続: ${c.connected?"確認済み（推定）":"未確認"}</p><p>地表: ${escapeHtml(c.surface||"—")}<br>バイオーム: ${escapeHtml(c.biome||"—")}</p>${c.reason?`<p>解析: ${escapeHtml(c.reason)}</p>`:""}`;draw();}
function selectProposal(p,i){focus(p.x,p.z);$("detail").innerHTML=`<strong>配置案 #${i+1} · ${habitatNames[p.habitat]}</strong><p>X ${p.x} / Y ${p.y} / Z ${p.z}<br>出現半径 ${p.radiusMeters}m（現行 ${p.originalRadiusMeters}m）</p><ul>${p.spawnMobs.map(m=>`<li>${escapeHtml(m.name)} Lv.${m.level} / 重み ${m.weight}</li>`).join("")}</ul><p>${escapeHtml(p.reason)}</p><p>新たにカバー: ${p.newCoveredCells}区画<br>${p.masterUpdateRequired?"適用する場合はマスタの半径変更が必要":"既存マスタと同じ半径"}</p><p class="small">${escapeHtml(p.id)}</p>`;}
async function calculate(){
  $("calculate").disabled=true;$("download").disabled=true;$("busy").classList.remove("hidden");$("busy").textContent="足場と出現範囲を照合しています…";$("error").textContent="";
  try{const response=await fetch("/api/plan",{method:"POST",headers:{"Content-Type":"application/json"},body:JSON.stringify({radiusMeters:Number($("radius").value),maxSpawners:Number($("maximum").value)})});const result=await response.json();if(!response.ok)throw new Error(result.error||"計算に失敗しました");plan=result;
    reasonImage=makeImage((c,i)=>colors[plan.reasonCodes[i]]||"#888");$("count").textContent=plan.proposals.length;$("coverage").textContent=`${plan.coveragePercent}%`;$("uncovered").textContent=plan.uncoveredCells.toLocaleString();$("unknown").textContent=(plan.counts.unknown||0).toLocaleString();
    $("radius-note").textContent=`計算済み半径 ${plan.parameters.radiusMeters}m。新規分の同時存在上限の合計は最大${plan.maxAliveUpperBound}体（実際はプレイヤー数や周辺上限で制限）。負荷低減の実測値ではありません。`;
    $("reasons").replaceChildren();$("reason-filter").innerHTML='<option value="">すべて表示</option>';
    Object.entries(plan.counts).sort((a,b)=>b[1]-a[1]).forEach(([code,n])=>{const option=document.createElement("option");option.value=code;option.textContent=`${data.reasons[code]} (${n})`;$("reason-filter").append(option);const button=document.createElement("button");button.className="reason-row";const text=document.createElement("span"),dot=document.createElement("i");dot.className="dot";dot.style.backgroundColor=colors[code];text.append(dot,document.createTextNode(data.reasons[code]));const number=document.createElement("b");number.textContent=n.toLocaleString();button.append(text,number);button.addEventListener("click",()=>{$("reason-filter").value=code;$("layer").value="reasons";draw();});$("reasons").append(button);});
    $("proposal-list").replaceChildren();plan.proposals.forEach((p,i)=>{const button=document.createElement("button");button.className="proposal-item";button.innerHTML=`<strong>#${i+1} ${habitatNames[p.habitat]} · Lv.${p.spawnMobs[0].level}</strong><span>X ${p.x} / Y ${p.y} / Z ${p.z}</span>`;button.addEventListener("click",()=>selectProposal(p,i));$("proposal-list").append(button);});
    if(!plan.proposals.length)$("proposal-list").textContent="条件を満たす新規配置候補はありません。未配置の理由を確認してください。";
    selected=null;$("detail").textContent="地図上の地点やスポナーを選択すると、座標・モブ・選定理由を表示します。";
    $("download").disabled=false;draw();
  }catch(error){$("error").textContent=error.message;}finally{$("calculate").disabled=false;$("busy").classList.add("hidden");}
}
$("calculate").addEventListener("click",calculate);$("layer").addEventListener("change",draw);$("reason-filter").addEventListener("change",draw);$("show-radii").addEventListener("change",draw);$("show-existing").addEventListener("change",draw);
$("zoom-in").addEventListener("click",()=>zoom(1.4));$("zoom-out").addEventListener("click",()=>zoom(1/1.4));$("reset").addEventListener("click",reset);$("spawn").addEventListener("click",()=>{if(data){scale=Math.max(1.5,scale);focus(data.world.spawnLocation.x,data.world.spawnLocation.z);}});
$("download").addEventListener("click",()=>{if(!plan)return;const blob=new Blob([JSON.stringify(plan,null,2)],{type:"application/json"}),url=URL.createObjectURL(blob),a=document.createElement("a");a.href=url;a.download=`${plan.worldId}-spawner-preview.json`;a.click();setTimeout(()=>URL.revokeObjectURL(url),1000);});
canvas.addEventListener("wheel",event=>{event.preventDefault();if(!data)return;const r=canvas.getBoundingClientRect();zoom(event.deltaY<0?1.15:1/1.15,event.clientX-r.left,event.clientY-r.top);},{passive:false});
canvas.addEventListener("pointerdown",event=>{drag=[event.clientX,event.clientY];moved=false;canvas.setPointerCapture(event.pointerId);});
canvas.addEventListener("pointermove",event=>{if(!data)return;const r=canvas.getBoundingClientRect(),[wx,wz]=world(event.clientX-r.left,event.clientY-r.top);$("coordinates").textContent=`X ${Math.floor(wx)} / Z ${Math.floor(wz)}`;if(drag){const dx=event.clientX-drag[0],dy=event.clientY-drag[1];if(Math.abs(dx)+Math.abs(dy)>2)moved=true;offsetX+=dx;offsetY+=dy;drag=[event.clientX,event.clientY];draw();}});
canvas.addEventListener("pointerup",event=>{drag=null;if(moved||!data)return;const r=canvas.getBoundingClientRect(),px=event.clientX-r.left,pz=event.clientY-r.top;if(plan){const n=plan.proposals.findIndex(p=>{const [x,z]=screen(p.x+.5,p.z+.5);return Math.hypot(x-px,z-pz)<8;});if(n>=0){selectProposal(plan.proposals[n],n);return;}}const [x,z]=world(px,pz),t=data.terrain,gx=Math.floor((x-t.minX)/t.cellSize)*t.cellSize+t.minX,gz=Math.floor((z-t.minZ)/t.cellSize)*t.cellSize+t.minZ,i=indexByGrid.get(`${gx},${gz}`);if(i!==undefined)selectCell(i);});
canvas.addEventListener("pointercancel",()=>{drag=null;});
canvas.addEventListener("keydown",event=>{if(!data)return;if(["ArrowLeft","ArrowRight","ArrowUp","ArrowDown","+","-","Home"].includes(event.key)){event.preventDefault();if(event.key==="+")zoom(1.4);else if(event.key==="-")zoom(1/1.4);else if(event.key==="Home")reset();else{offsetX+=event.key==="ArrowLeft"?40:event.key==="ArrowRight"?-40:0;offsetY+=event.key==="ArrowUp"?40:event.key==="ArrowDown"?-40:0;draw();}}});
new ResizeObserver(resize).observe(canvas.parentElement);
async function init(){try{const response=await fetch("/api/data");if(!response.ok)throw new Error("地形データを取得できませんでした");data=await response.json();const t=data.terrain;data.cells.forEach((c,i)=>indexByGrid.set(`${c.x},${c.z}`,i));terrainImage=makeImage(terrainColor);habitatImage=makeImage(c=>habitatColors[c.habitat]);$("region").textContent=data.regionName;$("extent").textContent=`${t.width} × ${t.height}m · X ${t.minX}〜${t.minX+t.width} / Z ${t.minZ}〜${t.minZ+t.height}`;$("source").textContent=`取得: ${new Date(t.capturedAt).toLocaleString('ja-JP',{timeZone:'Asia/Tokyo'})} JST / ${data.cells.length.toLocaleString()}区画 / ${t.cellSize}m単位`;
$("inventory").textContent=data.inventoryStatus==="loaded"?`この解析範囲に重なる既存配置: ${data.existing.length}件。再配置の比較案です。既存配置への追加・削除は行いません。` : "既存配置ファイルは未読込です。既存配置との比較は未確認です。";data.notes.forEach(note=>{const li=document.createElement("li");li.textContent=note;$("notes").append(li);});resize();reset();await calculate();}catch(error){$("busy").textContent="読込に失敗しました";$("error").textContent=error.message;}}
init();
