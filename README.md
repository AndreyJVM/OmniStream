# OmniStream AI

**[Open the live demo](https://omnistream-ai.onrender.com/)** · [AI workspace](https://omnistream-ai.onrender.com/workspace#ai) · [API reference](https://omnistream-ai.onrender.com/swagger-ui.html) · [CI status](https://github.com/SalarAmir/OmniStream/actions)

[Product](#what-you-can-do) · [AI output](#what-ai-extracts) · [Architecture](#architecture) · [Demo limits and privacy](#demo-limits-and-privacy) · [CI/CD](#cicd) · [Run locally](#run-locally) · [Deployment](#deploy-the-complete-web-app)

The free Render service may need about a minute to wake after idle time.

![OmniStream landing page](docs/screenshots/landing.png)

![AI workspace analyzing a synthetic incident report](docs/screenshots/ai-workspace.png)

**Turn webhooks and document text into structured, inspectable intelligence.**

OmniStream is an asynchronous ingestion engine with a browser workspace for watching AI turn raw input into a summary, category, risk assessment, sentiment score, and actionable entities. Every submission receives an event ID and a persistent processing state, so you can follow it from intake to the saved result.

Built with Java 21, Spring Boot, PostgreSQL, Apache Kafka, and Groq Cloud.

## What you can do

- **Analyze text and webhooks.** Paste a payload, load a text file, or submit JSON through the REST API.
- **Watch the workflow.** The AI workspace displays ingestion, analysis, and saved-result stages driven by the event’s actual server status.
- **Inspect useful output.** Review a concise summary, category and risk tags, sentiment, extracted key/value pairs, and expandable structured JSON.
- **Trace the original input.** Open an event to compare its raw payload, metadata, timestamps, processing state, and analysis.
- **Explore the stream.** Search by source, event type, or ID and filter by status.
- **Monitor recent activity.** The overview shows intake volume, category distribution, completed events, failures, and work in flight.
- **Start with examples.** Invoice, incident-report, and sensor-alert examples demonstrate different extraction tasks. The Help tab explains the workflow.
- **Choose a deployment model.** Use Kafka for the distributed pipeline or PostgreSQL-backed queuing for a smaller hosted installation.

## A typical workflow

1. Open the landing page and enter the workspace.
2. The hosted portfolio workspace connects automatically. The Windows app uses its local connection. Private deployments can require a service API key.
3. Open **AI workspace**, choose an example, or paste your own text.
4. Select **Analyze payload**. Intake saves the event before analysis begins.
5. Follow its progress and inspect the completed result. The event remains available in **Event stream**.

The interface shows the configured provider and model. While work is pending, it displays loading feedback and elapsed waiting time. Completed results show end-to-end event duration. If a status request fails, **Check status** resumes inspection of the accepted event.

## What AI extracts

| Field | Meaning |
| --- | --- |
| `summary` | A factual description of the payload |
| `category` | `FINANCIAL`, `SYSTEM_LOG`, `IOT_TELEMETRY`, or `UNKNOWN` |
| `sentiment_score` | A numeric score from −1 to +1 |
| `risk_level` | `LOW`, `MEDIUM`, `HIGH`, or `CRITICAL` |
| `actionable_entities` | Key/value pairs supported by the source text |

For example, an overdue invoice can yield its invoice ID, amount, currency, and payment status. An incident report can yield a service, host, region, error rate, and recovery action. Results depend on the supplied text and the active parser.

### Provider modes

| Mode | Behavior |
| --- | --- |
| `groq` | Default. Groq Cloud with `openai/gpt-oss-120b` |
| `openai` | Optional OpenAI adapter with a configurable model in private mode |
| `local` | Deterministic keyword and labeled-field extraction for offline demonstrations |

The Groq adapter requests strict JSON Schema output using medium reasoning effort, a 2,048-token completion budget, and a non-streaming response. It validates the returned data before saving it. Provider errors, incomplete responses, and invalid results enter the retry path.

Raw payloads and metadata are sent to the selected AI provider in cloud parser modes. Provider credentials stay on the server.

## Architecture

```mermaid
flowchart LR
    A[Browser or webhook client] --> B[Validated intake API]
    B --> C[(PostgreSQL event and outbox)]
    C --> D{Processing mode}
    D -->|Kafka| E[Broker and consumer workers]
    D -->|Hosted| F[PostgreSQL queue worker]
    E --> G[Structured extraction]
    F --> G
    G --> H[Validated result]
    H --> C
    C --> I[Workspace and event API]
```

The event and its outbox entry commit in one database transaction. The API then returns `202 Accepted`. Analysis runs asynchronously.

| Event state | Meaning |
| --- | --- |
| `RECEIVED` | Input is saved and waiting for processing |
| `PROCESSING` | A worker has started analysis |
| `COMPLETED` | Validated analysis is saved |
| `FAILED` | Processing retries were exhausted |

The acceptance response uses `PENDING` as its receipt status.

### Kafka processing

The relay publishes confirmed outbox batches to `raw-ingestion-events`. Three consumer threads share `omnistream-processing-group`. The producer uses idempotence and `acks=all`; consumers acknowledge after the database result commits.

After an initial failure and three exponentially delayed retries, recovery publishes to `raw-ingestion-events.DLQ` and marks the event failed. Terminal events skip repeated analysis on redelivery.

### Hosted processing

The `hosted` profile consumes the durable PostgreSQL outbox directly. Workers claim due entries with row locks and a 90-second lease. Failed analysis receives up to three retries. Exhausted events retain their payload and failed state for inspection.

Use one queue mode per database. Delivery is at least once. A crash between a provider call and the result commit can repeat the provider call. Each new HTTP submission receives a new UUID.

## CI/CD

GitHub Actions runs on pushes to `main`, pull requests, and manual dispatch. The pipeline installs Java 21, checks browser JavaScript syntax, runs `mvn verify` with real PostgreSQL and Kafka Testcontainers, builds the static preview, and uploads the executable JAR and test reports.

The Render Blueprint sets `autoDeployTrigger: checksPass`. With the repository connected to Render through GitHub, a passing CI run allows Render to build the Docker image, apply Flyway migrations, and check application readiness. Failed checks stop automatic deployment. This configuration takes effect when the Blueprint is synchronized; a repository commit alone does not create a Render service.

## Kafka and hosted-mode tradeoffs

| Concern | Kafka mode | Hosted PostgreSQL mode |
| --- | --- | --- |
| Queue transport | Three-partition Kafka topic and consumer group | Database outbox with leased rows |
| Scaling | Consumers can distribute work across partitions | Workers compete for due rows using row locks |
| Failed work | Dead-letter topic plus persisted failed event | Persisted failed event after retry exhaustion |
| Infrastructure | App, PostgreSQL, and Kafka | App and PostgreSQL |
| Best fit | Demonstrating distributed messaging and broker recovery | A compact portfolio deployment on a free web host |

Both modes save intake durably and process asynchronously. Hosted mode trades Kafka's partitioned log and broker-based distribution for fewer services, while queue polling and analysis consume database connections. Kafka remains fully implemented and exercised in CI. The hosted demo uses PostgreSQL queuing. Both provide at-least-once processing, so a crash can repeat an AI request.

## Technical stack

| Component | Implementation |
| --- | --- |
| Runtime | Java 21 |
| Application | Spring Boot 3.5.11 |
| Persistence | Spring Data JPA, PostgreSQL, JSONB |
| Migrations | Flyway |
| Messaging | Spring Kafka; Apache Kafka 3.9.1 in Compose |
| AI transport | Java HTTP client with typed response validation |
| API security | Spring Security and `X-API-KEY` authentication |
| Rate limiting | Resilience4j, 50 requests/second per peer IP per instance |
| API documentation | Springdoc OpenAPI and Swagger UI |
| Observability | Actuator health, readiness, liveness, and metrics |
| Frontend | Responsive HTML, CSS, JavaScript, and Canvas |
| Testing | JUnit, Mockito, RestAssured, Awaitility, Testcontainers |
| Build | Maven and a multi-stage Dockerfile |

## Run locally

### Docker Compose

Create `.env` from `.env.example` and supply `GROQ_API_KEY`.

```sh
docker compose up --build -d --wait
```

Open `http://localhost:8080`. Compose starts the app, PostgreSQL, and Kafka with persistent database and broker volumes. Set `PARSER_MODE=local` for an offline run.

```sh
docker compose logs -f app
docker compose down
```

### Java

Use JDK 21+ and Maven 3.9+.

```sh
mvn clean package -DskipTests
java -jar target/omnistream-ai.jar
```

Configure PostgreSQL and credentials through the environment. Select `SPRING_PROFILES_ACTIVE=hosted` for PostgreSQL queuing. Kafka-based profiles also require a reachable broker.

### Windows launcher

The separately packaged `Omnistream-Windows.jar` starts the local services and opens the browser after readiness succeeds. It selects an installed Java 21+ runtime and requires PostgreSQL 16+ tools.

Keep the launcher window open and press **Q** to stop. Data and logs live beside the JAR in `Omnistream-data/state`. The launcher reads `GROQ_API_KEY` or a credential encrypted for your Windows account. Without either, it starts the local parser. The source includes launcher packaging and credential-setup scripts under `windows/`.

## Deploy the complete web app

The included `render.yaml` configures a Free Render web service. A Neon PostgreSQL database provides persistent storage.

1. Create a Neon database and collect its hostname, database name, user, and password.
2. In Render, choose **New → Blueprint** and connect this repository.
3. Supply `DB_URL`, `DB_USER`, `DB_PASSWORD`, and `GROQ_API_KEY`. Use a JDBC URL such as `jdbc:postgresql://HOST/DATABASE?sslmode=require`.
4. Deploy. Render generates an administrative `API_KEY` and activates the `hosted` profile.
5. Open the public URL and enter the workspace. Visitors receive a private browser session and can submit within the public demo quotas using the server-side Groq configuration.

Flyway creates the schema at startup. The deployed application serves both the landing page and workspace. The hosted portfolio is publicly accessible, with each visitor’s events scoped to their opaque browser session. Historical unowned records remain administrator-only. `PUBLIC_WORKSPACE=false` restores API-key authentication for events. Metrics always require the administrative service key. Supply `GROQ_API_KEY` once in the server environment; the browser receives no provider credential.

Netlify and Vercel configuration files also support a static sample preview. Setting `WORKSPACE_ORIGIN` during that frontend build links it to a live backend. Full ingestion, provider calls, and persistence require the backend deployment.

Free hosting is subject to provider quotas and idle suspension. The optional Heroku manifest provisions paid resources. Check the selected provider’s current plan before deploying.

## Demo limits and privacy

| Protection | Public demo default |
| --- | --- |
| Visitor submissions | 3 per UTC day |
| Peer IP submissions | 10 per UTC day |
| Global submissions | 20 per UTC day |
| Visitor HTTP requests | 60 per minute |
| Payload | Up to 2,000 characters, no metadata |
| Retention | Visitor events deleted after 24 hours by a five-minute cleanup task |
| AI budget | At most $1.00 of conservative reservations per UTC day, including retries |

Visitor ownership comes from a random 256-bit HttpOnly, SameSite=Strict cookie. The database stores an HMAC of the token. Changing the URL or guessing an event ID does not grant access to another visitor’s records. Old unowned events and administrator submissions are excluded from visitor feeds. Clearing cookies loses access to that session’s events. Administrators retain API-key access for operations.

Submission quotas and AI budget counters are transactional PostgreSQL records shared across instances and restarts. New cookies cannot bypass the peer-IP or global quota. The peer address is the socket address; visitors behind a proxy may share that additional quota.

Before each real provider attempt, the application reserves a conservative upper estimate based on serialized request UTF-8 bytes, a framing allowance, the completion-token bound, and a default price ceiling of $5 per million tokens. Failed calls and retries keep their reservations. Reservations commit independently of processing rollbacks. When the remaining budget is insufficient, the provider request is blocked. The public cloud mode is restricted to the configured Groq GPT-OSS 120B model.

The application enforces this bound under the configured token-price ceiling. Provider-side spend limits add an independent billing control. Changing model prices requires reviewing the ceiling. Other uses of the same provider key are outside this application’s counters. Set `DEMO_DAILY_BUDGET_USD` to lower the cap, including zero to disable cloud calls, and `DEMO_MAX_TOKEN_USD_PER_MILLION` to change the conservative ceiling.

Avoid submitting secrets or personal information. The active provider receives submitted text. Automatic deletion runs while the service is awake and resumes after idle suspension. Hosted mode uses PostgreSQL only; demo cleanup does not remove messages retained in a separately deployed Kafka broker.

## Configuration

| Variable | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `hosted` for PostgreSQL queuing, `dev` for local Kafka, or `prod` for configured Kafka deployments |
| `DB_URL` | PostgreSQL JDBC URL |
| `DB_USER`, `DB_PASSWORD` | Database credentials |
| `API_KEY` | Administrative metrics key and private-mode event authentication |
| `PUBLIC_WORKSPACE` | Defaults to `true` in hosted mode with visitor isolation. Set `false` for API-key-only access |
| `DEMO_DAILY_BUDGET_USD` | Daily application AI reservation budget, default 1.00 |
| `DEMO_MAX_TOKEN_USD_PER_MILLION` | Conservative maximum token price for budget reservations, default 5.00 |
| `PARSER_MODE` | `groq`, `openai`, or `local` |
| `GROQ_API_KEY`, `GROQ_MODEL` | Groq credential and model |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | Optional OpenAI credential and model |
| `KAFKA_BROKERS` | Kafka bootstrap servers |
| `KAFKA_REPLICATION_FACTOR` | Topic replication factor, default 1 |
| `PORT` | HTTP listen port, default 8080 |
| `HTTP_PORT` | Compose host port |
| `WORKSPACE_ORIGIN` | Live workspace origin for a static frontend build |

## API

Event endpoints are available without a key in the hosted public portfolio. Private mode requires `X-API-KEY`.

| Method | Endpoint | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/events/ingest` | Accept an event asynchronously |
| `GET` | `/api/v1/events/{id}` | Read the event and analysis |
| `GET` | `/api/v1/events` | List the latest 100 events and active parser configuration |

Example request body:

```json
{
  "sourceId": "ledger-api",
  "eventType": "invoice.received",
  "rawPayload": "Invoice INV-42 for USD 85.00 is overdue.",
  "metadata": {
    "region": "west"
  }
}
```

A successful intake response contains `eventId`, `status`, and `timestamp`. Its `Location` header points to the event.

Payloads support up to 65,536 characters. Metadata supports up to 32 string key/value pairs. Overview counts and charts cover the latest 100 events. The console refreshes its feed every four seconds and polls an active AI analysis every two seconds in the public demo and every second in private mode.

## Health and diagnostics

| Endpoint | Purpose |
| --- | --- |
| `/swagger-ui.html` | Interactive API reference |
| `/actuator/health/readiness` | Database readiness and Kafka connectivity in Kafka modes |
| `/actuator/health/liveness` | Application liveness |
| `/actuator/metrics` | Authenticated operational metrics |

Rate limits use the socket peer IP. A reverse proxy can cause multiple clients to share that address.

## Verification

```sh
mvn test
mvn verify
mvn verify -DlatencyCheck=true
```

Unit tests cover worker acknowledgments and the provider response contract. Integration tests exercise acceptance, JSONB persistence, broker delivery, duplicate handling, authentication, retry exhaustion, dead-letter recovery, and PostgreSQL-only hosted processing. Testcontainers verification requires Docker.

The optional latency test checks warm loopback intake against a median under 30 ms and a p95 under 50 ms. These are test thresholds for intake.

For an explicitly enabled live Groq contract check:

```sh
mvn -DgroqLiveTest=true -Dtest=GroqLiveContractTest test
```

Supply `GROQ_API_KEY` in the environment. The test sends a synthetic invoice and validates the extracted identifier.
