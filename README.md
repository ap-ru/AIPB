# AIPB — AI Powered Banker Platform

A command center for Commercial Banking whose pages and widgets are defined by a layout document in MongoDB.

The project is being rebuilt from first principles. Before an AI can create a widget, it needs three things:

1. **Where the data comes from**: MongoDB, reached through an MCP server.
2. **What the data means**: collections, fields, units, relationships and how metrics are derived.
3. **How a widget should look**: a shared design system, so generated widgets look consistent on a page.

The foundation for all three is in place:
- a synthetic **source database** (`bank`) with a machine-readable **data dictionary**;
- a read-only MongoDB MCP server, the single gateway to that data, locked to `bank` by MongoDB auth;
- **declarative widget specs**, drawn by a small set of **design-system primitives** and checked by a **validator** against the real data before they can be saved.

The **widget assistant** (Claude) builds widgets from a plain request, with the same specs and the same validator as the workbench. A banker approves every widget before it is added.

| Page | What it does |
|---|---|
| **Command Center** | The whole book, through a period and filters (region, segment, tier, RM, line of business): exposure, deposits, pipeline, monthly revenue and revenue mix. |
| **Client 360** | Built entirely from spec widgets over `bank`, scoped by a client picker (`?clientId=`). |
| **Client 360 (reference)** | The original hand-built view on the app's demo data, kept until the spec-driven page reaches parity. |

---

## Quick start (Docker)

```bash
./start.sh            # or: docker compose up -d --build
```

| Service | URL |
|---|---|
| React UI | http://localhost:3080 |
| Spring Boot API | http://localhost:8080/api/status |
| MongoDB | localhost:27017, authentication on (`aipb` = app state, `bank` = source data) |
| MongoDB MCP server | http://localhost:3001/mcp (read-only, over `bank`) |

On first start:
- the backend seeds the app's own demo book and the default layout into `aipb` (this is what today's Client 360 reads);
- the one-shot `bank-seed` service generates the synthetic `bank` source database.

```bash
docker compose logs bank-seed                       # counts + "all self-checks passed"
BANK_RESET=true docker compose run --rm bank-seed   # regenerate the bank data
AIPB_RESET=true docker compose up -d backend        # wipe & reseed the app DB (aipb)
curl -X POST localhost:8080/api/layouts/default/reset   # restore the default pages
docker compose down -v                              # stop and delete all Mongo data (also needed to apply new passwords)
```

### Local dev without Docker

```bash
docker run -d -p 27017:27017 mongo:7        # no authentication; the app and generator default to this
mongosh mongodb://localhost:27017/bank data/generate.js    # source data
cd backend && mvn spring-boot:run          # :8080
cd frontend && npm install && npm run dev  # :5173 (proxies /api to :8080)
```

---

## The `bank` source database

This database stands in for the bank's system of record. It is **normalized**: each child collection links to `clients` by `clientId`. Metrics such as exposure, YTD revenue or share of wallet are **not stored**. They are derived from the facts, so every number in a widget can be traced back to its source.

| Collection | Grain |
|---|---|
| `clients` | one per client relationship: coverage, ratings, health, KYC, wallet size |
| `products` | reference list of the 24 products the bank sells |
| `holdings` | one per product a client holds: facilities (committed/drawn), deposits (balance), hedges and mandates (notional), treasury services |
| `revenue_monthly` | one per holding per closed month, over 24 months: NII + fees |
| `covenants` | one per covenant on a credit facility, with the latest test, headroom and status |
| `opportunities` | open and recently closed deals |
| `signals` | detected risk / opportunity / service / market signals, linked to holdings, covenants or deals where relevant |
| `contacts`, `activities` | people at the client and logged interactions |
| `_catalog` | **the data dictionary**: one document per collection, plus a `_database` overview with conventions and joins |
| `_meta` | `asOfDate` ("today" for this dataset), generator version and counts |

