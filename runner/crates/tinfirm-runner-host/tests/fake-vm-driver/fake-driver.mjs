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
//   FAKE_VM_SLOTS      the slots of the "nodes": verify's maxVms is the slots that the VMs
//                      not gone leave free, every host's VMs counted, as the node driver's
//                      sum of the nodes' free slots is (wins over FAKE_VM_LIMIT)
//   FAKE_VM_PROBLEMS   verify's problems, a JSON array (default: none)
//   FAKE_VM_UNREACHABLE  verify's unreachable, a JSON array that names the "nodes" that did
//                      not answer, as the node driver names them (default: left out)
//   FAKE_VM_NODE_DOWN  a file: while it is there, part of the "nodes" do not answer. Its
//                      content is a JSON object {"unreachable": [...], "slots": N}: `verify`
//                      names those nodes as FAKE_VM_UNREACHABLE does, and counts the N slots
//                      of the others in place of FAKE_VM_SLOTS
//   FAKE_VM_NOTICE     when set, the driver tells the host when FAKE_VM_NODE_DOWN's file
//                      appears, as the node driver does when a node's connection is lost:
//                      the notification `machinesChanged` (no id, `params.why`), once for each
//                      time the file appears; the log gets a line {method: "noticed"}
//   FAKE_VM_BROKEN    a file: while it is there, the "nodes" are broken, as a node whose
//                      firewall table is gone: verify answers its content (a JSON array) as
//                      the problems and maxVms 0, and a create fails
//   FAKE_VM_CREATE_FAILS  when set, every create fails with this message, and verify stays
//                      clean: a failure of the VM alone, such as its user data
//   FAKE_VM_HARDENED   a file: while it is there, `verify` adds its content, a JSON object such
//                      as {"hardened": "no", "notHardened": ["node n1: io-uring: ..."]}, to
//                      its answer, as the node driver reports its nodes' hardening; without
//                      it, `verify` says nothing of the hardening ("not known")
//   FAKE_VM_VERIFY     a file: while it is there, `verify` fails when it holds `fail`, and
//                      answers after N ms when it holds `slow N`, with what FAKE_VM_BROKEN
//                      said when the call came
//   FAKE_VM_LEFTOVERS  VMs that are there from the start, a JSON array of {id, labels}
//   FAKE_VM_BOOT_MS    how long a VM takes to come up (default 100)
//   FAKE_VM_STATE      a file that keeps the VMs: a driver started again (by a host that
//                      restarted, ADR-0101) has them, and they outlive the driver, as real
//                      VMs outlive their host
//   FAKE_VM_PUBLISH    when set, each "VM"'s bridge listens on a port of its own, not the
//                      user data's, and `describe` names it in `port`, as a sandbox node
//                      publishes each VM's bridge (ADR-0112); the log gets a line
//                      {method: "published", params: {id, port}}
//   FAKE_VM_DELETE_MS  how long a `delete` takes before it answers (default 0); the VM goes,
//                      and the log gets a line {method: "deleted", params: {id}}, when it
//                      answers
//   FAKE_VM_DOWN       a file: while it is there, `delete` and `describe` fail as for a node
//                      that does not answer (a node that reboots), and the VMs stay
//   FAKE_VM_DIE        a file: once it is there, every running "VM" dies as a VM whose VMM
//                      was killed: its bridge is killed, `describe` says `failed` from then
//                      on, and the file is removed
// Each line of the log has `at`, the time of the call in ms since the epoch.
// Templates: `stuck` never comes up; `failing` fails at its start; any other comes up.

import { spawn } from "node:child_process";
import { appendFileSync, existsSync, readFileSync, unlinkSync, writeFileSync } from "node:fs";
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
  appendFileSync(env.FAKE_VM_LOG, JSON.stringify({ method, params, at: Date.now() }) + "\n");
}

// The problems of FAKE_VM_BROKEN while its file is there, else null.
function brokenProblems() {
  if (!env.FAKE_VM_BROKEN || !existsSync(env.FAKE_VM_BROKEN)) return null;
  return JSON.parse(readFileSync(env.FAKE_VM_BROKEN, "utf8"));
}

// What FAKE_VM_HARDENED adds to `verify`'s answer while its file is there, else nothing.
function hardening() {
  if (!env.FAKE_VM_HARDENED || !existsSync(env.FAKE_VM_HARDENED)) return {};
  return JSON.parse(readFileSync(env.FAKE_VM_HARDENED, "utf8"));
}

// FAKE_VM_NODE_DOWN: the nodes that do not answer now and the others' slots, else null.
function nodesDown() {
  if (!env.FAKE_VM_NODE_DOWN || !existsSync(env.FAKE_VM_NODE_DOWN)) return null;
  return JSON.parse(readFileSync(env.FAKE_VM_NODE_DOWN, "utf8"));
}

// FAKE_VM_DOWN: whether the "node" does not answer now.
function down() {
  return Boolean(env.FAKE_VM_DOWN && existsSync(env.FAKE_VM_DOWN));
}

// FAKE_VM_DIE: the running "VMs" die once the file is there.
if (env.FAKE_VM_DIE) {
  setInterval(() => {
    if (!existsSync(env.FAKE_VM_DIE)) return;
    for (const vm of vms.values()) {
      if (vm.state !== "running") continue;
      vm.state = "failed";
      vm.child?.kill("SIGKILL");
      log("died", { id: vm.id });
    }
    save();
    unlinkSync(env.FAKE_VM_DIE);
  }, 50).unref();
}

