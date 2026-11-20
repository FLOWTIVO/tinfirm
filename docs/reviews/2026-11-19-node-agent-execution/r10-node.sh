#!/bin/bash
# Round 10, from the management machine: the jail's own file system, R1, the helper started again, the
# firewall's line in `check`, io.max. The control host is `host` (10.0.0.5), the node `ctl`.
DRV='cd ~/tinfirm-host && python3 control-host.py ./tinfirm-runner-host node-driver.json sandbox-2c2g tinfirm-runner-pi:local'
drv() { ssh host "$DRV $* 2>&1"; }
make() { drv create "$1" | sed -n 's/.*id \([^;]*\);.*/\1/p'; }
left() { ssh ctl 'H=$(systemctl show -p MainPID --value tinfirm-node-agent-helper.service); echo "jails $(sudo ls /srv/tinfirm-jails/firecracker | wc -l), VMMs $(pgrep -x firecracker | wc -l), device-mapper $(sudo dmsetup ls | grep -c tinfirm), loop devices of snapshots $(sudo losetup -a | grep -c snapshot), veths $(ip -o link | grep -c " tfv"), VM tables $(sudo nft list tables | grep -c -E "tinfirm_[0-9a-f]{12}"), cgroups $(ls /sys/fs/cgroup/tinfirm-vms 2>/dev/null | grep -c tfvm), work files $(sudo ls /var/lib/tinfirm-node-agent/work | wc -l), jail mounts in the helper'"'"'s namespace $(sudo grep -c " tmpfs tinfirm-jail " /proc/$H/mountinfo), in the node'"'"'s $(grep -c " tmpfs tinfirm-jail " /proc/self/mountinfo)"'; }
# As the VMM's user, in the VMM's own root (the jail, wherever it is mounted): $1 jail number, $2 script
user() { ssh ctl 'J=$(sudo ls /srv/tinfirm-jails/firecracker | sed -n '"$1"'p); P=$(sudo head -1 /sys/fs/cgroup/tinfirm-vms/$J/cgroup.procs); U=$(awk "/^Uid:/{print \$2}" /proc/$P/status); sudo sh -c "cd /proc/$P/root && exec setpriv --reuid $U --regid $U --clear-groups --inh-caps=-all --bounding-set=-all sh -c \"\$0\"" '"'$2'"; }

