# Verify your images

FLOWTIVO signs every image of a Tinfirm release and its Helm chart, and attaches to each image a signed list of what it contains (an SBOM). Check both before you deploy, to know that the image is the one FLOWTIVO released and that nobody changed it on its way to you: in a registry, in a mirror, or on the media that carried it into an air-gapped network.

You verify offline, with FLOWTIVO's public key alone. Nothing calls out to FLOWTIVO, to GitHub or to Sigstore's public services, so the same steps work on a machine with no route to the internet.

| Image | What it is |
|---|---|
| `ghcr.io/flowtivo/tinfirm-server` | The server: the web app, the REST API and the MCP endpoints |
| `ghcr.io/flowtivo/tinfirm-runner-host` | The runner host, for the Helm chart's runner pod; it holds no agent runtime |
| `oci://ghcr.io/flowtivo/charts/tinfirm` | The Helm chart, an OCI artifact; signed, without an SBOM |

A release names all three with its version: for v1.0.0, the images `ghcr.io/flowtivo/tinfirm-server:v1.0.0` and `ghcr.io/flowtivo/tinfirm-runner-host:v1.0.0`, and the chart `1.0.0`. Its GitHub Release carries the runner host archives and the Compose files, under a signed `SHA256SUMS` ([What is not signed](#what-is-not-signed)).

## What you need

- **cosign 3.1 or later**, Sigstore's command-line tool. One static binary; carry it into an air-gapped network like any other tool.
- **FLOWTIVO's public key**, [`cosign.pub`](../cosign.pub), from <https://docs.tinfirm.com/cosign.pub>. Check it before you trust it, against this page and against the fingerprint FLOWTIVO sends with your contract:

    | Check | Command | Expected |
    |---|---|---|
    | The key's fingerprint, whatever the file's line endings | `openssl pkey -pubin -in cosign.pub -outform DER \| sha256sum` | `f3aa395d8207acf829d58ad3a8d64b2d72f34af38d90ffe177fe93e912106e53` |
    | The file as published | `sha256sum cosign.pub` | `4c9684c54e6ae4b6f2d433070f1d616e13aeb6b8ce468e23b103187ba3b9e399` |

    The key's fingerprint is the one that counts: a file copied through a system that changes line endings has another file hash but the same key.

- **The image's digest.** Verify and deploy an image by its digest (`@sha256:…`), never by its tag alone: a tag can be moved to another image, a digest cannot. To read the digest a release tag names:

    ```sh
    docker buildx imagetools inspect ghcr.io/flowtivo/tinfirm-server:v1.0.0 --format '{{.Manifest.Digest}}'
    docker buildx imagetools inspect ghcr.io/flowtivo/tinfirm-runner-host:v1.0.0 --format '{{.Manifest.Digest}}'
    ```

## Verify the signature

```sh
cosign verify --key cosign.pub --insecure-ignore-tlog=true \
  ghcr.io/flowtivo/tinfirm-server@sha256:<digest>
```

The command lists the checks it made and prints the signed claims as JSON. It exits with `0` only when the image carries a valid signature by the key of `cosign.pub`. Anything else, such as `no signatures found` or `no matching signatures`, means: do not deploy the image, and tell FLOWTIVO.

`--insecure-ignore-tlog=true` is expected. FLOWTIVO records no signature in Sigstore's public transparency log, so there is nothing to look up and nothing your network needs to reach. The signature is checked against `cosign.pub` all the same; cosign warns that it skipped the log.

Each manifest of a multi-platform image is signed too, so you can also verify the digest of one platform.

## Verify the Helm chart

The chart of a release is signed the same way. Its version has no `v`: release `v1.0.0` is chart `1.0.0`. `helm pull` prints the chart's digest:

```sh
helm pull oci://ghcr.io/flowtivo/charts/tinfirm --version 1.0.0
cosign verify --key cosign.pub --insecure-ignore-tlog=true \
  ghcr.io/flowtivo/charts/tinfirm@sha256:<digest>
```

Then install that digest, not the version alone: `helm install tinfirm oci://ghcr.io/flowtivo/charts/tinfirm@sha256:<digest>`. To carry the chart into an air-gapped network, use `cosign save` and `cosign load` as for the images below, then install it from your registry by the same digest.

## Verify and read the SBOM

The SBOM is an [SPDX](https://spdx.dev) document of every package in the image, one for each of its platforms, attached as a signed attestation:

```sh
cosign verify-attestation --key cosign.pub --insecure-ignore-tlog=true --type spdxjson \
  ghcr.io/flowtivo/tinfirm-server@sha256:<digest> > attestation.json
```

The command checks each attestation's signature as `cosign verify` does, then prints the attestations, one a line. To extract the SBOM of the first one:

```sh
head -n 1 attestation.json | jq -r .payload | base64 -d | jq .predicate > tinfirm-server.spdx.json
```

Feed the file to the scanner you use, such as Grype or Trivy, or to your inventory of components.

## On an air-gapped network

Copy each image with its signatures and attestations, and verify it again inside the network. `docker pull` and `docker push` copy the image alone, without them.

1. On a machine with access to `ghcr.io`, verify the image as above, then save it with what is attached to it to a directory:

    ```sh
    cosign save --dir tinfirm-server-v1.0.0 ghcr.io/flowtivo/tinfirm-server@sha256:<digest>
    ```

2. Carry the directory into the network, with `cosign.pub` and the cosign binary.
3. Verify it from the directory, with no network at all:

    ```sh
    cosign verify --key cosign.pub --insecure-ignore-tlog=true --local-image tinfirm-server-v1.0.0
    cosign verify-attestation --key cosign.pub --insecure-ignore-tlog=true --type spdxjson \
      --local-image tinfirm-server-v1.0.0 > attestation.json
    ```

4. Load it into your registry:

    ```sh
    cosign load --dir tinfirm-server-v1.0.0 registry.example.internal/tinfirm/tinfirm-server
    ```

5. Verify it once more where your cluster pulls it from. The digest is the same as on `ghcr.io`:

    ```sh
    cosign verify --key cosign.pub --insecure-ignore-tlog=true \
      registry.example.internal/tinfirm/tinfirm-server@sha256:<digest>
    ```

Then point the Helm chart or the Compose file at your registry, by digest. Do the same for `tinfirm-runner-host` with the runner pod. An offline bundle of every image and update, for Enterprise, is planned; until it exists, copy the images this way.

An admission controller that verifies cosign signatures, such as Sigstore's policy-controller or Kyverno, can enforce the check in your cluster: give it `cosign.pub` as the key, and turn off its transparency log and certificate checks.

## What is not signed

- **The runner images.** A runner host builds them itself, from the Dockerfiles in its own binary; FLOWTIVO publishes no runner image.
- **The release's files** are not signed one by one: the runner host binaries and the Compose files are listed in the release's `SHA256SUMS`, and `SHA256SUMS` is signed with the same key, as `SHA256SUMS.sig` (and the cosign bundle `SHA256SUMS.sigstore.json`). Download them from the release's page on [GitHub](https://github.com/FLOWTIVO/tinfirm/releases), or for v1.0.0:

    ```sh
    base=https://github.com/FLOWTIVO/tinfirm/releases/download/v1.0.0
    curl -fsSL -O "$base/SHA256SUMS" -O "$base/SHA256SUMS.sig" -O "$base/SHA256SUMS.sigstore.json"
    curl -fsSL -O "$base/tinfirm-compose-1.0.0.tar.gz"     # or a runner host archive
    ```

    Check the signature first, then the files against it:

    ```sh
    cosign verify-blob --key cosign.pub --bundle SHA256SUMS.sigstore.json \
      --insecure-ignore-tlog=true SHA256SUMS
    # or, without cosign:
    openssl base64 -d -A -in SHA256SUMS.sig -out SHA256SUMS.der
    openssl dgst -sha256 -verify cosign.pub -signature SHA256SUMS.der SHA256SUMS
    sha256sum -c --ignore-missing SHA256SUMS
    ```

    The prerelease `rolling` is signed with a key of its own, not `cosign.pub`.
- **The images and charts of the development branch**: images tagged `main` and `sha-<commit>`, charts `0.0.0-sha-<commit>`. They are not releases. Run a release in production.
