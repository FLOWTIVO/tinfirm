#!/usr/bin/env python3
"""The control host's side of the two-machine run (docs/reviews/2026-11-19-node-agent-execution).

It starts the runner host's own node driver (`tinfirm-runner-host vm-driver node`) and speaks
the driver protocol with it on its stdin and stdout, as the runner host does: hello, verify,
create, describe, list, delete. The driver relays each call to the node agent's listener over
TLS with the pinned certificates. No server and no login is involved: the user data names a
ticket and a pin that are no one's, so the bridge in the guest listens and refuses every host.

    control-host.py RUNNER_HOST_BINARY DRIVER_CONFIG TEMPLATE RUNNER_IMAGE [cycle N | create | delete ID | list]

Each call's time is that of the driver's answer, measured here with a monotonic clock.
"""
import json, socket, ssl, subprocess, sys, time

binary, config, template, image = sys.argv[1:5]
action = sys.argv[5] if len(sys.argv) > 5 else "cycle"
LABELS = {"tinfirm-host": "two-machine-run"}
driver = subprocess.Popen([binary, "vm-driver", "node", "--config", config],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
serial = 0


def call(method, params):
    global serial
    serial += 1
    start = time.monotonic()
    driver.stdin.write(json.dumps({"jsonrpc": "2.0", "id": serial, "method": method, "params": params}) + "\n")
    driver.stdin.flush()
    answer = json.loads(driver.stdout.readline())
    ms = round((time.monotonic() - start) * 1000)
    if "error" in answer:
        print(f"{method}: ERROR after {ms} ms: {answer['error']}")
        return None, ms
    return answer["result"], ms


def probe(address, port):
    """What a TCP connection and a TLS client hello get: `tls ...`, `tcp`, `refused`, `nothing`."""
    s = socket.socket()
    s.settimeout(3)
    try:
        s.connect((address, port))
    except ConnectionRefusedError:
        return "refused"
    except OSError:
        return "nothing"
    context = ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
    context.check_hostname = False
    context.verify_mode = ssl.CERT_NONE
    try:
        return "tls " + context.wrap_socket(s).version()
    except ssl.SSLError:
        return "tls alert"
    except OSError:
        return "tcp"


def user_data(run):
    return json.dumps({"tinfirm": 1, "image": image, "env": {
        "TINFIRM_BRIDGE_LISTEN": "0.0.0.0:7443", "TINFIRM_BRIDGE_RUN": run,
        "TINFIRM_BRIDGE_TICKET": "a-ticket-of-no-run", "TINFIRM_BRIDGE_PIN": "00" * 32}})


def create(run):
    made, ms = call("create", {"template": template, "name": run, "userData": user_data(run), "labels": LABELS})
    if made is None:
        sys.exit(1)
    vm, described_ms = call("describe", {"id": made["id"]})
    print(f"create: {ms} ms, id {made['id']}; describe: {described_ms} ms, {vm['state']} at {vm.get('address')}:{vm.get('port')}")
    return made["id"], vm, time.monotonic()


hello, ms = call("hello", {"protocol": 1})
print(f"hello: {ms} ms, {hello}")
verified, ms = call("verify", {"template": template})
print(f"verify: {ms} ms, problems {verified['problems']}, maxVms {verified['maxVms']}")
for line in verified.get("checked", []):
    print(f"  checked: {line}")

if action == "cycle":
    for i in range(int(sys.argv[6]) if len(sys.argv) > 6 else 1):
        vm_id, vm, made_at = create(f"two-machine-run-{i}")
        answer = probe(vm["address"], vm["port"])
        while not answer.startswith("tls") and time.monotonic() - made_at < 120:
            time.sleep(0.1)
            answer = probe(vm["address"], vm["port"])
        ready = round((time.monotonic() - made_at) * 1000)
        listed, list_ms = call("list", {"labels": LABELS})
        print(f"guest ready: {ready} ms after create ({vm['address']}:{vm['port']} answers `{answer}`); "
              f"next port: `{probe(vm['address'], vm['port'] + 1)}`; list: {list_ms} ms, {len(listed['vms'])} VM")
        _, ms = call("delete", {"id": vm_id, "labels": LABELS})
        gone, _ = call("describe", {"id": vm_id})
        print(f"delete: {ms} ms; then describe says {gone['state']}; the port answers `{probe(vm['address'], vm['port'])}`")
elif action == "create":
    create(sys.argv[6] if len(sys.argv) > 6 else "two-machine-run")
elif action == "delete":
    print("delete:", call("delete", {"id": sys.argv[6], "labels": LABELS}))
elif action == "list":
    print("list:", json.dumps(call("list", {"labels": LABELS})[0]))
driver.stdin.close()
driver.wait(timeout=10)
