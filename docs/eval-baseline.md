# Assistant evaluation - baseline (2026-10-21)

The assistant as it was before the semantic layer (stages Intake -> Build -> Present writing raw pipelines), measured before Phase 2 changed the starter widgets. Four representative cases: a time window with a comparison, a ratio against its limit, records with formatting, and book scope. Each request asks for a new widget, since the golden widgets already exist on their pages. Re-run the same four after the assistant moves onto the semantic layer (Phase 6):

`mvn test -Dgroups=eval -Dsurefire.excludedGroups= -Daipb.eval.only=client-revenue-ytd,client-exposure,client-covenants,book-revenue-by-lob-monthly`


Model claude-opus-5-5, effort {"build":"medium","review":"medium","present":"medium","intake":"medium"}. Client CL-0001.

**4 / 4 passed** · total cost $0.46 · 27 s average

| case | request | result | proposal | cost | time |
|---|---|---|---|---|---|
| client-revenue-ytd | How much revenue have we booked with this client this year, compared with last year? Build it as a new widget; don't point me to or reuse an existing one. | PASS | new metric "Revenue YTD vs last year" | $0.191 | 29 s |
| client-exposure | What is our credit exposure to this client, and how much of their limit are they using? Build it as a new widget; don't point me to or reuse an existing one. | PASS | new meter "Credit exposure and utilization" | $0.067 | 20 s |
| client-covenants | Show this client's covenants, tightest headroom first. Build it as a new widget; don't point me to or reuse an existing one. | PASS | new table "Covenants by headroom" | $0.094 | 28 s |
| book-revenue-by-lob-monthly | Show monthly revenue across the book by line of business for the last two years. Build it as a new widget; don't point me to or reuse an existing one. | PASS | new line "Monthly book revenue by LOB" | $0.106 | 30 s |
