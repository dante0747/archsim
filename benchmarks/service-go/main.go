// ArchSim benchmark service in Go (net/http, a goroutine per request, and a pgx pool): the
// go-mixed held-out experiment. It serves the same HTTP contract as benchmarks/service/server.js,
// so benchmarks/run.py and benchmarks/k6/load.js drive it unchanged: GET /read?id=,
// POST /write?id=[&async=1], GET /health, POST /reset and GET /stats (CPU per request, the
// handler's own time, each PostgreSQL call's time, and garbage collection).
package main

import (
	"context"
	"encoding/json"
	"errors"
	"log"
	"math"
	"net/http"
	"os"
	"runtime"
	"sort"
	"strconv"
	"sync"
	"sync/atomic"
	"syscall"
	"time"

	"github.com/jackc/pgx/v5"
	"github.com/jackc/pgx/v5/pgxpool"
)

const maxSamples = 200000

// ---- timings ----------------------------------------------------------------------------------

type recorder struct {
	mu       sync.Mutex
	samples  map[string][]float64
	counters map[string]int64
	cpu0     int64
	wall0    time.Time
	gc0      [2]uint64 // collections, pause ns
}

var (
	rec      = &recorder{}
	requests atomic.Int64
)

func cpuNanos() int64 {
	var ru syscall.Rusage
	if err := syscall.Getrusage(syscall.RUSAGE_SELF, &ru); err != nil {
		return 0
	}
	return ru.Utime.Nano() + ru.Stime.Nano()
}

// Collections and their stop-the-world pause time (ReadMemStats stops the world itself, so it
// is read only at /reset and /stats).
func gcNow() [2]uint64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return [2]uint64{uint64(m.NumGC), m.PauseTotalNs}
}

func (r *recorder) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.samples = map[string][]float64{}
	r.counters = map[string]int64{}
	r.cpu0 = cpuNanos()
	r.wall0 = time.Now()
	r.gc0 = gcNow()
	requests.Store(0)
}

func (r *recorder) record(name string, ms float64) {
	r.mu.Lock()
	if s := r.samples[name]; len(s) < maxSamples {
		r.samples[name] = append(s, ms)
	}
	r.mu.Unlock()
}

func quantile(sorted []float64, q float64) any {
	if len(sorted) == 0 {
		return nil
	}
	i := int(math.Min(float64(len(sorted)-1), math.Max(0, math.Ceil(q*float64(len(sorted)))-1)))
	return sorted[i]
}

func (r *recorder) snapshot() map[string]any {
	r.mu.Lock()
	copies := make(map[string][]float64, len(r.samples))
	for k, v := range r.samples {
		copies[k] = append([]float64(nil), v...)
	}
	counters := make(map[string]int64, len(r.counters))
	for k, v := range r.counters {
		counters[k] = v
	}
	cpu0, wall0, gc0 := r.cpu0, r.wall0, r.gc0
	r.mu.Unlock()
	n := requests.Load()
	cpuMs := float64(cpuNanos()-cpu0) / 1e6
	g := gcNow()
	var perRequest any
	if n > 0 {
		perRequest = cpuMs / float64(n)
	}
	timings := map[string]any{}
	for name, s := range copies {
		if len(s) == 0 {
			continue
		}
		sort.Float64s(s)
		total := 0.0
		for _, v := range s {
			total += v
		}
		timings[name] = map[string]any{
			"count":  len(s),
			"mean":   total / float64(len(s)),
			"median": quantile(s, 0.5),
			"p95":    quantile(s, 0.95),
			"p99":    quantile(s, 0.99),
		}
	}
	return map[string]any{
		"role":            "api",
		"runtime":         runtime.Version(),
		"gomaxprocs":      runtime.GOMAXPROCS(0),
		"requests":        n,
		"wallMs":          float64(time.Since(wall0).Nanoseconds()) / 1e6,
		"cpuMs":           cpuMs,
		"cpuMsPerRequest": perRequest,
		"gc": map[string]any{
			"collections": g[0] - gc0[0],
			"pauseMs":     float64(g[1]-gc0[1]) / 1e6,
		},
		"counters": counters,
		"timings":  timings,
	}
}

