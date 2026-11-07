# Try Tinfirm with Docker Compose

This page starts Tinfirm on one machine: the server, PostgreSQL and the bundled LiteLLM in Docker Compose, from the Compose files of a release, then a model and a runner host, so that you can talk to a first agent.

!!! danger "For your own machine only"

    The Compose file reads its settings from `.env`, which you copy from `.env.example`. Its values are public development defaults, among them the vault's master key. Never use them on a system that other people can reach. Compose publishes every port on `127.0.0.1` only, unless you change `TINFIRM_HTTP_BIND`.

## Before you start

You need Docker with Compose v2, `curl` and `openssl`, and FLOWTIVO's public key [`cosign.pub`](../cosign.pub) to check the download ([Verify your images](verify-images.md)).

## 1. Start the stack

Download the Compose files of the release, v1.0.0, check them against the release's signed `SHA256SUMS`, and unpack them:

```sh
base=https://github.com/FLOWTIVO/tinfirm/releases/download/v1.0.0
curl -fsSL -O "$base/tinfirm-compose-1.0.0.tar.gz" -O "$base/SHA256SUMS" -O "$base/SHA256SUMS.sig"
openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
openssl dgst -sha256 -verify cosign.pub -signature SHA256SUMS.der SHA256SUMS   # must say Verified OK
sha256sum -c --ignore-missing SHA256SUMS                                        # macOS: shasum -a 256 -c --ignore-missing SHA256SUMS
tar -xzf tinfirm-compose-1.0.0.tar.gz
cd tinfirm-compose-1.0.0
```

Copy the example settings, name the release's server image, and start the stack:

```sh
cp .env.example .env
echo 'TINFIRM_SERVER_IMAGE=ghcr.io/flowtivo/tinfirm-server:v1.0.0' >> .env
docker compose up -d --no-build
```

Change a value in `.env`, not in the example. Compose pulls the release's image, and starts PostgreSQL, LiteLLM and the server. For anything beyond your own machine, name the image by the digest you verified, `ghcr.io/flowtivo/tinfirm-server@sha256:<digest>` ([Verify your images](verify-images.md)).

!!! info "From a checkout, for development"

    From a checkout of Tinfirm's repository, `make up` runs the development branch instead: it copies `deploy/compose/.env.example` to `deploy/compose/.env` the first time, builds the server's image from the checkout, which takes several minutes, and starts the same stack. That is not a release. The steps below are the same, with `deploy/compose/.env` in place of `.env`.

## 2. Sign in

Open <http://localhost:8080> and sign in as the bootstrap admin. The email and password are `TINFIRM_BOOTSTRAP_ADMIN_EMAIL` and `TINFIRM_BOOTSTRAP_ADMIN_PASSWORD` in `.env`: by default `admin@example.com` and `dev-only-admin-password`.

On its first start, while it has no users, the server creates an instance admin with the bootstrap email and password. It does this once. Changing the bootstrap values later changes no user.

Companies are declared in the deployment, never created in the app. `compose.yaml` declares one, `My Company`, or the name in `TINFIRM_COMPANY_NAME`. At every start the server creates it if it does not exist yet. While it has no Owner, the bootstrap admin, or the account named in `TINFIRM_COMPANY_OWNER`, becomes its Owner. Change the admin's password on your **Profile** after the first sign-in.

## 3. Add a model to LiteLLM

The bundled LiteLLM serves no model until you add one, with the master key (`LITELLM_MASTER_KEY` in `.env`) through its admin API on <http://127.0.0.1:4000>. Put the request in a file, so that the provider's key stays out of your shell history:

```json title="model.json"
{
  "model_name": "<the model name agents ask for>",
  "litellm_params": {
    "model": "anthropic/<the provider's model id>",
    "api_key": "sk-ant-…"
  }
}
```

```sh
curl -sS http://127.0.0.1:4000/model/new \
  -H "Authorization: Bearer <LITELLM_MASTER_KEY>" \
  -H "Content-Type: application/json" \
  -d @model.json
rm model.json
```

Claude Code also asks for a small, fast model for background work: add one too. A person picks their model in **My settings** of an agent, among the models their model access allows: by default, every model the gateway serves.

## 4. Connect a runner host

Agents' turns run on runner hosts, not in the server. Without one, no turn paid by a budget runs. The machine you are on can be the first.

1. In Tinfirm, open **Runner hosts** in the sidebar's **Company** section, and choose **Register a runner host**. Give the host a name and choose **Register**.
2. The dialog shows the host's token once, with the commands to run on the host. The host needs Docker and `tinfirm-runner-host`: install it from a release, the archive `tinfirm-runner-host-1.0.0-<platform>` of v1.0.0 with its signed `SHA256SUMS` ([Install and run runner hosts](runner-hosts.md#install)), or build it from your checkout with `make runner-install`, which needs Rust 1.89 or later.
3. Run the commands the dialog shows:

    ```sh
    tinfirm-runner-host login --server http://localhost:8080 --token tfr_…
    tinfirm-runner-host images build
    tinfirm-runner-host doctor
    tinfirm-runner-host run
    ```

    `images build` takes a few minutes the first time. `doctor` checks Docker, the images and the connection to the server.

The host shows as **Online** on the **Runner hosts** page.

## 5. Talk to an agent

Open **Agents**, make an agent, and start a conversation with it. The [User guide](../user-guide/index.md) goes on from there.

## Look after the stack

In the directory of the release's Compose files:

| Command | What it does | From a checkout |
|---|---|---|
| `docker compose logs -f` | Follow the logs. `docker compose logs -f server` follows one service. | `make logs`, `make logs SERVICE=server` |
| `docker compose down` | Stop and remove the containers. The database volume is kept. | `make down` |
| `docker compose -f compose.yaml -f compose.oidc.yaml --profile oidc up -d --no-build` | Start the stack with Keycloak, for sign-in through OIDC | `make up-oidc` |

To start again from an empty instance, remove the database volume as well:

```sh
docker compose -f compose.yaml -f compose.oidc.yaml --profile oidc down -v
```

From a checkout, the same command names `deploy/compose/compose.yaml` and `deploy/compose/compose.oidc.yaml`. [Upgrade the stack](maintenance.md#upgrade-the-stack) moves it to a later release.
