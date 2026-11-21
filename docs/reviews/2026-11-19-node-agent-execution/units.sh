#!/bin/bash
# What the units' lines are worth, each by a real cycle from the control host
# (docs/reviews/2026-11-19-node-agent-execution): the helper started again while a VM runs,
# ProtectKernelTunables on the helper, and each of the helper's capabilities removed in turn.
# Run on the control host; `ssh $NODE_SSH` must reach the node with sudo. It changes the
# helper's unit through a drop-in (`try.conf`) and removes it again.
#
#   units.sh CONTROL_HOST_PY RUNNER_HOST_BINARY DRIVER_CONFIG TEMPLATE RUNNER_IMAGE NODE_SSH NODE_ADDRESS FIRST_PORT
DRV="python3 $1 $2 $3 $4 $5"; NODE_SSH=$6; NODE=$7; PORT=$8
left() { ssh $NODE_SSH 'echo "jails $(sudo ls /srv/tinfirm-jails/firecracker | wc -l), VMMs $(pgrep -x firecracker | wc -l), device-mapper $(sudo dmsetup ls | grep -c tinfirm), loop devices of snapshots $(sudo losetup -a | grep -c snapshot), veths $(ip -o link 2>/dev/null | grep -c " tfv"), tables $(sudo nft list tables | grep -c -E 'tinfirm_[0-9a-f]{12}'), cgroups $(ls /sys/fs/cgroup/tinfirm-vms 2>/dev/null | grep -c tfvm), work files $(sudo ls /var/lib/tinfirm-node-agent/work | wc -l), named namespaces $(ip netns list | wc -l), network namespaces $(sudo lsns -t net --noheadings | wc -l)"'; }
restart() { ssh $NODE_SSH "sudo systemctl daemon-reload; sudo systemctl restart tinfirm-node-agent-helper.service; sleep 1.5; sudo systemctl restart tinfirm-node-agent-listener.service; sleep 1.5"; }
dropin() { ssh $NODE_SSH "sudo mkdir -p /etc/systemd/system/tinfirm-node-agent-helper.service.d; printf '[Service]\n$1\n' | sudo tee /etc/systemd/system/tinfirm-node-agent-helper.service.d/try.conf >/dev/null"; restart; }
undo() { ssh $NODE_SSH "sudo rm -f /etc/systemd/system/tinfirm-node-agent-helper.service.d/try.conf"; restart; }
cycle() { $DRV cycle 1 2>/dev/null | grep -E "problems|ERROR|create:|guest ready|delete:" | sed 's/^/    /'; }

echo "== 1. the helper started again while a VM runs"
ID=$($DRV create restart-test 2>/dev/null | sed -n 's/.*id \([^;]*\);.*/\1/p'); echo "made $ID"
sleep 4
ssh $NODE_SSH 'J=$(sudo ls /srv/tinfirm-jails/firecracker | head -1); P=$(sudo head -1 /sys/fs/cgroup/tinfirm-vms/$J/cgroup.procs); echo "VMM pid $P in $J"; sudo systemctl restart tinfirm-node-agent-helper.service; sleep 2; sudo systemctl is-active tinfirm-node-agent-helper.service tinfirm-node-agent-listener.service | tr "\n" " "; echo "; the VMM $(sudo kill -0 $P 2>/dev/null && echo still runs as pid $P || echo is GONE)"; echo "its snapshot loop device as the new helper reads it: $(sudo nsenter -t $(systemctl show -p MainPID --value tinfirm-node-agent-helper.service) -m sh -c "cat /sys/block/loop*/loop/backing_file | grep snapshot")"'
$DRV list 2>/dev/null | grep "^list" | cut -c1-200
python3 - $NODE $PORT <<'PY'
import socket, ssl, sys
s = socket.socket(); s.settimeout(3); s.connect((sys.argv[1], int(sys.argv[2])))
c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
try: print("its port after the restart: tls", c.wrap_socket(s).version())
except ssl.SSLError: print("its port after the restart: tls alert")
PY
$DRV delete $ID 2>/dev/null | grep "^delete"; echo "left after the delete: $(left)"

echo; echo "== 2. ProtectKernelTunables=yes on the helper (the setting that broke a run without an error in the owner's seventh sandbox round)"
dropin "ProtectKernelTunables=yes"; cycle; echo "left: $(left)"
undo

echo; echo "== 3. the helper's capabilities, one removed at a time"
ALL="CAP_CHOWN CAP_DAC_OVERRIDE CAP_SETGID CAP_SETUID CAP_SETPCAP CAP_NET_ADMIN CAP_SYS_ADMIN CAP_MKNOD"
for cap in $ALL; do
    dropin "CapabilityBoundingSet=\nCapabilityBoundingSet=$(echo $ALL | sed "s/$cap//")"
    echo "without $cap:"; cycle
    undo >/dev/null 2>&1
    echo "    left: $(left)"
done
echo; echo "== 4. with all eight again"; cycle; echo "left: $(left)"