// FAKE_VM_NOTICE: `machinesChanged` when FAKE_VM_NODE_DOWN's file appears.
if (env.FAKE_VM_NOTICE && env.FAKE_VM_NODE_DOWN) {
  let wasDown = existsSync(env.FAKE_VM_NODE_DOWN);
  setInterval(() => {
    const isDown = existsSync(env.FAKE_VM_NODE_DOWN);
    if (isDown && !wasDown) {
      log("noticed", {});
      const why = "node node-2: the connection to the node was lost";
      process.stdout.write(JSON.stringify({ jsonrpc: "2.0", method: "machinesChanged", params: { why } }) + "\n");
    }
    wasDown = isDown;
  }, 50).unref();
}

function random(n) {
  return Math.floor(Math.random() * n);
}

function view(vm) {
  const described = {
    id: vm.id,
    name: vm.name,
    state: vm.state,
    address: vm.state === "running" ? vm.address : null,
    labels: vm.labels,
  };
  if (vm.port) described.port = vm.port;
  return described;
}

const methods = {
  hello() {
    return { protocol: 1, driver: "fake", version: "test" };
  },
  async verify({ template }) {
    const mode = env.FAKE_VM_VERIFY && existsSync(env.FAKE_VM_VERIFY) ? readFileSync(env.FAKE_VM_VERIFY, "utf8").trim() : "";
    if (mode === "fail") throw new Error("the provider's API is not reachable");
    const broken = brokenProblems();
    if (mode.startsWith("slow ")) await new Promise((resolve) => setTimeout(resolve, Number(mode.slice(5))));
    if (broken) return { problems: broken, maxVms: 0, checked: [`template ${template}`], ...hardening() };
    const gone = nodesDown();
    const slots = gone ? gone.slots : env.FAKE_VM_SLOTS;
    return {
      ...hardening(),
      ...(env.FAKE_VM_UNREACHABLE ? { unreachable: JSON.parse(env.FAKE_VM_UNREACHABLE) } : {}),
      ...(gone ? { unreachable: gone.unreachable } : {}),
      problems: JSON.parse(env.FAKE_VM_PROBLEMS || "[]"),
      maxVms: slots !== undefined && slots !== ""
        ? Math.max(0, Number(slots) - [...vms.values()].filter((vm) => vm.state !== "gone").length)
        : env.FAKE_VM_LIMIT
          ? Number(env.FAKE_VM_LIMIT)
          : null,
      checked: [`template ${template}`],
    };
  },
  create({ template, name, userData, labels }) {
    const broken = brokenProblems();
    if (broken) throw new Error(`no node can take a VM: ${broken.join("; ")}`);
    if (env.FAKE_VM_CREATE_FAILS) throw new Error(env.FAKE_VM_CREATE_FAILS);
    const data = JSON.parse(userData);
    if (data.tinfirm !== 1) throw new Error(`user data of format ${data.tinfirm}`);
    // Unique across the drivers of one state file.
    const id = statePath ? `vm-${process.pid}-${++created}` : `vm-${++created}`;
    // A loopback address of its own: the "VM"s of parallel tests never share a port.
    const address = `127.${200 + random(50)}.${1 + random(250)}.${1 + random(250)}`;
    const listen = (data.env.TINFIRM_BRIDGE_LISTEN || "").split(":").pop();
    // A published port: never the user data's, so a host that dialed `--vm-port` would miss.
    const published = env.FAKE_VM_PUBLISH ? 20000 + random(40000) : null;
    const port = published && String(published) !== listen ? published : published && published + 1;
    const vm = { id, name, labels: { ...labels, "tinfirm-sandbox": "1" }, state: "starting", address, child: null };
    if (port) {
      vm.port = port;
      log("published", { id, port });
    }
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
        TINFIRM_BRIDGE_LISTEN: `${address}:${port || listen}`,
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
    if (down()) throw new Error("cannot reach the node: connection refused");
    const vm = vms.get(id);
    return vm ? view(vm) : { id, state: "gone", labels: {} };
  },
  async delete({ id, labels }) {
    if (down()) throw new Error("cannot reach the node: connection refused");
    const vm = vms.get(id);
    // A real driver's guard: only a VM with each of the host's labels.
    for (const [k, v] of Object.entries(labels || {})) {
      if (vm && vm.labels[k] !== v) throw new Error(`${id} is not this host's VM`);
    }
    const delay = Number(env.FAKE_VM_DELETE_MS || 0);
    if (delay > 0) await new Promise((resolve) => setTimeout(resolve, delay));
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
    log("deleted", { id });
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
// Answered in order, but for a call that takes its time (`delete` with FAKE_VM_DELETE_MS),
// which answers when it is done: JSON-RPC matches the answers by id.
lines.on("line", async (line) => {
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
    answer = { jsonrpc: "2.0", id: request.id, result: await method(params) };
  } catch (e) {
    answer = { jsonrpc: "2.0", id: request.id, error: { code: -32000, message: String(e.message || e) } };
  }
  process.stdout.write(JSON.stringify(answer) + "\n");
});
lines.on("close", () => {
  if (!statePath) for (const vm of vms.values()) vm.child?.kill("SIGKILL");
  process.exit(0);
});
