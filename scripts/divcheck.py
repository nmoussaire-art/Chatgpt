import sys, datetime as dt; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all()
print("=== Does a SPY/QQQ ex-dividend fall inside the Sep 8-14 trade week? ===")
print("(ETF total-return minus price-only return isolates the dividend effect)\n")
for a,p in [("SPY_adj","SPY_px"),("QQQ_adj","QQQ_px")]:
    sa,sp=S[a],S[p]
    print(f"  {sp.name} -> {sa.name}")
    for e,x,r in sa.weekly_returns():
        if x.month==9 and 8<=x.day<=14:
            rp=sp.ret(e,x)
            print(f"    {e} -> {x}  total return {r:+.3%}   price only {rp:+.3%}   dividend contribution {(r-rp)*100:+.3f}%")
    print()
# Where do the ex-div dates actually fall?
print("=== Largest adj-vs-price gaps in September (i.e. actual ex-div days), SPY 2017-2025 ===")
sa,sp=S["SPY_adj"],S["SPY_px"]
gaps=[]
for i in range(1,len(sa.days)):
    d0,d1=sa.days[i-1],sa.days[i]
    if d1.month==9 and d0 in sp.px and d1 in sp.px:
        g=(sa.px[d1]/sa.px[d0]-1)-(sp.px[d1]/sp.px[d0]-1)
        if g>0.0005: gaps.append((d1,g))
for d,g in sorted(gaps): print(f"    {d} ({d.strftime('%a')}) dividend {g*100:+.3f}%")
print("\n  -> SPY/QQQ go ex-dividend on the THIRD Friday of Sep (opex day), i.e. 2026-09-18,")
print("     which is AFTER this trade exits on 2026-09-11. No dividend accrues in this week.")
