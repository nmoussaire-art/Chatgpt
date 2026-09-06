import sys, datetime as dt, bisect, math; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all(); spx,ndx,vix=S["SPX"],S["NDX"],S["VIX"]

def ma_at(s,d,n):
    i=bisect.bisect_right(s.days,d)-1
    if i<n-1: return None
    return sum(s.px[x] for x in s.days[i-n+1:i+1])/n

def feat(s,entry):
    keys=sorted(s.week_last); k=entry.isocalendar()[:2]
    if k not in s.week_last: return None
    i=keys.index(k)
    if i<2: return None
    m200=ma_at(s,entry,200)
    if not m200: return None
    f={"dist200":s.px[entry]/m200-1,
       "w1":s.ret(s.week_last[keys[i-1]],entry),
       "w2":s.ret(s.week_last[keys[i-2]],s.week_last[keys[i-1]])}
    if entry in vix.px:
        vd=[d for d in vix.days if d<=entry][-252:]
        lv=sorted(vix.px[d] for d in vd)
        f["vixpct"]=sum(1 for x in lv if x<vix.px[entry])/len(lv)
        f["vix"]=vix.px[entry]
    return f

CUR={"dist200":0.0808,"w1":0.0009,"w2":0.0049,"vixpct":0.05}
print("=== 18. CLOSEST HISTORICAL ANALOGUES ===")
print("Similarity over: distance from 200dma, prior 2 weekly returns, VIX percentile.")
print("Universe = every Sep 8-14 exit week 1990-2025 (VIX data starts 1990). All shown, ranked.\n")
cands=[]
for e,x,r in spx.weekly_returns():
    if x.month==9 and 8<=x.day<=14 and 1990<=x.year<=2025:
        f=feat(spx,e)
        if not f or "vixpct" not in f: continue
        dist=(abs(f["dist200"]-CUR["dist200"])/0.06 + abs(f["w1"]-CUR["w1"])/0.015
              + abs(f["w2"]-CUR["w2"])/0.015 + abs(f["vixpct"]-CUR["vixpct"])/0.30)
        nr=ndx.ret(e,x) if e in ndx.px and x in ndx.px else None
        cands.append((dist,x.year,e,x,r,nr,f))
cands.sort()
print(f"  {'yr':4s} {'entry':10s} {'exit':10s} {'SPX':>7s} {'NDX':>7s}  {'d200':>6s} {'wk-1':>6s} {'wk-2':>6s} {'VIXpct':>6s} {'score':>5s}")
for d,y,e,x,r,nr,f in cands[:12]:
    print(f"  {y} {e} {x} {r*100:+6.2f}% {('%+6.2f%%'%(nr*100)) if nr is not None else '    n/a':>7s}  "
          f"{f['dist200']*100:+5.1f}% {f['w1']*100:+5.2f}% {f['w2']*100:+5.2f}% {f['vixpct']*100:5.0f}% {d:5.2f}")
top=[c for c in cands[:10]]
xs=[c[4] for c in top]
print(f"\n  Top-10 analogues SPX: n=10 win={sum(1 for v in xs if v>0)/10*100:.0f}% mean={sum(xs)/10*100:+.2f}% med={sorted(xs)[5]*100:+.2f}%")
ns=[c[5] for c in top if c[5] is not None]
print(f"  Top-10 analogues NDX: n={len(ns)} win={sum(1 for v in ns if v>0)/len(ns)*100:.0f}% mean={sum(ns)/len(ns)*100:+.2f}%")
print("  (NOTE: n=10 hand-ranked neighbours -> illustrative only, NOT an independent test.)")

print("\n=== 19. ADVERSE-MOVE PROFILE (intra-week drawdown from entry close) ===")
print("Uses daily closes only (no intraday); worst close-to-close mark against the entry.")
for s,lab,lo in [(spx,"SPX",1975),(ndx,"NDX",1996)]:
    mae_l,mae_s=[],[]
    for e,x,r in s.weekly_returns():
        if x.month==9 and 8<=x.day<=14 and x.year<=2025:
            i,j=s.days.index(e),s.days.index(x)
            path=[s.px[d]/s.px[e]-1 for d in s.days[i+1:j+1]]
            if path: mae_l.append(min(path)); mae_s.append(max(path))
    ml=sorted(mae_l); ms=sorted(mae_s)
    print(f"  {lab}: long  worst mark: median {ml[len(ml)//2]*100:+.2f}%  25th pct {ml[len(ml)//4]*100:+.2f}%  worst {ml[0]*100:+.2f}%")
    print(f"  {lab}: short worst mark: median {ms[len(ms)//2]*100:+.2f}%  75th pct {ms[3*len(ms)//4]*100:+.2f}%  worst {ms[-1]*100:+.2f}%")

print("\n=== 20. HEADLINE NUMBERS FOR THE VERDICT ===")
for s,lab,lo in [(spx,"SPX",1975),(ndx,"NDX",1996)]:
    a=[r for e,x,r in s.weekly_returns() if x.month==9 and 8<=x.day<=14 and x.year<=2025]
    b=[]
    for y in range(lo,2026):
        ld=sz.labor_day(y); e=s.prev_session(ld); x=s.last_session_in(ld,ld+dt.timedelta(4))
        if e and x and e<x and (ld-e).days<=5: b.append(s.ret(e,x))
    u=[r for _,_,r in s.weekly_returns()]
    da,db,du=sz.describe(a),sz.describe(b),sz.describe(u)
    blend_w=(da['win']+db['win'])/2; blend_m=(da['mean']+db['mean'])/2
    print(f"  {lab}: Sep8-14 win {da['win']*100:.1f}% mean {da['mean']*100:+.2f}% | LaborDay win {db['win']*100:.1f}% mean {db['mean']*100:+.2f}% "
          f"| unconditional win {du['win']*100:.1f}% mean {du['mean']*100:+.2f}%")
    print(f"       -> blend of the two competing seasonal framings: win {blend_w*100:.1f}%  mean {blend_m*100:+.2f}%")
