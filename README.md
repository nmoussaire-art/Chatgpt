# Weekly US Equity Seasonality Research

Weekly seasonality studies for the Nasdaq-100 (NDX/QQQ) and S&P 500 (SPX/SPY).

**Trade definition:** enter near Friday's close, exit at the following Friday's close.
`weekly return = (next Friday close / entry Friday close) - 1`. When Friday is a market
holiday, the final trading session of the week is used and the adjustment is stated in
the report.

## Layout

| Path | Contents |
|---|---|
| `scripts/fetch_data.py` | Re-downloads every price series into `data/` |
| `scripts/seasonality.py` | Shared calendar + statistics toolkit (weekly returns, bootstrap, permutation tests) |
| `scripts/*.py` | Per-section analyses: `context`, `labor_day`, `robust`, `analogues`, `window`, `regimes`, `validate`, `divcheck`, `final`, `sessions` |
| `data/` | Raw CSV price data (git-ignored; regenerate with `fetch_data.py`) |
| `results/` | Full numeric output logs |
| `reports/` | The weekly reports |

## Reproducing a report

```sh
python3 scripts/fetch_data.py
for f in context labor_day robust analogues window regimes validate divcheck final sessions; do
  echo "### $f"; python3 scripts/$f.py
done
```

Index price returns (SPX, NDX) and ETF total returns (adjusted SPY, QQQ) are always reported
separately and never mixed. All returns are close-to-close.

## Reports

- [2026-09-11](reports/2026-09-11_weekly_seasonality.md) — Labor Day week. **Verdict: no trade, both indices.**
