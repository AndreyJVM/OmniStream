# Omnistream AI

A Java 21 event ingestion service with a browser console. Submit a webhook or text document, follow its processing state, and inspect its structured analysis. PostgreSQL stores the event and a durable publication request before the API returns. Workers analyze events asynchronously. The landing page is at `/`, and **Enter workspace** opens `/workspace`.

## Full portfolio deployment

Use the Render Free + Neon Free setup below to run every live feature, including Groq extraction and event persistence. The **AI workspace** tab displays actual ingestion and analysis status, elapsed wait time, risk and category tags, extracted entities, and structured JSON. Open **Help → Try example** to analyze a prefilled invoice. The static Netlify/Vercel preview is optional.

## Deploy for $0 from GitHub

Extract the source ZIP, then upload the **contents of the `omnistream-ai` directory** to your repository root. Include dotfiles. The root should contain `netlify.toml`, `vercel.json`, `render.yaml`, and `pom.xml`. Keep desktop binaries, `Omnistream-data`, local environment files, and credentials on your computer.

### Free public preview on Netlify or Vercel

1. On **Netlify Free**, import an existing project from GitHub. On **Vercel Hobby**, import the repository as a personal, non-commercial project.
2. Use the repository root. The included configuration runs `node deploy/build-web.mjs` and publishes `dist`. Use the **Other** framework preset if Vercel asks.
3. Deploy and open the generated HTTPS URL. **Enter workspace** opens a read-only sample workspace with filters, event details, and Help. This version uses no database or AI key.

Netlify's credit-based Free plan has a hard monthly limit. Vercel Hobby is for personal, non-commercial use. [Netlify Free](https://docs.netlify.com/manage/accounts-and-billing/billing/billing-for-credit-based-plans/credit-based-pricing-plans/) · [Vercel Hobby](https://vercel.com/docs/plans/hobby)

### Free live app with Groq and saved events

For the full application, the fewest hosting services are **Render Free** for the entire Java app and **Neon Free** for PostgreSQL. The `hosted` profile uses PostgreSQL for durable queuing. The web host builds the Dockerfile from GitHub; your computer can be off.

1. Create a **Neon Free** database. In **Connect**, select the database and role and collect its hostname, database name, user, and password. A direct JDBC URL looks like `jdbc:postgresql://YOUR-NEON-HOST/YOUR-DATABASE?sslmode=require`.
2. In Render, choose **New → Blueprint**, connect the GitHub repo, and review `render.yaml`. It declares one **Free** web service. Set `DB_URL` to the JDBC URL above, `DB_USER` and `DB_PASSWORD` to the Neon role credentials, and `GROQ_API_KEY` to a fresh server-side key from a Groq Free account. Render generates a separate service `API_KEY`.
3. Deploy. Open the Render HTTPS URL, select **Enter workspace → Sample workspace → Connect API**, and enter the generated **service** `API_KEY` from the Render service environment. Create an event with **Help → Try example**.
4. If you also want a Netlify or Vercel landing-page URL, set **only** `WORKSPACE_ORIGIN` on that frontend to your Render HTTPS origin (e.g. `https://your-service.onrender.com`) and redeploy it. The landing page and old workspace links then open the live server. The frontend build never needs the Groq or database credentials.

For zero-cost use, stay on all three free plans, keep paid upgrades disabled, and check each account's limits. On Render, a workspace without a payment method suspends services or builds at the relevant usage caps. Check billing controls before using a workspace that has a payment method. Render Free sleeps after 15 minutes of inactivity. The next visit can take about a minute to wake it; queued work resumes when the service is running. The free limits suit intermittent demos. Render can restrict high outbound traffic and Neon can exhaust its monthly compute allowance if the worker stays busy. [Render free limits and billing behavior](https://render.com/docs/free) · [Neon Free](https://neon.com/pricing) · [Groq rate limits](https://console.groq.com/docs/rate-limits)

The hosted worker leases queued entries, retries a failed analysis up to three times, and records exhausted events as `FAILED`. An unfinished lease is reclaimable after 90 seconds. Use one queue mode per database. Windows and Compose use Kafka. The optional Heroku manifest requests paid services.

