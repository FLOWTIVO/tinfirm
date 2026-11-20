#!/usr/bin/env python3
"""Checks what render.yml rendered against the rules of the node agent's README and the
runner host's README, without a target machine (deploy/ansible/README.md, "Checks").

    python3 -I deploy/ansible/tests/check-rendered.py RENDER_DIR

It checks helper.json and listener.json field by field against the tables of
runner/crates/tinfirm-node-agent/README.md ("Configuration"), node-driver.json against
runner/README.md ("Sandbox nodes"), the rendered firewall against the units' own file with the
same values, and the units' command lines. Where nft can check a ruleset here (as root, or in a
user and network namespace of its own), it checks both firewall files with `nft -c -f`.
"""

import ipaddress
import json
import re
import shutil
import subprocess
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
UNITS = HERE.parent.parent.parent / "runner" / "crates" / "tinfirm-node-agent" / "units"
PLAIN_PATH = re.compile(r"^/[A-Za-z0-9/_.-]*$")
TEMPLATE_NAME = re.compile(r"^[a-z0-9][a-z0-9-]{0,62}$")
PIN = re.compile(r"^[0-9a-f]{64}$")
failures = []


def check(condition, what):
    print(("ok    " if condition else "FAIL  ") + what)
    if not condition:
        failures.append(what)


def plain(path):
    return isinstance(path, str) and bool(PLAIN_PATH.match(path)) and not any(
        part in (".", "..") for part in path.split("/")
    )


def is_int(value):
    return isinstance(value, int) and not isinstance(value, bool)


def keys(obj, required, optional=()):
    return set(required) <= set(obj) <= set(required) | set(optional)


def helper(h):
    check(keys(h, ["listenerUid", "socket", "spool", "state", "maxVms", "vmUidBase", "nodeFirewall",
                   "network", "firecracker", "templates"]), "helper.json: exactly the README's fields")
    # The role loads the node's own firewall table, so the helper requires it.
    check(h["nodeFirewall"] == "required", "helper.json: nodeFirewall required")
    check(is_int(h["listenerUid"]) and h["listenerUid"] != 0, "helper.json: listenerUid a number, not 0")
    check(is_int(h["maxVms"]) and 1 <= h["maxVms"] <= 1024, "helper.json: maxVms 1 to 1024")
    base, count = h["vmUidBase"], h["maxVms"]
    check(is_int(base) and base >= 100000 and base + count < 2**31,
          "helper.json: vmUidBase at least 100000, vmUidBase + maxVms below 2^31")
    check(not base <= h["listenerUid"] < base + count, "helper.json: listenerUid not one of the VMs' users")
    n = h["network"]
    check(keys(n, ["vmRange", "nodeAddress", "controlHost", "firstPort"]), "helper.json: network's fields")
    net = ipaddress.ip_network(n["vmRange"], strict=True)
    check(net.version == 4 and 8 <= net.prefixlen <= 29, "helper.json: vmRange A.B.C.D/P, P 8 to 29, no host bits")
    check(net.num_addresses >= 8 * count, "helper.json: vmRange holds maxVms slots of 8 addresses")
    node, control = ipaddress.IPv4Address(n["nodeAddress"]), ipaddress.IPv4Address(n["controlHost"])
    check(node != control and not node.is_loopback and not control.is_loopback
          and not node.is_unspecified and not control.is_unspecified,
          "helper.json: controlHost and nodeAddress two addresses, neither loopback nor 0.0.0.0")
    check(node not in net and control not in net, "helper.json: vmRange holds neither nodeAddress nor controlHost")
    check(is_int(n["firstPort"]) and n["firstPort"] >= 1024 and n["firstPort"] + count - 1 <= 65535,
          "helper.json: firstPort at least 1024, firstPort + maxVms - 1 at most 65535")
    f = h["firecracker"]
    check(keys(f, ["jailer", "firecracker", "chrootBase", "cgroupParent", "work"], ["guestConsole"]),
          "helper.json: firecracker's fields")
    check(Path(f["firecracker"]).name == "firecracker", "helper.json: firecracker.firecracker a file named firecracker")
    check(bool(re.match(r"^[A-Za-z0-9_-]{1,64}$", f["cgroupParent"])), "helper.json: cgroupParent one name")
    check(isinstance(f.get("guestConsole", False), bool), "helper.json: guestConsole a boolean")
    paths = [h["socket"], h["spool"], h["state"], f["jailer"], f["firecracker"], f["chrootBase"], f["work"]]
    check(h["templates"] != {}, "helper.json: at least one template")
    for name, t in h["templates"].items():
        check(bool(TEMPLATE_NAME.match(name)), f"helper.json: template name {name}")
        check(keys(t, ["vcpus", "memoryMib", "snapshotMib", "image", "kernel"], ["bridgePort", "pidsMax"]),
              f"helper.json: template {name}'s fields")
        check(all(is_int(t[k]) for k in ("vcpus", "memoryMib", "snapshotMib", "bridgePort", "pidsMax")),
              f"helper.json: template {name}'s numbers are numbers")
        check(1 <= t["vcpus"] <= 32 and 128 <= t["memoryMib"] <= 262144 and 64 <= t["snapshotMib"] <= 1048576
              and 1 <= t["pidsMax"] <= 4096 and t["bridgePort"] != 0, f"helper.json: template {name}'s ranges")
        paths += [t["image"], t["kernel"]]
    check(all(plain(p) for p in paths), "helper.json: every path absolute and plain")
    # The units name these paths.
    check(h["socket"] == "/run/tinfirm-node-agent-helper/helper.sock" and h["state"] == "/var/lib/tinfirm-node-agent/helper"
          and f["work"] == "/var/lib/tinfirm-node-agent/work" and f["chrootBase"] == "/srv/tinfirm-jails",
          "helper.json: the paths the helper's unit makes writable and tmpfiles.d makes")


