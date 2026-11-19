#!/bin/bash
# ADR-0112 decision 8, path B, as far as it runs without a server: a root shell on a node
# while a VM runs whose user data holds a canary in place of the ticket.
#
# Path B proper needs runs with canary credentials (a git token, the host's token, a model
# token) and the control host's record of what the bridge carried: that needs a server and
# is not here. What this script shows instead:
#
#   1. where the one secret that does reach a node, the run's ticket, is found on it, and
#      where it is not: it must be in the VM's user-data disk and the guest's memory (root on
#      a node reads its runs' tickets: ADR-0112, Consequences) and nowhere else;
#   2. that the agent's port gives a root shell on the node nothing without the control
#      host's key;
#   3. which connections the node itself can open. The agent filters none of them: that is
#      the node's and the control host's firewall, which the role is to set (planned).
#
#   node-root.sh < FILE     (the ticket's value in the user data of the VM that runs, on the
#                            standard input: on a command line, sudo would write it to the journal)
read -r CANARY
[ -n "$CANARY" ] || { echo "usage: node-root.sh < a file with the canary"; exit 2; }
JAILS=${JAILS:-/srv/tinfirm-jails/firecracker}
STATE=${STATE:-/var/lib/tinfirm-node-agent}
CGROUPS=${CGROUPS:-/sys/fs/cgroup/tinfirm-vms}
NODE=${NODE:?"NODE: the address of the node"}
CONTROL=${CONTROL:?"CONTROL: the address of the control host"}
AGENT_PORT=${AGENT_PORT:-7700}
[ "$(id -u)" = 0 ] || { echo "run as root"; exit 2; }
VM=$(ls $JAILS | head -1); F=$(head -1 $CGROUPS/$VM/cgroup.procs)
H=$(systemctl show -p MainPID --value tinfirm-node-agent-helper.service); L=$(systemctl show -p MainPID --value tinfirm-node-agent-listener.service)
found() { if "$@" >/dev/null 2>&1; then echo "FOUND"; else echo "not found"; fi; }
# Every readable mapping of a process, searched for the canary; nothing of it is printed.
memory() { CANARY="$CANARY" python3 - "$1" <<'PY'
import os, re, sys
pid, canary = sys.argv[1], os.environ["CANARY"].encode()
hits = 0
with open(f"/proc/{pid}/maps") as maps, open(f"/proc/{pid}/mem", "rb", 0) as mem:
    for line in maps:
        m = re.match(r"([0-9a-f]+)-([0-9a-f]+) r", line)
        if not m or "[vvar" in line or "[vsyscall" in line:
            continue
        start, end = int(m.group(1), 16), int(m.group(2), 16)
        tail = b""
        while start < end:
            try:
                mem.seek(start); chunk = mem.read(min(1 << 24, end - start))
            except (OSError, ValueError, OverflowError):
                break
            if not chunk:
                break
            hits += (tail + chunk).count(canary)
            tail = chunk[-len(canary):]
            start += len(chunk)
print("FOUND (%d places)" % hits if hits else "not found")
PY
}
echo "the VM $VM (VMM pid $F); helper pid $H; listener pid $L"
echo "== 1. the canary ticket on the node"
echo "where it must be (ADR-0112, Consequences: root on a node reads its runs' tickets):"
echo "  the VM's user-data disk ($JAILS/$VM/root/userdata.disk): $(found grep -a -q -F "$CANARY" $JAILS/$VM/root/userdata.disk)"
echo "  the guest's memory (the VMM's process): $(memory $F)"
echo "  the guest's own disk, through its snapshot device (the guest's Docker keeps the container's environment): $(found grep -a -q -F "$CANARY" /dev/mapper/tinfirm-$VM)"
echo "where it must not be:"
echo "  the listener's spool: $(found grep -r -a -q -F "$CANARY" $STATE/spool) ($(ls $STATE/spool | wc -l) files)"
echo "  the listener's records: $(found grep -r -a -q -F "$CANARY" $STATE/listener)"
echo "  the helper's record: $(found grep -r -a -q -F "$CANARY" $STATE/helper)"
echo "  the VM's console file: $(found grep -a -q -F "$CANARY" $STATE/work/$VM.console)"
echo "  the jail but for the user-data disk: $(found grep -r -a -q -F "$CANARY" --exclude=userdata.disk --exclude=root.disk --exclude=vmlinux $JAILS/$VM)"
echo "  the agent's configuration: $(found grep -r -a -q -F "$CANARY" /etc/tinfirm)"
echo "  the journal of the whole machine: $(journalctl --no-pager -o cat | grep -q -F -e "$CANARY" && echo FOUND || echo "not found")"
echo "  the helper's memory: $(memory $H)"
echo "  the listener's memory: $(memory $L)"
echo "  /run, /tmp, /var/tmp, /dev/shm: $(found grep -r -a -q -F "$CANARY" /run /tmp /var/tmp /dev/shm --exclude-dir=netns --exclude-dir=user --exclude-dir=credentials -s)"
echo "  the swap: $(swapon --noheadings --show=NAME | grep -q . && echo "there is swap: not searched" || echo "the node has no swap")"
echo "== 2. the agent's port, from the node itself, as root, without the control host's key"
python3 - "$NODE" "$AGENT_PORT" <<'PY'
import socket, ssl, sys
s = socket.socket(); s.settimeout(3)
try:
    s.connect((sys.argv[1], int(sys.argv[2])))
    c = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); c.check_hostname = False; c.verify_mode = ssl.CERT_NONE
    t = c.wrap_socket(s)
    t.sendall(b'{"jsonrpc":"2.0","id":1,"method":"hello","params":{"wire":1,"build":"x"}}\n')
    print("  answer:", t.recv(200) or "none: the connection was closed")
except Exception as error:
    print("  nothing (%s)" % type(error).__name__)
PY
echo "== 3. connections the node itself can open, as root (the agent filters none of these)"
for target in "$CONTROL 22 the-control-host's-SSH" "$CONTROL 443 the-control-host,-port-443" "169.254.169.254 80 the-metadata-service" "1.1.1.1 443 the-internet-by-address"; do
    set -- $target; echo "  $3 ($1:$2): $(python3 -c "
import socket,sys
s=socket.socket(); s.settimeout(3)
try: s.connect((sys.argv[1],int(sys.argv[2]))); print('CONNECTED')
except ConnectionRefusedError: print('refused by the peer (it was reached)')
except OSError as e: print('nothing (%s)' % type(e).__name__)" $1 $2)"
done
