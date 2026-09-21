# WrithLang DSL Reference

This document describes the WrithLang DSL: the grammar for **portfolios** and **shocks**, the instrument and field rules, and the separate **market-data** JSON format. It is derived from `com.writhlang.dsl.Parser`, `com.writhlang.dsl.Ast`, and `com.writhlang.marketdata.MarketDataJson`.

The DSL has two parts with distinct lifecycles:

- **The `.dsl` file** describes *what to value* (a recursive portfolio of instruments) and *how to stress it* (shocks). It never contains market numbers — instruments and shocks *reference* market objects by name.
- **Market data** is a separate JSON document supplying the actual quotes/surfaces/vectors/spots those names resolve to.

## 1. Program structure

```
shocks? portfolio+
```

A program is an optional `shocks` block followed by one or more top-level `portfolio` blocks (which may nest). A top-level `instrument` is also allowed and is treated as a leaf of an implicit portfolio.

## 2. Lexical elements

| Token | Pattern | Notes |
| --- | --- | --- |
| identifier | `[a-zA-Z][a-zA-Z0-9_.]*` | instrument ids, portfolio names, market-object names (may contain `.`, e.g. `EUR.CREDIT`) |
| number | `[+-]?[0-9]+(\.[0-9]+)?` | decimal (parsed as `Double`) |
| tenor | `<number>[YM]` | `2Y` = 2 years, `3M` = 0.25 years |

Reserved keywords (case-sensitive): `shocks`, `shock`, `portfolio`, `instrument`, `discountcurve`, `creditcurve`, `indexcurve`, `yieldcurve`, `fxspot`, `prepay`, `swaptionvol`, `capfloorvol`, `parallel`, `bucket`, `twist`, `sine`, `absolute`, `relative`, `bond`, `mortgage`, `mbs`, `swap`, `cap`, `swaption`, `fxforward`, `call`, `put`.

## 3. Grammar (EBNF)

```ebnf
program         := ws shocksBlock? (ws node)+ ws ;
node            := portfolioBlock | instrumentBlock ;
portfolioBlock  := "portfolio" ws ident ws "{" ws node* ws "}" ;
instrumentBlock := "instrument" ws kind ws ident ws "{" ws prop* ws "}" ;
kind            := "bond" | "mortgage" | "mbs" | "swap" | "cap" | "swaption" | "fxforward" ;

shocksBlock     := "shocks" ws "{" ws shockEntry* ws "}" ;
shockEntry      := "shock" ws ident ws "{" ws shockMove* ws "}" ;
shockMove       := curveMove | scalarMove ;
curveMove       := curveKw ws ident ws (curvePointTail | curveShapeTail) entryEnd ;
curvePointTail  := tenor ws shiftType? ws number ;
curveShapeTail  := "parallel" ws number
                 | "bucket" ws number ws number
                 | "twist" ws number ws number (ws number)?
                 | "sine" ws number ws number (ws number)? ;
scalarMove      := scalarKw ws ident ws shiftType? ws number entryEnd ;
curveKw         := "discountcurve" | "creditcurve" | "indexcurve" | "yieldcurve" ;
scalarKw        := "fxspot" | "prepay" | "swaptionvol" | "capfloorvol" ;
shiftType       := "absolute" | "relative" ;

prop            := refProp | flagProp | numberProp ;
refProp         := ("discountCurve" | "creditCurve" | "prepayCurve" | "volSurface"
                 | "domesticCurve" | "foreignCurve" | "fxSpot") ws ident entryEnd ;
flagProp        := ("call" | "put") entryEnd ;
numberProp      := ident ws number entryEnd ;

entryEnd        := ws ";"? ws ;
ws              := [ \r\n\t]* ;
```

## 4. Portfolios

A `portfolio` is a recursive container: a book may nest sub-books and instruments.

```
portfolio RatesDesk {
  portfolio BookA {
    instrument bond B1 { ... }
  }
  instrument swap S1 { ... }
}
```

Portfolio names are preserved as a path (e.g. `RatesDesk/BookA`) for future desk-level aggregation. Instrument ids must be unique across the whole program.

## 5. Instruments

```
instrument <kind> <id> { <field> <value>; ... }
```

Fields are numbers, market references, or the `call`/`put` flag. Market references resolve a name to a risk-factor key by the *field name*:

| Field | Risk-factor key |
| --- | --- |
| `discountCurve <n>` | `DiscountCurve:<n>` |
| `creditCurve <n>` | `CreditCurve:<n>` |
| `prepayCurve <n>` | `Prepay:<n>` |
| `volSurface <n>` | `SwaptionVolatility:<n>` |
| `domesticCurve <n>` / `foreignCurve <n>` | `DiscountCurve:<n>` |
| `fxSpot <n>` | `FxSpot:<n>` |

### 5.1 `bond`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `coupon` | number | yes | — | annual coupon rate |
| `maturity` | int | yes | — | years; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `freq` | int | no | `1` | coupons/year; `> 0` |

### 5.2 `mortgage`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `noteRate` | number | yes | — | the loan's contractual rate (a trade term, not market data) |
| `term` | int | yes | — | months; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `prepayCurve` | ref | yes | — | |

