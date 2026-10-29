# AIPB — AI Powered Banker Platform

A command center for Commercial Banking whose pages and widgets are defined by a layout document in MongoDB, over source data held as a **Neo4j property graph** (this is the `knowledge-graph` branch; `main` keeps the source data in MongoDB).

The project is being rebuilt from first principles. Before an AI can create a widget, it needs three things:

1. **Where the data comes from**: the bank graph in Neo4j, read directly by the pages and explored by the assistant through a Cypher MCP server.
2. **What the data means**: labels, properties, units, relationships and how metrics are derived.
3. **How a widget should look**: a shared design system, so generated widgets look consistent on a page.

The foundation for all three is in place:
- a synthetic **source graph** with a machine-readable **data dictionary**;
- read-only access to that data: the Neo4j driver for what pages show, the Neo4j Cypher MCP server for the assistant, and a database opened read-only;
- **declarative widget specs**, drawn by a small set of **design-system primitives** and checked by a **validator** against the real data before they can be saved.

The **widget assistant** (Claude) builds widgets from a plain request, with the same specs and the same validator as the workbench. A banker approves every widget before it is added.

| Page | What it does |
|---|---|
| **Command Center** | The whole book, through a period and filters (region, segment, tier, RM, line of business): exposure, deposits, pipeline, monthly revenue and revenue mix. |
| **Client 360** | Built entirely from spec widgets over the graph, scoped by a client picker (`?clientId=`). |
| **Pre-call Brief** | A static brief for one client (profile, ownership, holdings, covenants, meetings, balances, payments, industry context), read from the graph including the pre-call labels. |
| **New Issue (High Yield)** | Deal search and transaction details over the seeded `Hy*` labels. |
| **Signals** | Placeholder. |

---

## Quick start (Docker)

```bash
./start.sh            # or: docker compose up -d --build
```

| Service | URL |
|---|---|
| React UI | http://localhost:3080 |
| Spring Boot API | http://localhost:8080/api/status |
| Neo4j (the bank graph) | browser http://localhost:7474, bolt localhost:7687 (user `neo4j`, `NEO4J_PASSWORD` from `.env`); opened read-only |
| Cypher MCP server | http://localhost:3001/mcp/ (read-only, over the bank graph) |
| MongoDB | localhost:27017, authentication on (`aipb` = app state only) |

On first start:
- the one-shot `graph-seed` service generates the synthetic bank data and loads it into Neo4j (then `neo4j` starts, read-only);
- the backend seeds the app's own demo book and the default layout into `aipb`.

```bash
docker compose logs graph-seed                      # counts + "all self-checks passed" + "all read-back checks passed"
docker compose stop neo4j && BANK_RESET=true docker compose up -d   # regenerate and reload the bank graph
AIPB_RESET=true docker compose up -d backend        # wipe & reseed the app DB (aipb)
curl -X POST localhost:8080/api/layouts/default/reset   # restore the default pages
docker compose down -v                              # stop and delete all data (also needed to apply new passwords)
```

`BANK_AS_OF` (in `.env`, `YYYY-MM-DD`) pins the data's "today", so the data is identical on any day; it is set to `2026-10-27`, the date the parity fixtures were captured at. Leave it empty to use the day of seeding.

### Local dev without Docker

```bash
docker run -d -p 7687:7687 -p 7474:7474 -e NEO4J_AUTH=neo4j/local-dev neo4j:5.26.32-community
cd data && npm ci && BANK_AS_OF=2026-10-27 node build-bank.js && NEO4J_PASSWORD=local-dev node load-graph.js
docker run -d -p 27017:27017 mongo:7        # app state; no authentication
cd backend && AIPB_GRAPH_PASSWORD=local-dev mvn spring-boot:run   # :8080
cd frontend && npm install && npm run dev  # :5173 (proxies /api to :8080)
```

---

## The bank source graph

The graph stands in for the bank's system of record. Every node belonging to a client is connected to its `(:Client)`, and natural keys (`clientId`, `holdingId`, ...) stay on the nodes too, for display and filtering. Metrics such as exposure, YTD revenue or share of wallet are **not stored**. They are derived from the facts, so every number in a widget can be traced back to its source.

