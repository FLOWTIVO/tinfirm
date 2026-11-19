#!/bin/bash
# ADR-0112 decision 8, path A, against the node agent's own layout: a shell as one VM's VMM
# user while two VMs run. Run as root on a node on which the agent has made two VMs.
#
#   part A: inside that VM's jail: its user, its chroot and its mount, PID and network
#           namespaces, no capability, NoNewPrivs. The shell is a static busybox that this
#           script copies into the jail and removes again.
#   part B: outside the jail, in the node's own namespaces, with nothing but that user's
#           rights: what a VMM that left its chroot and its namespaces would still be.
#
# The shell is not under Firecracker's seccomp filter (a filter belongs to the process that
# installed it, and this shell is a new one): every refusal below comes from the user, the
# chroot, the namespaces, the file modes and the firewall alone, which is what decision 8's
# second run "with the seccomp filter off" asks for.
#
# Only opens, one-byte reads, connects and signal 0 are tried, and one SIGKILL that must be
# refused. No content of a disk, a memory or a key is printed.
#
# Settings, with the defaults of the crate's README:
JAILS=${JAILS:-/srv/tinfirm-jails/firecracker}
WORK=${WORK:-/var/lib/tinfirm-node-agent/work}
CGROUPS=${CGROUPS:-/sys/fs/cgroup/tinfirm-vms}
ETC=${ETC:-/etc/tinfirm/node-agent}
STATE=${STATE:-/var/lib/tinfirm-node-agent}
HELPER_SOCKET=${HELPER_SOCKET:-/run/tinfirm-node-agent-helper/helper.sock}
NODE=${NODE:?"NODE: the address of the node"}
CONTROL=${CONTROL:?"CONTROL: the address of the control host"}
AGENT_PORT=${AGENT_PORT:-7700}
FIRST_PORT=${FIRST_PORT:-20000}
VM_RANGE_BASE=${VM_RANGE_BASE:-10.231.0}
BRIDGE_PORT=${BRIDGE_PORT:-7443}

