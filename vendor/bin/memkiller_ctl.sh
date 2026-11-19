#!/system/bin/sh
# memkiller_ctl.sh - Deadlock-free lmkd controller + VM headroom optimizer (0..500 MB)

TAG="memkiller"
say() { log -t "$TAG" "$*"; }

# Wait for boot completion & ActivityManager readiness before touching lmkd/device_config
while [ "$(getprop sys.boot_completed)" != "1" ]; do
	sleep 1
done
n=0
while ! service check activity 2>/dev/null | grep -q "found" && [ "$n" -lt 60 ]; do
	sleep 1
	n=$((n + 1))
done
IFS=' .' read -r up_sec _ < /proc/uptime 2>/dev/null || up_sec=60
if [ "$up_sec" -lt 45 ]; then
	sleep 10
fi

# Capture original stock baselines once
read_node() {
	local v=""
	[ -r "$1" ] && IFS= read -r v < "$1" 2>/dev/null || v=""
	echo "$v"
}
write_node() {
	[ -w "$1" ] && printf '%s\n' "$2" > "$1" 2>/dev/null || true
}

STOCK_MINFREE=$(getprop sys.lmk.minfree_levels)
[ -z "$STOCK_MINFREE" ] && STOCK_MINFREE="1200:0,2400:100,3600:200,4800:250,17920:900,25600:950"
STOCK_MEMLEAK=$(getprop persist.sys.lmk.oplus.kill_memleak_process)
[ -z "$STOCK_MEMLEAK" ] && STOCK_MEMLEAK="1"
STOCK_THRASH_CRIT=$(getprop persist.device_config.lmkd_native.thrashing_limit_critical)
STOCK_VM_MINFREE=$(read_node /proc/sys/vm/min_free_kbytes)
[ -z "$STOCK_VM_MINFREE" ] && STOCK_VM_MINFREE="11584"
STOCK_VM_WSCALE=$(read_node /proc/sys/vm/watermark_scale_factor)
[ -z "$STOCK_VM_WSCALE" ] && STOCK_VM_WSCALE="65"
STOCK_VM_SWAPPINESS=$(read_node /proc/sys/vm/swappiness)
[ -z "$STOCK_VM_SWAPPINESS" ] && STOCK_VM_SWAPPINESS="100"
STOCK_VM_VFS=$(read_node /proc/sys/vm/vfs_cache_pressure)
[ -z "$STOCK_VM_VFS" ] && STOCK_VM_VFS="100"
STOCK_AM_CACHED=$(device_config get activity_manager max_cached_processes 2>/dev/null || echo "null")
STOCK_AM_PHANTOM=$(device_config get activity_manager max_phantom_processes 2>/dev/null || echo "null")

LAST_STATE=""
LAST_MINFREE=""
LMKD_PID=""
LMKD_SUSPENDED=0
SAFETY_TRIPPED=0

is_main_lmkd() {
	local pid="$1" ppid="" cmd=""
	[ -n "$pid" ] && [ -r "/proc/$pid/status" ] && [ -r "/proc/$pid/cmdline" ] || return 1
	while IFS= read -r line; do
		case "$line" in
			PPid:*) ppid=${line#PPid:}; ppid=${ppid##*	}; ppid=${ppid##* }; break ;;
		esac
	done < "/proc/$pid/status" 2>/dev/null || return 1
	[ "$ppid" = "1" ] || return 1
	cmd=$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null || true)
	case "$cmd" in
		/system/bin/lmkd\ ) return 0 ;;
		/system/bin/lmkd) return 0 ;;
		*) return 1 ;;
	esac
}

get_lmkd_pid() {
	if is_main_lmkd "$LMKD_PID"; then
		echo "$LMKD_PID"
		return 0
	fi
	local p="" cand=""
	p=$(getprop init.svc_debug_pid.lmkd)
	if is_main_lmkd "$p"; then
		LMKD_PID="$p"
		echo "$LMKD_PID"
		return 0
	fi
	for cand in $(pidof lmkd 2>/dev/null); do
		if is_main_lmkd "$cand"; then
			LMKD_PID="$cand"
			echo "$LMKD_PID"
			return 0
		fi
	done
	LMKD_PID=""
	return 1
}

reinit_active() {
	local cand=""
	for cand in $(pidof lmkd 2>/dev/null); do
		if ! is_main_lmkd "$cand"; then
			return 0
		fi
	done
	return 1
}

