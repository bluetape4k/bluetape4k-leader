# Issue 405 RDB Benchmark Plan

## Steps

1. Add benchmark dependencies for PostgreSQL/MySQL JDBC, R2DBC, and
   Testcontainers.
2. Extend blocking benchmark params with `exposed-jdbc-postgresql` and
   `exposed-jdbc-mysql`.
3. Extend suspend benchmark params with `exposed-r2dbc-postgresql` and
   `exposed-r2dbc-mysql`.
4. Run compile-only verification first.
5. Run the full default benchmark target sequentially and store raw JSON.
6. Update README tables and regenerate distributed backend charts as SVG and
   PNG.
7. Validate chart XML, PNG renderability, Gradle compile, and Markdown diff.

## Stop Condition

The work is done when compile and full benchmark run pass, README tables and
charts show PostgreSQL/MySQL rows, and generated chart images have been visually
inspected.
