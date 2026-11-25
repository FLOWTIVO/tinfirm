#!/usr/bin/env python3
"""A machine that is not the control host, with the control host's own certificate and key.

It starts the runner host's `node` driver as control-host.py does and makes three calls. The
first, `hello`, is answered by the driver itself, without the node. The other two, `verify`
and `list`, need the node: each prints the driver's answer or its error, and how long it took.

    other-machine.py RUNNER_HOST_BINARY DRIVER_CONFIG TEMPLATE
"""
import json, subprocess, sys, time

binary, config, template = sys.argv[1:4]
driver = subprocess.Popen([binary, "vm-driver", "node", "--config", config],
                          stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
for serial, (method, params) in enumerate(
        [("hello", {"protocol": 1}), ("verify", {"template": template}), ("list", {"labels": {}})], 1):
    start = time.monotonic()
    driver.stdin.write(json.dumps({"jsonrpc": "2.0", "id": serial, "method": method, "params": params}) + "\n")
    driver.stdin.flush()
    answer = json.loads(driver.stdout.readline())
    ms = round((time.monotonic() - start) * 1000)
    where = "the driver's own answer, without the node" if method == "hello" else "needs the node"
    if "error" in answer:
        print(f"  {method} ({where}): ERROR after {ms} ms: {answer['error']['message']}")
    else:
        text = json.dumps(answer["result"])
        print(f"  {method} ({where}): answered after {ms} ms: {text[:160]}")
driver.stdin.close()
driver.wait(timeout=10)
for line in driver.stderr.read().splitlines()[:4]:
    print("  the driver's log:", line[:200])