- **Dictionary.** [data/catalog.json](data/catalog.json) is loaded into `_catalog`. For each collection it gives the grain, primary key, relationships, every field (type, meaning, unit, allowed values), and `derivedMetrics` that say how to compute what Client 360 shows: `utilization = Σ drawnUsd / Σ committedUsd over credit holdings`, `shareOfWallet = trailing-12-month revenue / walletSizeUsd`, and so on.
- **Generator.** [data/generate.js](data/generate.js) is a mongosh script with no dependencies. The output is deterministic (a seeded PRNG, with dates relative to the run day). Hidden per-client drivers keep the story consistent: a stressed client has tighter covenants, higher utilization, more risk signals and a lower health score.
- **Self-checks.** The generator fails the run if a foreign key dangles, if a covenant's status disagrees with its headroom, if drawn exceeds committed, or if probability doesn't match stage. It also fails if the dictionary and the data disagree, field by field.

### MCP access

The official MongoDB MCP server runs as the `mcp` service in the stack. It is the **single gateway to the source data** and is used in two ways:

- **Widgets at render time:** the backend calls `aggregate` / `find` to get a widget's data.
- **The AI assistant while it designs a widget:** it reads `_catalog`, the collection list, inferred schemas and sample documents to understand what data exists.

```
 browser ──/api──► backend ── SourceData ──HTTP──► mcp (:3000, read-only) ──► mongo/bank
 Claude Code ────────────── .mcp.json ──HTTP──► localhost:3001 ┘
```

How the server is configured (`docker-compose.yml`):

| Setting | Why |
|---|---|
| `MDB_MCP_READ_ONLY=true` | Write tools are not registered, and `$out` / `$merge` pipelines are rejected. |
| `MDB_MCP_DISABLED_TOOLS=connect,disconnect,export,…` | The data source is pinned: no switching connections, no file exports, no calls to MongoDB's hosted services. |
| `MDB_MCP_MAX_DOCUMENTS_PER_QUERY=1000`, `MAX_TIME_M_S=10000` | Room for widget result sets, with a hard cap. A capped result says so (see `truncated` below). |
| `MDB_MCP_TRANSPORT=http`, `HTTP_RESPONSE_TYPE=json` | One long-running server shared by the backend and Claude Code. Plain JSON responses. |
| `ports: 127.0.0.1:3001:3000` | Reachable from this machine only. |

**Backend.** [`com.aipb.source`](backend/src/main/java/com/aipb/source/) holds the app side of the connection:

- **`McpClient`:** MCP over HTTP. It opens a session lazily, reopens it if the server expires it, and turns tool errors into `SourceException` (HTTP 503) carrying the server's reason.
- **`SourceData`:** the one gateway the rest of the app uses. Every call is pinned to `aipb.source.database` (`bank`). Results are plain JSON: Extended JSON dates and numbers are flattened by `Ejson`. Each `Result` carries `count`, `total` and `truncated`, so a widget never shows a capped result as complete.
- **`GET /api/source/status`:** reports the connection, tools, collections and dictionary size. The sidebar shows it as `SOURCE · MCP · bank`.

**Claude Code.** [.mcp.json](.mcp.json) points to `http://localhost:3001/mcp`. With the stack running, approve the `mongodb` server, then use `find` / `aggregate` / `collection-schema` with `connectionId: "preconfigured"` and `database: "bank"`.

### Access control

MongoDB authentication is on, and each part of the system has an account that can touch only what it needs (created by [mongo-init/01-users.js](mongo-init/01-users.js) when the volume is first initialized):

| Account | Access | Used by |
|---|---|---|
| `bank_reader` | `read` on `bank` | the MCP server, so widgets at runtime and the assistant exploring data |
| `bank_writer` | `readWrite` on `bank` | the data generator only |
| `aipb_app` | `readWrite` on `aipb` | the backend (layouts and the demo book) |
| `root` | administration | nothing in the stack |

This is the lock on the data source. It does not depend on our code: whatever a client sends the MCP server, MongoDB refuses anything outside `bank`. For example, reading `aipb.layouts` or `admin` fails with "not authorized", and `list-databases` returns only `bank`. `SourceData` also fixes the database on every call, so the app never sends anything else.

Passwords come from `.env` (see [.env.example](.env.example); the defaults there are for local development only). They are applied when the Mongo volume is first created. To change them, edit `.env` and run `docker compose down -v` (this deletes the data, which then regenerates). Mongo's port is bound to localhost.

## The semantic layer

