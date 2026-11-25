//! The run pod's tap in the init variant (ADR-0115 decision 2): `vm-pod tap-setup` makes it in
//! the pod's network namespace, as uid 0 of the pod's user namespace with `NET_ADMIN`, and with
//! no program of the image's but this one. Part of elastic runners, under the Tinfirm Enterprise
//! licence (`ee/LICENSE`).
//!
//! - The tap comes from `/dev/net/tun` with `TUNSETIFF`, is given to the main container's user
//!   and group (`TUNSETOWNER`, `TUNSETGROUP`) and kept after this program ends
//!   (`TUNSETPERSIST`).
//! - Its address and its up flag, and its IPv4 `forwarding` set to 0, go over rtnetlink: a
//!   pod's `/proc/sys` is read-only, and forwarding is on in its network namespace, which the
//!   pod cannot change. For IPv4 the interface's own setting is what the kernel reads for a
//!   packet that arrives on it, so nothing from the guest is forwarded out of the pod.
//! - IPv6 has no such setting: the kernel forwards it by the namespace's
//!   `net.ipv6.conf.all.forwarding` alone, whatever an interface's own `forwarding` reads, and
//!   nothing here can change that one. The main program refuses to start the VM while it reads
//!   1 (crate::kubernetes::vm_pod).

use std::net::Ipv4Addr;
use std::os::fd::AsFd;

use anyhow::{Context, Result, bail};
use rustix::net::netlink::SocketAddrNetlink;
use rustix::net::{AddressFamily, RecvFlags, SendFlags, SocketType};

const TUN: &str = "/dev/net/tun";
const IFF_TAP: i16 = 0x0002;
const IFF_NO_PI: i16 = 0x1000;
const IFF_UP: u32 = 0x1;
const RTM_NEWLINK: u16 = 16;
const RTM_NEWADDR: u16 = 20;
const NLMSG_ERROR: u16 = 2;
const NLM_F_REQUEST: u16 = 0x1;
const NLM_F_ACK: u16 = 0x4;
const NLM_F_REPLACE: u16 = 0x100;
const NLM_F_CREATE: u16 = 0x400;
const IFA_ADDRESS: u16 = 1;
const IFA_LOCAL: u16 = 2;
const IFLA_AF_SPEC: u16 = 26;
const IFLA_INET_CONF: u16 = 1;
const IPV4_DEVCONF_FORWARDING: u16 = 1;
const AF_INET: u8 = 2;
const NLA_F_NESTED: u16 = 0x8000;

/// `struct ifreq` with the flags of its union, as `TUNSETIFF` reads it.
#[repr(C)]
struct IfReq {
    name: [u8; 16],
    flags: i16,
    _rest: [u8; 22],
}

const TUNSETIFF: rustix::ioctl::Opcode = rustix::ioctl::opcode::write::<i32>(b'T', 202);
const TUNSETPERSIST: rustix::ioctl::Opcode = rustix::ioctl::opcode::write::<i32>(b'T', 203);
const TUNSETOWNER: rustix::ioctl::Opcode = rustix::ioctl::opcode::write::<i32>(b'T', 204);
const TUNSETGROUP: rustix::ioctl::Opcode = rustix::ioctl::opcode::write::<i32>(b'T', 206);

/// Makes the tap `name`, owned by `uid` and `gid`, with `address`/30, up, and not forwarding.
/// Run again on a tap that is there, it sets the same again.
pub fn set_up(name: &str, uid: u32, gid: u32, address: Ipv4Addr) -> Result<()> {
    make_tap(name, uid, gid)?;
    let index = interface_index(name)?;
    let netlink = Netlink::open()?;
    netlink
        .request(
            RTM_NEWADDR,
            NLM_F_CREATE | NLM_F_REPLACE,
            &address_message(index, address),
        )
        .with_context(|| format!("cannot give {name} the address {address}/30"))?;
    netlink
        .request(RTM_NEWLINK, 0, &link_message(index))
        .with_context(|| {
            format!("cannot set {name} up with its IPv4 forwarding off (rtnetlink, IFLA_AF_SPEC)")
        })?;
    Ok(())
}

