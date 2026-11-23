# pg-mixed on a busy laptop (22 Nov 2026)

Round 3's first attempt started at 14:32 on 22 Nov 2026, after a day's use of the laptop. pg-mixed's
calibration read a higher service CPU per request than rounds 1 and 2 (0.210 ms against 0.163 and
0.168), PostgreSQL busier at rest (16.0 ms/s against 13), and four disturbed passes, so the attempt
was stopped at 15:12 and restarted that night.

The decision not to count it was made on those conditions, before its result was read. Its finished
run measured 3,902 req/s against 4,201 predicted (+7.7%), better than the -31.1% of the run that
counts, so leaving it out did not flatter the results. The cause of the slowdown is not proven: the
restart's pg-mixed calibration was slow too, with the browser and audio closed.