| Label | Grain | Relationships |
|---|---|---|
| `Client` | one per client relationship: coverage, ratings, health, KYC, wallet size | |
| `Product` | reference list of the 24 products the bank sells | |
| `Holding` | one per product a client holds: facilities (committed/drawn), deposits (balance), hedges and mandates (notional), treasury services | `(:Client)-[:HOLDS]->(:Holding)-[:OF_PRODUCT]->(:Product)` |
| `RevenueMonth` | one per holding per closed month, over 24 months: NII + fees | `(:Holding)-[:EARNED]->(:RevenueMonth)-[:FOR_CLIENT]->(:Client)` |
| `Covenant` | one per covenant on a credit facility, with the latest test, headroom and status | `(:Holding)-[:HAS_COVENANT]->(:Covenant)` |
| `Opportunity` | open and recently closed deals | `(:Client)-[:HAS_OPPORTUNITY]->(:Opportunity)-[:FOR_PRODUCT]->(:Product)` |
| `Signal` | detected risk / opportunity / service / market signals | `(:Signal)-[:ABOUT]->(:Client)`, `(:Signal)-[:CONCERNS]->(:Holding\|:Covenant\|:Opportunity)` |
| `Contact`, `Activity` | people at the client and logged interactions | `(:Contact)-[:WORKS_AT]->(:Client)`, `(:Activity)-[:WITH_CONTACT]->(:Contact)` |
| pre-call labels | `CompanyProfile`, `CitizensProduct`, `FinancialKpi`, `Meeting`, `BalanceSnapshot`, `PaymentFlow`, `OwnershipStake`, `IndustryContext`, ... | `-[:FOR_COMPANY]->(:CompanyProfile)-[:PROFILE_OF]->(:Client)`, `-[:FOR_CLIENT]->(:Client)` |
| high-yield labels | `HyIssuer`, `HyDeal`, `HyTranche`, `HyCapitalLayer`, `HyCovenant`, `HyAllocation`, `HyFeeEconomics` | `(:HyTranche)-[:IN_DEAL]->(:HyDeal)-[:ISSUED_BY]->(:HyIssuer)-[:IS_CLIENT]->(:Client)` |
| `_Catalog` | **the data dictionary**: one node per label (`{id, doc}`), plus the `_database` overview with conventions, labels and path patterns | |
| `_Meta` | `asOfDate` ("today" for this dataset), generator version and counts | |

- **Dictionary.** [data/catalog.json](data/catalog.json) (plus `catalog-hy.json`, `catalog-precall-plus.json`) has one entry per label: grain, key, `relationships` (type, direction, the label at the other end, the property it is built from, cardinality, and its path pattern), every property (type, meaning, unit, allowed values; `storage: json` for nested records, which Neo4j properties cannot hold), and `derivedMetrics` such as `utilization = Σ drawnUsd / Σ committedUsd over credit holdings`.
- **Generator.** The generators ([data/generate.js](data/generate.js), `generate-hy.js`, `generate-precall-plus.js`) are mongosh scripts with a seeded PRNG; [data/build-bank.js](data/build-bank.js) runs them under plain Node against an in-memory stand-in for `db` ([memdb.js](data/memdb.js)), with their self-checks, and writes `bank.json`. Pinned to the same date, its core collections are identical, document for document, to what the MongoDB seed produced.
- **Loader.** [data/load-graph.js](data/load-graph.js) loads `bank.json`: uniqueness constraints on each key and indexes, nodes in batched `UNWIND`s (dates as UTC datetimes, nested records as JSON, nulls left out), relationships from the dictionary, `(:_Meta)` and `(:_Catalog)`. Then it reads back and fails the run on any mismatch: node counts, one relationship per foreign key for each many-to-one relationship, and the properties present on each label against the dictionary.
- **Self-checks.** The generators fail the run if a foreign key dangles, if a covenant's status disagrees with its headroom, if drawn exceeds committed, or if the dictionary and the data disagree, field by field.

### Source access: direct for pages, MCP for the assistant

- **Pages at render time** (widget data, page parameter choices, filter values): the backend reads Neo4j **directly with the driver**. No MCP is involved in showing data.
- **The AI assistant while it designs a widget:** it reads the dictionary, samples nodes, inspects the graph schema and tests Cypher through the official **Neo4j Cypher MCP server** (`mcp-neo4j-cypher` 0.6.0, the `graph-mcp` service, pinned by digest).

