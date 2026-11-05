-- A migration after V1, as a later change to the schema would be: MigrationTests checks that
-- the schema snapshot is still compared at V1 when one exists (ADR-0032), and that a database
-- taken over at V1 gets it. Its version is far above any real one, since Flyway refuses two
-- migrations of one version across its locations.
CREATE TABLE a_later_table (id INTEGER PRIMARY KEY);