/// The tap from `/dev/net/tun`, persistent, owned by `uid` and `gid`.
#[allow(unsafe_code)]
fn make_tap(name: &str, uid: u32, gid: u32) -> Result<()> {
    if name.is_empty() || name.len() > 15 || !name.bytes().all(|b| b.is_ascii_alphanumeric()) {
        bail!("`{name}` is not an interface name");
    }
    let tun = std::fs::OpenOptions::new()
        .read(true)
        .write(true)
        .open(TUN)
        .with_context(|| {
            format!("cannot open {TUN}: the init container needs the device plugin's tun resource")
        })?;
    let mut request = IfReq {
        name: [0; 16],
        flags: IFF_TAP | IFF_NO_PI,
        _rest: [0; 22],
    };
    request.name[..name.len()].copy_from_slice(name.as_bytes());
    // SAFETY: TUNSETIFF reads (and writes back) a `struct ifreq`, which `IfReq` is in size and
    // layout (16 bytes of name, then the union, 40 bytes in all); the Setter owns it for the call.
    unsafe {
        rustix::ioctl::ioctl(
            tun.as_fd(),
            rustix::ioctl::Setter::<TUNSETIFF, IfReq>::new(request),
        )
    }
    .with_context(|| {
        format!("cannot make the tap {name} (TUNSETIFF): the init container needs NET_ADMIN")
    })?;
    int_ioctl::<TUNSETOWNER>(&tun, uid)
        .with_context(|| format!("cannot give the tap {name} to uid {uid} (TUNSETOWNER)"))?;
    int_ioctl::<TUNSETGROUP>(&tun, gid)
        .with_context(|| format!("cannot give the tap {name} to gid {gid} (TUNSETGROUP)"))?;
    int_ioctl::<TUNSETPERSIST>(&tun, 1)
        .with_context(|| format!("cannot keep the tap {name} (TUNSETPERSIST)"))?;
    Ok(())
}

/// A tun ioctl that takes an integer by value.
#[allow(unsafe_code)]
fn int_ioctl<const OPCODE: rustix::ioctl::Opcode>(
    tun: &std::fs::File,
    value: u32,
) -> std::io::Result<()> {
    // SAFETY: TUNSETOWNER, TUNSETGROUP and TUNSETPERSIST take an integer by value (a uid, a
    // gid, a flag), which is what `IntegerSetter` passes; no memory is shared with the kernel.
    unsafe {
        rustix::ioctl::ioctl(
            tun.as_fd(),
            rustix::ioctl::IntegerSetter::<OPCODE>::new_usize(value as usize),
        )
    }
    .map_err(std::io::Error::from)
}

fn interface_index(name: &str) -> Result<u32> {
    let socket = rustix::net::socket(AddressFamily::INET, SocketType::DGRAM, None)
        .context("cannot open a socket")?;
    rustix::net::netdevice::name_to_index(&socket, name)
        .with_context(|| format!("there is no interface {name}"))
}

/// An rtnetlink attribute: its header, the value, and padding to 4 bytes.
fn attribute(kind: u16, value: &[u8]) -> Vec<u8> {
    let len = 4 + value.len();
    let mut out = Vec::with_capacity(len.div_ceil(4) * 4);
    out.extend_from_slice(&(len as u16).to_ne_bytes());
    out.extend_from_slice(&kind.to_ne_bytes());
    out.extend_from_slice(value);
    out.resize(len.div_ceil(4) * 4, 0);
    out
}

/// `RTM_NEWADDR`'s body: `ifaddrmsg` for an IPv4 /30 on `index`, the local address and the
/// peer address the same.
fn address_message(index: u32, address: Ipv4Addr) -> Vec<u8> {
    let mut body = vec![AF_INET, 30, 0, 0];
    body.extend_from_slice(&index.to_ne_bytes());
    body.extend(attribute(IFA_LOCAL, &address.octets()));
    body.extend(attribute(IFA_ADDRESS, &address.octets()));
    body
}

