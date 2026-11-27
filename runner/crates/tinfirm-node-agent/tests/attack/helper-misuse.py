#!/usr/bin/env python3
"""ADR-0112 decision 8, "the agent's two processes": misuse of the helper's socket.

Run as the listener's user by listener-user.sh, while the agent runs. Every request is one
the listener's user may send: the question is what the helper does with it. It prints each
request's kind and the helper's answer, cut to 150 characters.

    helper-misuse.py HELPER_SOCKET SPOOL TEMPLATE
"""
import json, os, socket, sys, threading, time

SOCKET, SPOOL, TEMPLATE = sys.argv[1:4]


def send(raw, wait=8):
    s = socket.socket(socket.AF_UNIX)
    s.settimeout(wait)
    try:
        s.connect(SOCKET)
        s.sendall(raw)
        answer = b""
        while not answer.endswith(b"\n"):
            chunk = s.recv(65536)
            if not chunk:
                break
            answer += chunk
        return answer.decode(errors="replace").strip() or "(closed, no answer)"
    except Exception as error:
        return f"({type(error).__name__})"
    finally:
        s.close()


def ask(**request):
    return send(json.dumps({"v": 1, **request}).encode() + b"\n")


def show(what, answer):
    print(f"  {what}: {answer[:150]}")


def vm_id(n):
    return "vm-%032x" % n


def path(i):
    return os.path.join(SPOOL, i + ".userdata")


def write(i, data=b'{"tinfirm":1}'):
    with open(path(i), "wb") as out:
        out.write(data)
    os.chmod(path(i), 0o600)


def clean(i):
    try:
        os.unlink(path(i))
    except OSError:
        pass


show("not JSON", send(b"hello\n"))
show("a JSON list", send(b"[1,2]\n"))
show("no version", send(b'{"op":"list"}\n'))
show("another version", send(b'{"v":2,"op":"list"}\n'))
show("an unknown operation", ask(op="shell"))
show("a key twice (the second a list)", send(b'{"v":1,"op":"delete","op":"list"}\n'))
show("list with an extra field", ask(op="list", path="/etc"))
show("a line of 100,000 bytes", send(b'{"v":1,"op":"list","x":"' + b"a" * 100000 + b'"}\n'))
show("5,000 bytes without a newline", send(b"a" * 5000, wait=8))
start = time.monotonic()
show("a connection that sends nothing", send(b"", wait=8) + f" after {time.monotonic() - start:.1f} s")
show("check with an unknown template", ask(op="check", template="no-such"))
show("check with a path as template", ask(op="check", template="../../etc"))
show("make with an unknown template", ask(op="make", id=vm_id(1), template="no-such"))
show("make with a memory size", ask(op="make", id=vm_id(1), template=TEMPLATE, memoryMib=1))
show("make with a path", ask(op="make", id=vm_id(1), template=TEMPLATE, path="/etc/shadow"))
for bad in [7, "../../../etc/x", "vm-../../x", "vm-" + "0" * 31 + "/", "VM-" + "0" * 32, "vm-" + "0" * 31 + "g",
            "vm-" + "0" * 32 + "\n", "vm-" + "0" * 32 + "; id", "vm-" + "0" * 33, "vm-" + "0" * 31, "vm-" + "0" * 31 + "é", ""]:
    show(f"make with id {bad!r}", ask(op="make", id=bad, template=TEMPLATE))
show("make with no user data file", ask(op="make", id=vm_id(2), template=TEMPLATE))

i = vm_id(3)
os.symlink("/etc/shadow", path(i)); show("make, user data a link to /etc/shadow", ask(op="make", id=i, template=TEMPLATE)); clean(i)
os.symlink("/etc/hostname", path(i)); show("make, user data a link to a readable file", ask(op="make", id=i, template=TEMPLATE)); clean(i)
os.mkfifo(path(i)); show("make, user data a FIFO", ask(op="make", id=i, template=TEMPLATE)); clean(i)
os.mkdir(path(i)); show("make, user data a directory", ask(op="make", id=i, template=TEMPLATE)); os.rmdir(path(i))
write(i, b""); show("make, user data empty", ask(op="make", id=i, template=TEMPLATE)); clean(i)
write(i, b"a" * 65537); show("make, user data of 65,537 bytes", ask(op="make", id=i, template=TEMPLATE)); clean(i)
with open(path(i), "wb") as out:
    out.truncate(1 << 40)
show("make, user data a sparse file of 1 TiB", ask(op="make", id=i, template=TEMPLATE)); clean(i)
write(i); os.link(path(i), path(i) + ".second"); show("make, user data with a second name", ask(op="make", id=i, template=TEMPLATE))
clean(i); os.unlink(path(i) + ".second")

# The file swapped with a link to /etc/shadow while makes are asked: the helper must make a
# VM from the regular file or refuse, and never read the link's target.
i = vm_id(4)
stop = False


def swapper():
    while not stop:
        try:
            write(i); os.unlink(path(i)); os.symlink("/etc/shadow", path(i)); os.unlink(path(i))
        except OSError:
            pass


thread = threading.Thread(target=swapper); thread.start()
outcomes = {}
for _ in range(30):
    answer = ask(op="make", id=i, template=TEMPLATE)
    key = "made" if '"ok"' in answer else json.loads(answer)["error"]["code"] + ": " + json.loads(answer)["error"]["message"][:50]
    outcomes[key] = outcomes.get(key, 0) + 1
    if '"ok"' in answer:
        ask(op="delete", id=i)
stop = True; thread.join(); clean(i)
show("30 makes while the file is swapped with a link to /etc/shadow", json.dumps(outcomes))

listed = json.loads(ask(op="list"))["ok"]
show("list, as the helper gives it", json.dumps(listed))
running = [vm["id"] for vm in listed["vms"]]
if running:
    write(running[0]); show("make with the id of a VM that runs", ask(op="make", id=running[0], template=TEMPLATE)); clean(running[0])
show("delete of an id that is not there", ask(op="delete", id=vm_id(5)))
show("delete with a path as id", ask(op="delete", id="../../srv/tinfirm-jails"))
show("delete with a jail's name as id", ask(op="delete", id="tfvm-000000000001-0000"))

start = time.monotonic()
refusals = {}
for n in range(2000):
    answer = ask(op="make", id=vm_id(100 + n), template=TEMPLATE)
    key = json.loads(answer).get("error", {}).get("code", "made")
    refusals[key] = refusals.get(key, 0) + 1
show(f"2000 makes without a user data file in {time.monotonic() - start:.1f} s", json.dumps(refusals))

made = []
for n in range(5):
    i = vm_id(5000 + n); write(i)
    answer = ask(op="make", id=i, template=TEMPLATE); clean(i)
    made.append("made" if '"ok"' in answer else json.loads(answer)["error"]["code"])
show("5 makes with a valid file, beside the VMs that run", str(made))
for n in range(5):
    ask(op="delete", id=vm_id(5000 + n))

# Connections that say nothing, more than the helper serves at once: a request beside them.
idle = []
for _ in range(40):
    s = socket.socket(socket.AF_UNIX)
    try:
        s.connect(SOCKET); idle.append(s)
    except OSError:
        break
start = time.monotonic()
answer = ask(op="list")
show(f"list beside {len(idle)} idle connections", f"after {time.monotonic() - start:.1f} s: {answer}")
for s in idle:
    s.close()
time.sleep(6)
start = time.monotonic()
show("list once the idle connections timed out", f"after {time.monotonic() - start:.3f} s: {ask(op='list')}")
print(f"  (not done to the VMs that run: `delete` of {running}, which the helper's rules allow the listener)")
