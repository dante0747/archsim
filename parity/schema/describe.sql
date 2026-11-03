-- The schema of the public tables, as one canonical JSON document: every table's columns in
-- order (type, nullability, default), its constraints and indexes, and the sequences. Both the
-- Alembic-made database (scripts/schema_snapshot.py) and the Flyway-made one (archsim-app's
-- tests) are described with this query, and must give the same document.
SELECT jsonb_build_object(
    'tables', (
        SELECT jsonb_object_agg(t.relname, jsonb_build_object(
            'columns', (
                SELECT jsonb_agg(jsonb_build_object(
                    'name', a.attname,
                    'type', format_type(a.atttypid, a.atttypmod),
                    'notNull', a.attnotnull,
                    'default', pg_get_expr(d.adbin, d.adrelid)
                ) ORDER BY a.attnum)
                FROM pg_attribute a
                LEFT JOIN pg_attrdef d ON d.adrelid = a.attrelid AND d.adnum = a.attnum
                WHERE a.attrelid = t.oid AND a.attnum > 0 AND NOT a.attisdropped
            ),
            'constraints', (
                SELECT coalesce(jsonb_object_agg(c.conname, pg_get_constraintdef(c.oid)), '{}'::jsonb)
                FROM pg_constraint c
                -- PostgreSQL 18 also records NOT NULL as a constraint; notNull above has it.
                WHERE c.conrelid = t.oid AND c.contype <> 'n'
            ),
            'indexes', (
                SELECT coalesce(jsonb_object_agg(i.indexname, i.indexdef), '{}'::jsonb)
                FROM pg_indexes i
                WHERE i.schemaname = 'public' AND i.tablename = t.relname
            )
        ))
        FROM pg_class t
        JOIN pg_namespace n ON n.oid = t.relnamespace
        WHERE n.nspname = 'public' AND t.relkind = 'r' AND t.relname <> 'flyway_schema_history'
    ),
    'sequences', (
        SELECT coalesce(jsonb_agg(s.relname ORDER BY s.relname), '[]'::jsonb)
        FROM pg_class s
        JOIN pg_namespace n ON n.oid = s.relnamespace
        WHERE n.nspname = 'public' AND s.relkind = 'S' AND s.relname NOT LIKE 'flyway%'
    )
) AS schema;