```
 browser ──/api──► backend ── GraphSource ──bolt (read transactions)──────────────► neo4j (read-only)
                           └─ GraphMcpSource ──HTTP──► graph-mcp (:8000, read-only) ──► neo4j   (assistant only)
 Claude Code ────────────── .mcp.json ──HTTP──► localhost:3001 ┘
```

**Backend.** [`com.aipb.source`](backend/src/main/java/com/aipb/source/) holds both connections behind one interface, `SourceReader` (`cypher(query, params)`, `find(label, filter, fields, sort, limit)`, `asOfDate`):

- **`GraphSource`:** the driver (`aipb.graph.uri`, `user`, `password`, `database`). It is the primary `SourceReader`, so `WidgetDataService`, `QueryExecutor`, `ContextResolver` and the Pre-call and New Issue pages use it. Every query runs in a read transaction with a 10 s timeout (`max-time-ms`) and at most 1000 records (`max-records`); a longer result is marked `truncated`, with its full count.
- **`GraphMcpSource`:** the same interface over MCP (`read_neo4j_cypher`), used by the assistant (`AssistantTools`, `ContextAssembler`) and the validator; it also provides `catalog()`, `meta()`, `labels()` and `schema(label)` (stored property types and relationship counts, from `db.schema.nodeTypeProperties` and a pattern count).
- **`ReadOnlyCypher`:** the gate both readers pass before anything is sent: no CREATE, MERGE, SET, DELETE, REMOVE, DROP, FOREACH, LOAD CSV, `CALL … IN TRANSACTIONS`, `USE`, admin commands or procedures outside a short read-only list (comments and strings are stripped first); then an `EXPLAIN` through the driver must plan as read-only. The MCP path runs this check through the backend's driver before the call.
- **`GraphJson`:** driver values and the MCP server's JSON become the same plain JSON: ISO instants with milliseconds (as before), ints, and JSON-stored properties parsed back into records.
- **`GET /api/source/status`:** the direct connection (labels, dictionary size, asOfDate, counts) and, under `mcp`, the server and its tools. The sidebar shows it as `SOURCE · neo4j`.

**Claude Code.** [.mcp.json](.mcp.json) points to `http://localhost:3001/mcp/` (server `bank-graph`). Use `read_neo4j_cypher` with a query and params; the schema tool needs APOC, which the stack does not install, so read `(:_Catalog)` instead.

### Read-only, on Neo4j Community

Neo4j Community has no role-based access control: there is one admin account, so the database cannot hand the app a read-only user the way MongoDB's `bank_reader` was. The lock is therefore layered:

| Layer | What it refuses |
|---|---|
| The database is opened read-only (`server.databases.read_only=neo4j` on the `neo4j` service) | every write, data or schema, from any client - including Claude Code calling the MCP server directly |
| `graph-seed` | it loads the graph through its own writable Neo4j on the same volume and shuts it down; `neo4j` starts only after it succeeded |
| The MCP server's read-only mode (`NEO4J_READ_ONLY=true`) | no write tool; the read tool refuses a query whose `EXPLAIN` plans data writes. It lets schema commands such as `CREATE INDEX` through - the database refuses those |
| `ReadOnlyCypher` + `EXPLAIN` in the backend | anything but a plain read, before it is sent, from pages and from the assistant |
| Read transactions | the driver opens every session in read mode |

`GraphSourceLiveTest.nothingWrites` checks every path against the running stack, including a `CREATE INDEX` sent straight to the MCP server past the backend. The weaker guarantee is the account: anyone holding `NEO4J_PASSWORD` could restart Neo4j writable. Ports are bound to localhost. `mongo-init/01-users.js` now creates only `aipb_app` (MongoDB holds app state only).

Passwords come from `.env` (see [.env.example](.env.example); the defaults there are for local development only). They are applied when a volume is first created; to change them, edit `.env` and run `docker compose down -v`.

## The semantic layer

What the numbers *mean* is defined once, in [backend/src/main/resources/semantic/](backend/src/main/resources/semantic/), and every figure is computed from there. Prompts, specs and tests refer to metric ids; they never restate a formula or do date arithmetic.

