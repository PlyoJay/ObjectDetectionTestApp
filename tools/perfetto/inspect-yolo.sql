-- Run each SELECT separately in Perfetto UI. These queries do not modify the trace.
-- Clock conversion: app boot_ns = trace ts - (clock_snapshot.ts-clock_value), clock_id=6.
SELECT ts, clock_id, clock_value, ts-clock_value AS trace_minus_boot_ns
FROM clock_snapshot WHERE clock_id=6;

-- Inspect ALL nonzero stats, including setup/parse/data-loss notices.
SELECT * FROM stats WHERE value != 0 ORDER BY severity,name,idx;
SELECT * FROM metadata WHERE name GLOB '*ftrace*' OR name GLOB '*clock*';
SELECT name,COUNT(*) AS events FROM ftrace_event
WHERE name IN ('sched_switch','sched_waking','cpu_frequency','cpu_idle') GROUP BY name;

-- Caller tid and trace timestamp/duration of existing synchronous app sections.
SELECT s.id,s.ts,s.dur,t.tid,t.name AS thread,s.name
FROM slice s JOIN thread_track tt ON s.track_id=tt.id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='com.samin.objectdetection'
AND (s.name GLOB 'YOLO.*' OR s.name GLOB 'CameraX.*' OR s.name='MLKit.InputImage')
ORDER BY s.ts;

-- All process threads scheduled during each invocation, clipped to slice boundaries.
-- Includes UI/ML Kit and other app work: it does not label every thread a YOLO worker.
WITH invokes AS (
  SELECT s.id,s.ts,s.dur,p.upid FROM slice s
  JOIN thread_track tt ON s.track_id=tt.id JOIN thread t USING(utid) JOIN process p USING(upid)
  WHERE p.name='com.samin.objectdetection' AND s.name='YOLO.Interpreter.run' AND s.dur>0
)
SELECT i.id AS invoke_id,t.tid,t.name,sc.cpu,COUNT(*) AS scheduled_slices,
 SUM(MIN(i.ts+i.dur,sc.ts+sc.dur)-MAX(i.ts,sc.ts))/1e6 AS running_ms
FROM invokes i JOIN thread t ON t.upid=i.upid JOIN sched sc ON sc.utid=t.utid
AND sc.dur>0 AND sc.ts<i.ts+i.dur AND sc.ts+sc.dur>i.ts
GROUP BY i.id,t.utid,sc.cpu ORDER BY i.id,running_ms DESC;

-- ART GC candidates for this process. Inspect stop-the-world/suspend slices and overlap
-- with the caller/worker states. Concurrent GC duration alone is not a pause duration.
SELECT s.ts,s.dur,t.tid,t.name,s.name
FROM slice s JOIN thread_track tt ON s.track_id=tt.id
JOIN thread t USING(utid) JOIN process p USING(upid)
WHERE p.name='com.samin.objectdetection'
AND (LOWER(s.name) GLOB '*gc*' OR LOWER(s.name) GLOB '*suspendall*') ORDER BY s.ts;
