# STATCODE — System Status Code Reference

The device reports its current state to the controller with `STATCODE,<code>` (controller replies `$1000`).
Read the controller's last-received code to know where any unit is. Codes are sent **only on state change**.

> **Hardware limit: STATCODE max = 9.** We use only **1–6**. Codes **7–9 are reserved** for future use.

## The 6-state lifecycle (shared by base and rover)
| Code | Constant | Meaning |
|---|---|---|
| 1 | STAT_BOOT | Program up / config loaded |
| 2 | STAT_ACQUIRING | Started; connecting / surveying, no valid data yet |
| 3 | STAT_RUNNING_OK | Operating normally with valid data (base streaming RTCM / rover RTK fix) |
| 4 | STAT_SLEEP | Going to sleep (burst mode) |
| 5 | STAT_ERROR | Any error (config / NTRIP unreachable / base-coord fail) |
| 6 | STAT_STOPPED | Thread stopped (graceful, not a sleep) |
| 7–9 | *(reserved)* | Future use |

## How the old detailed states map into these 6
| Old state | Now |
|---|---|
| boot, config-loaded | **1** BOOT |
| base-start, base-survey, rover-start, rover-connected | **2** ACQUIRING |
| base-coords-ready, base-streaming, rover-RTK-fix | **3** RUNNING_OK |
| prepare-sleep, alarm-set, shutting-down | **4** SLEEP |
| err-config, err-ntrip, err-base-coord-fail | **5** ERROR |
| base-stopped, rover-stopped | **6** STOPPED |

The legacy constant names (`STAT_BASE_STREAMING`, `STAT_ROVER_RTK_FIX`, etc.) still exist in `Constant.java` as **aliases** pointing at these 6 codes, so existing call sites are unchanged.

## Notes
- Command: `STATCODE,<code>` → controller replies `$1000`. Sent via `TcpClientService.sendStatusCode(int)`.
- De-duplicated: repeated identical codes are not re-sent. Because several old states now share a code (e.g. survey → coords-ready both inside ACQUIRING/RUNNING), transitions **within** the same coarse state are silent; only the change **between** the 6 states is transmitted.
- With Debug Logging enabled (UI Diagnostics, or `debugEnable` absent → default on), each send appears in the journal as `[DBG][STATCODE] sending STATCODE,<n>`.

## Typical healthy wake sequence (base or rover)
`1 (boot) → 2 (acquiring) → 3 (running ok) → 4 (sleep)`

## Error / stop examples
- NTRIP down on a rover wake: `1 → 2 → 5 (error)` (retries; goes to `3` if it recovers).
- Manual stop while streaming: `… → 3 → 6 (stopped)`.
