import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all()

def window_weeks(s, lo, hi, y1=2025):
    """Weekly trades whose EXIT falls on Sep lo..hi (the 2026 trade exits Sep 11)."""
    out=[]
    for e,x,r in s.weekly_returns():
        if x.month==9 and lo<=x.day<=hi and x.year<=y1: out.append((x.year,e,x,r))
    return out

print("=== 8. SETUP: weekly trade EXITING between Sep 8 and Sep 14 ===")
print("    (2026 trade: entry Fri 2026-09-04 -> exit Fri 2026-09-11)\n")
for key in ["SPX","NDX","SPY_px","QQQ_px","SPY_adj","QQQ_adj"]:
    s=S[key]; tr=window_weeks(s,8,14)
    if len(tr)<4: continue
    x=[r for *_,r in tr]; uni=[r for _,_,r in s.weekly_returns()]
    d=sz.describe(x,universe=uni); b=sz.describe(uni)
    print(f"--- {s.name:12s} [{s.kind}] {tr[0][0]}-{tr[-1][0]}")
    print(f"    setup   {sz.fmt(d)}")
    print(f"    all wks {sz.fmt(b)}")
    print(f"    P(>+1%)={d['p_gt1']:.0%} P(<-1%)={d['p_lt1']:.0%} avg up={d['avg_up']:+.2%} avg dn={d['avg_dn']:+.2%} mean/sd={d['sharpe']:+.3f}")
    print(f"    t={d['t']:+.2f} p={d['p']:.3f} perm p={d['perm_p']:.3f} | win streak {d['max_win_streak']} loss streak {d['max_loss_streak']}\n")

print("=== 9. NEIGHBOURING SEPTEMBER WINDOWS (SPX) — is Sep 8-14 special? ===")
spx=S["SPX"]
for lo,hi in [(1,7),(8,14),(15,21),(22,28)]:
    tr=window_weeks(spx,lo,hi); x=[r for *_,r in tr]
    print(f"  exit Sep {lo:2d}-{hi:2d}  {sz.fmt(sz.describe(x))}")
print("  -- August/October controls --")
for m,lo,hi,lbl in [(8,8,14,"Aug 8-14"),(10,8,14,"Oct 8-14")]:
    x=[r for e,xx,r in spx.weekly_returns() if xx.month==m and lo<=xx.day<=hi and xx.year<=2025]
    print(f"  exit {lbl}   {sz.fmt(sz.describe(x))}")

print("\n=== 10. SUBPERIOD STABILITY of Sep 8-14 exit week ===")
for s in [S["SPX"],S["NDX"]]:
    tr=window_weeks(s,8,14)
    print(f"  -- {s.name}")
    for lo,hi,lbl in [(1975,1999,"1975-1999"),(2000,2025,"2000-2025"),(1996,2010,"1996-2010"),
                      (2011,2025,"2011-2025"),(2016,2025,"last 10y"),(2021,2025,"last 5y")]:
        x=[r for y,e,xx,r in tr if lo<=y<=hi]
        if len(x)>=4: print(f"     {lbl:12s} {sz.fmt(sz.describe(x))}")

print("\n=== 11. YEAR BY YEAR (SPX, exit Sep 8-14) ===")
tr=window_weeks(spx,8,14)
row=""
for y,e,x,r in tr:
    row+=f"{y}:{r*100:+5.1f}%  "
    if len(row)>92: print("  "+row); row=""
print("  "+row)