/// `RTM_NEWLINK`'s body: `ifinfomsg` that sets `IFF_UP` on `index`, and the IPv4 device
/// setting `forwarding` 0 (`IFLA_AF_SPEC` → `AF_INET` → `IFLA_INET_CONF` →
/// `IPV4_DEVCONF_FORWARDING`).
fn link_message(index: u32) -> Vec<u8> {
    let mut body = vec![0u8, 0, 0, 0];
    body.extend_from_slice(&index.to_ne_bytes());
    body.extend_from_slice(&IFF_UP.to_ne_bytes());
    body.extend_from_slice(&IFF_UP.to_ne_bytes());
    let forwarding = attribute(IPV4_DEVCONF_FORWARDING, &0u32.to_ne_bytes());
    let conf = attribute(IFLA_INET_CONF | NLA_F_NESTED, &forwarding);
    let inet = attribute(u16::from(AF_INET) | NLA_F_NESTED, &conf);
    body.extend(attribute(IFLA_AF_SPEC | NLA_F_NESTED, &inet));
    body
}

/// A route netlink socket.
struct Netlink {
    socket: rustix::fd::OwnedFd,
}

impl Netlink {
    fn open() -> Result<Netlink> {
        // NETLINK_ROUTE is protocol 0.
        let socket = rustix::net::socket(AddressFamily::NETLINK, SocketType::RAW, None)
            .context("cannot open a route netlink socket")?;
        rustix::net::bind(&socket, &SocketAddrNetlink::new(0, 0))
            .context("cannot bind a route netlink socket")?;
        Ok(Netlink { socket })
    }

    /// Sends one request and reads its acknowledgement.
    fn request(&self, kind: u16, flags: u16, body: &[u8]) -> Result<()> {
        let len = 16 + body.len();
        let mut message = Vec::with_capacity(len);
        message.extend_from_slice(&(len as u32).to_ne_bytes());
        message.extend_from_slice(&kind.to_ne_bytes());
        message.extend_from_slice(&(NLM_F_REQUEST | NLM_F_ACK | flags).to_ne_bytes());
        message.extend_from_slice(&1u32.to_ne_bytes());
        message.extend_from_slice(&0u32.to_ne_bytes());
        message.extend_from_slice(body);
        rustix::net::sendto(
            &self.socket,
            &message,
            SendFlags::empty(),
            &SocketAddrNetlink::new(0, 0),
        )?;
        let mut answer = vec![0u8; 8192];
        let (n, _) = rustix::net::recv(&self.socket, &mut answer[..], RecvFlags::empty())?;
        let error = acknowledgement(&answer[..n])?;
        if error != 0 {
            return Err(std::io::Error::from_raw_os_error(-error).into());
        }
        Ok(())
    }
}

