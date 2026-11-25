//! The node's own egress, tried from the node (ADR-0112 decision 4): on each `verify`, the
//! listener sends one empty UDP datagram to the control host's address, on [`PORT`], and reads
//! what the kernel answers.
//!
//! Why a datagram from the listener. The listener's unit allows it the control host's address
//! and nothing else, so on the node only netfilter can stop that datagram, which is the node's
//! own table (`inet tinfirm_node`, whose output chain drops every new flow of the node's). A
//! drop or a reject of a locally made packet makes `sendto` fail at once with `EPERM`, and a
//! datagram that passed every output hook makes `sendto` succeed. So the node's own verdict is
//! known at once, with no wait and no timeout. A TCP connection could not tell it: a drop on
//! the node and a drop beyond it are the same silence, and a reject on the node looks like a
//! reset from the far side. The helper tries nothing: it has no network code.
//!
//! What the try shows: that a new flow from the node's own network namespace to the control
//! host is refused on the node. The output chain matches no user, so that holds for root and
//! every other user of that namespace, as long as the loaded table is the node's file. What it
//! does not show: egress to any other address (the listener's unit refuses those itself, so a
//! try would prove nothing), other protocols or ports where an edited table treats them
//! otherwise, a VMM's own egress (its VM's namespace has a table of its own), another namespace
//! with a way out of its own, and the network outside the node.
//!
//! On a node whose table holds, the datagram never leaves the node: the table's counter
//! "dropped: from the node" grows by one per `verify`. On a node whose table lets it pass, one
//! empty datagram goes to the control host's discard port.

use std::fmt;
use std::io;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::sync::Arc;

use crate::plan::NodeFirewall;

/// The control host's port the datagram is sent to: the discard port, so that a datagram that
/// does leave the node asks for nothing.
pub const PORT: u16 = 9;

/// What sends the datagram and reads the kernel's answer. The listener uses [`real_sender`];
/// the tests put a fake in its place, since a test machine has no node's table.
#[derive(Clone)]
pub struct Sender(pub Arc<dyn Fn(SocketAddr) -> Outcome + Send + Sync>);

impl fmt::Debug for Sender {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.write_str("Sender")
    }
}

/// The try could not be made: the socket could not be made or bound, so no `sendto` ran.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Untried(pub String);

/// What the kernel answered to the datagram.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Outcome {
    /// `sendto` failed with `EPERM` or `EACCES`: refused on the node.
    Refused,
    /// `sendto` succeeded: the datagram passed every output hook of the node.
    Passed,
    /// Another answer (no route, no buffer), or no try at all: nothing is known of the table.
    Unknown(String),
}

/// The datagram, sent for real: a fresh socket of the target's family, non-blocking, and one
/// empty datagram. Errors of the socket or of its bind are [`Untried`]: only `sendto`'s answer
/// speaks of the node's firewall.
pub fn send(target: SocketAddr) -> Result<io::Result<usize>, Untried> {
    let any: SocketAddr = match target.ip() {
        IpAddr::V4(_) => (Ipv4Addr::UNSPECIFIED, 0).into(),
        IpAddr::V6(_) => (Ipv6Addr::UNSPECIFIED, 0).into(),
    };
    let socket = UdpSocket::bind(any)
        .map_err(|e| Untried(format!("no UDP socket could be made ({})", e.kind())))?;
    socket.set_nonblocking(true).map_err(|e| {
        Untried(format!(
            "the socket cannot be made non-blocking ({})",
            e.kind()
        ))
    })?;
    Ok(socket.send_to(&[], target))
}

/// The real sender, as the listener holds it. A loopback address is not tried: the node's
/// table admits loopback, so the answer would say nothing of it.
pub fn real_sender() -> Sender {
    Sender(Arc::new(|target| {
        if is_loopback(target.ip()) {
            return Outcome::Unknown(
                "the control host's address is loopback, which the node's table admits".into(),
            );
        }
        match send(target) {
            Ok(result) => classify(&result),
            Err(Untried(why)) => Outcome::Unknown(why),
        }
    }))
}

