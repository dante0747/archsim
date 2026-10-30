# Rows of items beyond init.sql's 100,000, for an experiment whose keyspace is larger (cache-ttl:
# 1,000,000 keys, so every key a request names has a row). run.py sets BENCH_ITEMS per stack; at
# the default nothing happens. The postgres image sources this after init.sql (no `exit` here:
# it would end the image's entrypoint).
if [ "${BENCH_ITEMS:-100000}" -gt 100000 ]; then
  echo "extra-rows.sh: loading items 100001..${BENCH_ITEMS}"
  psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" \
    -c "INSERT INTO items (id, value, payload) SELECT g, 0, repeat('x', 100) FROM generate_series(100001, ${BENCH_ITEMS}) AS g" \
    -c "ANALYZE items"
fi
