#!/bin/bash
# ADR-0112 decision 8, "the agent's two processes": a shell as the listener's user must fail
# to do any of the helper's things directly, and get nothing from the helper but its fixed
# list with values it accepts. Run as root on a node while the agent runs under its units,
# with at least one VM made.
#
#   part A: as the listener's user and group with no capability, on the node at large (more
#           than the confined service has);
#   part B: inside the listener service's own namespaces: what its unit lets it see;
#   part C: misuse of the helper's socket (helper-misuse.py).
#
# Only opens, one-byte reads, connects and signal 0 are tried, and one SIGKILL that must be
# refused. No content is printed.
JAILS=${JAILS:-/srv/tinfirm-jails/firecracker}
WORK=${WORK:-/var/lib/tinfirm-node-agent/work}
CGROUPS=${CGROUPS:-/sys/fs/cgroup/tinfirm-vms}
ETC=${ETC:-/etc/tinfirm/node-agent}
STATE=${STATE:-/var/lib/tinfirm-node-agent}
HELPER_SOCKET=${HELPER_SOCKET:-/run/tinfirm-node-agent-helper/helper.sock}
LISTENER_USER=${LISTENER_USER:-tinfirm-node-listener}
TEMPLATE=${TEMPLATE:?"TEMPLATE: a template of the helper configuration"}
HERE=$(cd "$(dirname "$0")" && pwd)