/// What `sendto`'s result says. `EPERM` (netfilter's drop or reject, or a BPF filter of the
/// node's) and `EACCES` are refusals on the node; a count is a datagram that passed; any other
/// error says nothing of the table, and is named by its kind alone.
pub fn classify(result: &io::Result<usize>) -> Outcome {
    match result {
        Ok(_) => Outcome::Passed,
        // The standard library reads both EPERM and EACCES as `PermissionDenied`.
        Err(e) if e.kind() == io::ErrorKind::PermissionDenied => Outcome::Refused,
        Err(e) => Outcome::Unknown(format!("sendto answered: {}", e.kind())),
    }
}

/// What the try adds to `verify`: a problem, or a line of what was checked.
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Verdict {
    Problem(String),
    Checked(String),
}

/// The words of the outcome, with the helper's `nodeFirewall` (`None` from a helper of an
/// earlier build, which does not say it). A datagram that passed is a problem only where the
/// node's configuration requires its table, as a missing table is.
pub fn verdict(target: SocketAddr, outcome: &Outcome, firewall: Option<NodeFirewall>) -> Verdict {
    let what = format!(
        "the node's own egress: a datagram from the listener to the control host (udp:{target})"
    );
    match (outcome, firewall) {
        (Outcome::Refused, _) => Verdict::Checked(format!(
            "{what} was refused on the node, as its firewall table drops it"
        )),
        (Outcome::Passed, Some(NodeFirewall::Required)) => Verdict::Problem(format!(
            "{what} passed the node's firewall, and this node's configuration requires its table: a node opens no connection, and its own table (inet tinfirm_node) did not drop it, so no VM is placed on the node until it does. Load the table again (`systemctl reload tinfirm-node-agent-firewall.service`) and look for a rule or a table that admits the node's own packets"
        )),
        (Outcome::Passed, Some(NodeFirewall::NotRequired)) => Verdict::Checked(format!(
            "NOT HELD: {what} passed the node's firewall, which this node's configuration allows (nodeFirewall \"not-required\")"
        )),
        (Outcome::Passed, None) => Verdict::Checked(format!(
            "NOT HELD: {what} passed the node's firewall; the helper did not say whether this node requires its table (a helper of an earlier build: restart it), so this is not counted as a problem"
        )),
        (Outcome::Unknown(why), _) => Verdict::Checked(format!("{what} could not be tried: {why}")),
    }
}

/// The try, for the listener's `verify`: the control host's address from the listener's own
/// configuration (`None` for a listener without `tls`), sent with `sender`.
pub fn try_egress(
    control_host: Option<IpAddr>,
    sender: &Sender,
    firewall: Option<NodeFirewall>,
) -> Verdict {
    let Some(host) = control_host else {
        return Verdict::Checked(
            "the node's own egress was not tried: the listener has no control host's address (no `tls`)"
                .into(),
        );
    };
    let target = SocketAddr::new(host, PORT);
    verdict(target, &(sender.0)(target), firewall)
}

/// Loopback, also as an IPv4-mapped IPv6 address.
fn is_loopback(ip: IpAddr) -> bool {
    match ip {
        IpAddr::V4(v4) => v4.is_loopback(),
        IpAddr::V6(v6) => {
            v6.is_loopback() || v6.to_ipv4_mapped().is_some_and(|v4| v4.is_loopback())
        }
    }
}

#[cfg(test)]
mod tests {
    use std::sync::Mutex;
    use std::time::{Duration, Instant};

    use super::*;

    const CONTROL: IpAddr = IpAddr::V4(Ipv4Addr::new(10, 0, 0, 5));

    /// A sender that answers `outcome` and keeps every target it was given.
    fn fake(outcome: Outcome) -> (Sender, Arc<Mutex<Vec<SocketAddr>>>) {
        let seen = Arc::new(Mutex::new(Vec::new()));
        let kept = seen.clone();
        let sender = Sender(Arc::new(move |target| {
            kept.lock().unwrap().push(target);
            outcome.clone()
        }));
        (sender, seen)
    }

    #[test]
    fn sendto_is_read_as_refused_on_eperm_and_eacces_passed_on_a_count_and_unknown_otherwise() {
        assert_eq!(classify(&Ok(0)), Outcome::Passed);
        for errno in [1, 13] {
            assert_eq!(
                classify(&Err(io::Error::from_raw_os_error(errno))),
                Outcome::Refused,
                "{errno}"
            );
        }
        // ENETUNREACH, EHOSTUNREACH, ENOBUFS, EAGAIN: nothing is known of the table.
        for errno in [101, 113, 105, 11] {
            match classify(&Err(io::Error::from_raw_os_error(errno))) {
                Outcome::Unknown(why) => {
                    assert!(why.starts_with("sendto answered: "), "{why}");
                    assert!(!why.contains('\n'), "{why}");
                }
                other => panic!("{errno}: {other:?}"),
            }
        }
    }

