import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all(); spx,ndx=S["SPX"],S["NDX"]

def ld_ret(s,y):
    ld=sz.labor_day(y); e=s.prev_session(ld); x=s.last_session_in(ld,ld+dt.timedelta(4))
    if not e or not x or e>=x or (ld-e).days>5: return None
    return e,x,s.ret(e,x)

print("=== 5. CALENDAR CONFIGURATION: Labor Day date ===")
print("2026 Labor Day = Sep 7 = the LATEST possible date (occurs when Sep 1 is a Tuesday)\n")
bydate={}
for y in range(1975,2026):
    r=ld_ret(spx,y)
    if r: bydate.setdefault(sz.labor_day(y).day,[]).append((y,r[2]))
for day in sorted(bydate):
    xs=[v for _,v in bydate[day]]; d=sz.describe(xs)
    print(f"  Labor Day = Sep {day}:  n={d['n']:2d}  win={d['win']*100:5.1f}%  mean={d['mean']*100:+6.2f}%  med={d['median']*100:+6.2f}%  min={d['min']*100:+6.2f}%  max={d['max']*100:+6.2f}%")

print("\n  --- The Sep-7 analogue years in detail (SPX) ---")
for y,v in bydate[7]:
    e,x,_=ld_ret(spx,y); print(f"    {y}  {e} -> {x}  {v:+7.2%}")
xs=[v for _,v in bydate[7]]
d=sz.describe(xs, universe=[r for _,_,r in spx.weekly_returns()])
print(f"    SPX Sep-7 set: {sz.fmt(d)}")
print(f"    t={d['t']:+.2f} p={d['p']:.3f} permutation p={d['perm_p']:.3f}")
print("\n  --- same set, NDX (1996+) ---")
for y,_ in bydate[7]:
    r=ld_ret(ndx,y)
    if r: print(f"    {y}  {r[0]} -> {r[1]}  {r[2]:+7.2%}")
xn=[ld_ret(ndx,y)[2] for y,_ in bydate[7] if ld_ret(ndx,y)]
if len(xn)>=4: print(f"    NDX Sep-7 set: {sz.fmt(sz.describe(xn))}")
else: print(f"    NDX Sep-7 set: n={len(xn)} mean={sum(xn)/len(xn)*100:+.2f}% -- too few for CI")

print("\n=== 6. WHERE IN SEPTEMBER IS THE WEAKNESS? (SPX daily, 1975-2025) ===")
import collections
agg=collections.defaultdict(list)
days=spx.days
for i in range(1,len(days)):
    d=days[i]
    if d.month==9 and d.year<=2025:
        agg[(d.day-1)//7].append(spx.px[d]/spx.px[days[i-1]]-1)
lbl={0:"Sep 1-7",1:"Sep 8-14",2:"Sep 15-21",3:"Sep 22-28",4:"Sep 29-30"}
for k in sorted(agg):
    x=agg[k]; m=sum(x)/len(x)
    print(f"  {lbl[k]:10s} n={len(x):4d} sessions  avg daily {m*100:+.4f}%  cumulative avg {m*len(x)/51*100:+.2f}%/yr  win={sum(1 for v in x if v>0)/len(x)*100:.1f}%")

print("\n=== 7. WEEK BEFORE SEPTEMBER TRIPLE WITCHING ===")
print("(upcoming week ends Fri 2026-09-11; Sep triple witching = Fri 2026-09-18)")
for s,lo in [(spx,1975),(ndx,1996)]:
    xs=[]
    for y in range(lo,2026):
        tw=sz.triple_witching(y,9)
        # week that ends the Friday BEFORE opex week
        exit_=s.last_session_in(tw-dt.timedelta(11), tw-dt.timedelta(7))
        entry=s.last_session_in(tw-dt.timedelta(18), tw-dt.timedelta(14))
        if entry and exit_ and entry<exit_: xs.append(s.ret(entry,exit_))
    d=sz.describe(xs, universe=[r for _,_,r in s.weekly_returns()])
    print(f"  {s.name:4s} pre-opex wk  {sz.fmt(d)}  perm p={d['perm_p']:.3f}")
    # and the opex week itself for contrast
    xs2=[]
    for y in range(lo,2026):
        tw=sz.triple_witching(y,9)
        entry=s.last_session_in(tw-dt.timedelta(11), tw-dt.timedelta(7))
        exit_=s.last_session_in(tw-dt.timedelta(4), tw)
        if entry and exit_ and entry<exit_: xs2.append(s.ret(entry,exit_))
    print(f"  {s.name:4s} opex week    {sz.fmt(sz.describe(xs2))}")
