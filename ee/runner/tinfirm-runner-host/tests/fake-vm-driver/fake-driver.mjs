#!/usr/bin/env node
// Fake VM driver for the runner host tests (the driver protocol of ADR-0096): no dependencies;
// newline-delimited JSON-RPC 2.0 on stdio. A "VM" is this crate's binary run as `bridge` on a
// loopback address of its own, listening where the user data says, with the fake agent as the
// sandbox's own command. Nothing here is a real VM, and no credential is involved.
//
// Settings, from the environment:
//   FAKE_VM_BRIDGE     the bridge binary (this crate's)
//   FAKE_VM_AGENT      the sandbox's own command, a JSON array (TINFIRM_BRIDGE_COMMAND)
//   FAKE_VM_LOG        a file that gets one JSON line per call: {method, params}
//   FAKE_VM_LIMIT      verify's maxVms (default: none)
//   FAKE_VM_PROBLEMS   verify's problems, a JSON array (default: none)
//   FAKE_VM_LEFTOVERS  VMs that are there from the start, a JSON array of {id, labels}
//   FAKE_VM_BOOT_MS    how long a VM takes to come up (default 100)
//   FAKE_VM_STATE      a file that keeps the VMs: a driver started again (by a host that
//                      restarted, ADR-0101) has them, and they outlive the driver, as real
//                      VMs outlive their host
// Templates: `stuck` never comes up; `failing` fails at its start; any other comes up.

import { spawn } from "node:child_process";
import { appendFileSync, existsSync, readFileSync, writeFileSync } from "node:fs";
import { createInterface } from "node:readline";

const env = process.env;
const vms = new Map();
let created = 0;
const statePath = env.FAKE_VM_STATE;
if (statePath && existsSync(statePath)) {
  for (const vm of JSON.parse(readFileSync(statePath, "utf8"))) vms.set(vm.id, { ...vm, child: null });
}

function save() {
  if (!statePath) return;
  writeFileSync(statePath, JSON.stringify([...vms.values()].map(({ child, ...vm }) => vm)));
}

for (const leftover of JSON.parse(env.FAKE_VM_LEFTOVERS || "[]")) {
  vms.set(leftover.id, {
    id: leftover.id,
    name: leftover.id,
    labels: { ...leftover.labels, "tinfirm-sandbox": "1" },
    state: "running",
    address: "127.0.0.1",
    child: null,
  });
}

function log(method, params) {
  if (!env.FAKE_VM_LOG) return;
  appendFileSync(env.FAKE_VM_LOG, JSON.stringify({ method, params }) + "\n");
}

function random(n) {
  return Math.floor(Math.random() * n);
}

function view(vm) {
  return {
    id: vm.id,
    name: vm.name,
    state: vm.state,
    address: vm.state === "running" ? vm.address : null,
    labels: vm.labels,
  };
}

const methods = {
  hello() {
    return { protocol: 1, driver: "fake", version: "test" };
  },
  verify({ template }) {
    return {
      problems: JSON.parse(env.FAKE_VM_PROBLEMS || "[]"),
      maxVms: env.FAKE_VM_LIMIT ? Number(env.FAKE_VM_LIMIT) : null,
      checked: [`template ${template}`],
    };
  },
  create({ template, name, userData, labels }) {
    const data = JSON.parse(userData);
    if (data.tinfirm !== 1) throw new Error(`user data of format ${data.tinfirm}`);
    // Unique across the drivers of one state file.
    const id = statePath ? `vm-${process.pid}-${++created}` : `vm-${++created}`;
    // A loopback address of its own: the "VM"s of parallel tests never share a port.
    const address = `127.${200 + random(50)}.${1 + random(250)}.${1 + random(250)}`;
    const port = (data.env.TINFIRM_BRIDGE_LISTEN || "").split(":").pop();
    const vm = { id, name, labels: { ...labels, "tinfirm-sandbox": "1" }, state: "starting", address, child: null };
    vms.set(id, vm);
    if (template === "failing") {
      vm.state = "failed";
      save();
      return { id };
    }
    if (template === "stuck") {
      save();
      return { id };
    }
    const child = spawn(env.FAKE_VM_BRIDGE, ["bridge"], {
      env: {
        PATH: env.PATH,
        ...data.env,
        TINFIRM_BRIDGE_LISTEN: `${address}:${port}`,
        TINFIRM_BRIDGE_COMMAND: env.FAKE_VM_AGENT,
      },
      // A "VM" that outlives the driver writes to no pipe of the driver's.
      stdio: ["ignore", "ignore", statePath ? "ignore" : "pipe"],
      detached: Boolean(statePath),
    });
    child.stderr?.on("data", (chunk) => process.stderr.write(`[${id}] ${chunk}`));
    child.on("error", (e) => process.stderr.write(`[${id}] cannot start the bridge: ${e}\n`));
    if (statePath) child.unref();
    vm.child = child;
    vm.pid = child.pid;
    save();
    setTimeout(() => {
      if (vm.state === "starting") vm.state = "running";
      save();
    }, Number(env.FAKE_VM_BOOT_MS || 100));
    return { id };
  },
  describe({ id }) {
    const vm = vms.get(id);
    return vm ? view(vm) : { id, state: "gone", labels: {} };
  },
  delete({ id, labels }) {
    const vm = vms.get(id);
    // A real driver's guard: only a VM with each of the host's labels.
    for (const [k, v] of Object.entries(labels || {})) {
      if (vm && vm.labels[k] !== v) throw new Error(`${id} is not this host's VM`);
    }
    if (vm) {
      vm.state = "gone";
      if (vm.child) vm.child.kill("SIGKILL");
      else if (vm.pid) {
        try {
          process.kill(vm.pid, "SIGKILL");
        } catch {
          // gone already
        }
      }
      save();
    }
    return {};
  },
  list({ labels }) {
    const wanted = Object.entries(labels || {});
    return {
      vms: [...vms.values()]
        .filter((vm) => vm.state !== "gone")
        .filter((vm) => wanted.every(([k, v]) => vm.labels[k] === v))
        .map(view),
    };
  },
};

const lines = createInterface({ input: process.stdin });
lines.on("line", (line) => {
  let request;
  try {
    request = JSON.parse(line);
  } catch {
    return;
  }
  const params = request.params || {};
  log(request.method, request.method === "create" ? { ...params, userData: JSON.parse(params.userData) } : params);
  let answer;
  try {
    const method = methods[request.method];
    if (!method) throw new Error(`no method ${request.method}`);
    answer = { jsonrpc: "2.0", id: request.id, result: method(params) };
  } catch (e) {
    answer = { jsonrpc: "2.0", id: request.id, error: { code: -32000, message: String(e.message || e) } };
  }
  process.stdout.write(JSON.stringify(answer) + "\n");
});
lines.on("close", () => {
  if (!statePath) for (const vm of vms.values()) vm.child?.kill("SIGKILL");
  process.exit(0);
});
