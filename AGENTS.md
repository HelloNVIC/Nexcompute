# AGENTS.md

Guidance for OpenCode sessions in this repo. `README.md` is the full product/architecture doc (Chinese). This file only covers what an agent would otherwise get wrong.

## Repo layout

Three independently built components + spec-driven change tracking:

- `management-backend/` — Spring Boot 3.5, Java 21 (Gradle), JPA + Flyway, WebSocket agent channel, SSE
- `management-frontend/` — Vue 3 + TS + Vite, Ant Design Vue, Pinia
- `controlled-agent/` — Go tray GUI (Fyne, CGO) for Windows GPU hosts; outbound-only heartbeat (HTTP) + WS command channel
- `openspec/` — capability specs (source of truth) and change archives; see "OpenSpec workflow"

## Commands

### Backend (`management-backend/`)

```powershell
.\gradlew.bat bootJar -x test      # build JAR (build.ps1 copies it to app.jar for Dockerfile.prebuilt)
.\gradlew.bat test                 # all tests
.\gradlew.bat test --tests "com.nexcompute.management.service.AuthServiceTest"  # one class
.\gradlew.bat bootRun              # dev run
```

- `./gradlew` is known to hang on some machines (see README); fall back to a locally installed Gradle with JDK 21. `gradle.properties` sets `org.gradle.daemon=false` deliberately.
- `bootRun` does **not** read the root `.env` (`spring.config.import` was removed). All config comes from `application.yml` `${ENV:default}` placeholders — inject secrets via shell env, IDE run config, or docker-compose.
- Existing tests are plain JUnit 5 + Mockito unit tests. Testcontainers (PostgreSQL) is declared as a dependency but currently unused.

### Frontend (`management-frontend/`)

```bash
npm install --legacy-peer-deps     # peer conflicts otherwise
npm run dev                        # http://localhost:5173, proxies /api -> localhost:8080
npm run type-check                 # vue-tsc --noEmit — the ONLY automated check (no lint, no tests)
npm run build                      # runs vue-tsc --noEmit first, then vite build
```

- Ant Design Vue components are auto-imported via `unplugin-vue-components` — do not add manual imports for them.
- Path alias `@` → `src/`.

### Controlled agent (`controlled-agent/`)

```powershell
.\build.ps1 -Target Agent          # preferred: kills running exe, discovers gcc, injects version
go test ./...
```

- Fyne requires CGO → gcc (MinGW-w64) must be on PATH; `build.ps1` auto-discovers gcc from common/WinGet locations.
- Kill any running `nexcompute-agent.exe` before `go build`, or the file lock breaks the build.
- Manual build: `CGO_ENABLED=1 go build -ldflags "-X github.com/nexcompute/controlled-agent/internal/version.Version=<ver>" -o nexcompute-agent.exe ./cmd/nexcompute-agent`
- Agent version resolution order: git tag → `Makefile` `VERSION` → `0.1.0`.
- `.syso` embeds the icon + `requireAdministrator` manifest — the exe must run elevated.

### Full stack / deploy

- `.\build.ps1` (interactive) / `./deploy.sh`: builds JAR locally → frontend image (npm build inside Docker) → backend image (`Dockerfile.prebuilt`) → `docker compose up -d` → health check. Optionally pushes to private registry `10.13.66.18:5002/nexcompute/{backend,frontend}`.
- Dev-mode infra only: `docker compose up -d postgres redis`.
- Compose image tags come from the `VERSION` env var (default `latest`).
- `docker-compose.yml` and `docker-compose.prod.yml` are **gitignored** (real secrets); the committed `*.example.yml` files are templates — copy and fill. When adding env config, update BOTH example templates.
- `docker push` to the insecure registry is flaky (EOF with containerd image store); `build.ps1` retries up to 10× — mirror that behavior when pushing manually.
- Default login `admin` / `admin123`. Dev: frontend http://localhost, API http://localhost:8080/api; prod API goes through nginx at `/api`.
- `*.exe`, `*.jar` (incl. `management-backend/app.jar`), and `controlled-agent/nexcompute-agent.json` (contains agent token) are gitignored artifacts — never commit them.

## Backend conventions

- Flyway migrations in `src/main/resources/db/migration`, currently at **V35**. Never edit an applied migration — add the next `V{n}__name.sql`. Follow V32/V33 style (`COMMENT ON` for Chinese column docs).
- Migration SQL must not contain `${` anywhere, including comments/strings — an unconfigured placeholder crashes startup at Flyway parse time.
- `hibernate.ddl-auto=validate` — JPA entity fields/types must exactly match migration DDL.
- Permissions are DB-driven (role × module × VIEW/EDIT/DELETE). New endpoints only need `@RequirePermission(module = "...", action = Action.X)`; `PermissionAuthorizationInterceptor` enforces.
- Audit: a global interceptor records every non-GET write automatically. Use `@Audited` on service methods only to add semantics (annotated requests are skipped by the interceptor to avoid duplicates; `force=true` records even with the audit switch off).
- Agent commands are dispatched over the WS session registry in `agent/`, correlated by `commandId` (`container.*`, `storage.*`, `terminal.*`, ...). Agents are outbound-only — never assume inbound connectivity to a controlled host.
- New env config = `${ENV_VAR:default}` in `application.yml` + placeholder in both compose example templates.

## OpenSpec workflow (how features are built here)

Features are spec-driven: `openspec/specs/<capability>/spec.md` is the living source of truth; each iteration is a change folder `openspec/changes/<name>/{proposal,design,tasks.md,specs/}` archived to `openspec/changes/archive/` when done. Use the repo skills `openspec-propose` / `openspec-apply-change` / `openspec-update-change` / `openspec-archive-change` / `openspec-sync-specs` (also `/opsx-*` commands). Read a capability's spec before touching it.

## Language & commits

- Comments, docs, specs, and commit messages are in **Chinese**.
- Work lands directly on `main` as feature-bundle commits titled `主题 + 主题2：中文摘要` (e.g. `password-management + id-validation + ux-refinements：修改密码/...`).
