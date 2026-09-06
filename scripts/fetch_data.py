#!/usr/bin/env python3
"""Fetch daily price history for the weekly seasonality study.

Sources (all public, no API key):
  SPX  index close      : Cboe   cdn.cboe.com  (1975-01-02 -> today)
  VIX  index OHLC       : Cboe   cdn.cboe.com  (1990-01-02 -> today)
  NDX  index close      : Nasdaq api.nasdaq.com chart endpoint (1996-06-06 -> today)
  SPY/QQQ close         : Nasdaq api.nasdaq.com chart endpoint (1993 / 1999 -> today)
  SPY/QQQ adjusted close: stockanalysis.com history API (trailing 10 years only)

Writes plain CSV into data/ so every later step is reproducible from disk.
"""
import csv, datetime as dt, json, os, sys, time
import requests

UA = ("Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
      "(KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
DATA = os.path.join(os.path.dirname(os.path.dirname(os.path.abspath(__file__))), "data")
os.makedirs(DATA, exist_ok=True)
S = requests.Session()
S.headers.update({"User-Agent": UA, "Accept": "application/json, text/csv, */*"})


def get(url, tries=5, **kw):
    """GET with exponential backoff on network/5xx failures."""
    for i in range(tries):
        try:
            r = S.get(url, timeout=90, **kw)
            if r.status_code == 200:
                return r
            print(f"    HTTP {r.status_code} (attempt {i+1})", file=sys.stderr)
        except requests.RequestException as e:
            print(f"    {type(e).__name__} (attempt {i+1})", file=sys.stderr)
        time.sleep(2 ** i)
    raise SystemExit(f"FAILED: {url}")


def write(name, header, rows):
    path = os.path.join(DATA, name)
    with open(path, "w", newline="") as f:
        w = csv.writer(f)
        w.writerow(header)
        w.writerows(rows)
    print(f"  {name:16s} {len(rows):6d} rows  {rows[0][0]} -> {rows[-1][0]}")


def cboe_spx():
    r = get("https://cdn.cboe.com/api/global/us_indices/daily_prices/SPX_History.csv")
    rows = []
    for rec in csv.DictReader(r.text.splitlines()):
        d = dt.datetime.strptime(rec["DATE"], "%m/%d/%Y").date()
        rows.append([d.isoformat(), f'{float(rec["SPX"]):.4f}'])
    rows.sort()
    write("spx_index.csv", ["date", "close"], rows)


def cboe_vix():
    r = get("https://cdn.cboe.com/api/global/us_indices/daily_prices/VIX_History.csv")
    rows = []
    for rec in csv.DictReader(r.text.splitlines()):
        d = dt.datetime.strptime(rec["DATE"], "%m/%d/%Y").date()
        rows.append([d.isoformat(), rec["OPEN"], rec["HIGH"], rec["LOW"], rec["CLOSE"]])
    rows.sort()
    write("vix_index.csv", ["date", "open", "high", "low", "close"], rows)


def nasdaq_chart(symbol, assetclass, outfile, start="1985-01-01"):
    url = (f"https://api.nasdaq.com/api/quote/{symbol}/chart"
           f"?assetclass={assetclass}&fromdate={start}&todate={dt.date.today():%Y-%m-%d}")
    data = get(url).json()["data"]["chart"]
    rows = []
    for p in data:
        d = dt.datetime.strptime(p["z"]["dateTime"], "%m/%d/%Y").date()
        rows.append([d.isoformat(), f'{float(str(p["y"])):.4f}'])
    rows.sort()
    write(outfile, ["date", "close"], rows)


def stockanalysis_adj(symbol, outfile):
    url = (f"https://stockanalysis.com/api/symbol/s/{symbol}/history"
           f"?range=10Y&period=Daily")
    data = get(url).json()["data"]
    rows = [[p["t"], f'{p["c"]:.4f}', f'{p["a"]:.6f}'] for p in data]
    rows.sort()
    write(outfile, ["date", "close", "adj_close"], rows)


if __name__ == "__main__":
    stamp = dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")
    print(f"Retrieval timestamp (UTC): {stamp}")
    cboe_spx()
    cboe_vix()
    nasdaq_chart("NDX", "index", "ndx_index.csv")
    nasdaq_chart("SPY", "etf", "spy_price.csv", start="1993-01-01")
    nasdaq_chart("QQQ", "etf", "qqq_price.csv", start="1999-01-01")
    stockanalysis_adj("SPY", "spy_adj.csv")
    stockanalysis_adj("QQQ", "qqq_adj.csv")
    with open(os.path.join(DATA, "RETRIEVED.txt"), "w") as f:
        f.write(f"data retrieved (UTC): {stamp}\n")
    print("done")