## Start on Windows

Double-click **Omnistream-Windows.jar**. This Windows edition includes the service and Apache Kafka 3.9.1. Its Java 8-compatible launcher selects an installed Java 21+ runtime, initializes its own PostgreSQL cluster using installed PostgreSQL 16+ tools, and opens the console after readiness succeeds. The launcher reads `GROQ_API_KEY` or the Groq credential encrypted for your Windows account and starts without a key prompt. If neither is available, it starts in local rules mode. To save or rotate a key for this account, run `.\windows\Save-GroqKey.ps1` from the source project. The local console connects automatically. Choose **Help → Try example** for a prefilled invoice. Keep the launcher window open and press **Q** to stop. You can also run `java -jar Omnistream-Windows.jar --stop`.

Place the Windows JAR in a writable folder on a local disk. Local data and logs are stored beside the JAR in `Omnistream-data\state`. Set `OMNISTREAM_HOME` to change that directory, or `OMNISTREAM_PG_BIN` for a custom PostgreSQL installation. All three services bind to loopback. The ordinary `omnistream-ai.jar` is the server distribution and requires Java 21+, PostgreSQL, and the environment settings below. The Kafka-based profiles also require a broker.

To reproduce the Windows package, build the service with Maven, obtain the Apache Kafka binary distribution `kafka_2.13-3.9.1.tgz`, set `JAVA_HOME` to JDK 21+, and run `.\windows\package.ps1 -ServiceJar .\target\omnistream-ai.jar -KafkaArchive .\kafka_2.13-3.9.1.tgz -OutputJar .\Omnistream-Windows.jar`.

## Start with Docker Compose

Install Docker with Docker Compose v2. Copy `.env.example` to `.env` in the project directory and set `GROQ_API_KEY` there, or supply it through your deployment environment. Keep the key on the server. From that directory, run:

```sh
docker compose up --build -d --wait
```

Open **http://localhost:8080** and select **Enter workspace**. The loopback console connects automatically with the default development service key. On hosted or custom-key deployments, choose **Sample workspace → Connect API** and enter the deployment’s service key. Choose **Ingest event** to paste a webhook or load a plain-text file. The console refreshes every four seconds. Select a row to see the payload, status, extracted entities, and analysis.

The default Compose deployment binds all published ports to loopback and uses Groq for analysis. The image build downloads Maven dependencies on the first run. PostgreSQL and Kafka data survive `docker compose down` in named volumes.

### Send a first event