[ "$(id -u)" = 0 ] || { echo "run as root"; exit 2; }
U=$(id -u $LISTENER_USER); G=$(id -g $LISTENER_USER)
L=$(systemctl show -p MainPID --value tinfirm-node-agent-listener.service); H=$(systemctl show -p MainPID --value tinfirm-node-agent-helper.service)
VM=$(ls $JAILS | head -1); F=$(head -1 $CGROUPS/$VM/cgroup.procs)
[ -n "$VM" ] && [ "$L" != 0 ] && [ "$H" != 0 ] || { echo "needs the agent's two services and a VM"; exit 2; }
status() { grep -E '^(Uid|Gid|CapEff|CapBnd|NoNewPrivs|Seccomp|Seccomp_filters):' /proc/$1/status | tr '\n\t' '; '; }
as() { setpriv --reuid $U --regid $G --clear-groups --inh-caps=-all --bounding-set=-all --no-new-privs "$@" </dev/null 2>&1; }
r() { out=$("$@" 2>&1 | tr -d '\0'; exit ${PIPESTATUS[0]}); if [ $? = 0 ]; then echo "ALLOWED"; else echo "refused ($(echo "$out" | tail -1 | cut -c1-70))"; fi; }
UNIX='import socket,sys; s=socket.socket(socket.AF_UNIX); s.settimeout(2); s.connect(sys.argv[1])'
echo "listener: pid $L, $(status $L)"
echo "helper:   pid $H, $(status $H)"
echo "a VMM:    pid $F in $VM, $(status $F)"
echo "systemd-analyze security: $(systemd-analyze security tinfirm-node-agent-listener.service 2>/dev/null | tail -1) ; $(systemd-analyze security tinfirm-node-agent-helper.service 2>/dev/null | tail -1)"
echo
echo "== A. as the listener's user (uid $U), directly, on the node at large"
echo "a tap device: $(r as ip tuntap add dev tapx mode tap) ; a firewall table: $(r as nft add table inet x) ; read the firewall: $(r as nft list tables) ; list the device-mapper: $(r as dmsetup ls) ; map a device: $(r as dmsetup create x --table '0 8 zero') ; a loop device: $(r as losetup -f /etc/hostname) ; a cgroup: $(r as mkdir $CGROUPS/x) ; kill a VM by its cgroup: $(r as sh -c "echo 1 > $CGROUPS/$VM/cgroup.kill") ; a network namespace: $(r as ip netns add x)"
echo "a VM's jail: $(r as ls $JAILS/$VM) ; its disk's node: $(r as head -c1 $JAILS/$VM/root/root.disk) ; its user-data disk: $(r as head -c1 $JAILS/$VM/root/userdata.disk) ; its snapshot file: $(r as head -c1 $WORK/$VM.snapshot) ; its snapshot device: $(r as head -c1 /dev/mapper/tinfirm-$VM) ; its console: $(r as head -c1 $WORK/$VM.console) ; its API socket: $(r as python3 -c "import socket;s=socket.socket(socket.AF_UNIX);s.connect('$JAILS/$VM/root/run/firecracker.socket')")"
echo "the helper's record: $(r as ls $STATE/helper) ; the work directory: $(r as ls $WORK) ; change the agent's program: $(r as sh -c ': >> /usr/local/bin/tinfirm-node-agent') ; the helper's configuration, read: $(r as head -c1 $ETC/helper.json) (no secret) ; written: $(r as sh -c ": >> $ETC/helper.json") ; its own configuration, written: $(r as sh -c ": >> $ETC/listener.json") ; the jailer, written: $(r as sh -c ': >> /usr/local/bin/jailer') ; the shared image, written: $(r as sh -c ": >> $(sed -n 's/.*"image": *"\([^"]*\)".*/\1/p' $ETC/helper.json | head -1)")"
echo "signal 0 to a VMM: $(r as kill -0 $F) ; SIGKILL to it: $(r as kill -KILL $F) ; signal 0 to the helper: $(r as kill -0 $H) ; the helper's memory: $(r as sh -c ": < /proc/$H/mem") ; its environment: $(r as head -c1 /proc/$H/environ) ; a VMM's memory: $(r as sh -c ": < /proc/$F/mem")"
echo "its own things, which it holds by design: the node's key: $(r as head -c1 $ETC/node.key) ; its spool: $(r as ls $STATE/spool) ; its records: $(r as ls $STATE/listener) ; the helper's socket: $(r as python3 -c "$UNIX" $HELPER_SOCKET)"
groupmate() { setpriv --reuid 65534 --regid $G --clear-groups python3 - "$HELPER_SOCKET" <<'PY' 2>&1 | tail -1
import socket, sys
s = socket.socket(socket.AF_UNIX); s.settimeout(3); s.connect(sys.argv[1])
try:
    s.sendall(b'{"v":1,"op":"list"}\n')
    print("connected; answer:", s.recv(100) or "none (closed)")
except OSError as error:
    print("connected, then closed by the helper before it read a byte (%s)" % type(error).__name__)
PY
}
echo "another user (nobody) at the helper's socket: $(r setpriv --reuid 65534 --regid 65534 --clear-groups python3 -c "import socket;s=socket.socket(socket.AF_UNIX);s.connect('$HELPER_SOCKET')") ; a user of the listener's group that is not the listener (uid 65534, gid $G): $(groupmate)"
echo
echo "== B. inside the listener service's namespaces (its unit's view)"
inl() { nsenter -t $L -m -n -i -- setpriv --reuid $U --regid $G --clear-groups --inh-caps=-all --bounding-set=-all --no-new-privs "$@" </dev/null 2>&1; }
echo "/home: $(r inl ls /home/azureuser) ; write /etc: $(r inl touch /etc/x) ; write /usr: $(r inl touch /usr/local/bin/x) ; /dev/kvm: $(r inl head -c0 /dev/kvm) ; /dev/mapper/control: $(r inl head -c0 /dev/mapper/control) ; block devices it sees: $(inl sh -c 'ls /dev | grep -c -E "^(sd|nvme|dm-|loop)"') ; the jails: $(r inl ls $JAILS/$VM) ; the work directory: $(r inl ls $WORK) ; /proc of others: $(inl sh -c 'ls /proc | grep -c "^[0-9]"') process(es) visible"
echo "its own: the spool, written: $(r inl sh -c ": > $STATE/spool/x && rm $STATE/spool/x") ; the helper's socket: $(r inl python3 -c "import socket;s=socket.socket(socket.AF_UNIX);s.connect('$HELPER_SOCKET')")"
echo
echo "== C. misuse of the helper's socket, as the listener's user"
setpriv --reuid $U --regid $G --clear-groups --inh-caps=-all --bounding-set=-all --no-new-privs \
    python3 - $HELPER_SOCKET $STATE/spool $TEMPLATE < "$HERE/helper-misuse.py" 2>&1
echo
echo "== after: helper $(systemctl is-active tinfirm-node-agent-helper.service), listener $(systemctl is-active tinfirm-node-agent-listener.service), the VMM $(kill -0 $F 2>/dev/null && echo still runs || echo GONE), VMMs $(pgrep -x firecracker | wc -l), jails $(ls $JAILS | wc -l), device-mapper devices $(dmsetup ls | grep -c tinfirm), veths $(ip -o link 2>/dev/null | grep -c ' tfv'), tables $(nft list tables | grep -c tinfirm_), files in the spool $(ls $STATE/spool | wc -l)"
