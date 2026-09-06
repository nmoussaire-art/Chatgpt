import sys, datetime as dt, bisect, random, math; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all(); spx,ndx,vix=S["SPX"],S["NDX"],S["VIX"]

def two_up(s,entry):
    keys=sorted(s.week_last); k=entry.isocalendar()[:2]
    if k not in s.week_last: return None
    i=keys.index(k)
    if i<2: return None
    return s.ret(s.week_last[keys[i-1]],entry)>0 and s.ret(s.week_last[keys[i-2]],s.week_last[keys[i-1]])>0

print("=== 14. IS '2 UP WEEKS' A SEPTEMBER EFFECT OR A GENERAL EFFECT? ===")
print("    (SPX, all weekly trades 1975-2025, split by prior 2-week momentum)")
for s in [spx,ndx]:
    rows=[(e,x,r,two_up(s,e)) for e,x,r in s.weekly_returns()]
    rows=[r for r in rows if r[3] is not None]
    print(f"  -- {s.name}")
    for lbl,sel in [("ALL months",lambda x:True),("September only",lambda x:x.month==9),
                    ("Jan-Aug + Oct-Dec",lambda x:x.month!=9)]:
        t=[r for e,x,r,f in rows if sel(x) and f]
        fl=[r for e,x,r,f in rows if sel(x) and not f]
        dt_,df_=sz.describe(t),sz.describe(fl)
        print(f"     {lbl:20s} after 2 up wks: n={dt_['n']:4d} win={dt_['win']*100:5.1f}% mean={dt_['mean']*100:+6.3f}%"
              f"   | otherwise: n={df_['n']:4d} win={df_['win']*100:5.1f}% mean={df_['mean']*100:+6.3f}%"
              f"   | spread {(dt_['mean']-df_['mean'])*100:+.3f}%")
    print()

print("=== 15. OUT-OF-SAMPLE SPLIT: rule = 'Labor Day week after 2 up weeks' (SPX) ===")
def ld_rows(s):
    out=[]
    for y in range(s.start.year,2026):
        ld=sz.labor_day(y); e=s.prev_session(ld); x=s.last_session_in(ld,ld+dt.timedelta(4))
        if not e or not x or e>=x or (ld-e).days>5: continue
        out.append((y,e,x,s.ret(e,x),two_up(s,e)))
    return out
rows=ld_rows(spx)
for lo,hi,lbl in [(1975,1999,"DISCOVERY 1975-1999"),(2000,2025,"VALIDATION 2000-2025"),(1975,2025,"full")]:
    t=[r for y,e,x,r,f in rows if lo<=y<=hi and f]
    if len(t)>=3:
        d=sz.describe(t)
        print(f"  {lbl:22s} n={d['n']:2d} win={d['win']*100:5.1f}% mean={d['mean']*100:+6.2f}% med={d['median']*100:+6.2f}% "
              f"CI[{d['ci_lo']*100:+.2f}%,{d['ci_hi']*100:+.2f}%] worst={d['min']*100:+.2f}% best={d['max']*100:+.2f}%")
print("  years/returns:", [(y,round(r*100,1)) for y,e,x,r,f in rows if f])

print("\n=== 16. MULTIPLE-TESTING REALITY CHECK ===")
print("  Regime cuts examined: 6 flags x 2 setups x 2 indices = 24 tests.")
print("  At 95% confidence, ~1.2 cuts are expected to exclude zero by pure chance.")
print("  Cuts that actually excluded zero: 4 (all of them the SAME '2 up weeks' flag).")
print("  BUT those 4 cuts are highly correlated (overlapping years, SPX~NDX corr):")
ld_spx={y:r for y,e,x,r,f in ld_rows(spx)}
ld_ndx={y:r for y,e,x,r,f in ld_rows(ndx)}
com=sorted(set(ld_spx)&set(ld_ndx))
xs=[ld_spx[y] for y in com]; ys=[ld_ndx[y] for y in com]
mx,my=sum(xs)/len(xs),sum(ys)/len(ys)
cov=sum((a-mx)*(b-my) for a,b in zip(xs,ys))
cr=cov/math.sqrt(sum((a-mx)**2 for a in xs)*sum((b-my)**2 for b in ys))
print(f"     corr(SPX, NDX) on Labor Day weeks, {len(com)} common years = {cr:.2f}  -> NOT independent evidence")

print("\n  Permutation test of the exact rule (SPX Labor Day week AND 2 prior up weeks):")
allw=[r for _,_,r in spx.weekly_returns()]
t=[r for y,e,x,r,f in ld_rows(spx) if f]
p=sz.permutation_p(t, allw, reps=40000)
print(f"     n={len(t)} observed mean={sum(t)/len(t)*100:+.2f}%  vs all-week mean={sum(allw)/len(allw)*100:+.2f}%")
print(f"     permutation p = {p:.3f}   (Bonferroni over 24 cuts -> effective p ~ {min(1,p*24):.2f})")

print("\n=== 17. TRANSACTION COSTS (round trip) ===")
print("  Assumption: SPY/QQQ retail round trip = 1-2 bp spread + slippage; futures ~1bp; use 4 bp round trip as conservative.")
for lbl,vals in [("SPX Labor Day wk (all)",[r for y,e,x,r,f in ld_rows(spx)]),
                 ("SPX LD wk after 2 up wks",t),
                 ("SPX Sep 8-14 exit wk",[r for e,x,r in spx.weekly_returns() if x.month==9 and 8<=x.day<=14 and x.year<=2025])]:
    d=sz.describe(vals,cost_bps=4)
    print(f"  {lbl:26s} gross mean={d['mean']*100:+.3f}%  long net={d['mean_long_net']*100:+.3f}%  short net={d['mean_short_net']*100:+.3f}%")
    edge=abs(d['mean'])*100
    print(f"     {'':24s} |edge|={edge:.3f}%  vs weekly sd {d['sd']*100:.2f}%  -> need |mean|>~{d['sd']/math.sqrt(d['n'])*1.96*100:.2f}% for 95% sig at n={d['n']}")