Open the [interactive API reference](http://localhost:8080/swagger-ui.html), authorize with the same API key, and submit this body to `POST /api/v1/events/ingest`:

```json
{
  "sourceId": "ledger-api",
  "eventType": "invoice.received",
  "rawPayload": "Invoice: INV-2048 amount: 480.00. Payment is overdue.",
  "metadata": {"region": "west"}
}
```

The response is `202 Accepted`. Its `Location` header points to `GET /api/v1/events/{eventId}`. The receipt says `PENDING` while the database starts at `RECEIVED`. Read the event again to see `PROCESSING`, then `COMPLETED` or `FAILED`. Send `X-API-KEY` on all event requests. A text payload can contain up to 65,536 characters. Metadata supports up to 32 string key/value pairs.

### Groq Cloud analysis

Groq receives the event envelope, including raw text and metadata. The default model is `openai/gpt-oss-120b`. The Java worker sends `temperature=1`, `top_p=1`, `reasoning_effort=medium`, and `max_completion_tokens=2048`. It requests strict JSON Schema output with `stream=false`, validates the completed analysis, and saves it to PostgreSQL. Groq supports strict structured output on this model with non-streaming completions. [Groq structured outputs](https://console.groq.com/docs/structured-outputs)

Set `PARSER_MODE=local` for an offline demo. Existing OpenAI deployments can select `PARSER_MODE=openai` with `OPENAI_API_KEY` and `OPENAI_MODEL`. Restart the app after changing provider settings.

## Deployment

| Setting | Purpose |
| --- | --- |
| `SPRING_PROFILES_ACTIVE` | `dev` for the bundled Compose stack. Use `hosted` for PostgreSQL queue mode and `prod` for a managed Kafka deployment. |
| `API_KEY` | Shared authentication secret for the API, console connection, and metrics. |
| `DB_URL`, `DB_USER`, `DB_PASSWORD` | PostgreSQL JDBC connection and credentials. Default database and user are `omnistream`. |
| `KAFKA_BROKERS` | Comma-separated bootstrap servers reachable from the app. |
| `KAFKA_REPLICATION_FACTOR` | Topic replication factor, default 1. Choose a value supported by the broker cluster. |
| `PARSER_MODE` | `groq` (default), `local`, or `openai`. |
| `GROQ_API_KEY`, `GROQ_MODEL` | Server-side Groq credential and model. The default model is `openai/gpt-oss-120b`. |
| `OPENAI_API_KEY`, `OPENAI_MODEL` | Provider credentials and model in OpenAI mode. |
| `PORT` / `HTTP_PORT` | Application listen port / local Compose host port. Both default to 8080. |

For a container host, build the app with `docker build -t omnistream-ai:1.0.0 .`. Deploy the image with the production profile and the settings above to a network that can reach PostgreSQL and Kafka. The database user needs migration privileges. The Kafka principal needs access to create the two configured topics, publish, and join the consumer group. Spring Boot supports broker TLS/SASL through its standard `spring.kafka` configuration. Terminate HTTPS at the ingress and use deployment secrets for shared credentials. Configure managed database backups, broker replication and retention for your environment. The bundled Compose topology is single-node.

The executable JAR can also run with `java -jar omnistream-ai.jar` on Java 21+, using the same environment variables. Flyway applies migrations at startup. Readiness checks both the Kafka cluster and PostgreSQL at `/actuator/health/readiness`. Public liveness is available at `/actuator/health/liveness`. Metrics at `/actuator/metrics` require the API key.

## Kafka processing and recovery

1. The REST adapter validates input. The ingestion service calls the event storage port, whose PostgreSQL adapter commits the event and an outbox row in one transaction.
2. The relay selects up to 64 pending rows with `FOR UPDATE SKIP LOCKED`, publishes keyed JSON to `raw-ingestion-events`, and deletes the outbox rows after broker confirmation. A broker outage leaves pending rows available for retry.
3. Three consumer threads share `omnistream-processing-group`. The worker commits `PROCESSING`, then locks the event during analysis. It commits the analysis and `COMPLETED` before acknowledging the offset. A replay of a terminal event skips analysis.
4. After an initial failure and three exponentially delayed retries, recovery publishes the original record to `raw-ingestion-events.DLQ`, commits `FAILED`, and commits the recovered offset. A recovery failure leaves the source offset available for retry.

Delivery is at least once. A crash between Kafka confirmation and the database commit can repeat an event or a DLQ entry. The event UUID identifies duplicates. A crash after a provider call but before the result commits can repeat that call. Each new HTTP submission receives a new UUID.

The API limits requests to 50 per second per socket peer IP, per application instance. Behind a reverse proxy, this address may be the proxy. Configure ingress-level client quotas when clients share a proxy. Dashboard counts and charts cover the latest 100 events. Raw payloads are loaded on demand when inspecting an event.

## Build and test

Use JDK 21 and Maven 3.9+. The Maven build is also available through the Dockerfile.

```sh
mvn test
mvn verify
mvn verify -DlatencyCheck=true
```

`test` runs worker and Groq adapter contract tests, including a local HTTP fixture for the provider. `verify` adds Testcontainers with PostgreSQL 16 and Confluent Kafka 7.5.0 and requires a working Docker engine. Tests exercise durable acceptance with the worker paused, real broker delivery, JSONB storage, duplicate handling, invalid input, API-key authentication, and dead-letter recovery. The optional latency check times 20 warm loopback requests and checks a median under 30 ms and a p95 under 50 ms. Measure your deployment under representative load to establish its latency budget.

With `GROQ_API_KEY` in the environment, `mvn -DgroqLiveTest=true -Dtest=GroqLiveContractTest test` sends one synthetic invoice to Groq and validates the extracted invoice identifier.

To inspect the running Compose stack, use `docker compose ps` and `docker compose logs -f app`. Stop it with `docker compose down`.
