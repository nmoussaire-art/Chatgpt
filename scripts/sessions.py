import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all(); spx,ndx=S["SPX"],S["NDX"]
print("=== 21. NUMBER OF TRADING SESSIONS IN THE HELD WEEK ===")
print("    (2026 trade week = 4 sessions: Tue 9/8, Wed 9/9, Thu 9/10, Fri 9/11)\n")
for s in [spx,ndx]:
    byn={}
    for e,x,r in s.weekly_returns():
        i,j=s.days.index(e),s.days.index(x)
        byn.setdefault(j-i,[]).append(r)
    print(f"  -- {s.name}")
    for n in sorted(byn):
        if len(byn[n])<10: continue
        d=sz.describe(byn[n])
        print(f"     {n}-session week: n={d['n']:4d} win={d['win']*100:5.1f}% mean={d['mean']*100:+6.3f}% med={d['median']*100:+6.3f}% sd={d['sd']*100:5.2f}%")
    # holiday-shortened weeks specifically
    print()
print("=== 22. ALL SEPTEMBER 4-SESSION (Labor-Day-shortened) WEEKS ===")
for s in [spx,ndx]:
    xs=[]
    for e,x,r in s.weekly_returns():
        i,j=s.days.index(e),s.days.index(x)
        if j-i==4 and x.month==9 and x.day<=14 and x.year<=2025: xs.append(r)
    if len(xs)>=5:
        d=sz.describe(xs)
        print(f"  {s.name:4s} {sz.fmt(d)}")