| File | Holds |
|---|---|
| `dimensions.json` | **Conformed dimensions**: the ways a figure can be broken down or filtered (lob, client, region, dealStage, severity, month / quarter / year, ...), with allowed values and badge tones. A dimension means the same in every model, so figures from different labels line up on the same rows. |
| `models/*.json` | One **model** per node label (`node`): which property each dimension lives in, the time property, named **segments** (open deals, credit holdings, active signals), **measures** (sum / count / avg / min / max / countDistinct of a property or a Cypher expression over `n`, over segments) and listable **record fields**. **Joins** are relationships - one hop (`{rel, dir, node}`) or a path of hops (`{path: [...]}`), each many-to-one - and client-owned models reach the client's dimensions (region, industry, tier, ...) through their `entityJoin`, so they never multiply rows. |
| `metrics.json` | **Derived metrics** across measures and models: `utilization = exposureUsd / committedUsd`, `shareOfWallet = revenueUsd over the last 12 months / walletSizeUsd`, `revenueYoY`, `winRate`, ... |

A **semantic query** asks in those terms. An `aggregate` names metrics, dimensions, filters and a window; a `records` query names a model's fields:

```json
{ "metrics": ["revenueUsd", "revenueYoY"], "dimensions": ["quarter"],
  "window": { "type": "lastClosed", "unit": "quarter", "n": 8 }, "compare": "priorYear" }
```

- **Windows** ([Window.java](backend/src/main/java/com/aipb/semantic/Window.java)) are relative to the data's today: `ytd`, `trailing` / `lastClosed` n units, `current` unit, `next` n units, `range`, `all`. `on` picks another date (e.g. holdings maturity). `compare` (`priorYear` / `priorPeriod`) adds each metric's prior value as `<metric>Prior`, with periods lined up.
- **Planning and compiling** ([QueryPlanner](backend/src/main/java/com/aipb/semantic/QueryPlanner.java), [CypherCompiler](backend/src/main/java/com/aipb/semantic/CypherCompiler.java)): one read-only, parameterized Cypher query per (model, window): `MATCH (n:Label) WHERE …`, an `OPTIONAL MATCH` per join a path or expression needs, then `RETURN` the dimensions (implicit grouping keys) and the aggregations. Shared segments go into the `WHERE`, others become `CASE WHEN` inside the aggregation. Every value is a parameter (`$p0`, `$start`, …); every identifier comes from the validated model. Conditions keep MongoDB's missing-field behaviour (`ne`/`nin` hold on a missing property, everything else fails), and record lists sort missing values as MongoDB did.
- **Executing** ([QueryExecutor](backend/src/main/java/com/aipb/semantic/QueryExecutor.java)), through `GraphSource` (the driver):
  - rows from different models and windows are merged on their dimension values;
  - every period of a time breakdown appears (a quarter without revenue shows 0);
  - a ratio with nothing to divide by is unknown, not 0.
- **Results** ([ResultSet](backend/src/main/java/com/aipb/semantic/ResultSet.java)) carry each column's type, unit, format, tones and **lineage**: the metric and its version, its definition in plain words, the data-dictionary fields it reads, and the exact window it covered. They also carry every query that ran (`{cypher, params}`).
- **Checks.**
  - The model is checked for structure on every start (a broken definition stops the backend).
  - It is checked against `(:_Catalog)` once the source is reachable: every label and property must exist, every hop of a join must be a relationship the dictionary declares many-to-one, expressions may read only the sources they declare, and every filtered or enumerated value must be allowed.
  - `GET /api/semantic` reports any mismatch.

```bash
curl localhost:8080/api/semantic                                   # what can be asked
curl -XPOST localhost:8080/api/semantic/query -H 'content-type: application/json' \
  -d '{"entityId": "CL-0001", "query": {"metrics": ["exposureUsd", "utilization"]}}'
```

