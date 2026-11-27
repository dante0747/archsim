-- Share links hide the telemetry addresses a calibration writes: its source, with the URL's
-- credentials when it has them, in the design's calibration and in every measured input's
-- provenance record, which the report's assumptions repeat. Links made before hid the first only.
-- This rewrites their stored copies by the API's own rule (SharePayloads.hideAddresses), so the
-- links keep working without the addresses: in every string under a "calibration" key, and in
-- every object whose "kind" is "measured", each address becomes "(address hidden)". Each string
-- is rewritten as a value, never as JSON text, so no escape or quote is cut. Other addresses stay:
-- documented inputs and the report's formulas link public documentation. The helper lives in this
-- schema only while the migration runs, which needs no privilege V1 did not.
CREATE FUNCTION archsim_hide_addresses(value jsonb, hiding boolean) RETURNS jsonb
LANGUAGE plpgsql IMMUTABLE AS $$
DECLARE
    result jsonb;
    hiding_here boolean;
BEGIN
    CASE jsonb_typeof(value)
        WHEN 'string' THEN
            IF NOT hiding THEN
                RETURN value;
            END IF;
            -- the API's pattern: a scheme after no letter, digit or underscore, then no space
            RETURN to_jsonb(regexp_replace(value #>> '{}',
                '(?<![A-Za-z0-9_])[A-Za-z][A-Za-z0-9+.-]*://[^ \t\n\v\f\r]+', '(address hidden)', 'g'));
        WHEN 'array' THEN
            SELECT coalesce(jsonb_agg(archsim_hide_addresses(item, hiding) ORDER BY position), '[]'::jsonb)
            INTO result
            FROM jsonb_array_elements(value) WITH ORDINALITY AS items(item, position);
            RETURN result;
        WHEN 'object' THEN
            hiding_here := hiding OR coalesce(value ->> 'kind' = 'measured', false);
            SELECT coalesce(jsonb_object_agg(key,
                       archsim_hide_addresses(item, hiding_here OR key = 'calibration')), '{}'::jsonb)
            INTO result
            FROM jsonb_each(value) AS fields(key, item);
            RETURN result;
        ELSE
            RETURN value;
    END CASE;
END
$$;

-- only the copies with something to hide: a measured record or a calibration (an uncalibrated
-- design's is null)
UPDATE shared_reports
SET payload = archsim_hide_addresses(payload, false)
WHERE jsonb_typeof(payload) = 'object'
  AND (jsonb_path_exists(payload, 'strict $.** ? (@.kind == "measured")')
       OR jsonb_path_exists(payload, 'strict $.**.calibration ? (@ != null)'));

DROP FUNCTION archsim_hide_addresses(jsonb, boolean);
