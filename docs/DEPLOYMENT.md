# Production deployment guide

VH MEDIA LIVE DOWNLOADER backend — host-based production deploy.

> FFmpeg/FFprobe stay on the **host** (or a dedicated media VM). This guide does **not** Dockerize FFmpeg.

Related files:

| File | Purpose |
| --- | --- |
| [`.env.example`](../.env.example) | Environment variable template (no secrets) |
| [`application-prod.yml`](../src/main/resources/application-prod.yml) | Production Spring profile |
| [`docker-compose.yml`](../docker-compose.yml) | **Local** PostgreSQL only |

---

## 1. Architecture assumptions

```text
[ Angular UI / reverse proxy ]
            │
            ▼
[ Spring Boot :8080 ] ── Hikari ──► [ PostgreSQL ]
            │
            ├── FFmpeg / FFprobe (host PATH or absolute paths)
            └── RECORDINGS_DIRECTORY (dedicated volume)
```

- Job metadata lives in **PostgreSQL** (Flyway-managed schema).
- Media bytes live on a **writable volume**; paths never come from user input.
- Active FFmpeg processes are **in-memory**; on unclean kill, startup recovery marks jobs `INTERRUPTED`.

---

## 2. Host prerequisites

| Requirement | Notes |
| --- | --- |
| JDK 21 | Temurin or equivalent |
| PostgreSQL 16+ | Managed or self-hosted; durable storage |
| FFmpeg + FFprobe | Same major version pair; verify with `-version` |
| Disk | Dedicated volume for recordings; monitor free space |
| OS user | Non-root service account that can write the recordings dir |
| Reverse proxy | TLS termination, rate limits, optional basic auth for `/actuator` |

### Install FFmpeg on the host (examples)

```bash
# Debian/Ubuntu (distro packages vary — pin a known-good build in prod)
sudo apt-get update && sudo apt-get install -y ffmpeg
ffmpeg -version
ffprobe -version
```

Windows Server: install a static FFmpeg build and set `FFMPEG_PATH` / `FFPROBE_PATH` to the `.exe` absolute paths.

---

## 3. Secrets and environment

1. Copy `.env.example` → `.env` **outside Git** (or inject the same keys via your secret store).
2. Fill **real** values for `DB_USERNAME`, `DB_PASSWORD`, `APP_CORS_ALLOWED_ORIGINS`.
3. Confirm `.env` is gitignored (this repo ignores `.env`).

**Never** commit production passwords, tokens, or private keys.

Required for `prod`:

| Variable | Required | Notes |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | yes | `prod` |
| `DB_HOST` | yes | |
| `DB_NAME` | yes | |
| `DB_USERNAME` | yes | |
| `DB_PASSWORD` | yes | strong secret |
| `APP_CORS_ALLOWED_ORIGINS` | yes | HTTPS origins, comma-separated, **never** `*` |
| `FFMPEG_PATH` | recommended | absolute path |
| `FFPROBE_PATH` | recommended | absolute path |
| `RECORDINGS_DIRECTORY` | recommended | e.g. `/var/lib/live-downloader/recordings` |

---

## 4. PostgreSQL

```sql
CREATE DATABASE live_downloader;
CREATE USER live_downloader_app WITH ENCRYPTED PASSWORD '...';
GRANT ALL PRIVILEGES ON DATABASE live_downloader TO live_downloader_app;
-- On PG 15+: grant schema privileges as required by your policy.
```

Flyway runs on startup (`validate-on-migrate: true`, `ddl-auto: validate`):

- Migrations: `classpath:db/migration`
- Do **not** enable Hibernate `update`/`create` in production
- Back up before upgrades; test migrations on a staging clone

Hikari pool (prod defaults / overridable via env):

- `maximum-pool-size` ≈ 10
- `minimum-idle` ≈ 2
- `initialization-fail-timeout` fails fast if DB is down at boot

---

## 5. Storage permissions and capacity

```bash
sudo mkdir -p /var/lib/live-downloader/recordings
sudo chown -R livedl:livedl /var/lib/live-downloader
sudo chmod 750 /var/lib/live-downloader/recordings
```

At startup the app:

1. Creates the recordings directory if missing
2. Verifies it is writable (probe file)
3. Refuses to start when free space &lt; `MIN_FREE_DISK_BYTES` (prod default 1 GiB)

Before each new recording it re-checks writability and free space.

Ops recommendations:

- Mount a separate volume; enable OS disk alerts below ~20%
- Align `DISK_SPACE_THRESHOLD_BYTES` (Actuator diskspace) with `MIN_FREE_DISK_BYTES`
- Keep `DELETE_TEMP_AFTER_REMUX=true` unless you need TS for forensics
- Remux **failure** retains `.ts` for recovery — monitor orphaned TS size
- Soft-delete (`DELETE /api/v1/recordings/{id}`) removes files for deletable terminal/pre-start jobs

---

## 6. CORS and security headers