def listener(l, h):
    check(keys(l, ["helperSocket", "spool", "state", "address"], ["tls", "socket", "peerBuild", "watchMs"]),
          "listener.json: the README's fields")
    check("tls" in l and "socket" not in l, "listener.json: tls alone (socket is the single machine's)")
    t = l["tls"]
    check(keys(t, ["listen", "controlHost", "certificate", "key", "clientSha256"]), "listener.json: tls's fields")
    check(bool(PIN.match(t["clientSha256"])), "listener.json: clientSha256 64 lower-case hex digits")
    check(t["controlHost"] == h["network"]["controlHost"], "listener.json: tls.controlHost the helper's network.controlHost")
    host, port = t["listen"].rsplit(":", 1)
    check(host == l["address"] == h["network"]["nodeAddress"], "listener.json: listen and address the node's address")
    first, last = h["network"]["firstPort"], h["network"]["firstPort"] + h["maxVms"] - 1
    check(not first <= int(port) <= last, "listener.json: the listener's port not one of the published ports")
    check(l["helperSocket"] == h["socket"] and l["spool"] == h["spool"], "listener.json: the helper's socket and spool")
    check(l["state"] == "/var/lib/tinfirm-node-agent/listener", "listener.json: the state its unit makes writable")
    check(l.get("peerBuild", "warn") in ("warn", "refuse"), "listener.json: peerBuild warn or refuse")
    check(is_int(l.get("watchMs", 1000)) and 100 <= l.get("watchMs", 1000) <= 60000, "listener.json: watchMs 100 to 60000")


def driver(d):
    check(keys(d, ["nodes"], ["peerBuild"]), "node-driver.json: nodes and peerBuild")
    check(1 <= len(d["nodes"]) <= 64, "node-driver.json: 1 to 64 nodes")
    names = [node["name"] for node in d["nodes"]]
    check(len(names) == len(set(names)), "node-driver.json: each name once")
    check(names == ["node-1", "node-2"], "node-driver.json: the pinned nodes alone, node-3 (no pin) left out")
    for node in d["nodes"]:
        check(keys(node, ["name", "address", "nodeSha256", "certificate", "key", "templates"]),
              f"node-driver.json: {node['name']}: a TLS node's fields")
        check(bool(re.match(r"^[a-z0-9-]{1,32}$", node["name"])), f"node-driver.json: {node['name']}: name")
        check(bool(PIN.match(node["nodeSha256"])), f"node-driver.json: {node['name']}: nodeSha256")
        check(bool(re.match(r"^[^:]+:[1-9][0-9]{0,4}$", node["address"])), f"node-driver.json: {node['name']}: HOST:PORT")
        check(node["templates"] and all(TEMPLATE_NAME.match(t) for t in node["templates"]),
              f"node-driver.json: {node['name']}: templates")
        check(plain(node["certificate"]) and plain(node["key"]), f"node-driver.json: {node['name']}: absolute paths")