### 5.3 `mbs`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `wac` | number | yes | — | weighted-average coupon |
| `wam` | int | yes | — | weighted-average maturity (months); `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `prepayCurve` | ref | yes | — | |

### 5.4 `swap`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `fixedRate` | number | yes | — | |
| `maturity` | int | yes | — | years; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `freq` | int | no | `1` | payments/year; `> 0` |

### 5.5 `cap`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `strike` | number | yes | — | |
| `maturity` | int | yes | — | years; `> 0` |
| `freq` | int | no | `4` | caplets/year; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `volSurface` | ref | yes | — | |

### 5.6 `swaption`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `strike` | number | yes | — | |
| `expiry` | number | yes | — | years; `> 0` |
| `maturity` | int | yes | — | swap maturity (years); `> 0` |
| `freq` | int | no | `2` | payments/year; `> 0` |
| `discountCurve` | ref | yes | — | |
| `creditCurve` | ref | yes | — | |
| `volSurface` | ref | yes | — | |
| `call`/`put` | flag | no | `call` | `call` = payer, `put` = receiver |

### 5.7 `fxforward`

| Field | Type | Req | Default | Notes |
| --- | --- | --- | --- | --- |
| `notional` | number | yes | — | |
| `fxRate` | number | yes | — | the contracted forward rate (trade term) |
| `maturity` | number | yes | — | years; `> 0` |
| `domesticCurve` | ref | yes | — | |
| `foreignCurve` | ref | yes | — | |
| `fxSpot` | ref | yes | — | |

## 6. Shocks

A `shock` is a named bundle of moves. The scenario name is an arbitrary label; the *direction* is the sign of each move, and the *shift type* (`absolute`/`relative`) defaults by factor kind (rates/prepay → absolute; fx/vol → relative).

```
shock <name> { <move>; <move>; ... }
```

**Curve pillar move** — bump one par instrument's quote, then re-bootstrap (par-conversion):

```
discountcurve EUR 2Y absolute +0.0001
```

**Curve shape move** — transform all of a curve's quotes, then re-bootstrap:

```
discountcurve EUR twist -0.001 0.001 10
discountcurve EUR parallel +0.0005
discountcurve EUR bucket 5 0.001
discountcurve EUR sine 0.005 0.5 0
```

**Scalar move** — bump a spot/surface/vector:

```
fxspot EURUSD relative +0.01
prepay EUR.MBS +0.005
swaptionvol EUR.SWAPTION relative +0.01
capfloorvol EUR.CAP relative +0.01
```

## 7. Market data (JSON)

Market data is a separate JSON document. A curve is either bootstrapped from quoted instruments or flat (a credit spread).

```json
{
  "curves": {
    "EUR": { "type": "DiscountCurve", "instruments": [
      { "kind": "deposit", "tenor": "1Y", "rate": 0.035 },
      { "kind": "future",  "start": "6M", "end": "12M", "rate": 0.037 },
      { "kind": "swap",    "tenor": "2Y", "rate": 0.040, "freq": 1 },
      { "kind": "swap",    "tenor": "3Y", "rate": 0.045, "freq": 1 }
    ]},
    "EUR.CREDIT": { "type": "CreditCurve", "flat": 0.0015 }
  },
  "fxSpots": { "EURUSD": 1.25 },
  "volSurfaces": { "EUR.SWAPTION": { "flat": 0.20 } },
  "prepayVectors": {
    "EUR.MBS": { "termMonths": 360, "flat": 0.02 },
    "EUR.RAMP": { "termMonths": 360, "ramp": { "start": 0.02, "end": 0.06, "months": 24 } }
  }
}
```

- `curves.<name>.type` is a `KeyType` name (`DiscountCurve`, `CreditCurve`, `IndexCurve`, `YieldCurve`).
- A curve with `instruments` is bootstrapped (deposits → futures → swaps, log-linear discount-factor interpolation). A curve with `flat` is a flat curve.
- `fxSpots.<name>` is a spot quote; `volSurfaces.<name>.flat` is a flat volatility; `prepayVectors.<name>` is `flat` (constant CPR) or `ramp` (linear ramp).

## 8. Validation

`Parser.parseProgram` returns `Left(error)` when a required field is missing, a field has the wrong shape, `maturity`/`term`/`wam`/`freq` is not a positive integer, `expiry`/`maturity` is not positive, or the `kind` is unknown. `MarketDataJson.parse` returns `Left` on malformed JSON, an unknown curve type, or a curve that fails to bootstrap (non-positive/non-decreasing discount factors). Errors are `; `-separated.

## 9. Example

```
shocks {
  shock up2y { discountcurve EUR 2Y absolute +0.0001; }
  shock fxup { fxspot EURUSD relative +0.01; }
}

portfolio RatesBook {
  instrument bond BondA {
    notional 1000000
    coupon 0.05
    maturity 3
    discountCurve EUR
    creditCurve EUR.CREDIT
    freq 1
  }
  instrument swap SwapA {
    notional 1000000
    fixedRate 0.04
    maturity 2
    discountCurve EUR
    creditCurve EUR.CREDIT
    freq 1
  }
}
```

Run with `--input portfolio.dsl --market market.json`.


