# GNSS Data Processing — Technical & Verification Document

**Product:** GNSS Rover / Base Deformation Monitoring System
**Prepared by:** Encardio ______________  **Date:** ______________
**Customer:** ______________  **Site / Unit ID:** ______________

---

## 1. Purpose and Scope

This document describes, **step by step and with the exact mathematics implemented in the firmware**, how the system processes GNSS data — from the raw `$GPGGA` sentence in WGS-84 up to the deviation values reported by the Rover and the surveyed position reported by the Base.

It is written so that the customer can **independently reproduce every value by hand** (or in a spreadsheet) and compare it against the values reported by our software. Section 8 lists the constants, and Sections 2–6 each include a **fully worked numeric example** with exact intermediate results.

---

## 2. Reference Ellipsoid and Constants (WGS-84)

All conversions use the WGS-84 ellipsoid:

| Symbol | Meaning | Value (as used in code) |
|---|---|---|
| `a` | Semi-major axis | `6378137.0` m |
| `f` | Flattening | `1 / 298.257223563` |
| `e²` | First eccentricity squared = `f(2 − f)` | `0.0066943799901413165` |

---

## 3. Step 1 — Reception of GGA Data (WGS-84)

The GNSS receiver outputs standard NMEA `$GPGGA` sentences once per second (1 Hz). Example:

```
$GPGGA,120000.00,2833.1234560,N,07712.5678900,E,4,18,0.8,213.500,M,...
```

Fields used by the software:

| Field # | Content | Example |
|---|---|---|
| 2 | Latitude `ddmm.mmmmmmm` | `2833.1234560` |
| 3 | N/S hemisphere | `N` |
| 4 | Longitude `dddmm.mmmmmmm` | `07712.5678900` |
| 5 | E/W hemisphere | `E` |
| 6 | Fix quality | `4` (RTK fixed) |
| 7 | Satellites in use | `18` |
| 8 | HDOP | `0.8` |
| 9 | Altitude (above MSL) | `213.500` |

### 3.1 Data-quality acceptance gates

A fix is **only** accepted for processing if:

| Parameter | Rover requirement | Base survey requirement |
|---|---|---|
| Fix quality (field 6) | **= 4** (RTK **Fixed** only) | **≠ 0** (any valid fix) |
| Satellites (field 7) | **≥ 15** | **≥ 15** |
| HDOP (field 8) | **≤ 2.0** | **≤ 2.0** |
| Latitude/Longitude/Altitude | must be numeric, non-NaN, and not (0,0) | same |

Fixes failing any gate are discarded and never enter the average.

---

## 4. Step 2 — Conversion of GGA (Geodetic) to ECEF

### 4.1 NMEA degrees-minutes → decimal degrees

For a latitude/longitude field in `ddmm.mmmm` form:

```
deg      = floor(value / 100)
minutes  = value − (deg × 100)
decimal  = deg + minutes / 60
```
If hemisphere is `S` or `W`, negate the result.

### 4.2 Geodetic (decimal degrees, ellipsoidal height) → ECEF (metres)

With latitude `φ` and longitude `λ` in **radians**, and height `h`:

```
N = a / sqrt(1 − e²·sin²φ)                (prime-vertical radius of curvature)

X = (N + h)·cosφ·cosλ
Y = (N + h)·cosφ·sinλ
Z = (N·(1 − e²) + h)·sinφ
```

> **Note on height (`h`):** the software uses **GGA field 9 (altitude) directly** as `h`. The geoid–ellipsoid separation is not added. Because the Rover reports **relative deviation** (Section 6), this constant offset cancels out and does not affect the reported deviation. For manual verification, use GGA field 9 directly, exactly as the software does.

### 4.3 Worked Example A — one fix converted to ECEF

**Input:** `latitude = 2833.1234560 N`, `longitude = 07712.5678900 E`, `altitude = 213.500 m`

Step 1 — decimal degrees:
```
lat = 28 + 33.1234560/60 = 28.5520576000°
lon = 77 + 12.5678900/60 = 77.2094648333°
```

Step 2 — ECEF (using the constants in Section 2):
```
N = 6383019.591553 m
X = 1241302.544771 m
Y = 5467794.230140 m
Z = 3030487.891673 m
```

*(These are the exact values the software computes for this input.)*

---

## 5. Step 3 — Base Station Processing (Position Survey)

The Base establishes its own fixed reference position by averaging many accepted fixes, with robust outlier rejection.

### 5.1 Two-level averaging structure

