SELECT json_build_object(
  'statements', (
    SELECT coalesce(json_agg(json_build_object(
      'query', s.query, 'calls', s.calls, 'rows', s.rows,
      'meanExecMs', s.mean_exec_time, 'stddevExecMs', s.stddev_exec_time,
      'minExecMs', s.min_exec_time, 'maxExecMs', s.max_exec_time) ORDER BY s.calls DESC), '[]')
    FROM pg_stat_statements s JOIN pg_database d ON d.oid = s.dbid
    WHERE d.datname = current_database() AND s.query !~* 'pg_stat'),
  'wal', (SELECT json_build_object(
    'records', wal_records, 'bytes', wal_bytes, 'buffersFull', wal_buffers_full,
    'writes', wal_write, 'syncs', wal_sync, 'writeMs', wal_write_time,
    'syncMs', wal_sync_time) FROM pg_stat_wal),
  'database', (SELECT json_build_object(
    'commits', xact_commit, 'rollbacks', xact_rollback, 'activeMs', active_time,
    'sessionMs', session_time) FROM pg_stat_database WHERE datname = current_database()))