**Tests:**
- `CypherCompilerTest` snapshots the compiled Cypher of representative queries in `src/test/resources/semantic/compiled/*.cypher`, so a change in how a metric is computed shows up as a reviewable diff. Regenerate them after an intended change with `-Daipb.snapshots.update=true`.
- `GraphParityLiveTest` checks the move itself: every starter for all 60 clients and the book, and a battery of 209 semantic queries (every model's measures by each of its dimensions, the time grains, the client join, derived metrics, record lists), return what the MongoDB stack returned for the same data - captured into `src/test/resources/graph-parity/` before the move, at the pinned `BANK_AS_OF`.
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

The escape hatch is `data.custom`: a hand-written read-only Cypher query (`custom.cypher`), the columns it declares, and top-level `params` it references as `$name` - plus the built-in `$asOfDate`, ISO text compared as `datetime($asOfDate)`. Values are driver parameters, never spliced into the text ([ParamBinder](backend/src/main/java/com/aipb/widget/ParamBinder.java)). It is for what the semantic layer cannot express yet. A custom spec written for MongoDB (`collection` + `pipeline`, including format 1) cannot be translated mechanically: [SpecMigrator](backend/src/main/java/com/aipb/widget/SpecMigrator.java) reads it as `needsRebuild`, the validator reports `needs_rebuild`, and its card says it has to be rebuilt for the graph. Query specs carry over untouched.

- **Scope.**
  - `entity`: the selected client, so the widget can sit only on a page with a client picker.
  - `book`: always the whole book.
  - `inherit` (the default): the page's client when it has one, otherwise the book.
- **Series.** `line` and `bar` take a `series` role. With it, rows are one per period and series (e.g. month × line of business), and the renderer draws one line or bar group per value (at most 4).
- **Contract.** The format is defined by [widget-spec.schema.json](backend/src/main/resources/widgets/widget-spec.schema.json). The design system is described by [primitives.json](backend/src/main/resources/widgets/primitives.json), which lists `metric`, `bar`, `line`, `table` and `list` with their roles, row limits, units, formats and usage guidance. Both are served by `GET /api/widgets/manifest`, which is the same contract the assistant will be given.
- **Parameters** (custom data) are typed and referenced as `{"$param": "name"}`. Each one is replaced by a single JSON value, never spliced into text, so a value can't change the query. Dates become BSON dates; a plain-string date would silently match nothing. `asOfDate` is built in (`_meta.asOfDate`), so a spec never uses the wall clock.
- **Primitives** live in [frontend/src/widgets/spec/](frontend/src/widgets/spec/). They use one validated chart palette (brand green first; checked for color-vision deficiency and 3:1 contrast), status badges that always carry a label, legends for two or more series, direct labels on line ends, exact axis ticks, and a table view for every chart.
- **Runtime.** A placed widget (`{type: "spec", config: {definitionId}}`) fetches `GET /api/widgets/library/{id}/data?clientId=…`. The backend binds the parameters and runs the stored spec through `GraphSource` (the driver, not MCP). The browser never sends a query.

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
  - a custom query is Cypher (not a MongoDB pipeline to rebuild), passes the read-only gate, and matches labels the graph has (not `_Meta` / `_Catalog`);
  - every `$param` is declared (but `$asOfDate`), and every declared parameter is used;
  - no wall clock: `date()`, `datetime()`, `timestamp()` with no argument are refused in favour of `datetime($asOfDate)`;
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
- **Starter specs.** [widgets/starter/](backend/src/main/resources/widgets/starter/) holds 13 semantic-query specs: the 12 that rebuild Client 360, and **Signal trail** (graph-native: each unresolved risk signal traced through `CONCERNS` to its covenant, holding or deal, and on through `HAS_COVENANT` / `OF_PRODUCT` / `FOR_PRODUCT` to the product - multi-hop joins in the signals model). `GraphParityLiveTest` checks the 12 against MongoDB. The assistant's worked examples in `resources/assistant/examples/` are those starters written by hand as Cypher (`GraphSourceLiveTest` checks they return what the starters did), until the assistant moves onto the semantic layer. Starters are loaded at startup through the same validator; a file with a higher `version` replaces the stored copy.
- **Golden set.** `GoldenSpecsLiveTest` runs every starter spec through the runtime path (the driver, as pages read) and compares it, across three clients and the whole book, with figures computed independently in plain Java over raw documents. The natural-language requests in [golden/cases.json](backend/src/test/resources/golden/cases.json) will become the assistant's evaluation set. Run it with the stack up:

```bash
cd backend && mvn test                                            # unit tests
cd backend && NEO4J_PASSWORD=... mvn test -Dgroups=live -Dsurefire.excludedGroups=   # parity, golden set, readers, against the running stack
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
| **Build** | A read-only Cypher query returning exactly the brief's figures; every figure cited from the data dictionary; assumptions confirmed with tools; tested before submitting | `submit_data` | the full widget validator runs live with the page's sample client; every `catalogReference` (`Label.property`) must exist in the dictionary; every number has a definition |
| **Present** | The clearest primitive, roles, title, width and badges | structured output ([schema](backend/src/main/resources/assistant/schemas/present.json)) | the assembled spec passes the validator for this page |
| **Review** (opt-in only) | Independently verify the numbers, recomputing a key figure a different way | `submit_review` | a pass requires a matching independent recomputation |
| **Proposal** | What the banker sees | assembled by code from the artifacts (no LLM), so it can't misdescribe what was built | |

**The review never runs by default.** It costs extra tokens, so it runs only when the banker clicks **Verify numbers** or asks for a check. If a requested review can't confirm the numbers, the proposal is marked **Not verified** and adding it needs an explicit confirmation.

### What each stage sees

Static context goes in the system prompt. It is generated deterministically from `(:_Catalog)`, the design catalog and the starter specs, so it is byte-identical across calls and served from the prompt cache. Dynamic context goes in the user message.

| Block | Intake | Build | Present | Review |
|---|---|---|---|---|
| [Primer](backend/src/main/resources/assistant/prompts/primer.md) + the stage's [rules](backend/src/main/resources/assistant/prompts/) | ✓ | ✓ | ✓ | ✓ |
| Data overview (conventions, joins) | ✓ | ✓ | | ✓ |
| Data index (properties and relationships per label, derived-metric names) | ✓ | | | |
| Full data dictionary (every field, allowed values, units, derived-metric definitions) | | ✓ | | ✓ |
| Worked examples from starter specs | | data | display | |
| Design catalog (primitives, roles, row limits, guidance) | menu | | ✓ | |
| Page (scope, selected client, existing widgets), the data's today | ✓ | ✓ | ✓ | ✓ |
| Widget library | ✓ | | | |
| Brief / data / sample rows / change request | revisions | ✓ | ✓ | ✓ |

Every stage run records a **manifest** of what the model was given (block, source, size, hash), plus the dynamic message verbatim, its tokens, cost and tool calls. You can see these under **Details** in the drawer or at `GET /api/assistant/sessions/{id}/runs`. `ContextAssemblyTest` writes each stage's full request to `backend/target/assistant-context/<stage>.md` for review.

### Skills and templates
A widget is one **kind of skill**; an **insight** (a few sentences about figures, rendered by code) is another, and actions (buttons that run app-internal automations) are planned next. Every assistant run builds one kind, guided by a **Skills Studio template**: `backend/src/main/resources/skills/templates/*.json`, validated against `skills/skill-template.schema.json` and checked at startup by `SkillTemplateRegistry`.

A template carries:
- the pages it fits (`pageScopes`);
- form `inputs`, whose values reach Intake as `<skill_inputs>`;
- per-stage `guidance`, appended as one `template-guidance` block after the shared, cached system blocks;
- worked `examples` that replace a stage's defaults;
- `constraints` (scope, labels, primitives, max rows), enforced by `TemplateGate` inside each stage's gate, so a breach goes back to Claude as a correction;
- brief `defaults`, which fill fields Intake left open and are listed as assumptions.

**Skills Studio** (`/studio`, Builder/Reviewer): a grid of templates. Picking one asks for its inputs and a page that fits, then opens the assistant there with the template chosen. The assistant's empty state also has a **Start from** picker, limited to the templates that fit the page.

If a templated run gets a request the template doesn't build, Intake answers `redirect` with a `suggestedTemplateId` taken from the `<other_templates>` it was shown. The chat then offers **Switch to …**, which starts a new conversation with that template and the same request. Custom widget runs take any request, so they never redirect.

`custom-widget` is the free-form template. It adds nothing, so its prompts are byte-identical to running with no template; `ContextAssemblyTest.customWidgetSystemBlocksStayPinned` pins every stage's blocks by hash (`src/test/resources/assistant/system-blocks.sha256`). Sessions started without a `templateId` use it. Saved definitions record `kind`, `templateId` and `templateVersion`. Kind-specific flow lives behind `SkillKind`: `WidgetSkill` and `InsightSkill`, which share Build and the opt-in review (`DataSkill`) and differ in their design step.

### Insights
An insight is a spec like any widget, drawn by the **`narrative`** primitive: `view.statements` (text with slots, an optional `when` condition on the first row, a tone) and `view.figures`. Slots are `{column}`, `{column:format}` and `{column>0?up:down}`. The assistant writes the statements once, in the **Narrate** stage (`prompts/narrate.md`, `schemas/narrate.json`). `NarrativeRenderer` fills them on every view, so viewing an insight makes no model call. The validator checks every slot, condition and figure, and renders the statements on the sample: one that says nothing is refused. The data endpoints and previews return the rendered `narrative` next to the rows. Templates: `revenue-momentum` and `pipeline-outlook`. The `narrative` primitive is tagged `"skill": "insight"` in `primitives.json`, so it stays out of the widget stages' catalogs.

### Roles and maker-checker
Three demo roles, chosen from the sidebar's **Viewing as** dropdown (the `X-AIPB-Role` header, no login): **Banker** (dashboards and the library only), **Builder** (the maker) and **Reviewer** (the checker).
- **Add & share** (approving a proposal) saves the widget to the Builder's personal library and places it where only they see it, and submits it for review. A Reviewer's own widget is approved at once.
- The **Skills** window lists widgets in progress, approved and stock widgets; Reviewers get the approval queue (approve, feedback & revise, reject). Feedback joins the conversation, and the Builder's next message revises the widget.
- **Modify** starts a conversation from a library widget (always a Custom widget run). The change waits in `aipb.widget_submissions`; everyone keeps seeing the current version until it's approved.
- Every approved version is kept in `aipb.widget_versions` and can be previewed or restored.

### Tools

Build and Review use read-only tools. All of them go through `GraphMcpSource`, so every query passes the read-only gate, results are capped, and outputs are trimmed to a few rows:
- `sample_nodes`: look at real nodes of a label;
- `distinct_values`: exact values of a property before filtering;
- `graph_schema`: what the graph holds for a label - stored property types, relationships in and out, with counts;
- `run_query`: test Cypher with the page's sample client;
- the stage's `submit_*` tool.

### Model, cost, limits

- **Model:** per stage - `claude-haiku-5-5` for intake and present, `claude-sonnet-5-5` for build and review (`AIPB_MODEL_<STAGE>`) - through the official Java SDK ([AnthropicGateway](backend/src/main/java/com/aipb/assistant/llm/AnthropicGateway.java)).
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
        │                     │ pages:     WidgetDataService → QueryExecutor → CypherCompiler → GraphSource (bolt)
        │                     │ assistant: AssistantTools → GraphMcpSource → McpClient (HTTP)
        │                     │              → Cypher MCP server (:8000 in the stack, :3001 on localhost; read-only)
        │                     │
 Neo4j 5.26 Community (read-only)  the bank graph + (:_Catalog) + (:_Meta)
 MongoDB 7                         aipb: layouts, widgets (spec library), sessions, clients, opportunities, signals (demo book)
```

`layouts/default` holds the pages, their parameters (e.g. Client 360's `clientId` picker, whose choices come from `(:Client)`) and their widgets. The React shell renders each widget's `type` from [frontend/src/widgets/registry.jsx](frontend/src/widgets/registry.jsx). `spec` renders a library definition. The legacy types `kpi-strip`, `client-360` and `client-table` are also declared in [WidgetManifest.java](backend/src/main/java/com/aipb/service/WidgetManifest.java) and are no longer placed on any default page.


## API

| Method | Path | Purpose |
|---|---|---|
| GET | `/api/status` | Health + layout version |
| GET | `/api/source/status` | Source-data connections: direct (labels, dictionary entries, asOfDate, counts) and `mcp` (server, tools) |
| GET | `/api/kpis?lob=&region=&marketId=` | Book KPI tiles |
| GET | `/api/clients?sort=revenue\|exposure\|health\|walletGap\|pipeline&q=` | Client book |
| GET | `/api/clients/{id}` | Client 360 bundle |
| GET/PUT | `/api/layouts/{id}` · POST `/reset` | Layout |
| POST/DELETE | `/api/layouts/{id}/pages[/{pageId}]` | Pages |
| POST/PATCH/DELETE | `/api/layouts/{id}/pages/{pageId}/widgets[/{widgetId}]` · POST `.../move?delta=` | Widgets |
| GET | `/api/semantic` | The semantic layer: dimensions, models (measures, segments, record fields), derived metrics, windows; mismatches with `(:_Catalog)` |
| POST | `/api/semantic/query` `{query, entityId}` | Run a semantic query: rows, typed columns with lineage, the Cypher that ran (422 with issues if it can't be answered) |
| GET | `/api/widgets/manifest` | Widget types, the spec JSON Schema and the design-system primitives |
| GET | `/api/widgets/library` · `/library/{id}` | Widget definitions (summary · with spec) |
| POST/PUT | `/api/widgets/library[/{id}]` `{spec, pageId?, params?}` | Save a spec: validated first; 422 with the report if invalid, 409 on a duplicate id; PUT bumps the version |
| GET | `/api/widgets/library/{id}/data?<params>` | Run a stored definition: rows, view, count/total/truncated |
| POST | `/api/widgets/validate` `{spec, pageId?, params?}` | Validation report with a live preview (nothing saved) |
| GET | `/api/layouts/{id}/pages/{pageId}/params/{name}/options` | Choices for a page parameter |
| GET | `/api/layouts/{id}/pages/{pageId}/context/filters/{dimension}/options` | Choices for one of a page's filters |
| GET | `/api/widgets/placed/{layoutId}/{pageId}/{widgetId}/data?<page values>` | Run a placed widget in its page's context (client, period, filters, placement bindings): rows, view, applied context, notes |
| GET | `/api/assistant/status` | Assistant enabled, model, effort per stage |
| GET | `/api/skills/templates?pageId=` | Skills Studio templates (Builder/Reviewer), optionally only those that fit a page |
| GET | `/api/skills/templates/{id}` | One template: kind, summary, inputs, suggestions |
| POST | `/api/assistant/sessions` `{layoutId, pageId, params, templateId, inputs, modifies}` | Start a conversation on a page, from a template (default `custom-widget`), or to change library widget `modifies` |
| GET | `/api/assistant/sessions/{id}` | Conversation state (turns, status, proposal, cost) |
| POST | `/api/assistant/sessions/{id}/messages` `{text, params}` | Send a message → **SSE**: `stage`, `activity`, `message`, `proposal`, `error`, `done` |
| POST | `/api/assistant/sessions/{id}/verify` | Opt-in independent review → SSE |
| POST | `/api/assistant/sessions/{id}/approve` `{confirmUnverified, note}` | Add & share: save to the personal library, place it for its owner, submit for review |
| POST | `/api/assistant/sessions/{id}/submit` `{note}` | Share a personal widget for review |
| GET | `/api/identity` | The acting role and the available roles |
| GET | `/api/skills` | The Skills window for the caller: sections and how many need attention |
| GET | `/api/submissions/{id}` · POST `/approve` · `/send-back` · `/reject` `{comment}` | One submission (preview, history, current version) and a Reviewer's decision |
| GET | `/api/widgets/library/{id}/versions[/{v}]` · POST `/versions/{v}/restore` | Version history, one version's preview, restore |
| GET | `/api/precall/clients` · `/api/precall/{clientId}` | Pre-call Brief: client list, one client's brief |
| GET | `/api/new-issue/deals` · `/deals/{dealId}` | New Issue deals |
| GET | `/api/assistant/sessions/{id}/runs` | Inspector: each stage run's context manifest, tokens, cost, tool calls |
| POST | `/api/admin/reseed` | Wipe and reseed the app DB |

## Next steps

- Run the evaluation, then tune effort per stage against it.
- Retire the legacy Client 360 code (the reference page is gone).

All names and figures are **synthetic**. Security (SSO, entitlements) is out of scope for this local build.