1. Each accepted fix is converted to ECEF `(X, Y, Z)` and added to a **window buffer**.
2. Every **600 samples** (`≈10 minutes at 1 Hz`) the buffer is reduced to **one window-average** using the MAD filter (5.2–5.3).
3. The number of windows required for a complete survey is:
   ```
   windows_required = base_reading_points / 600
   ```
   where `base_reading_points` is derived from the configured **Base Reading duration** (e.g. `6 hr → 21600`, so `21600 / 600 = 36` windows).
4. When enough windows exist, all windows are combined into the **final Base position** by a weighted average (5.4), then converted back to latitude/longitude/height (5.5).

### 5.2 MAD (Median Absolute Deviation) outlier filter

For a set of values (applied **independently to each of X, Y, Z**, but the keep/reject decision is **joint** — see below):

```
med   = median(values)
MAD   = median( |valueᵢ − med| )
sigma = 1.4826 × MAD                 (1.4826 makes MAD a robust estimate of std-dev)
```

A sample `i` is **kept** only if it is within the cutoff on **all three axes simultaneously**:

```
|Xᵢ − medX| ≤ k·sigmaX   AND
|Yᵢ − medY| ≤ k·sigmaY   AND
|Zᵢ − medZ| ≤ k·sigmaZ
```
where the cutoff per axis is `max(1e-6, k·sigma)`.

### 5.3 Adaptive k (retain ≥ 70 %)

The multiplier `k` starts at **1.0** and increases in steps of **0.2** (up to a maximum of 10.0) until at least **70 %** of the samples are retained:

```
k = 1.0
while (fraction_kept < 0.70 and k ≤ 10.0):
        k = k + 0.2
```
This guarantees the filter never rejects so aggressively that too little data remains.

### 5.4 Window average and weighted combination

- **Within a window:** the kept samples are averaged (simple mean) per axis; the **count** of kept samples is stored with the window.
- **Across windows:** the final position is a **count-weighted mean**:
  ```
  X_final = Σ(meanXᵢ × countᵢ) / Σ(countᵢ)      (same for Y, Z)
  ```

### 5.5 ECEF → Geodetic (final Base coordinate)

The final averaged ECEF is converted back to latitude/longitude/height using the standard iterative method:

```
λ   = atan2(Y, X)
p   = sqrt(X² + Y²)
φ₀  = atan2(Z, p·(1 − e²))                       (initial estimate)

repeat until |φ − φ_prev| < 1e-12 :
    N = a / sqrt(1 − e²·sin²φ)
    h = p / cosφ − N
    φ = atan2( Z, p·(1 − e²·N/(N + h)) )

h = p / cosφ − N                                  (final height)
```
The resulting lat/lon/height is stored as the Base reference and used to command the receiver (`FIX position …`).

### 5.5 Worked Example B — MAD filter on one window (X-axis shown)

Seven X-values (metres), six clustered and one gross outlier:
```
1241226.100, 1241226.140, 1241226.090, 1241226.160,
1241226.120, 1241226.130, 1241228.900   ← outlier
```
```
median (X)      = 1241226.130000
MAD             = 0.030000
sigma = 1.4826×MAD = 0.044478

k = 1.0 → cutoff = 0.044478 → kept = 6 / 7 = 85.7 %  ≥ 70 %  → STOP

kept values     = 1241226.100 … 1241226.130   (outlier 1241228.900 rejected)
window mean (X) = 1241226.123333
count           = 6
```
*(The same procedure runs simultaneously on Y and Z; a sample survives only if it passes on all three axes.)*

### 5.6 Worked Example C — weighted average across windows (X-axis)

| Window | mean X (m) | count |
|---|---|---|
| 1 | 1241226.123 | 98 |
| 2 | 1241226.118 | 100 |
| 3 | 1241226.130 | 95 |

```
X_final = (1241226.123×98 + 1241226.118×100 + 1241226.130×95) / (98+100+95)
        = 363679254.2040 / 293
        = 1241226.123563 m
```

---

## 6. Step 4 — Rover Processing (Deviation Reporting)

The Rover measures how much its position has moved relative to its own **initial reference reading**.

### 6.1 Accepted data

Only **RTK-Fixed** fixes (quality = 4) meeting the gates in Section 3.1 are used. Each is converted to ECEF (Section 4).

### 6.2 Moving-average structure

1. Each accepted ECEF fix is added to a window buffer.
2. Every **100 samples** the buffer is reduced to one window-average using the **same MAD filter** described in 5.2–5.4.
3. The moving average uses the most recent **N windows**, where:
   ```
   Continuous mode:  N = base_line_duration / 100
   Burst mode:       N = (sessions × burst_window_seconds) / 100
                     sessions = baseline_seconds / burst_interval_seconds
   ```
   Example (Burst, 6 hr baseline, 2 hr interval, 15 min window):
   `sessions = 6/2 = 3`, `N = (3 × 900)/100 = 27` windows ≈ the last 2700 valid fixes.