def firewall(rendered):
    units = (UNITS / "tinfirm-node-agent.nft").read_text()
    # The template adds one line, the second, for ansible_managed.
    lines = rendered.splitlines(keepends=True)
    check(lines[1].startswith("# Ansible managed"), "firewall.nft: the ansible_managed line")
    check("".join(lines[:1] + lines[2:]) == units, "firewall.nft: the units' file, with the README's example values")


def unit_lines(text):
    return re.sub(r" *\\\n\s*", " ", text)


def units(docker, vm):
    d, v = unit_lines(docker), unit_lines(vm)
    check("--sandbox-runtime \"kata\"" in d and "docker.service" in d, "docker unit: Kata and Docker")
    exec_vm = next(line for line in v.splitlines() if line.startswith("ExecStart="))
    check("--executor vm --vm-driver node --vm-template \"sandbox-4c8g\" --vm-port 7443" in exec_vm,
          "control host unit: --executor vm --vm-driver node --vm-template --vm-port")
    for refused in ("--sandbox-runtime", "--sandbox-bridge", "--egress-proxy"):
        check(refused not in exec_vm, f"control host unit: no {refused} (refused or always on)")
    check("docker.service" not in v, "control host unit: no Docker")
    check("--vm-probe \"tcp:db.internal:5432\"" in exec_vm and "--egress-allow \"registry.npmjs.org\"" in exec_vm
          and "--sandbox-env \"TINFIRM_SANDBOX_NPM_REGISTRY=" in exec_vm and "--sandbox-docker" in exec_vm,
          "control host unit: probes, egress allowlist, sandbox settings, the agent's Docker")
    check("\n\n\n" not in v and "{" not in v.replace("${", ""), "control host unit: no Jinja left, no double blank line")


def nft_check(path):
    nft = shutil.which("nft")
    if not nft:
        print(f"skip  nft -c -f {path.name}: nft is not installed")
        return
    for argv in ([nft, "-c", "-f", str(path)], ["unshare", "-rn", nft, "-c", "-f", str(path)]):
        try:
            done = subprocess.run(argv, capture_output=True, text=True, timeout=30)
        except (OSError, subprocess.TimeoutExpired) as e:
            print(f"skip  {' '.join(argv)}: {e}")
            continue
        if done.returncode == 0:
            check(True, f"{' '.join(argv[:-1])} {path.name}")
            return
        if "Operation not permitted" in done.stderr or "unshare" in done.stderr:
            print(f"skip  {' '.join(argv)}: {done.stderr.strip()}")
            continue
        check(False, f"{' '.join(argv)}: {done.stderr.strip()}")
        return


def main():
    out = Path(sys.argv[1])
    h = json.loads((out / "helper.json").read_text())
    helper(h)
    listener(json.loads((out / "listener.json").read_text()), h)
    driver(json.loads((out / "node-driver.json").read_text()))
    derived = json.loads((out / "derived.json").read_text())
    check(derived["nodes_unpinned"] == ["node-3"] and derived["nodes_pinned"] == ["node-1", "node-2"],
          "the play names node-3 as waiting, and pins node-1 and node-2")
    firewall((out / "firewall.nft").read_text())
    one = (out / "firewall-one-vm-static.nft").read_text()
    check("define published_ports = 20000\n" in one and "udp sport 67" not in one,
          "firewall for one VM and a static address: one port, no DHCP rules")
    check("define ssh_port = 2222\n" in one and "define ssh_port = 22\n" in (out / "firewall.nft").read_text(),
          "firewall: ssh_port from tinfirm_sandbox_node_ssh_port, 22 by default")
    dropin = (out / "listener-control-host.conf").read_text()
    check("\n[Service]\nIPAddressAllow=10.0.0.5\n" in dropin, "listener's drop-in: IPAddressAllow the control host")
    units((out / "tinfirm-runner-host.docker.service").read_text(), (out / "tinfirm-runner-host.vm.service").read_text())
    for name in ("firewall.nft", "firewall-one-vm-static.nft"):
        nft_check(out / name)
    print(f"\n{len(failures)} failed" if failures else "\nall passed")
    return 1 if failures else 0


if __name__ == "__main__":
    sys.exit(main())