/// The error of a netlink acknowledgement (0 when it is one of success).
fn acknowledgement(answer: &[u8]) -> Result<i32> {
    if answer.len() < 20 || u16::from_ne_bytes([answer[4], answer[5]]) != NLMSG_ERROR {
        bail!("the kernel's answer is not a netlink acknowledgement");
    }
    Ok(i32::from_ne_bytes(
        answer[16..20].try_into().expect("4 bytes"),
    ))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_messages_are_laid_out_as_rtnetlink_reads_them() {
        assert_eq!(std::mem::size_of::<IfReq>(), 40);
        assert_eq!(attribute(1, &[7]), vec![5, 0, 1, 0, 7, 0, 0, 0]);
        let address = address_message(3, Ipv4Addr::new(172, 30, 0, 1));
        assert_eq!(&address[..8], &[2, 30, 0, 0, 3, 0, 0, 0]);
        assert_eq!(&address[8..16], &[8, 0, 2, 0, 172, 30, 0, 1]);
        assert_eq!(&address[16..24], &[8, 0, 1, 0, 172, 30, 0, 1]);
        let link = link_message(3);
        assert_eq!(link.len(), 16 + 4 + 4 + 4 + 8);
        // IFLA_AF_SPEC { AF_INET { IFLA_INET_CONF { FORWARDING = 0 } } }
        assert_eq!(
            &link[16..],
            &[
                20, 0, 26, 0x80, 16, 0, 2, 0x80, 12, 0, 1, 0x80, 8, 0, 1, 0, 0, 0, 0, 0
            ]
        );
        let mut ack = vec![0u8; 36];
        ack[4] = 2;
        ack[16..20].copy_from_slice(&(-1i32).to_ne_bytes());
        assert_eq!(acknowledgement(&ack).unwrap(), -1);
        assert!(acknowledgement(&[0; 8]).is_err());
        assert!(make_tap("tap0; rm", 0, 0).is_err());
    }

    /// The tap's code in a network namespace of its own: the test runs itself again under
    /// `unshare -Urn` (uid 0 of a new user namespace, which owns the new network namespace).
    #[test]
    fn makes_a_tap_with_its_owner_and_address_that_does_not_forward() {
        if std::env::var_os("TINFIRM_TAP_TEST_INNER").is_some() {
            return;
        }
        let me = std::env::current_exe().unwrap();
        // A mount namespace too, for a /sys of the new network namespace.
        let status = std::process::Command::new("unshare")
            .args([
                "-Urnm",
                "--",
                "sh",
                "-c",
                r#"mount -t sysfs sysfs /sys; exec "$0" "$@""#,
            ])
            .arg(&me)
            .args([
                "--exact",
                "kubernetes::tap::tests::inner_tap_in_a_namespace",
                "--ignored",
                "--nocapture",
            ])
            .env("TINFIRM_TAP_TEST_INNER", "1")
            .output();
        let output = match status {
            Ok(output) => output,
            Err(e) => {
                eprintln!("SKIPPED: no `unshare` to make a network namespace ({e})");
                return;
            }
        };
        let text = String::from_utf8_lossy(&output.stdout).into_owned()
            + &String::from_utf8_lossy(&output.stderr);
        if text.contains("SKIPPED") || !text.contains("running 1 test") {
            eprintln!("SKIPPED: no network namespace or no /dev/net/tun here: {text}");
            return;
        }
        assert!(
            output.status.success() && text.contains("1 passed"),
            "{text}"
        );
        assert!(
            text.contains("the tap is there, up, at 172.30.0.1/30, forwarding 0"),
            "{text}"
        );
    }

    #[test]
    #[ignore = "run by makes_a_tap_with_its_owner_and_address_that_does_not_forward"]
    fn inner_tap_in_a_namespace() {
        if std::env::var_os("TINFIRM_TAP_TEST_INNER").is_none() {
            return;
        }
        if std::fs::OpenOptions::new()
            .read(true)
            .write(true)
            .open(TUN)
            .is_err()
        {
            println!("SKIPPED: {TUN} cannot be opened here");
            return;
        }
        let before = std::fs::read_to_string("/proc/sys/net/ipv4/conf/default/forwarding").unwrap();
        set_up("tap0", 0, 0, Ipv4Addr::new(172, 30, 0, 1)).unwrap();
        // Again: the same.
        set_up("tap0", 0, 0, Ipv4Addr::new(172, 30, 0, 1)).unwrap();
        match std::fs::read_to_string("/sys/class/net/tap0/owner") {
            Ok(owner) => assert_eq!(owner.trim(), "0"),
            Err(e) => println!("the tap's owner cannot be read here ({e})"),
        }
        match std::fs::read_to_string("/sys/class/net/tap0/group") {
            Ok(group) => assert_eq!(group.trim(), "0"),
            Err(e) => println!("the tap's group cannot be read here ({e})"),
        }
        let forwarding =
            std::fs::read_to_string("/proc/sys/net/ipv4/conf/tap0/forwarding").unwrap();
        assert_eq!(
            forwarding.trim(),
            "0",
            "forwarding of the namespace: {before}"
        );
        let trie = std::fs::read_to_string("/proc/net/fib_trie").unwrap();
        assert!(trie.contains("172.30.0.1"), "{trie}");
        let flags = std::fs::read_to_string("/sys/class/net/tap0/flags").unwrap_or_default();
        if let Some(hex) = flags.trim().strip_prefix("0x") {
            assert_eq!(u32::from_str_radix(hex, 16).unwrap() & IFF_UP, IFF_UP);
        }
        println!(
            "the tap is there, up, at 172.30.0.1/30, forwarding {}",
            forwarding.trim()
        );
    }
}
