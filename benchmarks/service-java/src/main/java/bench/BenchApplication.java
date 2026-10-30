// ArchSim benchmark service on Spring Boot 3 (Spring MVC on Tomcat's thread pool, HikariCP,
// PostgreSQL JDBC): the java-mixed held-out experiment. It serves the same HTTP contract as
// benchmarks/service/server.js, so benchmarks/run.py and benchmarks/k6/load.js drive it unchanged:
// GET /read?id=, POST /write?id=[&async=1], GET /health, POST /reset and GET /stats (CPU per
// request, the handler's own time, each PostgreSQL call's time, and garbage collection).
package bench;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@SpringBootApplication
public class BenchApplication {
  public static void main(String[] args) {
    SpringApplication.run(BenchApplication.class, args);
  }
}

/** The service's PostgreSQL pools: HikariCP with PG_POOL connections, and a small second pool
 * whose commits do not wait for the WAL flush (calibration only, as in server.js). */
@Configuration
class Db {
  private static HikariConfig config(String name, int size, int idle) {
    URI url = URI.create(System.getenv().getOrDefault("DATABASE_URL", "postgresql://bench:bench@postgres:5432/bench"));
    String[] user = url.getUserInfo().split(":", 2);
    HikariConfig c = new HikariConfig();
    c.setPoolName(name);
    c.setJdbcUrl("jdbc:postgresql://" + url.getHost() + ":" + url.getPort() + url.getPath());
    c.setUsername(user[0]);
    c.setPassword(user.length > 1 ? user[1] : "");
    c.setMaximumPoolSize(size);
    c.setMinimumIdle(idle);
    return c;
  }

  @Bean
  @Primary
  DataSource dataSource() {
    int size = Integer.parseInt(System.getenv().getOrDefault("PG_POOL", "10"));
    return new HikariDataSource(config("bench", size, size));
  }

  @Bean
  DataSource asyncDataSource() {
    HikariConfig c = config("bench-async", 2, 0);
    c.setConnectionInitSql("SET synchronous_commit = off");
    return new HikariDataSource(c);
  }

  @Bean
  @Primary
  JdbcTemplate jdbc(DataSource dataSource) {
    return new JdbcTemplate(dataSource);
  }

  @Bean
  JdbcTemplate asyncJdbc(@Qualifier("asyncDataSource") DataSource asyncDataSource) {
    return new JdbcTemplate(asyncDataSource);
  }
}

/** Timings, counters and CPU since the last reset. */
@Component
class Stats {
  static final int MAX_SAMPLES = 200_000;

  static final class Samples {
    final double[] values = new double[MAX_SAMPLES];
    final AtomicInteger n = new AtomicInteger();

    void add(double v) {
      int i = n.getAndIncrement();
      if (i < MAX_SAMPLES) values[i] = v;
    }

    double[] sorted() {
      double[] s = Arrays.copyOf(values, Math.min(n.get(), MAX_SAMPLES));
      Arrays.sort(s);
      return s;
    }
  }

  /** Time the current request spent in dependency calls (one request per Tomcat thread). */
  static final ThreadLocal<double[]> IO = ThreadLocal.withInitial(() -> new double[1]);

  private volatile ConcurrentHashMap<String, Samples> samples = new ConcurrentHashMap<>();
  private volatile ConcurrentHashMap<String, LongAdder> counters = new ConcurrentHashMap<>();
  private volatile LongAdder requests = new LongAdder();
  private volatile long cpu0 = cpuNanos();
  private volatile long wall0 = System.nanoTime();
  private volatile long[] gc0 = gc();

  static long cpuNanos() {
    return ((com.sun.management.OperatingSystemMXBean) ManagementFactory.getOperatingSystemMXBean())
        .getProcessCpuTime();
  }