is_pid_stopped() {
	local pid="$1" line rest st
	[ -n "$pid" ] && [ -r "/proc/$pid/stat" ] || return 1
	IFS= read -r line < "/proc/$pid/stat" 2>/dev/null || return 1
	rest=${line##*)}
	set -- $rest
	st=$1
	[ "$st" = "T" ] || [ "$st" = "t" ]
}

resume_lmkd() {
	local why="$1" pid
	pid=$(get_lmkd_pid) || return 0
	if is_pid_stopped "$pid" || [ "$LMKD_SUSPENDED" = 1 ]; then
		kill -CONT "$pid" 2>/dev/null || true
		LMKD_SUSPENDED=0
		[ -n "$why" ] && say "lmkd RESUMED (pid=$pid $why)"
	fi
}

suspend_lmkd() {
	local why="$1" pid
	reinit_active && return 0
	pid=$(get_lmkd_pid) || return 0
	if ! is_pid_stopped "$pid"; then
		kill -STOP "$pid" 2>/dev/null || true
		LMKD_SUSPENDED=1
		[ -n "$why" ] && say "lmkd SUSPENDED (pid=$pid $why)"
	else
		LMKD_SUSPENDED=1
	fi
}

# Smart socket drain pulse: prevents /dev/socket/lmkd buffer stall (60s Watchdog) while keeping lmkd frozen under low-RAM pressure
should_drain_lmkd() {
	local avail="$1" ss_pid="" w=""
	if [ "$avail" -gt 900 ]; then
		return 0
	fi
	ss_pid=$(pidof system_server 2>/dev/null | { read -r first _; echo "$first"; })
	[ -n "$ss_pid" ] || return 1
	for w in /proc/"$ss_pid"/task/*/wchan; do
		[ -r "$w" ] || continue
		case "$(cat "$w" 2>/dev/null || true)" in
			*unix*|*sock*) return 0 ;;
		esac
	done
	return 1
}

drain_lmkd_socket() {
	local pid
	pid=$(get_lmkd_pid) || return 0
	if is_pid_stopped "$pid"; then
		kill -CONT "$pid" 2>/dev/null || true
		sleep 0.005
		if ! reinit_active; then
			kill -STOP "$pid" 2>/dev/null || true
			LMKD_SUSPENDED=1
		else
			LMKD_SUSPENDED=0
		fi
	fi
}

read_mem_avail_mb() {
	local k v u
	while read -r k v u; do
		if [ "$k" = "MemAvailable:" ]; then
			echo $(( v / 1024 ))
			return 0
		fi
	done < /proc/meminfo
	echo 0
}

trap 'resume_lmkd "daemon-exit"; exit 0' EXIT INT TERM HUP

apply_disabled() {
	local floor_mb="$1"
	local p950 p900 p250 p200 p100 p0 levels
	case "$floor_mb" in ''|*[!0-9]*) floor_mb=500 ;; esac
	[ "$floor_mb" -lt 0 ] && floor_mb=0
	[ "$floor_mb" -gt 500 ] && floor_mb=500

	p950=$(( floor_mb * 256 ))
	p900=$(( p950 * 7 / 10 ))
	p250=$(( floor_mb * 48 ))
	p200=$(( floor_mb * 36 ))
	p100=$(( floor_mb * 24 ))
	p0=$(( floor_mb * 12 ))
	levels="${p0}:0,${p100}:100,${p200}:200,${p250}:250,${p900}:900,${p950}:950"

	# Always keep lmkd awake while applying props & awaiting --reinit completion
	resume_lmkd "pre-reinit"
	setprop persist.sys.lmk.oplus.kill_memleak_process 0
	if [ "$floor_mb" -eq 0 ]; then
		setprop persist.device_config.lmkd_native.lowmem_min_oom_score 1001
		setprop persist.device_config.lmkd_native.psi_partial_stall_ms 60000
		setprop persist.device_config.lmkd_native.psi_complete_stall_ms 60000
		setprop persist.device_config.lmkd_native.thrashing_limit 10000
		setprop persist.device_config.lmkd_native.thrashing_limit_critical 10000
		setprop persist.device_config.lmkd_native.thrashing_limit_decay 0
		setprop persist.device_config.lmkd_native.swap_free_low_percentage 0
		setprop persist.device_config.lmkd_native.kill_timeout_ms 60000
	else
		setprop persist.device_config.lmkd_native.lowmem_min_oom_score 950
		setprop persist.device_config.lmkd_native.psi_partial_stall_ms 2000
		setprop persist.device_config.lmkd_native.psi_complete_stall_ms 3000
		setprop persist.device_config.lmkd_native.thrashing_limit 200
		setprop persist.device_config.lmkd_native.thrashing_limit_critical 500
		setprop persist.device_config.lmkd_native.thrashing_limit_decay 10
		setprop persist.device_config.lmkd_native.swap_free_low_percentage 2
		setprop persist.device_config.lmkd_native.kill_timeout_ms 200
	fi
	sleep 0.5
	setprop sys.lmk.minfree_levels "$levels"
	LAST_MINFREE="$levels"

	# Maximize app/LLM allocation headroom: lower reserved VM floor & boost zram swap-out willingness
	write_node /proc/sys/vm/min_free_kbytes 4096
	write_node /proc/sys/vm/watermark_scale_factor 10
	write_node /proc/sys/vm/swappiness 120
	write_node /proc/sys/vm/vfs_cache_pressure 150
	device_config put activity_manager max_cached_processes 256 >/dev/null 2>&1 || true
	device_config put activity_manager max_phantom_processes 256 >/dev/null 2>&1 || true

	say "APPLIED memkiller_ctl floor=${floor_mb}MB levels=$levels vm_minfree=4096 wscale=10"
}

restore_stock() {
	LAST_MINFREE=""
	resume_lmkd "restore-stock"
	setprop persist.sys.lmk.oplus.kill_memleak_process "$STOCK_MEMLEAK"
	setprop persist.device_config.lmkd_native.lowmem_min_oom_score ""
	setprop persist.device_config.lmkd_native.psi_partial_stall_ms ""
	setprop persist.device_config.lmkd_native.psi_complete_stall_ms ""
	setprop persist.device_config.lmkd_native.thrashing_limit ""
	setprop persist.device_config.lmkd_native.thrashing_limit_critical "$STOCK_THRASH_CRIT"
	setprop persist.device_config.lmkd_native.thrashing_limit_decay ""
	setprop persist.device_config.lmkd_native.swap_free_low_percentage ""
	setprop persist.device_config.lmkd_native.kill_timeout_ms ""
	sleep 0.5
	setprop sys.lmk.minfree_levels "$STOCK_MINFREE"

	write_node /proc/sys/vm/min_free_kbytes "$STOCK_VM_MINFREE"
	write_node /proc/sys/vm/watermark_scale_factor "$STOCK_VM_WSCALE"
	write_node /proc/sys/vm/swappiness "$STOCK_VM_SWAPPINESS"
	write_node /proc/sys/vm/vfs_cache_pressure "$STOCK_VM_VFS"
	if [ "$STOCK_AM_CACHED" = "null" ] || [ -z "$STOCK_AM_CACHED" ]; then
		device_config delete activity_manager max_cached_processes >/dev/null 2>&1 || true
	else
		device_config put activity_manager max_cached_processes "$STOCK_AM_CACHED" >/dev/null 2>&1 || true
	fi
	if [ "$STOCK_AM_PHANTOM" = "null" ] || [ -z "$STOCK_AM_PHANTOM" ]; then
		device_config delete activity_manager max_phantom_processes >/dev/null 2>&1 || true
	else
		device_config put activity_manager max_phantom_processes "$STOCK_AM_PHANTOM" >/dev/null 2>&1 || true
	fi

	say "RESTORED stock lmkd/VM/AM levels=$STOCK_MINFREE vm_minfree=$STOCK_VM_MINFREE"
}

tick=0
say "watcher ready (stock=$STOCK_MINFREE vm_minfree=$STOCK_VM_MINFREE wscale=$STOCK_VM_WSCALE)"
while :; do
	en=$(getprop persist.sys.oplus.disable_mem_killer)
	floor=$(getprop persist.sys.oplus.mem_killer_floor_mb)
	[ "$en" = "1" ] || en=0
	case "$floor" in ''|*[!0-9]*) floor=500 ;; esac
	[ "$floor" -lt 0 ] && floor=0
	[ "$floor" -gt 500 ] && floor=500

	cur_state="${en}:${floor}"
	if [ "$cur_state" != "$LAST_STATE" ]; then
		SAFETY_TRIPPED=0
		if [ "$en" = "1" ]; then
			apply_disabled "$floor"
		elif [ -n "$LAST_STATE" ]; then
			restore_stock
		fi
		LAST_STATE="$cur_state"
	fi

	if [ "$en" = "1" ]; then
		tick=$((tick + 1))
		avail=$(read_mem_avail_mb)
		if [ "$floor" -eq 0 ]; then
			suspend_lmkd "floor=0MB"
			if [ "$LMKD_SUSPENDED" = 1 ]; then
				sleep 0.35
				if [ $((tick % 6)) = 0 ] && should_drain_lmkd "$avail"; then
					drain_lmkd_socket
				fi
			else
				sleep 0.25
			fi
		else
			if [ "$avail" -gt "$floor" ]; then
				if [ "$SAFETY_TRIPPED" = 0 ] || [ "$avail" -ge $((floor + 32)) ]; then
					SAFETY_TRIPPED=0
					suspend_lmkd "avail=${avail}MB>floor=${floor}MB"
				fi
			else
				SAFETY_TRIPPED=1
				resume_lmkd "avail=${avail}MB<=floor=${floor}MB"
			fi
			if [ "$LMKD_SUSPENDED" = 1 ]; then
				sleep 0.35
				if [ $((tick % 6)) = 0 ]; then
					drain_lmkd_socket
				fi
			else
				sleep 0.25
			fi
		fi
		if [ $((tick % 16)) = 0 ] && [ -n "$LAST_MINFREE" ]; then
			if [ "$(getprop sys.lmk.minfree_levels)" != "$LAST_MINFREE" ]; then
				setprop sys.lmk.minfree_levels "$LAST_MINFREE"
			fi
		fi
	else
		sleep 1
	fi
done
