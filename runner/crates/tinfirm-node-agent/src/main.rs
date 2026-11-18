//! `tinfirm-node-agent`: the node agent of ADR-0112. See the crate's README.

use std::path::PathBuf;

use anyhow::Result;
use clap::{Parser, Subcommand};
use tinfirm_node_agent::{helper, linux, listener, plan, tls};

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
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();
    match Cli::parse().command {
        Command::Helper { config } => {
            let config = plan::HelperConfig::load(&config)?;
            let backend = linux::LinuxBackend::new(config.clone());
            let helper = helper::Helper::start(config, backend)?;
            helper::run(helper).await
        }
        Command::Listener { config } => {
            listener::run(listener::ListenerConfig::load(&config)?).await
        }
        Command::Identity { cert, key } => {
            println!("{}", tls::write_identity(&cert, &key, tls::SERVER_NAME)?);
            Ok(())
        }
        Command::Fingerprint { cert } => {
            println!("{}", tls::fingerprint(&cert)?);
            Ok(())
        }
    }
}