[ "$(id -u)" = 0 ] || { echo "run as root"; exit 2; }
BB=$(command -v busybox) || { echo "needs a static busybox"; exit 2; }
mapfile -t JAIL < <(ls "$JAILS" | sort)
[ "${#JAIL[@]}" -ge 2 ] || { echo "needs two VMs under $JAILS, found ${#JAIL[@]}"; exit 2; }
A=${JAIL[0]}; B=${JAIL[1]}
PA=$(head -1 "$CGROUPS/$A/cgroup.procs"); PB=$(head -1 "$CGROUPS/$B/cgroup.procs")
UA=$(awk '/^Uid/{print $2}' /proc/$PA/status); UB=$(awk '/^Uid/{print $2}' /proc/$PB/status)
SLOT_A=$((16#${A##*-})); SLOT_B=$((16#${B##*-}))
GUEST_A=$VM_RANGE_BASE.$((SLOT_A * 8 + 6)); GUEST_B=$VM_RANGE_BASE.$((SLOT_B * 8 + 6))
NS_B=$VM_RANGE_BASE.$((SLOT_B * 8 + 2)); VETH_NODE_A=$VM_RANGE_BASE.$((SLOT_A * 8 + 1))
HELPER=$(pgrep -f 'tinfirm-node-agent helper' | head -1); LISTENER=$(pgrep -f 'tinfirm-node-agent listener' | head -1)

status() { grep -E '^(Uid|Gid|Groups|CapInh|CapPrm|CapEff|CapBnd|NoNewPrivs|Seccomp|Seccomp_filters):' /proc/$1/status | tr '\n\t' '; '; }
r() { out=$("$@" 2>&1 | tr -d '\0'; exit ${PIPESTATUS[0]}); if [ $? = 0 ]; then echo "ALLOWED"; else echo "refused ($(echo "$out" | tail -1 | cut -c1-70))"; fi; }
echo "two VMs: $A (VMM pid $PA, uid $UA, guest $GUEST_A) and $B (VMM pid $PB, uid $UB, guest $GUEST_B)"
echo "VMM of $A: $(status $PA)"
echo "VMM of $B: $(status $PB)"
echo "its namespaces: net $(readlink /proc/$PA/ns/net) pid $(readlink /proc/$PA/ns/pid) mnt $(readlink /proc/$PA/ns/mnt); the node's: net $(readlink /proc/1/ns/net) pid $(readlink /proc/1/ns/pid) mnt $(readlink /proc/1/ns/mnt)"

cp "$BB" /proc/$PA/root/busybox && chmod 755 /proc/$PA/root/busybox
trap 'rm -f /proc/$PA/root/busybox' EXIT
# The shell: the VMM's namespaces and root, then its user; no capability is left after the
# change of user, and none can come back.
inside() { setpriv --no-new-privs --bounding-set -all,+sys_admin,+sys_chroot,+setuid,+setgid,+dac_override,+sys_ptrace \
    nsenter --target $PA --mount --net --pid --root --wd=/ --setuid $UA --setgid $UA /busybox "$@" </dev/null 2>&1; }
echo
echo "== A. inside the jail of $A, as its VMM's user"
inside sleep 3 &
sleep 0.5; SHELL_PID=$(pgrep -n -u $UA -x busybox)
echo "a process of the shell (pid $SHELL_PID on the node): $(status $SHELL_PID) (the shell sees no /proc: $(r inside ls /proc))"; wait
echo "what / holds: $(inside ls -A / | tr '\n' ' ')"
echo "interfaces: $(inside ip -o link 2>&1 | cut -d: -f2 | tr '\n' ' '); routes: $(inside ip route 2>&1 | tr '\n' ';')"
echo "-- the other VM, by every name the node has for it"
echo "its jail: $(r inside ls $JAILS/$B/root) ; the jails' directory: $(r inside ls $JAILS) ; its snapshot file: $(r inside head -c1 $WORK/$B.snapshot) ; its snapshot device: $(r inside head -c1 /dev/mapper/tinfirm-$B) ; its API socket: $(r inside ls $JAILS/$B/root/run/firecracker.socket)"
MM_B=$(stat -L -c '%Hr %Lr' /dev/mapper/tinfirm-$B)
echo "a device node of its disk made here (mknod b $MM_B): $(r inside mknod /other.disk b $MM_B)"
echo "signal 0 to its VMM (pid $PB on the node): $(r inside kill -0 $PB) ; to pid 1 of its own PID namespace, its own VMM: $(r inside kill -0 1)"
echo "-- the agent and the node"
echo "the helper's socket: $(r inside ls $HELPER_SOCKET) ; the node's key: $(r inside head -c1 $ETC/node.key) ; the spool: $(r inside ls $STATE/spool) ; /etc: $(r inside ls /etc) ; /home: $(r inside ls /home)"
echo "-- leaving the chroot and the namespaces"
echo "chroot: $(r inside chroot / /busybox true) ; mount proc: $(r inside mount -t proc proc /run) ; a new network namespace: $(r inside unshare -n /busybox true) ; a new mount namespace: $(r inside unshare -m /busybox true) ; a new user namespace: $(r inside unshare -U /busybox true)"
echo "write outside its own directories: $(r inside touch /dev/x) (its own, by design) ; a set-uid copy of the shell, run: uid $(inside sh -c '/busybox cp /busybox /run/id && /busybox chmod 4755 /run/id && /run/id -u; /busybox rm -f /run/id' | tail -1) (its own: the file is its own, and NoNewPrivs)"
echo "-- its own things, which it holds by design"
echo "its own disk: $(r inside head -c1 /root.disk) ; its own user data: $(r inside head -c1 /userdata.disk) ; /dev/kvm: $(r inside head -c0 /dev/kvm)"
echo "-- the network, from its namespace"
for target in "$GUEST_A $BRIDGE_PORT its-own-guest's-bridge" "$GUEST_B $BRIDGE_PORT the-other-guest's-bridge" "$NS_B $BRIDGE_PORT the-other-VM's-namespace" \
    "$NODE $((FIRST_PORT + SLOT_B)) the-other-VM's-published-port" "$NODE $((FIRST_PORT + SLOT_A)) its-own-published-port" "$NODE $AGENT_PORT the-agent's-port" \
    "$NODE 22 the-node's-SSH" "$VETH_NODE_A 22 the-node's-end-of-its-veth" "$CONTROL 22 the-control-host" "169.254.169.254 80 a-metadata-service" \
    "168.63.129.16 80 Azure's-host-agent" "1.1.1.1 443 the-internet"; do
    set -- $target; echo "connect to $3 ($1:$2): $(r inside nc -w 2 $1 $2)"
done

outside() { setpriv --reuid $UA --regid $UA --clear-groups --inh-caps=-all --bounding-set=-all --no-new-privs "$@" </dev/null 2>&1; }
connect() { outside python3 -c "import socket,sys
s=socket.socket(socket.AF_UNIX if len(sys.argv)==2 else socket.AF_INET); s.settimeout(2)
s.connect(sys.argv[1] if len(sys.argv)==2 else (sys.argv[1],int(sys.argv[2])))" "$@"; }
echo
echo "== B. outside the jail, in the node's namespaces, with only the rights of uid $UA"
echo "the shell itself: $(outside sh -c 'grep -E "^(Uid|CapEff|CapBnd|NoNewPrivs):" /proc/self/status | tr "\n\t" "; "')"
echo "-- the other VM"
echo "its jail: $(r outside ls $JAILS/$B) ; its chroot: $(r outside ls $JAILS/$B/root) ; its disk's node there: $(r outside head -c1 $JAILS/$B/root/root.disk) ; its user data: $(r outside head -c1 $JAILS/$B/root/userdata.disk) ; its API socket: $(r connect $JAILS/$B/root/run/firecracker.socket)"
echo "its snapshot file: $(r outside head -c1 $WORK/$B.snapshot) ; its console file: $(r outside head -c1 $WORK/$B.console) ; the work directory: $(r outside ls $WORK) ; its snapshot device: $(r outside head -c1 /dev/mapper/tinfirm-$B) ; write to it: $(r outside sh -c ": >> /dev/mapper/tinfirm-$B")"
echo "its VMM's memory: $(r outside sh -c ": < /proc/$PB/mem") ; environment: $(r outside head -c1 /proc/$PB/environ) ; root: $(r outside ls /proc/$PB/root/) ; open files: $(r outside ls /proc/$PB/fd) ; command line: $(r outside head -c1 /proc/$PB/cmdline) (public on Linux; it holds no secret)"
echo "trace its VMM: $(outside python3 -c "
import ctypes
l=ctypes.CDLL(None,use_errno=True); r=l.ptrace(16,$PB,0,0)
print('ALLOWED' if r==0 else 'refused (errno %d)'%ctypes.get_errno())
if r==0: l.ptrace(17,$PB,0,0)" | tail -1) ; signal 0: $(r outside kill -0 $PB) ; SIGKILL: $(r outside kill -KILL $PB)"
echo "-- its own VM's things, by the node's names"
echo "its own jail directory: $(r outside ls $JAILS/$A) (root's, 0700: even its own chroot has no path from outside) ; its own snapshot file: $(r outside head -c1 $WORK/$A.snapshot) ; the list of jails: $(r outside ls $JAILS) (their names say nothing of a run)"
echo "-- the agent"
echo "the helper's socket: $(r connect $HELPER_SOCKET) ; its directory: $(r outside ls ${HELPER_SOCKET%/*}) ; the helper's record: $(r outside ls $STATE/helper) ; the spool: $(r outside ls $STATE/spool) ; the listener's records: $(r outside ls $STATE/listener)"
echo "the node's key: $(r outside head -c1 $ETC/node.key) ; the listener's configuration: $(r outside head -c1 $ETC/listener.json) ; the helper's configuration: $(r outside head -c1 $ETC/helper.json) (0644: paths and numbers, no secret) ; write it: $(r outside sh -c ": >> $ETC/helper.json") ; the shared image: $(r outside head -c1 $(sed -n 's/.*"image": *"\([^"]*\)".*/\1/p' $ETC/helper.json | head -1)) (0444, by design)"
echo "the helper's memory: $(r outside sh -c ": < /proc/$HELPER/mem") ; signal 0 to the helper: $(r outside kill -0 $HELPER) ; to the listener: $(r outside kill -0 $LISTENER)"
echo "-- what needs root"
echo "a network namespace: $(r outside ip netns add x) ; a firewall table: $(r outside nft add table inet x) ; read the firewall: $(r outside nft list tables) ; the device-mapper: $(r outside dmsetup ls) ; a loop device: $(r outside losetup -f /etc/hostname) ; a cgroup: $(r outside mkdir $CGROUPS/x) ; kill a VM by its cgroup: $(r outside sh -c "echo 1 > $CGROUPS/$B/cgroup.kill") ; a tap device: $(r outside ip tuntap add dev tapx mode tap)"
echo "-- the network, from the node itself"
for target in "$NODE $((FIRST_PORT + SLOT_B)) the-other-VM's-published-port" "$NS_B $BRIDGE_PORT the-other-VM's-namespace" "$GUEST_B $BRIDGE_PORT the-other-guest" \
    "$NODE $AGENT_PORT the-agent's-port" "$CONTROL 22 the-control-host's-SSH"; do
    set -- $target; echo "connect to $3 ($1:$2): $(r connect $1 $2)"
done
echo "the agent's port without the control host's key: $(outside python3 -c "
import socket,ssl
s=socket.socket(); s.settimeout(3)
try:
    s.connect(('$NODE',$AGENT_PORT)); c=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT); c.check_hostname=False; c.verify_mode=ssl.CERT_NONE
    t=c.wrap_socket(s); t.sendall(b'{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"hello\",\"params\":{\"wire\":1,\"build\":\"x\"}}\n'); print('answer:', t.recv(200) or 'closed, no answer')
except Exception as e: print('refused (%s)' % type(e).__name__)" | tail -1)"
echo
echo "== after: VMM of $A $(kill -0 $PA 2>/dev/null && echo runs || echo GONE), VMM of $B $(kill -0 $PB 2>/dev/null && echo runs || echo GONE), helper $(kill -0 $HELPER 2>/dev/null && echo runs || echo GONE), listener $(kill -0 $LISTENER 2>/dev/null && echo runs || echo GONE), jails $(ls $JAILS | wc -l)"