What the numbers *mean* is defined once, in [backend/src/main/resources/semantic/](backend/src/main/resources/semantic/), and every figure is computed from there. Prompts, specs and tests refer to metric ids; they never restate a formula or do date arithmetic.

| File | Holds |
|---|---|
| `dimensions.json` | **Conformed dimensions**: the ways a figure can be broken down or filtered (lob, client, region, dealStage, severity, month / quarter / year, ...), with allowed values and badge tones. A dimension means the same in every model, so figures from different collections line up on the same rows. |
| `models/*.json` | One **model** per source collection: which field each dimension lives in, the time field, named **segments** (open deals, credit holdings, active signals), **measures** (sum / count / avg / min / max / countDistinct of a field, over segments) and listable **record fields**. Client-owned models inherit the client's dimensions (region, industry, tier, ...) through a many-to-one join, so they never multiply rows. |
| `metrics.json` | **Derived metrics** across measures and models: `utilization = exposureUsd / committedUsd`, `shareOfWallet = revenueUsd over the last 12 months / walletSizeUsd`, `revenueYoY`, `winRate`, ... |

A **semantic query** asks in those terms. An `aggregate` names metrics, dimensions, filters and a window; a `records` query names a model's fields:

```json
{ "metrics": ["revenueUsd", "revenueYoY"], "dimensions": ["quarter"],
  "window": { "type": "lastClosed", "unit": "quarter", "n": 8 }, "compare": "priorYear" }
```

- **Windows** ([Window.java](backend/src/main/java/com/aipb/semantic/Window.java)) are relative to the data's today: `ytd`, `trailing` / `lastClosed` n units, `current` unit, `next` n units, `range`, `all`. `on` picks another date (e.g. holdings maturity). `compare` (`priorYear` / `priorPeriod`) adds each metric's prior value as `<metric>Prior`, with periods lined up.
- **Planning and compiling** ([QueryPlanner](backend/src/main/java/com/aipb/semantic/QueryPlanner.java), [PipelineCompiler](backend/src/main/java/com/aipb/semantic/PipelineCompiler.java)): one read-only pipeline per (model, window). Shared segments go into `$match`, others become conditional accumulators. Joins are added only when a path needs one.
- **Executing** ([QueryExecutor](backend/src/main/java/com/aipb/semantic/QueryExecutor.java)), through `SourceData`:
  - rows from different models and windows are merged on their dimension values;
  - every period of a time breakdown appears (a quarter without revenue shows 0);
  - a ratio with nothing to divide by is unknown, not 0.
- **Results** ([ResultSet](backend/src/main/java/com/aipb/semantic/ResultSet.java)) carry each column's type, unit, format, tones and **lineage**: the metric and its version, its definition in plain words, the data-dictionary fields it reads, and the exact window it covered. They also carry every pipeline that ran.
- **Checks.**
  - The model is checked for structure on every start (a broken definition stops the backend).
  - It is checked against `_catalog` once the source is reachable: every field must exist, joins must land on primary keys, and every filtered or enumerated value must be allowed.
  - `GET /api/semantic` reports any mismatch.

```bash
curl localhost:8080/api/semantic                                   # what can be asked
curl -XPOST localhost:8080/api/semantic/query -H 'content-type: application/json' \
  -d '{"entityId": "CL-0001", "query": {"metrics": ["exposureUsd", "utilization"]}}'
```

**Tests:**
- `PipelineCompilerTest` snapshots the compiled pipelines of representative queries in `src/test/resources/semantic/compiled/`, so a change in how a metric is computed shows up as a reviewable diff. Regenerate them after an intended change with `-Daipb.snapshots.update=true`.
- `MetricConformanceLiveTest` (tag `live`) checks every metric against figures computed independently in plain Java. It covers three clients, a client with no covenants and the whole book.

## Widget specs

A widget is a **spec**: what data it shows and one design-system primitive to show it. It is declarative JSON, never code. A spec is saved to the **library** (`aipb.widgets`) and **placed** on a page by id. The same definition can sit on several pages, and the page supplies the selected client.

