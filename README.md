# disable_lmkd_coloros

> **Dynamic LMKD suspension & free-RAM floor controller (`0–500 MB`) — probably can be used for every Android ROM, but only tested on ColorOS 17 (Xiaomi 15 `dada`).**

## Overview

Modern Android ROMs (especially ColorOS / HyperOS / OneUI) aggressively terminate large foreground or background processes (such as local LLMs, emulation workloads, or heavy multitasking sessions) via `lmkd` and vendor memory managers (`osense` / `Athena`) long before physical RAM + ZRAM is actually exhausted.

`disable_lmkd_coloros` provides a **deadlock-free dynamic LMKD controller** paired with a **Developer Options UI (`0 MB` to `500 MB` free-RAM floor slider)** so users can maximize usable RAM headroom without risking `OomAdjuster` stalls or system freezes.

## How It Works

1. **Deadlock-Free `lmkd` Suspension (`SIGSTOP` / `SIGCONT` with Safe Socket-Drain Guard):**
   - `vendor/bin/memkiller_ctl.sh` monitors `persist.sys.oplus.disable_mem_killer` (`0`/`1`) and `persist.sys.oplus.mem_killer_floor_mb` (`0..500`, default `500` MB).
   - **Floor = `0 MB` (`0 MB (Fully Disabled)`):** `lmkd` is held paused (`SIGSTOP`) continuously, with periodic brief `SIGCONT` socket-drain pulses (`lmkd` socket buffer stays clear so `system_server`'s `OomAdjuster` IPC never blocks or deadlocks).
   - **Floor = `50..500 MB`:** `lmkd` remains paused while `MemAvailable > floor_mb`, and automatically resumes (`SIGCONT`) if `MemAvailable` drops below the configured floor (with hysteresis to avoid rapid oscillation).
2. **Kernel VM Watermarks & ColorOS `osense` Tuning:**
   - Tunes `/proc/sys/vm/min_free_kbytes`, `/proc/sys/vm/watermark_scale_factor`, `/proc/sys/vm/swappiness`, and `device_config` / `lmkd_native` properties dynamically when enabled, and cleanly restores stock baselines when disabled.
   - Ships an updated `my_product/etc/extension/sys_custom_osense_memory_config.xml` to relax ColorOS `osense` background kill thresholds.
3. **Developer Options Integration (`0..500 MB` Slider):**
   - Adds a native COUI **"Disable memory killer"** switch directly below **Memory** in `Developer Options`, revealing an inline **"Minimum free RAM floor"** slider (`500 MB` down to `0 MB (Fully Disabled)` in `50 MB` steps).
   - Writes `Settings.Global.oplus_disable_memory_killer` and `Settings.Global.oplus_memory_killer_min_free_mb`, synced to system properties by `services.jar` (`InlineSdrSaturationController`).
4. **Rootless Static SELinux CIL Policy:**
   - Runs natively under `u:r:cos17_memkiller:s0` via `vendor/etc/init/cos17_memkiller.rc` with zero root/KernelSU dependency after ROM integration.
5. **Included Benchmark App (`memfill-test/`):**
   - `com.dada.memfill` (`MemFillTest`): native C/JNI Android benchmark app that allocates uncompressible xorshift64-filled 64 MB chunks on arm64-v8a while logging `MemAvailable`, `MemFree`, and `SwapFree` so you can empirically verify your RAM headroom before/after enabling the controller.

## Repository Layout

| Path | Description |
|---|---|
| `vendor/bin/memkiller_ctl.sh` | Deadlock-free `lmkd` `SIGSTOP`/`SIGCONT` + VM watermark controller daemon |
| `vendor/etc/init/cos17_memkiller.rc` | Native `init` service trigger (`vendor.cos17_memkiller`) |
| `my_product/etc/extension/sys_custom_osense_memory_config.xml` | Relaxed ColorOS `osense` memory configuration |
| `selinux/cos17_memkiller_plat_sepolicy.cil` | Static SELinux CIL policy for rootless `cos17_memkiller` |
| `framework/services/com/android/server/display/color/InlineSdrSaturationController.java` | `ContentObserver` syncing `Settings.Global` keys to `persist.sys.oplus.*` properties |
| `settings/src/com/oplus/settings/feature/othersettings/development/memkiller/` | `DevMemKillerUi.java` & `MemKillerSliderPreference.java` (`0..500 MB` COUI slider) |
| `memfill-test/` | `MemFillTest` Android RAM allocation benchmark app source (`MainActivity.java`, `memfill_jni.c`, `AndroidManifest.xml`, `build.sh`) |
| `patches/` | Clean unified `.patch` files for `Settings.apk`, `services.jar`, and `vendor/etc/init/` |

## Porting to Non-ColorOS ROMs

The core daemon (`vendor/bin/memkiller_ctl.sh`), `init` `.rc`, SELinux CIL rules, and `MemFillTest` app are completely ROM-agnostic and **should work on AOSP, LineageOS, HyperOS, OneUI, or any Android 13–17 ROM** by reading the same two properties (`persist.sys.oplus.disable_mem_killer` and `persist.sys.oplus.mem_killer_floor_mb`, or renaming them to your ROM namespace). Only `sys_custom_osense_memory_config.xml` and the COUI `Settings.apk` widget classes are ColorOS-specific.
