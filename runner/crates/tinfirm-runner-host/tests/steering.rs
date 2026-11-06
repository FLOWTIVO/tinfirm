//! The protocol v2 feature `run.steer`: a person's message reaches the agent's running turn
//! through its steering (ACP `_session/steering`), goes with the prompt when it arrives before
//! the prompt is sent, and is refused when the agent has no steering or the run is not here.
//! Every steer is answered by one `run.steer.ack` before the run's `run.finished`.

mod common;

use common::*;
use serde_json::{Value, json};
use uuid::Uuid;

fn steer(run_id: Uuid, steer_id: Uuid, text: &str) -> Value {
    json!({"type": "run.steer", "runId": run_id, "epoch": 1, "steerId": steer_id, "text": text})
}

async fn ack_of(conn: &mut Conn, steer_id: Uuid) -> Value {
    let id = steer_id.to_string();
    conn.take_where("run.steer.ack", |f| {
        f["type"] == "run.steer.ack" && f["steerId"] == id
    })
    .await
}

fn position(conn: &Conn, wanted: impl Fn(&Value) -> bool) -> usize {
    conn.frames.iter().position(wanted).expect("frame received")
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_steer_reaches_the_running_turn_before_it_ends() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let mut setup = HostSetup::v2();
        setup.executor = local_executor_with("--steering");
        let host = start_host(&server.url, setup).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["features"], json!(["run.steer", "session.bundle"]));

        let spec = AssignSpec::v2("steer", None);
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        let first = Uuid::new_v4();
        conn.send(steer(spec.run_id, first, "Use the staging database."));
        let ack = ack_of(&mut conn, first).await;
        assert_eq!(ack["status"], "DELIVERED", "{ack}");
        assert_eq!(ack["runId"], spec.run_id.to_string());
        assert_eq!(ack["epoch"], 1);
        // The server may send a steer again: the same answer again.
        conn.send(steer(spec.run_id, first, "Use the staging database."));
        let again = ack_of(&mut conn, first).await;
        assert_eq!(again, ack);
        let last = Uuid::new_v4();
        conn.send(steer(spec.run_id, last, "That is all. [done]"));
        let ack = ack_of(&mut conn, last).await;
        assert_eq!(ack["status"], "DELIVERED", "{ack}");

        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        let text = t.agent_text();
        assert!(
            text.contains("Steered: Use the staging database."),
            "{text}"
        );
        assert!(text.contains("Steered: That is all. [done]"), "{text}");
        assert!(
            t.text()
                .contains("a message of the person reached the agent's running turn"),
            "{}",
            t.text()
        );
        // Each ack went out before the run's end.
        let finished = position(&conn, |f| f["type"] == "run.finished");
        let last_ack = position(&conn, |f| {
            f["type"] == "run.steer.ack" && f["steerId"] == last.to_string()
        });
        assert!(last_ack < finished);

        // Once the run is over here, a steer is refused.
        let late = Uuid::new_v4();
        conn.send(steer(spec.run_id, late, "Too late."));
        let ack = ack_of(&mut conn, late).await;
        assert_eq!(ack["status"], "REFUSED");
        assert_eq!(ack["code"], "NOT_RUNNING");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_steer_before_the_prompt_goes_with_the_prompt() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        // No steering in the agent: the prompt carries the message all the same.
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::v2("steer", None);
        conn.send(spec.to_json());
        let early = Uuid::new_v4();
        conn.send(steer(spec.run_id, early, "Before you start: [done]"));
        let ack = ack_of(&mut conn, early).await;
        assert_eq!(ack["status"], "DELIVERED", "{ack}");
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "SUCCEEDED", "{}", t.text());
        assert!(
            t.agent_text().contains("Prompt had [done]."),
            "{}",
            t.agent_text()
        );
        assert!(
            t.text()
                .contains("1 message(s) of the person went with the prompt"),
            "{}",
            t.text()
        );
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn an_agent_without_steering_and_an_unknown_run_refuse() {
    with_timeout(120, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::v2()).await;
        let mut conn = server.accept().await;
        conn.handshake(5).await;
        let spec = AssignSpec::v2("slow", None);
        conn.send(spec.to_json());
        conn.wait_running(spec.run_id).await;
        let refused = Uuid::new_v4();
        conn.send(steer(spec.run_id, refused, "Use staging."));
        let ack = ack_of(&mut conn, refused).await;
        assert_eq!(ack["status"], "REFUSED", "{ack}");
        assert_eq!(ack["code"], "NOT_SUPPORTED");

        // Another epoch, and a run the host never had.
        let stale = Uuid::new_v4();
        conn.send(json!({"type": "run.steer", "runId": spec.run_id, "epoch": 7, "steerId": stale, "text": "x"}));
        let ack = ack_of(&mut conn, stale).await;
        assert_eq!(ack["code"], "NOT_RUNNING");
        assert_eq!(ack["epoch"], 7);
        let unknown = Uuid::new_v4();
        let steer_id = Uuid::new_v4();
        conn.send(steer(unknown, steer_id, "x"));
        let ack = ack_of(&mut conn, steer_id).await;
        assert_eq!(ack["status"], "REFUSED");
        assert_eq!(ack["code"], "NOT_RUNNING");
        assert_eq!(ack["runId"], unknown.to_string());

        conn.send(json!({"type": "run.cancel", "runId": spec.run_id, "epoch": 1}));
        let t = conn.until_finished(spec.run_id).await;
        assert_eq!(t.finished()["outcome"], "CANCELLED");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread", worker_threads = 4)]
async fn a_v1_host_lists_no_features() {
    with_timeout(60, async {
        let mut server = FakeServer::start().await;
        let host = start_host(&server.url, HostSetup::local()).await;
        let mut conn = server.accept().await;
        let hello = conn.handshake(5).await;
        assert_eq!(hello["protocolVersion"], 1);
        assert!(hello.get("features").is_none(), "{hello}");
        host.shutdown.cancel();
        host.handle.await.unwrap().unwrap();
    })
    .await;
}
