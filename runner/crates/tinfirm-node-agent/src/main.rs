//! `tinfirm-node-agent`: the node agent of ADR-0112. See the crate's README.

use std::path::PathBuf;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tinfirm_node_agent::{harden, helper, linux, listener, plan, tls};

/// Every block of memory that this program frees is overwritten with zeros first, in the
/// helper and in the listener: a VM's user data, with its run's one-time ticket, passes
/// through buffers of the TLS library and of the JSON parser, which the agent's own code
/// cannot overwrite (`spool::Wiped` covers what it holds itself). A buffer that a library
/// keeps and uses again is not freed, so it keeps its bytes until they are written over
/// (threat model, 19.7).
#[global_allocator]
static ALLOCATOR: zeroizing_alloc::ZeroAlloc<std::alloc::System> =
    zeroizing_alloc::ZeroAlloc(std::alloc::System);

#[derive(Parser)]
#[command(name = "tinfirm-node-agent", version = tinfirm_node_agent::wire::build_id())]
#[command(about = "Makes and deletes jailed Firecracker VMs for a Tinfirm control host (ADR-0112)")]
struct Cli {
    #[command(subcommand)]
    command: Command,
}

#[derive(Subcommand)]
enum Command {
    /// The helper: root, no network. Serves the listener on a local socket.
    Helper {
        #[arg(
            long,
            env = "TINFIRM_NODE_HELPER_CONFIG",
            default_value = "/etc/tinfirm/node-agent/helper.json"
        )]
        config: PathBuf,
    },
    /// The listener: an unprivileged user. Takes the control host's connection.
    Listener {
        #[arg(
            long,
            env = "TINFIRM_NODE_LISTENER_CONFIG",
            default_value = "/etc/tinfirm/node-agent/listener.json"
        )]
        config: PathBuf,
    },
    /// Sets the hardening's settings that last until a reboot, as `hardening.json` asks: kexec
    /// off, lockdown at `integrity`, module loading off once every module the agent needs is
    /// there. Run at each boot by `tinfirm-node-agent-hardening.service`; a second run changes
    /// nothing.
    Harden {
        #[arg(long, default_value = harden::CONFIG)]
        config: PathBuf,
    },
    /// Writes a new key (PKCS #8 DER, 0600) and its self-signed certificate (DER), which must
    /// not exist, and prints the certificate's SHA-256: the pin of the other side.
    Identity {
        #[arg(long)]
        cert: PathBuf,
        #[arg(long)]
        key: PathBuf,
    },
    /// Prints the SHA-256 of a certificate (DER).
    Fingerprint {
        #[arg(long)]
        cert: PathBuf,
    },
    /// Cuts this process's capability bounding set to the six capabilities the jailer needs
    /// and replaces it with the jailer (ADR-0112 decision 4). The helper starts it for each
    /// VM; it is not run by hand. It takes root's own file named `jailer` and arguments that
    /// start with `--id` and a jail's name of the helper's, and nothing else.
    #[command(hide = true, name = tinfirm_node_agent::bounding::START_JAILER)]
    StartJailer {
        jailer: PathBuf,
        #[arg(trailing_var_arg = true, allow_hyphen_values = true)]
        args: Vec<std::ffi::OsString>,
    },
}

fn main() -> Result<()> {
    let command = Cli::parse().command;
    // Before any other thread exists: a bounding set is a thread's own, and the exec must
    // come from the thread that cut it.
    if let Command::StartJailer { jailer, args } = &command {
        return Err(tinfirm_node_agent::bounding::start_jailer(jailer, args));
    }
    tokio::runtime::Builder::new_multi_thread()
        .enable_all()
        .build()?
        .block_on(run(command))
}

async fn run(command: Command) -> Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
    match command {
        Command::Helper { config } => {
            let config = plan::HelperConfig::load(&config)?;
            let backend = linux::LinuxBackend::new(config.clone());
            let helper = helper::Helper::start(config, backend)?;
            helper::run(helper).await
        }
        Command::Listener { config } => {
            listener::run(listener::ListenerConfig::load(&config)?).await
        }
        Command::Harden { config } => harden::harden(
            std::path::Path::new("/"),
            &harden::HardenConfig::load(&config)?,
        ),
        Command::Identity { cert, key } => {
            println!("{}", tls::write_identity(&cert, &key, tls::SERVER_NAME)?);
            Ok(())
        }
        Command::Fingerprint { cert } => {
            println!("{}", tls::fingerprint(&cert)?);
            Ok(())
        }
        Command::StartJailer { .. } => unreachable!("handled before the runtime starts"),
    }
}

#[cfg(all(test, target_os = "linux"))]
mod tests {
    use std::io::{Read, Seek, SeekFrom};

    /// The size of a test's block: one that the system allocator keeps mapped once it is
    /// free. The first bytes of a block are left out, where an allocator keeps its own list
    /// of free blocks.
    const LEN: usize = 4096;
    const SKIP: usize = 64;

    /// This process's memory as the kernel gives it (`/proc/self/mem`): no pointer of a test
    /// is followed after its block is freed. The file and the buffer exist before the block
    /// does, so that reading allocates nothing in the block's place.
    struct Memory {
        mem: std::fs::File,
        bytes: Vec<u8>,
    }

    impl Memory {
        fn new() -> Memory {
            Memory {
                mem: std::fs::File::open("/proc/self/mem").unwrap(),
                bytes: vec![0; LEN - SKIP],
            }
        }

        /// Whether the block at `address` holds `canary`. A block that the allocator gave
        /// back to the system cannot be read, and holds nothing.
        fn holds(&mut self, address: usize, canary: &[u8]) -> bool {
            self.mem
                .seek(SeekFrom::Start((address + SKIP) as u64))
                .unwrap();
            self.mem.read_exact(&mut self.bytes).is_ok()
                && self.bytes.windows(canary.len()).any(|part| part == canary)
        }
    }

    /// The binary's allocator overwrites a block when it is freed. Another thread may take
    /// the block in between, and then it holds that thread's bytes: not the canary either way.
    #[test]
    fn a_freed_block_no_longer_holds_what_was_in_it() {
        let canary = b"TINFIRM-CANARY-OF-A-FREED-BLOCK-";
        let mut memory = Memory::new();
        let block: Vec<u8> = canary.iter().copied().cycle().take(LEN).collect();
        let address = block.as_ptr() as usize;
        assert!(memory.holds(address, canary), "the block holds the canary");
        drop(block);
        assert!(
            !memory.holds(address, canary),
            "the canary is still in the freed block: the allocator did not overwrite it"
        );
    }

    /// A buffer that grows moves to a new block, and its old block is overwritten too.
    #[test]
    fn a_buffer_that_grew_leaves_nothing_in_its_old_block() {
        let canary = b"TINFIRM-CANARY-OF-A-GROWN-BUFFER-";
        let mut memory = Memory::new();
        let mut buffer: Vec<u8> = Vec::with_capacity(LEN);
        buffer.extend(canary.iter().copied().cycle().take(LEN));
        let old = buffer.as_ptr() as usize;
        assert!(memory.holds(old, canary), "the buffer holds the canary");
        buffer.reserve_exact(1 << 16);
        assert_ne!(buffer.as_ptr() as usize, old, "the buffer moved");
        assert!(
            buffer.windows(canary.len()).any(|part| part == canary),
            "the buffer kept its bytes"
        );
        assert!(
            !memory.holds(old, canary),
            "the canary is still in the buffer's old block"
        );
    }
}