Normally the data is a **semantic query** (see [The semantic layer](#the-semantic-layer)). Its columns (type, unit, format, label, lineage) come from the semantic model, and the spec only relabels them or adds badge tones:

```json
{ "id": "client-revenue-ytd", "specVersion": 2, "title": "Revenue YTD",
  "description": "Revenue (NII + fees) booked this calendar year for the selected client, compared with the same months last year.",
  "data": { "query": { "metrics": ["revenueUsd"], "window": { "type": "ytd" }, "compare": "priorYear", "scope": "entity" },
            "columns": [{ "name": "revenueUsd", "label": "Revenue YTD" }, { "name": "revenueUsdPrior", "label": "Prior YTD" }] },
  "view": { "primitive": "metric", "value": "revenueUsd", "compare": "revenueUsdPrior", "polarity": "higherIsBetter" },
  "layout": { "span": 3 } }
```

The escape hatch is `data.custom`: a hand-written read-only pipeline over one collection, the columns it declares, and top-level `params` it references as `{"$param": "name"}`. It is for what the semantic layer cannot express yet. Specs without `specVersion` (format 1: `data.collection` / `pipeline` / `columns`) are read as custom data by [SpecMigrator](backend/src/main/java/com/aipb/widget/SpecMigrator.java), so stored widgets, the workbench and the assistant keep working.

- **Scope.**
  - `entity`: the selected client, so the widget can sit only on a page with a client picker.
  - `book`: always the whole book.
  - `inherit` (the default): the page's client when it has one, otherwise the book.
- **Series.** `line` and `bar` take a `series` role. With it, rows are one per period and series (e.g. month × line of business), and the renderer draws one line or bar group per value (at most 4).
- **Contract.** The format is defined by [widget-spec.schema.json](backend/src/main/resources/widgets/widget-spec.schema.json). The design system is described by [primitives.json](backend/src/main/resources/widgets/primitives.json), which lists `metric`, `bar`, `line`, `table` and `list` with their roles, row limits, units, formats and usage guidance. Both are served by `GET /api/widgets/manifest`, which is the same contract the assistant will be given.
- **Parameters** (custom data) are typed and referenced as `{"$param": "name"}`. Each one is replaced by a single JSON value, never spliced into text, so a value can't change the query. Dates become BSON dates; a plain-string date would silently match nothing. `asOfDate` is built in (`_meta.asOfDate`), so a spec never uses the wall clock.
- **Primitives** live in [frontend/src/widgets/spec/](frontend/src/widgets/spec/). They use one validated chart palette (brand green first; checked for color-vision deficiency and 3:1 contrast), status badges that always carry a label, legends for two or more series, direct labels on line ends, exact axis ticks, and a table view for every chart.
- **Runtime.** A placed widget (`{type: "spec", config: {definitionId}}`) fetches `GET /api/widgets/library/{id}/data?clientId=…`. The backend binds the parameters and runs the stored spec through `SourceData`. The browser never sends a query.

### Page context

A page sets the lens its widgets share, and every widget card states the lens it was computed in (e.g. "Whole book · Calendar year to date (Jan 1 - Sep 30, 2026) · Region: EMEA").

- **Client.** The page's `params` are its entity: Client 360's client picker.
- **Period.** `context.period` offers named windows (Command Center: last 12 months by default, year to date, last 4 quarters, last 24 months). A query follows it only if it says `"period": "page"`; its own `window` is the default elsewhere.
- **Filters.** `context.filters` are one slot per conformed dimension (Command Center: region, segment, tier, RM, line of business). They apply to every query whose figures can be filtered that way, unless the query says `"pageFilters": false`. Where a filter cannot apply, the card says "Not filtered by ...", so it is never dropped silently.
- **Placements.** A placement can pin its own context in `config.bindings` (e.g. `{"period": "t24m", "filters": {"region": ""}}`) or a fixed client in `config.params`.

Context values live in the page URL (`?clientId=...&period=ytd&region=EMEA`), so a view can be shared. A placed widget is fetched by its placement (`GET /api/widgets/placed/{layout}/{page}/{widget}/data` plus the URL's values), and [ContextResolver](backend/src/main/java/com/aipb/context/ContextResolver.java) resolves page, URL and placement on the server.

Because several starters follow the page (scope `inherit`, period `page`), the same definitions show the selected client on Client 360 and the whole book on Command Center. Saved layouts get Command Center's context and those widgets through an idempotent upgrade at startup ([LayoutUpgrades](backend/src/main/java/com/aipb/service/LayoutUpgrades.java)), so pages and widgets bankers added are kept.

### The validator (`WidgetSpecValidator`)

Nothing reaches the library without passing it. Its messages are written so a person, or later the assistant, can act on them directly.

- **Static checks:**
  - the spec matches the JSON Schema;
  - the collection exists and is a data collection;
  - every `$param` is declared, and every declared parameter is used;
  - no write stages, server-side JavaScript or `$$NOW`;
  - each unit agrees with its format (`USD` → `currency`, `ratio` → `percent`, `percent` → `percentPoints`);
  - the view's roles fit the primitive and the declared columns;
  - the target page can supply every parameter.
- **Live checks**, run with sample parameters (by default, the page's first choice):
  - the query succeeds;
  - the result is non-empty, with a hint about the usual causes if it isn't;
  - the result is not truncated;
  - the declared columns exist and their values match the declared types;
  - the row count suits the primitive;
  - unit sanity: values in the 0–100 range formatted as a 0–1 ratio are flagged.
- **Result:** errors and warnings, each with a code and the part of the spec it concerns, plus a preview of up to 50 rows.

### Authoring and starter specs

- **Workbench.** **+ Widget → Write a spec** gives you a JSON editor, *Validate* (errors, warnings and a live preview drawn by the real primitives) and *Save to library & place*. It is the manual version of the assistant's loop. **Library** places an existing definition; **Built-in** offers the legacy types.
- **Starter specs.** [widgets/starter/](backend/src/main/resources/widgets/starter/) holds 12 semantic-query specs that rebuild Client 360 from `bank`. `StarterParityLiveTest` checks that they return the same figures, for every client, as the hand-written pipelines they replaced (kept in `src/test/resources/legacy-starters/`). The assistant's worked examples are frozen copies of those pipelines in `resources/assistant/examples/` until the assistant moves onto the semantic layer. Starters are loaded at startup through the same validator; a file with a higher `version` replaces the stored copy.
- **Golden set.** `GoldenSpecsLiveTest` runs every starter spec through the real MCP server and compares it, across three clients and the whole book, with figures computed independently in plain Java over raw documents. The natural-language requests in [golden/cases.json](backend/src/test/resources/golden/cases.json) will become the assistant's evaluation set. Run it with the stack up:

```bash
cd backend && mvn test                                            # unit tests
cd backend && mvn test -Dgroups=live -Dsurefire.excludedGroups=   # golden set, against the running stack
```

## The widget assistant

**✦ Assistant** on any page opens a docked conversation. Describe a widget ("deposits by product for this client"), and the assistant finds the data, checks it, designs the display and shows a **proposal**: a live preview, what each figure means and where its definition comes from, every assumption it made, and the checks it passed. Nothing is added until you click **Add to page**. Follow-up messages revise it ("make it monthly", "call it Deposits mix").

### How it works: narrow stages, each checked by code

```
message ─► INTAKE ─proceed─► BUILD ⇄ gate ─► PRESENT ⇄ gate ─► PROPOSAL ─► banker approves ─► library + page
              └─ clarify / answer / reuse / decline                   └─ "Verify numbers" (opt-in) ─► REVIEW
```

Code, not the model, runs the sequence ([AssistantOrchestrator](backend/src/main/java/com/aipb/assistant/AssistantOrchestrator.java)). Each stage is a separate Claude conversation with one objective, a defined process and a deterministic gate. Stages pass each other **artifacts** (a brief, a data definition, a design), never transcripts.

| Stage | Objective | Ends with | Gate (code) |
|---|---|---|---|
| **Intake** | Turn the request into one precise brief, or clarify / answer / reuse an existing widget / decline | structured output ([schema](backend/src/main/resources/assistant/schemas/intake.json)) | reuse names a real widget; a brief has a measure and a scope the page supports |
| **Build** | A read-only pipeline returning exactly the brief's figures; every figure cited from the data dictionary; assumptions confirmed with tools; tested before submitting | `submit_data` | the full widget validator runs live with the page's sample client; every `catalogReference` must exist in `_catalog`; every number has a definition |
| **Present** | The clearest primitive, roles, title, width and badges | structured output ([schema](backend/src/main/resources/assistant/schemas/present.json)) | the assembled spec passes the validator for this page |
| **Review** (opt-in only) | Independently verify the numbers, recomputing a key figure a different way | `submit_review` | a pass requires a matching independent recomputation |
| **Proposal** | What the banker sees | assembled by code from the artifacts (no LLM), so it can't misdescribe what was built | |

**The review never runs by default.** It costs extra tokens, so it runs only when the banker clicks **Verify numbers** or asks for a check. If a requested review can't confirm the numbers, the proposal is marked **Not verified** and adding it needs an explicit confirmation.

### What each stage sees

Static context goes in the system prompt. It is generated deterministically from `_catalog`, the design catalog and the starter specs, so it is byte-identical across calls and served from the prompt cache. Dynamic context goes in the user message.

| Block | Intake | Build | Present | Review |
|---|---|---|---|---|
| [Primer](backend/src/main/resources/assistant/prompts/primer.md) + the stage's [rules](backend/src/main/resources/assistant/prompts/) | ✓ | ✓ | ✓ | ✓ |
| Data overview (conventions, joins) | ✓ | ✓ | | ✓ |
| Data index (fields per collection, derived-metric names) | ✓ | | | |
| Full data dictionary (every field, allowed values, units, derived-metric definitions) | | ✓ | | ✓ |
| Worked examples from starter specs | | data | display | |
| Design catalog (primitives, roles, row limits, guidance) | menu | | ✓ | |
| Page (scope, selected client, existing widgets), the data's today | ✓ | ✓ | ✓ | ✓ |
| Widget library | ✓ | | | |
| Brief / data / sample rows / change request | revisions | ✓ | ✓ | ✓ |

Every stage run records a **manifest** of what the model was given (block, source, size, hash), plus the dynamic message verbatim, its tokens, cost and tool calls. You can see these under **Details** in the drawer or at `GET /api/assistant/sessions/{id}/runs`. `ContextAssemblyTest` writes each stage's full request to `backend/target/assistant-context/<stage>.md` for review.

### Tools

Build and Review use read-only tools. All of them go through `SourceData`, so the database stays pinned to `bank`, results are capped, and outputs are trimmed to a few rows:
- `sample_documents`: look at real documents;
- `distinct_values`: exact values before filtering;
- `run_query`: test a pipeline with the page's sample client;
- the stage's `submit_*` tool.

### Model, cost, limits

- **Model:** `claude-opus-5-5` through the official Java SDK ([AnthropicGateway](backend/src/main/java/com/aipb/assistant/llm/AnthropicGateway.java)).
- **Request settings:** adaptive thinking; effort per stage (`AIPB_EFFORT_*`, default medium); prompt caching; server-side refusal fallbacks (`fallbacks: "default"`).
- **Expected cost:** about **$0.35–0.45 per widget** at medium effort. The session's running cost is shown in the drawer.
- **Caps** (`aipb.assistant.*`): 16 tool calls and 4 submissions per stage, 3 design attempts, 6 minutes per run. When a cap is hit, the assistant says what it couldn't finish.
- **Requirement:** `ANTHROPIC_API_KEY` must be set for the backend. Without it the assistant is disabled and the rest of the app works.

### Evaluation

`AssistantEvalLiveTest` sends the 12 golden requests to the real assistant and scores each proposed widget's numbers against the verified starter widget for the same client ([EvalScorer](backend/src/test/java/com/aipb/assistant/eval/EvalScorer.java)). It writes `backend/target/assistant-eval/report.md` with the pass rate, cost and time per case. **It calls Claude and costs money (about $0.40 per case)**, so it never runs in the normal build:

```bash
cd backend && mvn test -Dgroups=eval -Dsurefire.excludedGroups= -Daipb.eval.limit=3   # first 3 cases; drop the limit for all 12
```

---

## App architecture

```
 React 18 + Vite (nginx :3080)     renders nav + pages + widgets FROM the layout doc
        │ /api/*
 Spring Boot 3 / Java 21 (:8080)   PlatformController  layouts, widgets, status
                                   BookController      clients, Client 360 bundle, book KPIs
                                   SourceController    source-data connection status
                                   WidgetController    widget library, validation, widget data
                                   AssistantController widget assistant (stages → Claude via the Java SDK)
        │                     │ WidgetDataService / WidgetSpecValidator → SourceData → McpClient (HTTP)
        │               MongoDB MCP server (:3000 in the stack, :3001 on localhost; read-only)
        │                     │
 MongoDB 7                         aipb: layouts, widgets (spec library), clients, opportunities, signals (demo book)
                                   bank: synthetic source data + _catalog (read only via MCP, as bank_reader)
```

`layouts/default` holds the pages, their parameters (e.g. Client 360's `clientId` picker, whose choices come from `bank.clients`) and their widgets. The React shell renders each widget's `type` from [frontend/src/widgets/registry.jsx](frontend/src/widgets/registry.jsx). `spec` renders a library definition. The legacy types `kpi-strip`, `client-360` and `client-table` are also declared in [WidgetManifest.java](backend/src/main/java/com/aipb/service/WidgetManifest.java) and serve only the reference page.


## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | Health + layout version |
| GET | `/api/source/status` | Source-data connection: MCP server, tools, collections, dictionary entries, asOfDate |
| GET | `/api/kpis?lob=&region=&marketId=` | Book KPI tiles |
| GET | `/api/clients?sort=revenue\|exposure\|health\|walletGap\|pipeline&q=` | Client book |
| GET | `/api/clients/{id}` | Client 360 bundle |
| GET/PUT | `/api/layouts/{id}` · POST `/reset` | Layout |
| POST/DELETE | `/api/layouts/{id}/pages[/{pageId}]` | Pages |
| POST/PATCH/DELETE | `/api/layouts/{id}/pages/{pageId}/widgets[/{widgetId}]` · POST `.../move?delta=` | Widgets |
| GET | `/api/semantic` | The semantic layer: dimensions, models (measures, segments, record fields), derived metrics, windows; mismatches with `_catalog` |
| POST | `/api/semantic/query` `{query, entityId}` | Run a semantic query: rows, typed columns with lineage, the pipelines that ran (422 with issues if it can't be answered) |
| GET | `/api/widgets/manifest` | Widget types, the spec JSON Schema and the design-system primitives |
| GET | `/api/widgets/library` · `/library/{id}` | Widget definitions (summary · with spec) |
| POST/PUT | `/api/widgets/library[/{id}]` `{spec, pageId?, params?}` | Save a spec: validated first; 422 with the report if invalid, 409 on a duplicate id; PUT bumps the version |
| GET | `/api/widgets/library/{id}/data?<params>` | Run a stored definition: rows, view, count/total/truncated |
| POST | `/api/widgets/validate` `{spec, pageId?, params?}` | Validation report with a live preview (nothing saved) |
| GET | `/api/layouts/{id}/pages/{pageId}/params/{name}/options` | Choices for a page parameter |
| GET | `/api/layouts/{id}/pages/{pageId}/context/filters/{dimension}/options` | Choices for one of a page's filters |
| GET | `/api/widgets/placed/{layoutId}/{pageId}/{widgetId}/data?<page values>` | Run a placed widget in its page's context (client, period, filters, placement bindings): rows, view, applied context, notes |
| GET | `/api/assistant/status` | Assistant enabled, model, effort per stage |
| POST | `/api/assistant/sessions` `{layoutId, pageId, params}` | Start a conversation on a page |
| GET | `/api/assistant/sessions/{id}` | Conversation state (turns, status, proposal, cost) |
| POST | `/api/assistant/sessions/{id}/messages` `{text, params}` | Send a message → **SSE**: `stage`, `activity`, `message`, `proposal`, `error`, `done` |
| POST | `/api/assistant/sessions/{id}/verify` | Opt-in independent review → SSE |
| POST | `/api/assistant/sessions/{id}/approve` `{confirmUnverified}` | Save the proposal to the library and place it |
| GET | `/api/assistant/sessions/{id}/runs` | Inspector: each stage run's context manifest, tokens, cost, tool calls |
| POST | `/api/admin/reseed` | Wipe and reseed the app DB |

## Next steps

- Run the evaluation, then tune effort per stage against it.
- Edit placed widgets through the assistant (today it creates new ones and revises its own proposals).
- Retire the reference page and the legacy Client 360 code once the spec-driven page covers everything.

All names and figures are **synthetic**. Security (SSO, entitlements) is out of scope for this local build.
