#!/bin/bash
# Round 10, from the management machine: io.max from the template, on a real VM.
DRV='cd ~/tinfirm-host && python3 control-host.py ./tinfirm-runner-host node-driver.json sandbox-2c2g tinfirm-runner-pi:local'
drv() { ssh host "$DRV $* 2>&1"; }
setio() { ssh ctl "sudo python3 - '$1' <<'P'
import json,sys
p='/etc/tinfirm/node-agent/helper.json'; c=json.load(open(p)); t=c['templates']['sandbox-2c2g']
t.pop('ioMax',None)
if sys.argv[1]!='none': t['ioMax']=json.loads(sys.argv[1])
json.dump(c,open(p,'w'),indent=1)
P
sudo systemctl restart tinfirm-node-agent-helper; sleep 1; sudo systemctl restart tinfirm-node-agent-listener; sleep 2"; }
ready() { T0=$(date +%s%N); ID=$(drv create "$1" | sed -n 's/.*id \([^;]*\);.*/\1/p'); until ssh host "python3 -c \"
import socket,sys
s=socket.socket(); s.settimeout(1)
try:
    s.connect(('10.0.0.6',20000)); s.send(b'x'); sys.exit(0 if s.recv(1) else 1)
except Exception: sys.exit(1)\""; do [ $(( ($(date +%s%N)-T0)/1000000000 )) -gt 180 ] && break; done; echo "$1: the guest's bridge answers $(( ($(date +%s%N)-T0)/1000000 )) ms after the create was sent"; }
show() { ssh ctl 'J=$(sudo ls /srv/tinfirm-jails/firecracker | head -1); C=/sys/fs/cgroup/tinfirm-vms/$J; echo "  the snapshot'"'"'s device $(sudo dmsetup info -c --noheadings -o major,minor tinfirm-$J); io.max of the VM'"'"'s cgroup: $(sudo cat $C/io.max | tr "\n" ";"); io.stat: $(sudo cat $C/io.stat | tr "\n" ";")"'; }
echo "== io.max from the template, on a real VM (guest: the image's boot, Docker's start and the bridge's)"
echo "-- no ioMax in the template"
setio none; ready no-limit; show; echo "  delete: $(drv delete $ID | grep -E '^delete:|rror' | cut -c1-120)"
echo "-- ioMax: readBytesPerSecond 4 MiB, writeBytesPerSecond 4 MiB, readsPerSecond 200, writesPerSecond 200"
setio '{"readBytesPerSecond": 4194304, "writeBytesPerSecond": 4194304, "readsPerSecond": 200, "writesPerSecond": 200}'
drv cycle 0 | grep -E "^verify" | cut -c1-120
ready limited; show; echo "  delete: $(drv delete $ID | grep -E '^delete:|rror' | cut -c1-120)"
echo "-- back to no limit"
setio none; ready no-limit-again; echo "  delete: $(drv delete $ID | grep -E '^delete:|rror' | cut -c1-120)"
ssh ctl 'echo "left: jails $(sudo ls /srv/tinfirm-jails/firecracker | wc -l), VMMs $(pgrep -x firecracker | wc -l), device-mapper $(sudo dmsetup ls | grep -c tinfirm), cgroups $(sudo ls /sys/fs/cgroup/tinfirm-vms 2>/dev/null | grep -c tfvm)"'
