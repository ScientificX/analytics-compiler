# WrithLang DSL Reference

This document describes the WrithLang risk/pricing DSL: its grammar, the available shocks and instruments, field requirements, and validation rules. It is derived from `com.writhlang.dsl.Parser` and `com.writhlang.dsl.Ast`.

## 1. Program structure

A program consists of a single `shocks` block followed by zero or more `instrument` blocks:

```text
shocks { ... }

instrument <kind> <id> { ... }
instrument <kind> <id> { ... }
```

Whitespace is insignificant between tokens, and each entry may end with an optional `;`.

## 2. Lexical elements

| Token      | Pattern                                  | Notes                                  |
| ---------- | ---------------------------------------- | -------------------------------------- |
| identifier | `[a-zA-Z][a-zA-Z0-9_]*`                  | instrument ids, shock names, field names |
| number     | `[+-]?[0-9]+(\.[0-9]+)?`                 | decimal (parsed as `Double`)           |
| int        | `[0-9]+`                                 | whole number (parsed as `Int`)         |
| keyword    | reserved words below                     | case-sensitive                         |

Reserved keywords: `shocks`, `shock`, `curve`, `parallel`, `bucket`, `twist`, `instrument`, `bond`, `mortgage`, `mbs`, `swap`, `cap`, `swaption`, `fxforward`, `prepayCurve`, `flat`, `ramp`, `call`, `put`.

## 3. Grammar (EBNF)

```ebnf
program         := ws shocksBlock ws instrumentBlock* ws ;

shocksBlock     := "shocks" ws "{" ws shockEntry* ws "}" ;
shockEntry      := "shock" ws ident ws "{" ws shockItem* ws "}" entryEnd ;
shockItem       := curveItem | scalarItem ;
scalarItem      := ident ws number entryEnd ;
curveItem       := "curve" ws curveShift entryEnd ;

curveShift      := "parallel" ws number
                 | "bucket" ws number ws number
                 | "twist" ws number ws number (ws number)? ;

instrumentBlock := "instrument" ws kind ws ident ws "{" ws prop* ws "}" ;
kind            := "bond" | "mortgage" | "mbs" | "swap" | "cap" | "swaption" | "fxforward" ;
prop            := prepayProp | flagProp | numberProp ;
prepayProp      := "prepayCurve" ws prepayCurve entryEnd ;
prepayCurve     := "flat" ws number
                 | "ramp" ws number ws number ws int ;
flagProp        := ("call" | "put") entryEnd ;
numberProp      := ident ws number entryEnd ;

ident           := [a-zA-Z] [a-zA-Z0-9_]* ;
number          := ["+" | "-"]? [0-9]+ ("." [0-9]+)? ;
int             := [0-9]+ ;
ws              := [ \r\n\t]* ;
entryEnd        := ws ";"? ws ;
```

## 4. Shocks

A `shock` is a named bundle of market moves. Each item is either a **scalar factor move** or a **curve shift** (introduced by `curve`).

### 4.1 Scalar factors

```text
shock <name> { <factor> <value>; <factor> <value>; ... }
```

Recognized scalar factors and their meanings:

| Factor       | Meaning                                              |
| ------------ | ---------------------------------------------------- |
| `rate`       | Discount-rate move (treated as a flat curve shift).  |
| `spread`     | Credit/liquidity spread move (flat curve shift).     |
| `prepay`     | Prepayment (CPR) shift.                              |
| `volatility` | Volatility shift.                                    |
| `fx`         | FX rate shift (relative move).                       |

Any scalar item whose name is not one of the five recognized factors is parsed but ignored when the shock is turned into computation nodes (see `DslCompiler.shockMarket`).

### 4.2 Curve shifts

```text
shock <name> { curve <shift>; }
```

| Syntax                         | AST node      | Meaning                                                  |
| ------------------------------ | ------------- | -------------------------------------------------------- |
| `parallel <amount>`            | `FlatShift`   | Uniform bump across all tenors.                          |
| `bucket <tenor> <amount>`      | `BucketShift` | Local bump centred on `tenor` (linear tent, width 1 yr). |
| `twist <short> <long> [pivot]` | `TwistShift`  | Short→long twist; `pivot` defaults to `10` years.        |

## 5. Instruments

```text
instrument <kind> <id> { <field> <value>; ... }
```

`<id>` is a unique identifier used in output and DOT clustering. Fields may appear in any order and may be separated by an optional `;`.

### 5.1 `bond`

Fixed-rate coupon bond.

| Field      | Type   | Required | Default | Notes                      |
| ---------- | ------ | -------- | ------- | -------------------------- |
| `notional` | number | yes      | —       |                            |
| `coupon`   | number | yes      | —       | annual coupon rate         |
| `maturity` | int    | yes      | —       | years; must be `> 0`       |
| `rate`     | number | yes      | —       |                            |
| `spread`   | number | no       | `0`     |                            |
| `freq`     | int    | no       | `1`     | coupons per year; `> 0`    |

### 5.2 `mortgage`

Amortizing loan with a prepayment curve.

