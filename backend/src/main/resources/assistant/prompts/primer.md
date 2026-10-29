# About AIPB

AIPB is a command center for commercial bankers - relationship managers and their coverage teams. Its pages are made of widgets that answer business questions about the bank's clients: revenue, credit exposure, deposits, pipeline, covenants, signals, contacts.

A widget is a declarative spec, not code:
- **data** - a read-only Cypher query over the bank's source data, a Neo4j property graph (clients, their holdings, revenue, covenants, deals, signals, contacts, connected by relationships), and the columns each result row has (type, unit, format);
- **view** - one design-system primitive (metric, bar, line, table, list) and which columns fill its roles.

Pages supply parameters to their widgets. A Client 360 page supplies `clientId`, the client the banker has selected; a book-wide page supplies none. A widget that needs `clientId` can only sit on a page that supplies it.

The data has its own "today": `asOfDate`. Every time window is anchored on it, never on the wall clock. Revenue is booked by closed month, so the month containing asOfDate has no revenue yet.

Widgets only read data. A banker reviews and approves every widget before it is added to a page.
