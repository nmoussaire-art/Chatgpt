import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S = sz.load_all()

def ld_trade(s, year, entry_off=0, weeks=1):
    ld = sz.labor_day(year)
    entry = s.prev_session(ld)
    for _ in range(entry_off): entry = s.prev_session(entry)
    exit_ = s.last_session_in(ld, ld + dt.timedelta(4 + 7*(weeks-1)))
    if not entry or not exit_ or entry>=exit_: return None
    return s.ret(entry, exit_)

spx, ndx = S["SPX"], S["NDX"]
def block(s, ys):
    x=[ld_trade(s,y) for y in ys]; x=[v for v in x if v is not None]
    return x

print("=== 1. SUBPERIODS (SPX Labor Day week) ===")
for lo,hi,lbl in [(1975,1989,"1975-1989"),(1990,2004,"1990-2004"),(2005,2015,"2005-2015"),
                  (2016,2025,"2016-2025"),(1975,1999,"1975-1999 discovery"),(2000,2025,"2000-2025 validation"),
                  (2006,2025,"last 20y"),(2016,2025,"last 10y"),(2021,2025,"last 5y")]:
    x=block(spx,range(lo,hi+1)); d=sz.describe(x)
    print(f"  {lbl:22s} {sz.fmt(d)}")
print("\n=== NDX subperiods ===")
for lo,hi,lbl in [(1996,2010,"1996-2010"),(2011,2025,"2011-2025"),(2016,2025,"last 10y"),(2021,2025,"last 5y")]:
    x=block(ndx,range(lo,hi+1)); d=sz.describe(x)
    print(f"  {lbl:22s} {sz.fmt(d)}")

print("\n=== 2. NEIGHBOURING WEEKS (SPX, is the Labor Day week special?) ===")
def week_offset_ret(s, year, k):
    """k=0 -> Labor Day week; k=-1 -> week before; k=+1 -> week after."""
    ld = sz.labor_day(year) + dt.timedelta(7*k)
    entry = s.prev_session(ld)
    exit_ = s.last_session_in(ld, ld + dt.timedelta(4))
    if not entry or not exit_ or entry>=exit_ or (ld-entry).days>5: return None
    return s.ret(entry, exit_)
for k,lbl in [(-2,"2 wks before LD"),(-1,"week before LD"),(0,"LABOR DAY WEEK"),(1,"week after LD"),(2,"2 wks after LD (opex)"),(3,"3 wks after LD")]:
    x=[week_offset_ret(spx,y,k) for y in range(1975,2026)]; x=[v for v in x if v is not None]
    d=sz.describe(x); print(f"  {lbl:22s} {sz.fmt(d)}")

print("\n=== 3. ALTERNATIVE DEFINITIONS (SPX, does the rule survive perturbation?) ===")
alts = {
  "baseline (Fri->Fri)":        lambda y: ld_trade(spx,y),
  "entry 1 session earlier":    lambda y: ld_trade(spx,y,entry_off=1),
  "entry 2 sessions earlier":   lambda y: ld_trade(spx,y,entry_off=2),
  "hold 2 weeks":               lambda y: ld_trade(spx,y,weeks=2),
}
for lbl,f in alts.items():
    x=[f(y) for y in range(1975,2026)]; x=[v for v in x if v is not None]
    print(f"  {lbl:26s} {sz.fmt(sz.describe(x))}")

# ISO week number alternative
print("\n  -- ISO-week proxies (calendar-week rule instead of event-date rule) --")
wr = spx.weekly_returns()
for wk in (36,37,38):
    x=[r for e,x2,r in wr if x2.isocalendar()[1]==wk]
    print(f"  exit in ISO week {wk:2d}         {sz.fmt(sz.describe(x))}")

print("\n=== 4. SEPTEMBER CONTEXT ===")
x=[r for e,x2,r in wr if x2.month==9]
print(f"  all weeks exiting in Sept  {sz.fmt(sz.describe(x))}")
for m in range(1,13):
    xm=[r for e,x2,r in wr if x2.month==m]
    d=sz.describe(xm); print(f"    month {m:2d}: n={d['n']:4d} win={d['win']*100:5.1f}% mean={d['mean']*100:+6.3f}% med={d['median']*100:+6.3f}%")