    #[test]
    fn only_a_datagram_that_passed_on_a_node_that_requires_its_table_is_a_problem() {
        let target = SocketAddr::new(CONTROL, PORT);
        let unknown = Outcome::Unknown("sendto answered: network unreachable".into());
        for firewall in [
            Some(NodeFirewall::Required),
            Some(NodeFirewall::NotRequired),
            None,
        ] {
            for outcome in [Outcome::Refused, Outcome::Passed, unknown.clone()] {
                let words = match verdict(target, &outcome, firewall) {
                    Verdict::Problem(words) => {
                        assert_eq!(
                            (&outcome, firewall),
                            (&Outcome::Passed, Some(NodeFirewall::Required))
                        );
                        assert!(words.contains("no VM is placed on the node"), "{words}");
                        words
                    }
                    Verdict::Checked(words) => {
                        assert_ne!(
                            (&outcome, firewall),
                            (&Outcome::Passed, Some(NodeFirewall::Required))
                        );
                        if outcome == Outcome::Passed {
                            assert!(words.starts_with("NOT HELD: "), "{words}");
                        }
                        words
                    }
                };
                assert!(words.contains("(udp:10.0.0.5:9)"), "{words}");
                assert!(!words.contains('\n'), "{words}");
            }
        }
    }

    #[test]
    fn the_try_goes_to_the_control_hosts_address_on_the_discard_port_once() {
        let (sender, seen) = fake(Outcome::Refused);
        let verdict = try_egress(Some(CONTROL), &sender, Some(NodeFirewall::Required));
        assert!(matches!(&verdict, Verdict::Checked(w) if w.contains("was refused on the node")));
        assert_eq!(*seen.lock().unwrap(), [SocketAddr::new(CONTROL, 9)]);

        let v6: IpAddr = "2001:db8::5".parse().unwrap();
        let (sender, seen) = fake(Outcome::Passed);
        let verdict = try_egress(Some(v6), &sender, Some(NodeFirewall::Required));
        assert!(matches!(&verdict, Verdict::Problem(w) if w.contains("(udp:[2001:db8::5]:9)")));
        assert_eq!(*seen.lock().unwrap(), [SocketAddr::new(v6, 9)]);
    }

    #[test]
    fn a_listener_without_tls_tries_nothing_and_says_so() {
        let (sender, seen) = fake(Outcome::Passed);
        let verdict = try_egress(None, &sender, Some(NodeFirewall::Required));
        assert!(matches!(&verdict, Verdict::Checked(w) if w.contains("was not tried")));
        assert!(seen.lock().unwrap().is_empty());
    }

    #[test]
    fn the_real_sender_does_not_try_a_loopback_address() {
        for host in ["127.0.0.1", "127.0.0.2", "::1", "::ffff:127.0.0.1"] {
            let host: IpAddr = host.parse().unwrap();
            let verdict = try_egress(Some(host), &real_sender(), Some(NodeFirewall::Required));
            assert!(
                matches!(&verdict, Verdict::Checked(w) if w.contains("could not be tried") && w.contains("loopback")),
                "{host}: {verdict:?}"
            );
        }
    }

    /// A real datagram, on loopback alone, where the try's own path runs: a fresh socket, a
    /// non-blocking `sendto` that does not wait. What a sandbox of the test machine answers is
    /// its own: the test says what it saw, and fails only when the try waited.
    #[test]
    fn a_real_datagram_to_loopback_is_answered_at_once() {
        let target = SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), PORT);
        let started = Instant::now();
        let outcome = match send(target) {
            Ok(result) => classify(&result),
            Err(Untried(why)) => {
                eprintln!("no socket for the real datagram on this machine, so not tried: {why}");
                return;
            }
        };
        let took = started.elapsed();
        eprintln!("a real datagram to {target}: {outcome:?} in {took:?}");
        assert!(took < Duration::from_secs(1), "{took:?}");
    }
}