4. The current moving average is a count-weighted mean over those N windows (plus the in-progress window), identical in form to Section 5.4.

### 6.3 Initial reading (baseline)

The **baseline** is the Rover's reference "zero" position. It is computed **once**, the first time enough windows are available (i.e. when the window buffer first reaches N windows), using the same weighted average. It is then stored and never changes (until a manual reset). All subsequent deviations are measured against it.

### 6.4 Deviation calculation and reporting

For each reporting interval, the current moving-average ECEF is differenced against the stored baseline ECEF:

```
dX = X_current_average − X_baseline
dY = Y_current_average − Y_baseline
dZ = Z_current_average − Z_baseline
```

The result is then scaled to the reporting unit:

| Unit | Factor | Decimals |
|---|---|---|
| mm | × 1000 | 1 |
| cm | × 100 | 2 |
| inch | × 39.3701 | 2 |
| feet | × 3.28084 | 4 |

> **Important for manual verification — coordinate frame of the deviation columns:**
> The reported columns **dNORTHING, dEASTING, dALTITUDE** are the **ECEF component differences** `dX, dY, dZ` respectively (the software carries ECEF X/Y/Z through these internal names). They are **not** local North/East/Up. To reproduce them, difference the ECEF coordinates as shown above. The **total displacement magnitude** `sqrt(dX²+dY²+dZ²)` is frame-independent and can also be checked directly.

### 6.5 Worked Example D — deviation reported in mm

| Component | Baseline (m) | Current avg (m) | Difference (m) | Reported (mm) |
|---|---|---|---|---|
| dX (dNORTHING) | 1241226.120 | 1241226.125 | +0.005000 | **+5.0** |
| dY (dEASTING) | 5467447.300 | 5467447.318 | +0.018000 | **+18.0** |
| dZ (dALTITUDE) | 3030380.500 | 3030380.492 | −0.008000 | **−8.0** |

Total 3-D displacement = `sqrt(5² + 18² + 8²) = 20.2 mm`.

### 6.6 CSV output columns

Each reported record contains:

```
DATE/TIME, dNORTHING(unit), dEASTING(unit), dALTITUDE(unit),
NORTHING, EASTING, ALTITUDE(m), SATELLITES, BATTERY(V), TEMPERATURE(DEG)
```
- `dNORTHING/dEASTING/dALTITUDE` = ECEF `dX/dY/dZ` (Section 6.4), in the selected unit.
- `NORTHING/EASTING/ALTITUDE(m)` = the current averaged **absolute** position, converted back to latitude/longitude/height via Section 5.5.
- `SATELLITES` = satellites used in the last accepted fix.

---

## 7. Summary of the End-to-End Pipeline

```
$GPGGA (WGS-84)                                   [Section 3]
      │  quality gates: fix, sats ≥ 15, HDOP ≤ 2.0
      ▼
Decimal degrees → ECEF (X,Y,Z)                    [Section 4]
      │
      ├── BASE:  600-sample windows → MAD (≥70%)   [Section 5]
      │          → count-weighted mean of windows
      │          → ECEF→geodetic = fixed Base position
      │
      └── ROVER: 100-sample windows → MAD (≥70%)   [Section 6]
                 → count-weighted moving average (N windows)
                 → baseline captured once (initial reading)
                 → deviation = current − baseline (ECEF ΔX,ΔY,ΔZ)
                 → × unit factor → reported (mm/cm/inch/feet)
```

---

## 8. Appendix — Constants and Verification Checklist

**Constants**
```
a  = 6378137.0
f  = 1 / 298.257223563
e² = 0.0066943799901413165
MAD scale factor = 1.4826
MAD retain target = 70 %,  k from 1.0 step 0.2 (max 10.0)
Base window size  = 600 samples
Rover window size = 100 samples
```

**Manual verification checklist**
1. Convert a chosen `$GPGGA` to decimal degrees (§4.1) and to ECEF (§4.2). Compare with Example A.
2. Take a set of accepted fixes; compute median, MAD, sigma, apply the ≥70 % keep rule (§5.2–5.3, Example B).
3. Compute the count-weighted window average (§5.4, Example C).
4. For the Rover, difference the current average against the stored baseline in **ECEF** and apply the unit factor (§6.4, Example D).
5. Cross-check the total displacement `sqrt(dX²+dY²+dZ²)` — this is independent of coordinate frame.

*All formulas in this document correspond exactly to the firmware implementation. For any value that does not match, please share the raw `$GPGGA` records and the reported CSV row, and we will reconcile it line by line.*