- CORS is enforced for `/api/**` only; origins must be explicit (startup fails on empty or `*`).
- Prod enables `BLOCK_PRIVATE_STREAM_ADDRESSES=true` (SSRF mitigation for private/loopback hosts).
- `SecurityHeadersFilter` adds `X-Content-Type-Options`, `X-Frame-Options`, `Referrer-Policy`, CSP `default-src 'none'`, etc.
- **No authentication** ships in-app yet — put the API behind a private network, VPN, or reverse-proxy auth.
- OpenAPI/Swagger UI is **disabled** in `prod`.

---

## 7. Actuator health

Base path: `/actuator`

| Endpoint | Use |
| --- | --- |
| `GET /actuator/health` | Aggregated health (details hidden in prod) |
| `GET /actuator/health/liveness` | Liveness probe |
| `GET /actuator/health/readiness` | Readiness probe (DB / disk) |
| `GET /actuator/info` | Build/app info (env dump disabled in prod) |

Also available: `GET /api/v1/health` (lightweight app liveness for the UI).

Contributors include: DB, disk space on `RECORDINGS_DIRECTORY`, custom `recordingsDisk` indicator.

**Protect `/actuator` at the reverse proxy** (IP allowlist or auth). Do not expose details publicly.

Example nginx snippet:

```nginx
location /actuator/ {
    allow 10.0.0.0/8;
    deny all;
    proxy_pass http://127.0.0.1:8080;
}
```

---

## 8. Graceful Spring shutdown and active recordings

Prod enables:

```yaml
server.shutdown: graceful
spring.lifecycle.timeout-per-shutdown-phase: ${SHUTDOWN_TIMEOUT:45s}
```

On context close, `RecordingShutdownCoordinator`:

1. Requests graceful FFmpeg stop (`q` → destroy) for every live process
2. Waits up to ~`STOP_TIMEOUT_SECONDS + 3`
3. Remaining processes are still covered by `ProcessDestroyOnExit` (JVM shutdown hook)

**Expected behavior**

| Scenario | Outcome |
| --- | --- |
| Clean stop within window | FFmpeg exits; remux may still be racing shutdown |
| Remux unfinished | Next boot: `StartupJobRecoveryService` → `INTERRUPTED` (or `COMPLETED` if MP4 already valid) |
| Kill -9 | Processes destroyed by OS; jobs recovered as `INTERRUPTED` |

Set `SHUTDOWN_TIMEOUT` ≥ `STOP_TIMEOUT_SECONDS` + remux buffer if you drain recordings on deploy. Prefer draining traffic and waiting for active jobs before rolling restart when possible.

---

## 9. Logging

Prod: `root=WARN`, `com.vhmedia.livedownloader=INFO`, timestamped console pattern.

- Stream URL query strings are redacted (`?[REDACTED]`) in logs and stored errors.
- Do not enable Hibernate SQL logging in prod.
- Ship console/journald to your log aggregator; avoid logging `.env` contents.

---

## 10. API input validation

| Layer | Behavior |
| --- | --- |
| Bean Validation | `@NotBlank` + `@Size(max=2048)` on probe/start bodies |
| `StreamUrlValidator` | http(s) only; rejects `file://`; optional private-host block |
| Pageable | max page size 100 |
| Tomcat | small POST body limits (JSON URLs, not file uploads) |
| Path storage | basenames generated server-side; traversal rejected |

---

## 11. Build and run

```bash
# Build
./mvnw -DskipTests package

# Export env (example)
set -a && source /etc/live-downloader/env && set +a

# Run
java -jar target/live-downloader-0.0.1-SNAPSHOT.jar
```

Windows (PowerShell), after setting env vars:

```powershell
.\mvnw.cmd -DskipTests package
java -jar target\live-downloader-0.0.1-SNAPSHOT.jar
```

Systemd sketch:

```ini
[Service]
User=livedl
EnvironmentFile=/etc/live-downloader/env
ExecStart=/usr/bin/java -jar /opt/live-downloader/live-downloader.jar
SuccessExitStatus=143
TimeoutStopSec=60
Restart=on-failure
```

---

## 12. Pre-flight checklist

- [ ] `SPRING_PROFILES_ACTIVE=prod`
- [ ] DB reachable; Flyway migrates cleanly on staging
- [ ] `ffmpeg -version` / `ffprobe -version` as the service user
- [ ] `VALIDATE_MEDIA_EXECUTABLES=true`
- [ ] Recordings directory owned + writable; free space ≥ threshold
- [ ] CORS origins match the real UI origin(s)
- [ ] TLS at reverse proxy; `/actuator` locked down
- [ ] Backups for PostgreSQL + recordings volume
- [ ] Disk alerts configured
- [ ] Manual E2E checklist smoke (probe, record, stop, download, delete)

---

## 14. Frontend (Angular)

Build the UI against the public API origin (HTTPS). Set `apiBaseUrl` in the Angular environment used for production builds so it points at the reverse-proxied API (for example `https://api.example.com/api/v1`). The UI origin must be listed in `APP_CORS_ALLOWED_ORIGINS`.

---

## 15. What is intentionally not done yet

- Docker image bundling FFmpeg
- In-app authentication / API keys
- Multi-node concurrent recording coordination (semaphore is JVM-local)
- Automated orphaned-`.ts` janitor beyond soft-delete and remux cleanup
