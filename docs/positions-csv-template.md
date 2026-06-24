# Positions CSV import template

`POST /api/portfolio/import` (and the **Import CSV** button on the Portfolio Console)
ingests positions from a CSV file using the fixed column order below. The first
line is a header and is ignored; every subsequent non-blank line is one position.

Malformed rows are **reported, never silently dropped** — the response lists each
rejected row by its 1-based line number with a reason, and the valid rows are still
imported.

## Columns (order-sensitive)

```
kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
```

| Column       | STOCK            | OPTION                     | Notes                                  |
|--------------|------------------|----------------------------|----------------------------------------|
| `kind`       | `STOCK`          | `OPTION`                   | case-insensitive                       |
| `symbol`     | ticker           | underlying ticker          | upper-cased on import                  |
| `quantity`   | shares           | contracts                  | must be > 0                            |
| `costBasis`  | per-share basis  | per-contract premium       | decimal                                |
| `openedDate` | `YYYY-MM-DD`     | `YYYY-MM-DD` (optional)    | required for STOCK                     |
| `optionType` | *(blank)*        | `CALL` or `PUT`            | option only                            |
| `strike`     | *(blank)*        | strike price               | option only, must be > 0               |
| `expiry`     | *(blank)*        | `YYYY-MM-DD`               | option only                            |
| `side`       | *(blank)*        | `LONG` or `SHORT`          | option only                            |

Every row must have all 9 comma-separated fields (leave option-only cells empty
for stock rows). A wrong column count is reported as an error.

## Example

```csv
kind,symbol,quantity,costBasis,openedDate,optionType,strike,expiry,side
STOCK,AAPL,100,150.25,2026-01-15,,,,
STOCK,MSFT,50,300.00,2026-02-01,,,,
OPTION,AAPL,2,3.50,2026-01-15,CALL,160,2026-06-19,LONG
OPTION,TSLA,1,5.00,2026-03-01,PUT,200,2026-09-19,SHORT
```
