# RentRadar Backend

Spring Boot 3.5 API gateway for RentRadar, a rental property intelligence platform for Ghana. It fronts the Python inference service and persists to MongoDB. The full design is in the RentRadar build reference.

## Running locally

**Prerequisites:** JDK 21 and Docker (Docker Desktop on Windows or macOS). Maven does not need to be installed, the wrapper fetches it.

**First time only**, create your local environment file and replace the `change-me` passwords:

```bash
cp .env.example .env
```

**Start the stack** with one command:

```bash
docker compose up -d --wait && ./mvnw spring-boot:run
```

This starts MongoDB, waits until its health check passes, then runs the API on port 8080 under the `dev` profile (the default when no profile is set). On Windows use `mvnw.cmd` in place of `./mvnw`.

**Confirm it is healthy:**

```bash
curl http://localhost:8080/actuator/health
```

The response should report `"status":"UP"` with `mongo` listed under `components`.

**Stop:** `docker compose down` keeps the data volume. `docker compose down -v` deletes it, which is also required after changing any password in `.env`, because the database user is only created on first start.

## Configuration

| Profile | Activated by | MongoDB connection |
|---|---|---|
| `dev` | default when no profile is set | compose instance on `localhost`, credentials from `.env` |
| `test` | `@ActiveProfiles("test")` in tests | throwaway Testcontainers instance, wired automatically |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | `MONGODB_URI` environment variable, required, no default |

No credential lives in source control. `.env` is gitignored, the dev profile reads it, and the API connects as a least-privilege application user rather than the database superuser.

## Tests and coverage

```bash
./mvnw verify
```

Docker must be running: the integration tests start their own MongoDB container, independent of the compose stack. The JaCoCo report is written to `target/site/jacoco/index.html`. The 80 per cent line coverage target is measured on every build but does not yet fail it.

## Continuous integration

`.github/workflows/ci.yml` runs on every push and pull request, and weekly:

- **Build and test:** `./mvnw verify`, with coverage and test reports uploaded as artifacts.
- **Dependency scan:** OWASP Dependency-Check against the NVD, failing the build on any shipped dependency with a CVSS score of 7.0 or higher. Add a free NVD API key as the repository secret `NVD_API_KEY`, otherwise the scan is heavily rate limited. Documented false positives go in `config/dependency-check-suppressions.xml`.

To run the scan locally:

```bash
NVD_API_KEY=your-key ./mvnw org.owasp:dependency-check-maven:check
```