| Field         | Type   | Required | Default | Notes                      |
| ------------- | ------ | -------- | ------- | -------------------------- |
| `notional`    | number | yes      | —       |                            |
| `rate`        | number | yes      | —       | note rate                  |
| `spread`      | number | no       | `0`     |                            |
| `term`        | int    | yes      | —       | months; must be `> 0`      |
| `prepayCurve` | prepay | yes      | —       | see [§6](#6-prepay-curves) |

### 5.3 `mbs`

Pass-through MBS pool (WAC note rate, WAM maturity).

| Field         | Type   | Required | Default | Notes                                       |
| ------------- | ------ | -------- | ------- | ------------------------------------------- |
| `notional`    | number | yes      | —       |                                             |
| `rate`        | number | yes      | —       |                                             |
| `spread`      | number | no       | `0`     |                                             |
| `wac`         | number | yes      | —       | weighted-average coupon                     |
| `wam`         | int    | yes      | —       | weighted-average maturity (months); `> 0`   |
| `prepayCurve` | prepay | yes      | —       | see [§6](#6-prepay-curves)                  |

### 5.4 `swap`

Interest-rate swap (fixed vs. floating).

| Field       | Type   | Required | Default | Notes                    |
| ----------- | ------ | -------- | ------- | ------------------------ |
| `notional`  | number | yes      | —       |                          |
| `rate`      | number | yes      | —       |                          |
| `spread`    | number | no       | `0`     |                          |
| `fixedRate` | number | yes      | —       |                          |
| `maturity`  | int    | yes      | —       | years; `> 0`             |
| `freq`      | int    | no       | `1`     | payments per year; `> 0` |

### 5.5 `cap`

Interest-rate cap (portfolio of caplets).

| Field        | Type   | Required | Default | Notes                       |
| ------------ | ------ | -------- | ------- | --------------------------- |
| `notional`   | number | yes      | —       |                             |
| `rate`       | number | yes      | —       |                             |
| `spread`     | number | no       | `0`     |                             |
| `strike`     | number | yes      | —       |                             |
| `maturity`   | int    | yes      | —       | years; `> 0`                |
| `freq`       | int    | no       | `4`     | caplets per year; `> 0`     |
| `volatility` | number | yes      | —       |                             |

### 5.6 `swaption`

Option on an interest-rate swap.

| Field        | Type   | Required | Default | Notes                            |
| ------------ | ------ | -------- | ------- | -------------------------------- |
| `notional`   | number | yes      | —       |                                  |
| `rate`       | number | yes      | —       |                                  |
| `spread`     | number | no       | `0`     |                                  |
| `strike`     | number | yes      | —       |                                  |
| `expiry`     | number | yes      | —       | years; must be `> 0`             |
| `maturity`   | int    | yes      | —       | swap maturity (years); `> 0`     |
| `freq`       | int    | no       | `2`     | payments per year; `> 0`         |
| `volatility` | number | yes      | —       |                                  |
| `call`/`put` | flag   | no       | `call`  | `call` = payer, `put` = receiver |

### 5.7 `fxforward`

Foreign-exchange forward.

| Field          | Type   | Required | Default | Notes                |
| -------------- | ------ | -------- | ------- | -------------------- |
| `notional`     | number | yes      | —       |                      |
| `domesticRate` | number | yes      | —       |                      |
| `fxRate`       | number | yes      | —       |                      |
| `foreignRate`  | number | yes      | —       |                      |
| `maturity`     | number | yes      | —       | years; must be `> 0` |

## 6. Prepay curves

Used by `mortgage` and `mbs` via the `prepayCurve` field.

| Syntax                        | AST node      | Meaning                                              |
| ----------------------------- | ------------- | ---------------------------------------------------- |
| `flat <cpr>`                  | `FlatPrepay`  | Constant CPR for the whole life.                     |
| `ramp <start> <end> <months>` | `RampPrepay`  | CPR ramps linearly from `start` to `end` over `months`. |

`ramp` months must be `> 0`.

## 7. Validation rules

`Parser.validateInstrument` rejects a program (returning a `Left(error)`) when:

- A required field is missing.
- A field is present with the wrong shape (e.g., a number where an int is required).
- `maturity`, `term`, `wam`, or `freq` is not a positive integer.
- `expiry` or `maturity` (in `fxforward`) is not a positive number.
- `prepayCurve` uses a `ramp` with `months <= 0`.
- The instrument `kind` is not one of the seven recognized kinds.

Errors are aggregated (`; `-separated) when multiple instruments are invalid.

## 8. Example

```text
shocks {
  shock up   { rate 0.0005; spread 0.0001; prepay -0.005; }
  shock down { rate -0.0005; spread -0.0001; prepay 0.005; }
  shock twist { curve twist -0.001 0.001 10; }
  shock bucket { curve bucket 5 0.001; }
  shock vol  { volatility 0.01; }
}

instrument bond BondA {
  notional 1000000
  coupon 0.05
  maturity 7
  rate 0.042
  spread 0.0015
  freq 2
}

instrument mortgage MortA {
  notional 350000
  rate 0.045
  term 360
  spread 0.0020
  prepayCurve ramp 0.02 0.06 24
}

instrument cap CapA {
  notional 2000000
  rate 0.035
  spread 0.0005
  strike 0.04
  maturity 3
  freq 4
  volatility 0.20
}
```

This matches the built-in example in `com.writhlang.app.Main.defaultDsl`.


