# Tinfirm developer entry points. `make` (or `make help`) lists them.
#
# The Compose stack reads deploy/compose/.env; the first target that needs it copies it
# from .env.example, whose values are public DEVELOPMENT-ONLY defaults.

SHELL := /bin/bash
.SHELLFLAGS := -euo pipefail -c
.DEFAULT_GOAL := help

COMPOSE_DIR  := deploy/compose
COMPOSE_ENV  := $(COMPOSE_DIR)/.env
COMPOSE      := docker compose -f $(COMPOSE_DIR)/compose.yaml
# The oidc profile plus the server's OIDC settings (see compose.oidc.yaml for why they are
# a separate file).
COMPOSE_OIDC := docker compose -f $(COMPOSE_DIR)/compose.yaml -f $(COMPOSE_DIR)/compose.oidc.yaml --profile oidc
# SAML sign-in against the same Keycloak (compose.saml.yaml), with the SP's dev key made in
# $(SAML_DEV_DIR), which git ignores.
COMPOSE_SAML := docker compose -f $(COMPOSE_DIR)/compose.yaml -f $(COMPOSE_DIR)/compose.saml.yaml --profile oidc
SAML_DEV_DIR := $(COMPOSE_DIR)/.saml

CHART    := deploy/helm/tinfirm
IMAGE    ?= ghcr.io/flowtivo/tinfirm-server
MODEL_API_IMAGE ?= ghcr.io/flowtivo/tinfirm-model-api
TAG      ?= dev
REVISION := $(shell git rev-parse --verify -q HEAD 2>/dev/null || echo unknown)

