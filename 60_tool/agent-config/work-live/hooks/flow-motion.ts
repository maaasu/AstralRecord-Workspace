import { reducedMotion, rippleStyles, stepMotionStyles } from './icons'

export function recent(now: number, at?: number): boolean {
  return at !== undefined && at <= now && now - at <= 6_000
}

export function flowMotionStyle(): string {
  return `<style>
.edge-dash{stroke-dasharray:6 6;animation:edge-dash 1.2s linear infinite}
.edge-tip{animation:edge-tip .6s linear infinite}
.card-halo{animation:card-halo 2s ease-out infinite}
.card-sheen{animation:card-sheen 2.4s linear infinite}
.status-dot{animation:status-dot 1.2s ease-in-out infinite}
.card-idle{opacity:.75}.idle-border{animation:idle-border 8s linear infinite}
.card-enter{animation:card-enter .5s cubic-bezier(.2,.8,.2,1) both}
.edge-reveal{animation:show 0s .6s both}
.edge-draw{stroke-dasharray:1;animation:edge-draw .6s cubic-bezier(.2,.8,.2,1) both}
.edge-badges{animation:fade .25s .85s cubic-bezier(.2,.8,.2,1) both}
.step-enter{animation:step-enter .3s cubic-bezier(.2,.8,.2,1) both}
.count-flash{animation:count-flash .65s ease-out}
.done-border{animation:done-border .8s ease-out both}
.done-pop{transform-box:fill-box;transform-origin:center;animation:done-pop .4s ease-out both}
.card-shake{animation:card-shake .4s linear}
.error-flash{animation:error-flash .4s linear both}
.card-enter .step-enter,.card-enter .count-flash{animation-delay:.25s}
.card-enter .done-pop,.card-enter .done-border,.card-enter .card-shake,.card-enter .error-flash{animation-delay:.5s}
.edge-after-draw{animation-delay:.6s}
@keyframes edge-dash{to{stroke-dashoffset:-12}}
@keyframes edge-tip{0%,65%,100%{opacity:.8}85%{opacity:1;stroke-width:.65}}
@keyframes card-halo{0%{stroke-width:1.5;opacity:.32}100%{stroke-width:7;opacity:0}}
@keyframes card-sheen{0%{transform:translateX(-70px);opacity:0}12%{opacity:1}85%{opacity:1}100%{transform:translateX(208px);opacity:0}}
@keyframes status-dot{50%{opacity:.3}}
@keyframes idle-border{to{stroke-dashoffset:-20}}
@keyframes card-enter{from{transform:translateX(-16px);opacity:0}to{transform:translateX(0);opacity:1}}
@keyframes show{from{opacity:0}to{opacity:1}}
@keyframes edge-draw{0%{stroke-dashoffset:1;opacity:1}99%{opacity:1}100%{stroke-dashoffset:0;opacity:0}}
@keyframes fade{from{opacity:0}to{opacity:1}}
@keyframes step-enter{from{transform:translateY(6px);opacity:0}to{transform:translateY(0);opacity:1}}
@keyframes count-flash{0%{fill:#FFFFFF}}
@keyframes done-border{0%{opacity:.95;stroke-width:4}100%{opacity:0;stroke-width:1}}
@keyframes done-pop{0%{transform:scale(.6);opacity:0}60%{transform:scale(1.15);opacity:1}100%{transform:scale(1);opacity:1}}
@keyframes card-shake{0%,100%{transform:translateX(0)}10%,35%,60%,85%{transform:translateX(-3px)}22%,47%,72%,95%{transform:translateX(3px)}}
@keyframes error-flash{0%,50%,100%{opacity:0}20%,70%{opacity:1;stroke-width:4}}
${rippleStyles}${stepMotionStyles}${reducedMotion}</style>`
}

export function flowMotionDefs(id: string): string {
  return `<filter id="${id}-glow" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.5"/></filter>` +
    `<linearGradient id="${id}-sheen"><stop stop-color="#FFFFFF" stop-opacity="0"/><stop offset=".55" stop-color="#FFFFFF" stop-opacity=".17"/><stop offset="1" stop-color="#FFFFFF" stop-opacity="0"/></linearGradient>` +
    `<clipPath id="${id}-band"><path d="M0 24V10Q0 0 10 0H198Q208 0 208 10V24Z"/></clipPath>`
}

export function runningParticles(id: string, edgeId: string, color: string): string {
  return ['0s', '-0.6s', '-1.2s'].map(begin => {
    const timing = `dur="1.8s" begin="${begin}" repeatCount="indefinite"`
    return `<g class="smil" opacity="0"><circle r="7" fill="${color}" fill-opacity=".46" filter="url(#${id}-glow)">` +
      `<animate attributeName="r" values="4;7" ${timing}/></circle><circle r="3.5" fill="#FFFFFF" fill-opacity=".8">` +
      `<animate attributeName="r" values="1.7;3.5" ${timing}/></circle>` +
      `<animate attributeName="opacity" values="0;.72;1;0;0" keyTimes="0;.16;.79;.96;1" ${timing}/>` +
      `<animateMotion ${timing}><mpath href="#${edgeId}"/></animateMotion></g>`
  }).join('')
}

export function returnParticle(id: string, edgeId: string, begin: number): string {
  const timing = `begin="${begin}s" dur=".8s"`
  return `<g class="smil" opacity="0" fill="#22C55E"><circle r="7" filter="url(#${id}-glow)"/><circle r="3.5"/>` +
    `<animate attributeName="opacity" values="0;1;1;0" keyTimes="0;.12;.8;1" ${timing}/>` +
    `<animateMotion ${timing} keyPoints="1;0" keyTimes="0;1" calcMode="linear"><mpath href="#${edgeId}"/></animateMotion></g>`
}
