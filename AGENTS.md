# ratekit

Open-source usage metering and rating engine. Learning project: Java 21, Spring Boot 3, Maven multi-module, Kafka, PostgreSQL. Three services: `ingest`, `rating`, `billing`.

## Rules

- Keep this file a short map; detail lives in `docs/` (see the `project-context` skill).
- Prefer small, reviewable changes and run the relevant checks before handoff.
- To-dos, status and project decisions live in this repo (`docs/`), not in the vault.
- The owner is learning the architecture: go one step at a time, explain the why of each decision, and wait for review before the next step.
- Delivery semantics are at-least-once plus idempotent consumers. Never claim exactly-once.
- Code is layered per service: `controller`, `dto`, `mapper`, `service`, `repository`, `messaging`, `scheduler`, `config`, `exception`; pricing rules live in a framework-free `domain` package (a test enforces it). One class, one job. New code follows this layout (`docs/adr/0003-package-structure.md`).
- Money uses `BigDecimal` with an explicit rounding rule, never `double`.
- Balance model is prepaid with a hard stop: an event that exceeds the balance is rejected.
- License is Apache-2.0.
- AGPL projects (Lago, CGRateS) are read for ideas only; do not copy their code.

## Commands

- JDK 21 is keg-only. Set it per shell before Maven: `export JAVA_HOME=/opt/homebrew/opt/openjdk@21`. The system default is JDK 25, which is not the target.
- Build and test: `mvn -B verify` from the repo root.
- Testcontainers on Podman: run Maven with `DOCKER_HOST=unix:///var/run/docker.sock` (the Podman VM exposes the Docker API there). Verified on 2026-10-03; Ryuk needed no extra setting.
- Containers run on Podman (no Docker). The VM must be up: `podman machine start`. Compose files stay Docker-compatible.
- Whole stack in containers: `podman compose up -d --build` (five containers). On a 2 GB Podman VM stop it before `mvn verify` (Testcontainers needs room; running both got Kafka OOM-killed, exit 137). This machine's VM was raised to 4 GB on 2026-10-03. Details in `docs/specs/local-dev.md`.
- Load test: `COMPOSE="podman compose" CONTAINER=podman load/run.sh`; commands, results and limits in `docs/perf.md`.
- Plan and its status: `docs/plans/2026-10-03-ratekit-plan.md`.

## Read when relevant

- What each docs path holds: `docs/README.md`; decisions: `docs/adr/README.md`; measurements: `docs/perf.md`
- Landscape, stack options, risks, open questions: `docs/research/2026-10-03-kickoff.md`