  /** Stop-the-world collections and their pause time (ms): every collector except the
   * concurrent ones, whose time is not a pause ("G1 Concurrent GC" counts concurrent cycles). */
  static long[] gc() {
    long count = 0;
    long ms = 0;
    for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
      if (b.getName().contains("Concurrent")) continue;
      count += Math.max(0, b.getCollectionCount());
      ms += Math.max(0, b.getCollectionTime());
    }
    return new long[] {count, ms};
  }

  static List<String> collectors() {
    List<String> out = new ArrayList<>();
    for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) out.add(b.getName());
    return out;
  }

  void record(String name, double ms) {
    samples.computeIfAbsent(name, k -> new Samples()).add(ms);
  }

  void count(String name) {
    counters.computeIfAbsent(name, k -> new LongAdder()).increment();
  }

  void request() {
    requests.increment();
  }

  <T> T timed(String name, Supplier<T> call) {
    long t = System.nanoTime();
    try {
      return call.get();
    } finally {
      double ms = (System.nanoTime() - t) / 1e6;
      record(name, ms);
      IO.get()[0] += ms;
    }
  }

  void reset() {
    samples = new ConcurrentHashMap<>();
    counters = new ConcurrentHashMap<>();
    requests = new LongAdder();
    cpu0 = cpuNanos();
    wall0 = System.nanoTime();
    gc0 = gc();
  }

  private static Double quantile(double[] sorted, double q) {
    if (sorted.length == 0) return null;
    int i = (int) Math.min(sorted.length - 1, Math.max(0, Math.ceil(q * sorted.length) - 1));
    return sorted[i];
  }

  Map<String, Object> snapshot() {
    long n = requests.sum();
    double cpuMs = (cpuNanos() - cpu0) / 1e6;
    long[] g = gc();
    Map<String, Object> out = new LinkedHashMap<>();
    out.put("role", "api");
    out.put("runtime", "java " + System.getProperty("java.version"));
    out.put("requests", n);
    out.put("wallMs", (System.nanoTime() - wall0) / 1e6);
    out.put("cpuMs", cpuMs);
    out.put("cpuMsPerRequest", n > 0 ? cpuMs / n : null);
    Map<String, Object> gcOut = new LinkedHashMap<>();
    gcOut.put("collections", g[0] - gc0[0]);
    gcOut.put("pauseMs", (double) (g[1] - gc0[1]));
    gcOut.put("collectors", collectors());
    out.put("gc", gcOut);
    Map<String, Long> c = new LinkedHashMap<>();
    counters.forEach((k, v) -> c.put(k, v.sum()));
    out.put("counters", c);
    Map<String, Object> timings = new LinkedHashMap<>();
    samples.forEach((name, s) -> {
      double[] v = s.sorted();
      if (v.length == 0) return;
      double total = 0;
      for (double x : v) total += x;
      Map<String, Object> t = new LinkedHashMap<>();
      t.put("count", v.length);
      t.put("mean", total / v.length);
      t.put("median", quantile(v, 0.5));
      t.put("p95", quantile(v, 0.95));
      t.put("p99", quantile(v, 0.99));
      timings.put(name, t);
    });
    out.put("timings", timings);
    return out;
  }
}

/** The handler's time for /read and /write, from Tomcat handing the request to the filter chain
 * until the response is written: Spring MVC's dispatch and JSON included, as a real service's
 * handler would be; ``own`` is that less the dependency calls. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TimingFilter implements Filter {
  private final Stats stats;

  TimingFilter(Stats stats) {
    this.stats = stats;
  }

  @Override
  public void doFilter(ServletRequest req, ServletResponse res, FilterChain chain)
      throws IOException, ServletException {
    String path = ((HttpServletRequest) req).getRequestURI();
    if (!path.equals("/read") && !path.equals("/write")) {
      chain.doFilter(req, res);
      return;
    }
    double[] io = Stats.IO.get();
    io[0] = 0;
    long t = System.nanoTime();
    chain.doFilter(req, res);
    double ms = (System.nanoTime() - t) / 1e6;
    if (((HttpServletResponse) res).getStatus() == 200) {
      stats.request();
      stats.record("handler", ms);
      stats.record("own", ms - io[0]);
    }
  }
}

@RestController
class Api {
  private final JdbcTemplate db;
  private final JdbcTemplate asyncDb;
  private final Stats stats;

  Api(JdbcTemplate jdbc, @Qualifier("asyncJdbc") JdbcTemplate asyncJdbc, Stats stats) {
    this.db = jdbc;
    this.asyncDb = asyncJdbc;
    this.stats = stats;
  }

  @GetMapping("/read")
  Map<String, Object> read(@RequestParam(defaultValue = "1") int id) {
    Long value = stats.timed("db_read", () ->
        db.query("SELECT value FROM items WHERE id = ?", rs -> rs.next() ? rs.getLong(1) : null, id));
    return Collections.singletonMap("value", value);
  }

  @PostMapping("/write")
  Map<String, Object> write(@RequestParam(defaultValue = "1") int id, @RequestParam(defaultValue = "0") String async) {
    boolean noFlush = "1".equals(async);
    stats.timed(noFlush ? "db_write_async" : "db_write", () ->
        (noFlush ? asyncDb : db).update("UPDATE items SET value = value + 1 WHERE id = ?", id));
    return Map.of("ok", true);
  }

  @GetMapping("/health")
  Map<String, Object> health() {
    return Map.of("ok", true);
  }

  @RequestMapping("/reset")
  Map<String, Object> reset() {
    stats.reset();
    return Map.of("ok", true);
  }

  @RequestMapping("/stats")
  Map<String, Object> stats() {
    return stats.snapshot();
  }
}
