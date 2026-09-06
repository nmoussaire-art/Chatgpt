import sys, datetime as dt, bisect; sys.path.insert(0,'scripts')
import seasonality as sz
S=sz.load_all(); spx,ndx,vix=S["SPX"],S["NDX"],S["VIX"]

def ma_at(s,d,n):
    i=bisect.bisect_right(s.days,d)-1
    if i<n-1: return None
    w=s.days[i-n+1:i+1]; return sum(s.px[x] for x in w)/n

def state(s,entry):
    """Regime flags known AT the entry close (no look-ahead)."""
    st={}
    m200=ma_at(s,entry,200); m50=ma_at(s,entry,50)
    st["above200"]= (s.px[entry]>m200) if m200 else None
    st["above50"] = (s.px[entry]>m50) if m50 else None
    keys=sorted(s.week_last); k=entry.isocalendar()[:2]
    if k in s.week_last and keys.index(k)>=2:
        i=keys.index(k)
        st["prev_wk_up"]= s.ret(s.week_last[keys[i-1]],entry)>0
        st["two_up"]= st["prev_wk_up"] and s.ret(s.week_last[keys[i-2]],s.week_last[keys[i-1]])>0
    # prior calendar month direction
    m1=entry.replace(day=1)
    prev_end=s.prev_session(m1); 
    if prev_end:
        pm1=prev_end.replace(day=1); pprev=s.prev_session(pm1)
        st["prev_month_up"]= s.ret(pprev,prev_end)>0 if pprev else None
    # VIX
    if entry in vix.px:
        vd=[d for d in vix.days if d<=entry][-252:]
        lv=sorted(vix.px[d] for d in vd)
        st["vix"]=vix.px[entry]
        st["vix_pct"]=sum(1 for x in lv if x<vix.px[entry])/len(lv)
        st["vix_low"]= st["vix_pct"]<0.33
    return st

CUR = {"above200":True,"above50":True,"prev_wk_up":True,"two_up":True,"prev_month_up":True,"vix_low":True}
print("Current state at 2026-09-04:", CUR, "(VIX 14.53, 5th pct)\n")

def setup_rows(s, y1=2025):
    """Sep 8-14 exit week, with entry-time regime flags."""
    out=[]
    for e,x,r in s.weekly_returns():
        if x.month==9 and 8<=x.day<=14 and x.year<=y1:
            out.append((x.year,e,x,r,state(s,e)))
    return out

def ld_rows(s,y1=2025):
    out=[]
    for y in range(s.start.year,y1+1):
        ld=sz.labor_day(y); e=s.prev_session(ld); x=s.last_session_in(ld,ld+dt.timedelta(4))
        if not e or not x or e>=x or (ld-e).days>5: continue
        out.append((y,e,x,s.ret(e,x),state(s,e)))
    return out

for name,rowfn in [("Sep 8-14 exit week",setup_rows),("Labor Day week",ld_rows)]:
    print(f"=== 12. REGIME BREAKDOWN — {name} ===")
    for s in [spx,ndx]:
        rows=rowfn(s)
        allx=[r for *_ ,r,_ in rows]
        print(f"  -- {s.name} ({rows[0][0]}-{rows[-1][0]})")
        print(f"     {'FULL SAMPLE':28s} {sz.fmt(sz.describe(allx))}")
        for flag,lbl in [("above200","above 200dma"),("above50","above 50dma"),
                         ("prev_wk_up","prev week up"),("two_up","2 up weeks"),
                         ("prev_month_up","prev month (Aug) up"),("vix_low","VIX in bottom third")]:
            x=[r for *_,r,st in rows if st.get(flag) is True]
            xn=[r for *_,r,st in rows if st.get(flag) is False]
            if len(x)>=4:
                d=sz.describe(x)
                print(f"     {lbl+' = TRUE (today)':28s} {sz.fmt(d)}")
            if len(xn)>=4:
                d2=sz.describe(xn)
                print(f"     {lbl+' = false':28s} {sz.fmt(d2)}")
        # all flags matching today
        x=[r for *_,r,st in rows if all(st.get(k) is True for k in ["above200","prev_wk_up","prev_month_up"])]
        if len(x)>=3: print(f"     {'ALL 3 (200dma+wk+mo up)':28s} n={len(x)}  mean={sum(x)/len(x)*100:+.2f}%  win={sum(1 for v in x if v>0)/len(x)*100:.0f}%  vals={[round(v*100,1) for v in x]}")
        print()

print("=== 13. IS THE REGIME ITSELF A SIGNAL? (all weeks, not seasonal) ===")
for s in [spx,ndx]:
    rows=[(e,x,r,state(s,e)) for e,x,r in s.weekly_returns()]
    base=[r for _,_,r,_ in rows]
    print(f"  -- {s.name}  baseline {sz.fmt(sz.describe(base))}")
    for flag,lbl in [("above200","above 200dma"),("two_up","2 consecutive up weeks"),("vix_low","VIX bottom third")]:
        x=[r for _,_,r,st in rows if st.get(flag) is True]
        if len(x)>=30: print(f"     {lbl+' = TRUE':26s} {sz.fmt(sz.describe(x))}")
    x=[r for _,_,r,st in rows if st.get("above200") is True and st.get("vix_low") is True]
    if len(x)>=30: print(f"     {'above200 AND VIX low':26s} {sz.fmt(sz.describe(x))}")
    print()