# Lint tools, pinned. ci.yml's helm job runs the same versions as release binaries; change
# both together.
HADOLINT_IMAGE   := hadolint/hadolint:v2.15.1
ACTIONLINT_IMAGE := rhysd/actionlint:1.7.12
SHELLCHECK_IMAGE := koalaman/shellcheck:v0.11.0
DOCKERFILES      := Dockerfile deploy/release/runner-musl.Dockerfile runner/ci.Dockerfile model-api/Dockerfile docs-site/Dockerfile
SHELL_SCRIPTS    := deploy/compose/deploy-host.sh deploy/compose/auto-deploy/*.sh deploy/release/assets.sh deploy/release/sign-images.sh ee/runner/images/vm/setup.sh \
                    .github/check-pins.sh .github/actions/set-version/*.sh

# `make release-assets`: the Linux release assets of HEAD (runner-artifacts.yml builds them
# all), with this version in their names.
VERSION ?= dev-$(shell git rev-parse --short=7 HEAD 2>/dev/null || echo unknown)
ASSETS_DIR := dist/release-assets

# `make logs SERVICE=server` follows one service; empty = all.
SERVICE ?=

# The docs site (docs-site/README.md): its own virtual environment, with the locked Zensical.
DOCS_DIR  := docs-site
DOCS_VENV := $(DOCS_DIR)/.venv

.PHONY: help dev-db up up-oidc up-saml down logs build-image smoke-image model-api-image server-test model-api-test web-test helm-lint deploy-lint ci runner-build runner-test runner-licences runner-licences-check runner-install deploy-host release-assets docs-serve docs-build docs-lock

help: ## List the targets
	@echo "Tinfirm make targets:"
	@awk 'BEGIN {FS = ":.*## "} /^[a-z][a-z-]*:.*## / {printf "  %-12s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

$(COMPOSE_ENV): | $(COMPOSE_DIR)/.env.example
	cp $(COMPOSE_DIR)/.env.example $@
	@echo "Created $@ from .env.example (development-only values)."

dev-db: | $(COMPOSE_ENV) ## Start only Postgres, on 127.0.0.1:5432 (server from the IDE, tests)
	$(COMPOSE) up -d --wait postgres

up: | $(COMPOSE_ENV) ## Build and start postgres + LiteLLM + server; app on http://localhost:8080
	$(COMPOSE) up -d --build
	@echo "Tinfirm: $$(grep -E '^TINFIRM_PUBLIC_URL=' $(COMPOSE_ENV) | cut -d= -f2-)  (bootstrap admin: see $(COMPOSE_ENV))  logs: make logs"
	@echo "LiteLLM admin API: http://127.0.0.1:$$(grep -E '^TINFIRM_LITELLM_HOST_PORT=' $(COMPOSE_ENV) | cut -d= -f2- | grep . || echo 4000)  (master key: LITELLM_MASTER_KEY in $(COMPOSE_ENV); docs/operations/litellm.md)"

up-oidc: | $(COMPOSE_ENV) ## Same as up, plus Keycloak (profile oidc) and OIDC login
	$(COMPOSE_OIDC) up -d --build
	@echo "Tinfirm: $$(grep -E '^TINFIRM_PUBLIC_URL=' $(COMPOSE_ENV) | cut -d= -f2-)  Keycloak: http://keycloak.localhost:8180 (user alice@example.com, see $(COMPOSE_ENV))"

# DEVELOPMENT ONLY: the SAML service provider's key and self-signed certificate (ADR-0086).
$(SAML_DEV_DIR)/sp.crt:
	mkdir -p $(SAML_DEV_DIR)
	openssl req -x509 -newkey rsa:3072 -nodes -keyout $(SAML_DEV_DIR)/sp.key -out $@ -days 825 \
	  -subj "/CN=tinfirm-dev-saml-sp" 2>/dev/null
	@echo "Made the SAML service provider's dev key in $(SAML_DEV_DIR)."

up-saml: | $(COMPOSE_ENV) $(SAML_DEV_DIR)/sp.crt ## Same as up, plus Keycloak and SAML sign-in (needs an Enterprise licence key)
	TINFIRM_SAML_SP_KEY="$$(cat $(SAML_DEV_DIR)/sp.key)" TINFIRM_SAML_SP_CERT="$$(cat $(SAML_DEV_DIR)/sp.crt)" \
	TINFIRM_SAML_SP_CERT_B64="$$(openssl x509 -in $(SAML_DEV_DIR)/sp.crt -outform der | base64 | tr -d '\n')" \
	  $(COMPOSE_SAML) up -d --build
	@echo "Tinfirm: $$(grep -E '^TINFIRM_PUBLIC_URL=' $(COMPOSE_ENV) | cut -d= -f2-)  Keycloak: http://keycloak.localhost:8180 (realm tinfirm-saml, user sam, see $(COMPOSE_ENV))"

down: | $(COMPOSE_ENV) ## Stop and remove all containers, Keycloak included; the volumes are kept
	$(COMPOSE_OIDC) down --remove-orphans
	@echo "Database volume kept. To wipe it: $(COMPOSE_OIDC) down -v"

logs: | $(COMPOSE_ENV) ## Follow the logs (SERVICE=server|postgres|litellm|model-api|keycloak for one)
	$(COMPOSE_OIDC) logs -f --tail=200 $(SERVICE)

build-image: ## Build the server image as $(IMAGE):$(TAG)
	docker build --build-arg REVISION="$(REVISION)" -t $(IMAGE):$(TAG) .

model-api-image: ## Build the model port's image as $(MODEL_API_IMAGE):$(TAG)
	docker build -f model-api/Dockerfile --build-arg REVISION="$(REVISION)" -t $(MODEL_API_IMAGE):$(TAG) .

smoke-image: build-image ## Build $(IMAGE):$(TAG), start it against PostgreSQL and wait for /api/system/info (ci.yml's image job)
	deploy/compose/smoke.sh $(IMAGE):$(TAG)

deploy-host: ## Deploy to one host over SSH: make deploy-host HOST=user@host [URL=https://…] [PORT=8080]
	deploy/compose/deploy-host.sh "$(HOST)" "$(URL)" "$(or $(PORT),8080)"

release-assets: ## Static Linux runner host binaries (x86_64, aarch64) and the Compose tarball of HEAD, in dist/release-assets
	rm -rf $(ASSETS_DIR)
	deploy/release/assets.sh runner-linux x86_64-unknown-linux-musl "$(VERSION)" $(ASSETS_DIR)
	deploy/release/assets.sh runner-linux aarch64-unknown-linux-musl "$(VERSION)" $(ASSETS_DIR)
	deploy/release/assets.sh compose "$(VERSION)" $(ASSETS_DIR)
	deploy/release/assets.sh sums $(ASSETS_DIR)

runner-build: ## Runner: release build of tinfirm-runner-host (runner/target/release/)
	cd runner && cargo build --release -p tinfirm-runner-host

runner-test: ## Runner: cargo test, the local end-to-end tests again over the sandbox bridge and with a VM per run, clippy and fmt (Docker tests run when Docker is available)
	cd runner && cargo test --workspace && TINFIRM_TEST_SANDBOX_BRIDGE=1 cargo test -p tinfirm-runner-host --test local_runs && TINFIRM_TEST_VM_EXECUTOR=1 cargo test -p tinfirm-runner-host --test local_runs && cargo clippy --workspace --all-targets -- -D warnings && cargo fmt --check

runner-licences: ## Runner: write runner/THIRD-PARTY-LICENSES.txt again from Cargo.lock (cargo-about, in Docker)
	docker build -f runner/ci.Dockerfile --target licences-file --output type=local,dest=runner .

runner-licences-check: ## Runner: the crates' licences and runner/THIRD-PARTY-LICENSES.txt, as ci.yml's runner job checks them (in Docker)
	docker build -f runner/ci.Dockerfile --target licences --output type=cacheonly .

runner-install: ## Runner: install tinfirm-runner-host into ~/.cargo/bin
	cargo install --path runner/crates/tinfirm-runner-host

server-test: ## Server: ./mvnw verify (needs Docker for Testcontainers)
	cd server && ./mvnw -B -ntp verify

model-api-test: ## Model port: ./mvnw verify
	cd model-api && ./mvnw -B -ntp verify

web-test: ## Web: npm ci, the packages' licences, lint, test and build
	cd web && npm ci && npm run licences && npm run lint && npm test && npm run build

# Installed again when the lock changes: exactly the packages and hashes requirements.txt lists.
$(DOCS_VENV)/.installed: $(DOCS_DIR)/requirements.txt
	python3 -m venv $(DOCS_VENV)
	$(DOCS_VENV)/bin/pip install --disable-pip-version-check --require-hashes --no-deps -r $(DOCS_DIR)/requirements.txt
	touch $@

docs-serve: $(DOCS_VENV)/.installed ## Docs site: preview on http://localhost:8000, rebuilt on every save
	cd $(DOCS_DIR) && .venv/bin/zensical serve

docs-build: $(DOCS_VENV)/.installed ## Docs site: strict build into docs-site/site (a broken link or anchor fails it)
	cd $(DOCS_DIR) && .venv/bin/zensical build --strict --clean

docs-lock: ## Docs site: lock requirements.in into requirements.txt with hashes (needs uv)
	cd $(DOCS_DIR) && uv pip compile --quiet --universal --python-version 3.11 --generate-hashes requirements.in -o requirements.txt

helm-lint: ## Helm: the chart's LICENSE, lint --strict and template, with default values and each ci/*.yaml
	cmp LICENSE $(CHART)/LICENSE || { echo "$(CHART)/LICENSE must be a copy of the root LICENSE: cp LICENSE $(CHART)/LICENSE" >&2; exit 1; }
	helm lint --strict $(CHART)
	helm template tinfirm $(CHART) > /dev/null
	for values in $(CHART)/ci/*.yaml; do \
	  echo "--- $$values"; \
	  helm lint --strict $(CHART) -f "$$values"; \
	  helm template tinfirm $(CHART) -f "$$values" > /dev/null; \
	done

deploy-lint: ## Compose config (with and without oidc, SAML, Entra ID and second-company examples), hadolint, shellcheck, actionlint, the action pins
	$(COMPOSE) --env-file $(COMPOSE_DIR)/.env.example config --quiet
	$(COMPOSE) --env-file $(COMPOSE_DIR)/.env.example --profile oidc config --quiet
	$(COMPOSE_OIDC) --env-file $(COMPOSE_DIR)/.env.example config --quiet
	TINFIRM_SAML_SP_KEY=lint TINFIRM_SAML_SP_CERT=lint TINFIRM_SAML_SP_CERT_B64=lint \
	  $(COMPOSE_SAML) --env-file $(COMPOSE_DIR)/.env.example config --quiet
	TINFIRM_ENTRA_TENANT_ID=lint TINFIRM_ENTRA_CLIENT_ID=lint TINFIRM_ENTRA_CLIENT_SECRET=lint \
	  $(COMPOSE) -f $(COMPOSE_DIR)/compose.entra.yaml --env-file $(COMPOSE_DIR)/.env.example config --quiet
	TINFIRM_ACME_ISSUER_URI=https://sso.example.com TINFIRM_ACME_CLIENT_ID=lint TINFIRM_ACME_CLIENT_SECRET=lint \
	  $(COMPOSE) -f $(COMPOSE_DIR)/compose.companies.example.yaml --env-file $(COMPOSE_DIR)/.env.example config --quiet
	for file in $(DOCKERFILES); do echo "--- $$file"; docker run --rm -i $(HADOLINT_IMAGE) < "$$file"; done
	docker run --rm -v "$(CURDIR):/mnt:ro" -w /mnt $(SHELLCHECK_IMAGE) $(SHELL_SCRIPTS)
	docker run --rm -v "$(CURDIR):/repo:ro" -w /repo $(ACTIONLINT_IMAGE) -color
	.github/check-pins.sh

ci: deploy-lint helm-lint server-test model-api-test web-test runner-test runner-licences-check docs-build smoke-image ## Everything ci.yml runs, except the security scans and the publishing (smoke-image builds the image first)
