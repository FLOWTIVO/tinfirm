//! `tinfirm-node-agent`: the node agent of ADR-0112. See the crate's README.

use std::path::PathBuf;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tinfirm_node_agent::{harden, helper, linux, listener, plan, tls};

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
