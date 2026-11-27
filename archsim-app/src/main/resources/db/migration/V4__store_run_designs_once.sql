-- A run's design is stored once per digest (ADR-0110): each run carried a copy of its design in
-- simulation_runs.input_document, however many runs simulated the same design; runs now refer to
-- it in run_designs by its digest. The digest is the SHA-256 of the design's JSON as PostgreSQL
-- writes jsonb out, in its own key order and spacing, so the designs moved here and those the API
-- stores later (RunRepository) agree on it however the JSON was written.
--
-- The runs stay locked while their designs move: none is queued, changed or read halfway.
LOCK TABLE simulation_runs IN ACCESS EXCLUSIVE MODE;

CREATE TABLE run_designs (
    digest VARCHAR(64) NOT NULL,
    document JSONB NOT NULL,
    PRIMARY KEY (digest)
);

-- each run's digest, computed once, in a table that goes when the migration's transaction ends
CREATE TEMPORARY TABLE run_design_digests ON COMMIT DROP AS
SELECT id, encode(sha256(convert_to(input_document::text, 'UTF8')), 'hex') AS digest
FROM simulation_runs;

-- one copy of each design: runs with the same digest carry the same design
INSERT INTO run_designs (digest, document)
SELECT digests.digest, runs.input_document
FROM simulation_runs AS runs JOIN run_design_digests AS digests ON digests.id = runs.id
ON CONFLICT (digest) DO NOTHING;

-- writing every run again, after the copies' column is dropped, frees their space once vacuumed
ALTER TABLE simulation_runs DROP COLUMN input_document, ADD COLUMN design_digest VARCHAR(64);
UPDATE simulation_runs AS runs SET design_digest = digests.digest
FROM run_design_digests AS digests WHERE digests.id = runs.id;

ALTER TABLE simulation_runs ALTER COLUMN design_digest SET NOT NULL,
    ADD CONSTRAINT fk_runs_design_digest FOREIGN KEY (design_digest) REFERENCES run_designs (digest);
CREATE INDEX ix_simulation_runs_design_digest ON simulation_runs (design_digest);

-- A design goes with the last run that refers to it, whatever deletes the runs: a request, their
-- project's or workspace's deletion (which cascades), or a prune. Any statement that makes a run
-- refer to a design stores the design through RunRepository's upsert, which locks a stored
-- design's row; a statement that only copied a digest would lock the design only in its
-- foreign-key check, after the run's row is written, and would then fail on the foreign key where
-- the upsert stores the design again. This locks the deleted runs' designs, in one order, before
-- it reads which runs remain, in a statement of its own: under READ COMMITTED, the isolation the
-- application uses, each statement of a volatile function sees what was committed before it
-- began. So the deletion of a design's last run waits for a run being queued with it and then
-- keeps it, or the queued run waits and stores the design again; no design is deleted while a run
-- refers to it. (Under REPEATABLE READ, such a deletion would fail with a serialization error
-- instead, and delete nothing.)
CREATE FUNCTION delete_orphaned_run_designs() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    PERFORM 1 FROM run_designs
    WHERE digest IN (SELECT design_digest FROM deleted_runs)
    ORDER BY digest
    FOR UPDATE;
    DELETE FROM run_designs AS designs
    WHERE designs.digest IN (SELECT design_digest FROM deleted_runs)
      AND NOT EXISTS (SELECT 1 FROM simulation_runs AS runs WHERE runs.design_digest = designs.digest);
    RETURN NULL;
END
$$;

CREATE TRIGGER delete_orphaned_run_designs
AFTER DELETE ON simulation_runs
REFERENCING OLD TABLE AS deleted_runs
FOR EACH STATEMENT
EXECUTE FUNCTION delete_orphaned_run_designs();