// Time of one dependency call; “io“ adds up the calls so the handler can record its own time.
func timed(name string, io *float64, call func() error) error {
	t := time.Now()
	err := call()
	ms := float64(time.Since(t).Nanoseconds()) / 1e6
	rec.record(name, ms)
	*io += ms
	return err
}

// ---- API --------------------------------------------------------------------------------------

func writeJSON(w http.ResponseWriter, status int, body any) {
	data, _ := json.Marshal(body)
	w.Header().Set("content-type", "application/json")
	w.Header().Set("content-length", strconv.Itoa(len(data)))
	w.WriteHeader(status)
	_, _ = w.Write(data)
}

// A /read or /write handler, timed from the mux handing it the request until its response is
// ready (as server.js times it); “own“ is that less the dependency calls.
func bench(handle func(r *http.Request, io *float64) (any, error)) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		t := time.Now()
		io := 0.0
		body, err := handle(r, &io)
		if err != nil {
			writeJSON(w, http.StatusInternalServerError, map[string]any{"error": err.Error()})
			return
		}
		ms := float64(time.Since(t).Nanoseconds()) / 1e6
		requests.Add(1)
		rec.record("handler", ms)
		rec.record("own", ms-io)
		writeJSON(w, http.StatusOK, body)
	}
}

func id(r *http.Request) int {
	if v, err := strconv.Atoi(r.URL.Query().Get("id")); err == nil {
		return v
	}
	return 1
}

func newPool(ctx context.Context, size int32, idle int32, noFlush bool) *pgxpool.Pool {
	cfg, err := pgxpool.ParseConfig(os.Getenv("DATABASE_URL"))
	if err != nil {
		log.Fatal(err)
	}
	cfg.MaxConns = size
	cfg.MinConns = idle
	if noFlush {
		cfg.ConnConfig.RuntimeParams["synchronous_commit"] = "off"
	}
	pool, err := pgxpool.NewWithConfig(ctx, cfg)
	if err != nil {
		log.Fatal(err)
	}
	return pool
}

func main() {
	ctx := context.Background()
	size := 10
	if v, err := strconv.Atoi(os.Getenv("PG_POOL")); err == nil {
		size = v
	}
	pool := newPool(ctx, int32(size), int32(size), false)
	// Same database, commits without waiting for the WAL flush: calibration only.
	asyncPool := newPool(ctx, 2, 0, true)
	rec.reset()

	mux := http.NewServeMux()
	mux.HandleFunc("GET /read", bench(func(r *http.Request, io *float64) (any, error) {
		var value *int64
		err := timed("db_read", io, func() error {
			return pool.QueryRow(r.Context(), "SELECT value FROM items WHERE id = $1", id(r)).Scan(&value)
		})
		if errors.Is(err, pgx.ErrNoRows) {
			err = nil
		}
		return map[string]any{"value": value}, err
	}))
	mux.HandleFunc("POST /write", bench(func(r *http.Request, io *float64) (any, error) {
		name, p := "db_write", pool
		if r.URL.Query().Get("async") == "1" {
			name, p = "db_write_async", asyncPool
		}
		err := timed(name, io, func() error {
			_, err := p.Exec(r.Context(), "UPDATE items SET value = value + 1 WHERE id = $1", id(r))
			return err
		})
		return map[string]any{"ok": true}, err
	}))
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	mux.HandleFunc("/reset", func(w http.ResponseWriter, r *http.Request) {
		rec.reset()
		writeJSON(w, http.StatusOK, map[string]any{"ok": true})
	})
	mux.HandleFunc("/stats", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, http.StatusOK, rec.snapshot())
	})

	// Keep-alive like the Node.js service (65 s idle).
	server := &http.Server{Addr: ":8080", Handler: mux, IdleTimeout: 65 * time.Second}
	log.Printf("api listening on 8080 (GOMAXPROCS %d)", runtime.GOMAXPROCS(0))
	log.Fatal(server.ListenAndServe())
}