echo "== the node: $(ssh ctl '/usr/local/bin/tinfirm-node-agent --version; systemctl is-active tinfirm-node-agent-firewall tinfirm-node-agent-helper tinfirm-node-agent-listener | tr "\n" " "')"
echo "== verify, as the control host's driver gets it (the lines about the firewall, the room and the memory)"
drv cycle 0 | grep -E "^verify" | cut -c1-200
ssh host 'cd ~/tinfirm-host && (printf "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"protocol\":1}}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"verify\",\"params\":{\"template\":\"sandbox-2c2g\"}}\n"; sleep 2) | TINFIRM_NODE_DRIVER_CONFIG=node-driver.json ./tinfirm-runner-host vm-driver node 2>/dev/null | tail -1 | python3 -c "
import json,sys
r=json.load(sys.stdin)[\"result\"]
print(\"problems:\", r.get(\"problems\"))
for c in r.get(\"checked\",[]):
    if any(w in c for w in (\"firewall\",\"room\",\"memory\",\"free\")): print(\"checked:\", c)"'

echo; echo "== 1. a jail is a file system of its own"
A=$(make jail-a); sleep 3
ssh ctl 'J=$(sudo ls /srv/tinfirm-jails/firecracker | head -1); H=$(systemctl show -p MainPID --value tinfirm-node-agent-helper.service); P=$(sudo head -1 /sys/fs/cgroup/tinfirm-vms/$J/cgroup.procs)
echo "in the helper'"'"'s mount namespace: $(sudo grep " tmpfs tinfirm-jail " /proc/$H/mountinfo | cut -d" " -f5-)"
echo "in the node'"'"'s own namespace the chroot is an empty directory: $(sudo ls -A /srv/tinfirm-jails/firecracker/$J/root | wc -l) entries; the user data and the VMM'"'"'s files are in memory only"
echo "the VMM'"'"'s root: $(sudo ls /proc/$P/root | tr "\n" " "); $(sudo stat -f -c "%b blocks of %S bytes, %a free; %c inodes, %d free" /proc/$P/root/.)"
echo "the VMM'"'"'s standard output and error: $(sudo readlink /proc/$P/fd/1), $(sudo readlink /proc/$P/fd/2)"
echo "Firecracker'"'"'s log: $(sudo sh -c "wc -l < /proc/$P/root/firecracker.log") lines, the last: $(sudo tail -1 /proc/$P/root/firecracker.log | cut -c1-160)"
df -k --output=avail /var/lib/tinfirm-node-agent/work | tail -1 > /tmp/free-before'
echo "-- the VM's user fills it: bytes, then names, then a sticky directory and the sticky bit on the chroot itself (R1)"
user 1 'dd if=/dev/zero of=fill bs=1M 2>&1 | tail -1; i=0; while true 2>/dev/null > n$i; do i=$((i+1)); done; echo "names until none is left: $i"; rm n0 n1 n2; mkdir sticky && true > sticky/file && chmod 1700 sticky . && echo "chmod 1700 on a directory with a file, and on the chroot: done"'
ssh ctl 'J=$(sudo ls /srv/tinfirm-jails/firecracker | head -1); P=$(sudo head -1 /sys/fs/cgroup/tinfirm-vms/$J/cgroup.procs); echo "the jail now: $(sudo stat -f -c "%a blocks free, %d inodes free" /proc/$P/root/.); the node'"'"'s work file system changed by $(( $(cat /tmp/free-before) - $(df -k --output=avail /var/lib/tinfirm-node-agent/work | tail -1) )) KiB; the VMM still runs: $(pgrep -x firecracker | wc -l)"'
echo "-- the next VM beside it"
drv create jail-b | grep -E "^create" | cut -c1-120; sleep 3
B=$(drv list | grep -o 'node-1:vm-[0-9a-f]*' | grep -v "${A#node-1:}" | head -1)
drv list | grep -E "^list" | cut -c1-330
echo "delete of the filled, sticky one: $(drv delete $A | grep -E '^delete:|rror' | cut -c1-200)"
echo "delete of the other: $(drv delete $B | grep -E '^delete:|rror' | cut -c1-200)"
echo "left: $(left)"

echo; echo "== 2. the helper is started again while a VM runs (its new mount namespace does not hold the jail's file system)"
C=$(make restart); sleep 3
ssh ctl 'sudo systemctl restart tinfirm-node-agent-helper; sleep 1.5; sudo systemctl restart tinfirm-node-agent-listener; sleep 1.5; H=$(systemctl show -p MainPID --value tinfirm-node-agent-helper.service); echo "after the restart: VMMs $(pgrep -x firecracker | wc -l); jail mounts in the new helper'"'"'s namespace: $(sudo grep -c " tmpfs tinfirm-jail " /proc/$H/mountinfo)"'
drv list | grep -E "^list" | cut -c1-200
echo "delete: $(drv delete $C | grep -E '^delete:|rror' | cut -c1-200)"
echo "left: $(left)"

echo; echo "== 3. R5: the node's firewall table and the agent"
ssh ctl 'sudo nft delete table inet tinfirm_node'
ssh host 'cd ~/tinfirm-host && (printf "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"protocol\":1}}\n{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"verify\",\"params\":{\"template\":\"sandbox-2c2g\"}}\n"; sleep 2) | TINFIRM_NODE_DRIVER_CONFIG=node-driver.json ./tinfirm-runner-host vm-driver node 2>/dev/null | tail -1 | python3 -c "
import json,sys
r=json.load(sys.stdin)[\"result\"]
print(\"with the table deleted by hand, verify: problems\", r.get(\"problems\"))
for c in r.get(\"checked\",[]):
    if \"firewall\" in c: print(\"checked:\", c)"'
ssh ctl 'sudo sed -i "s/^define control_host = .*/define control_host = 10.0.0.555/" /etc/tinfirm/node-agent/firewall.nft
echo "a typing mistake in the firewall file (control_host = 10.0.0.555), then a restart of the firewall and of the agent:"
sudo systemctl stop tinfirm-node-agent-listener tinfirm-node-agent-helper tinfirm-node-agent-firewall
sudo systemctl start tinfirm-node-agent-listener 2>&1 | head -2 | cut -c1-200
echo "  firewall $(systemctl is-active tinfirm-node-agent-firewall), helper $(systemctl is-active tinfirm-node-agent-helper), listener $(systemctl is-active tinfirm-node-agent-listener)"
sudo journalctl -u tinfirm-node-agent-firewall --since "-10s" --no-pager -o cat | grep -i -E "error|could not" | head -2 | cut -c1-200 | sed "s/^/  /"
sudo sed -i "s/^define control_host = .*/define control_host = 10.0.0.5/" /etc/tinfirm/node-agent/firewall.nft
sudo systemctl start tinfirm-node-agent-listener; sleep 2
echo "corrected and started: firewall $(systemctl is-active tinfirm-node-agent-firewall), helper $(systemctl is-active tinfirm-node-agent-helper), listener $(systemctl is-active tinfirm-node-agent-listener)"
V=$(sudo nft list tables | grep -c tinfirm_node); sudo systemctl restart tinfirm-node-agent-firewall; echo "a restart of the firewall unit: the table before $V, after $(sudo nft list tables | grep -c tinfirm_node); a stop leaves it: $(sudo systemctl stop tinfirm-node-agent-firewall; sudo nft list tables | grep -c tinfirm_node) (the helper, which requires the unit, stops with it: $(systemctl is-active tinfirm-node-agent-helper))"
sudo systemctl start tinfirm-node-agent-listener; sleep 2'
drv cycle 1 | grep -E "^(create|guest|delete)" | cut -c1-140

echo; echo "== 4. R6: a jailer of another name in the configuration"
ssh ctl 'sudo sh -c "sed s#/usr/local/bin/jailer#/usr/local/bin/jailer-1.14# /etc/tinfirm/node-agent/helper.json > /root/helper-bad.json"; sudo /usr/local/bin/tinfirm-node-agent helper --config /root/helper-bad.json 2>&1 | sed "s/\x1b\[[0-9;]*m//g" | tail -2 | cut -c1-240; sudo rm -f /root/helper-bad.json'
