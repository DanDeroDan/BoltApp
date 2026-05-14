package com.example.boltapp;

// Standard Android / Java imports
import android.content.Context;        // needed for the Geocoder (address lookup)
import android.location.Address;       // holds a decoded street address
import android.location.Geocoder;      // converts lat/lng to human-readable addresses

import org.json.JSONArray;   // JSON list
import org.json.JSONObject;  // JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // HTTP connection
import java.net.URL;                    // web address
import java.nio.charset.StandardCharsets; // UTF-8 encoding
import java.text.SimpleDateFormat;     // formats Date objects as strings
import java.util.ArrayList;            // resizable list
import java.util.Date;                 // current date/time
import java.util.List;                 // List interface
import java.util.Locale;               // used for number formatting and Geocoder
import java.util.UUID;                 // generates random unique IDs

// ══════════════════════════════════════════════════════════════════════════════
// DriveSessionManager
//
// This class manages ONE driving trip from start to finish.
// MapFragment creates one instance and calls onLocationUpdate() on every GPS fix.
// MainActivity calls onPhoneTouch() whenever the screen is tapped during a drive.
//
// It automatically detects when a drive STARTS (speed stays high for 3+ readings)
// and when it ENDS (speed stays low for 30+ seconds).
//
// When a drive ends it:
//   1. Saves the trip summary to the "driving_reports" table
//   2. Saves every GPS point to the "driving_points" table (for replay)
// ══════════════════════════════════════════════════════════════════════════════
public class DriveSessionManager {

    // ── Turso database connection info ──────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── Speed thresholds ────────────────────────────────────────────────
    // DRIVE_START_SPEED_MS: must exceed this speed (in m/s) to count as driving.
    //   2.8 m/s ≈ 10 km/h — low enough to catch city driving without false positives from fast walking.
    //   We use the RAW GPS speed (not smoothed) for detection, so this can be a lower value.
    private static final float DRIVE_START_SPEED_MS = 2.8f;

    // DRIVE_STOP_SPEED_MS: below this speed the stop-timer begins counting.
    //   0.5 m/s ≈ 2 km/h — very slow rolling; anything slower counts as stopped
    private static final float DRIVE_STOP_SPEED_MS  = 0.5f;

    // START_CONFIRMATIONS: how many consecutive above-threshold GPS readings
    //   we need before declaring a session has started.
    //   2 = two readings ≈ 2 seconds of confirmed driving speed.
    private static final int   START_CONFIRMATIONS  = 2;

    // STOP_DEBOUNCE_MS: how long (in ms) speed must stay low before we end the session.
    //   20 000 ms = 20 seconds — handles red lights without cutting the session short.
    // 'long' is needed here because System.currentTimeMillis() returns a very
    // large number (milliseconds since 1970) that doesn't fit in an int.
    private static final long  STOP_DEBOUNCE_MS     = 20000;

    // ── Context ─────────────────────────────────────────────────────────
    // Application context is used for the Geocoder.
    // We use getApplicationContext() to avoid memory leaks (never hold an Activity).
    private final Context appContext;

    // ── User / group data ────────────────────────────────────────────────
    private final String uid; // the logged-in user's ID (matches users.uid in the DB)
    private String gid;       // current group ID (may change if user switches groups)

    // ── Session state ────────────────────────────────────────────────────
    private boolean sessionActive = false; // true while recording a drive
    private String  sessionId;             // UUID for the current session
    private String  startTime;             // ISO timestamp when the drive started
    private double  startLat, startLng;    // GPS coordinates at trip start
    private double  lastLat,  lastLng;     // most recent GPS coordinates
    private float   currentSpeedKmh = 0;  // current smoothed speed (km/h)
    private long    sessionStartMs   = 0;  // System.currentTimeMillis() at session start
    // 'long' needed: currentTimeMillis() returns ~1.7 trillion ms (too big for int)

    // ── Live stats (built up during the drive) ──────────────────────────
    private float  maxSpeedKmh   = 0;   // highest speed seen this session
    private double speedSumKmh   = 0;   // sum of all recorded speeds (for average)
    private int    speedSamples  = 0;   // how many speed readings we've averaged
    private double distanceKm    = 0;   // total distance driven (Haversine formula)
    private int    phoneTouches  = 0;   // times the screen was touched while driving
    private int    pointSequence = 0;   // increments for each replay point recorded

    // ── Detection state ─────────────────────────────────────────────────
    private int  drivingConfirmations = 0; // consecutive fast GPS readings seen
    private long stoppedSinceMs       = 0; // when we first saw slow speed (0 = moving)

    // ── Position-based speed calculation ────────────────────────────────
    // Many Android devices don't populate location.getSpeed() reliably.
    // We compute our own speed from distance-between-fixes ÷ time-between-fixes.
    // This is the ONLY reliable way to detect speed on all devices.
    private long lastUpdateMs = 0; // System.currentTimeMillis() when the last fix arrived

    // ── Replay points ────────────────────────────────────────────────────
    // One ReplayPoint is stored for every GPS update during a session.
    // These get uploaded to the "driving_points" table when the session ends.
    private final List<ReplayPoint> points = new ArrayList<>();


    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC INNER CLASS — ReplayPoint
    // One GPS reading during a drive (used to re-draw the route later)
    // ══════════════════════════════════════════════════════════════════════
    public static class ReplayPoint {
        public final int    seq;       // ordering index (0, 1, 2, …)
        public final String timestamp; // "2025-01-15 14:30:01" — stored in DB
        public final double lat, lng;  // GPS coordinates
        public final float  speedKmh;  // speed at this moment (km/h)

        ReplayPoint(int seq, String timestamp, double lat, double lng, float speedKmh) {
            this.seq       = seq;
            this.timestamp = timestamp;
            this.lat       = lat;
            this.lng       = lng;
            this.speedKmh  = speedKmh;
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // LISTENER — lets MapFragment know when a session starts or ends
    //            (optional — only used to show/hide a "REC" indicator etc.)
    // ══════════════════════════════════════════════════════════════════════
    public interface SessionListener {
        void onSessionStarted();  // drive detected — session is now recording
        void onSessionEnded();    // drive over — data saved to DB
    }
    private SessionListener listener;


    // ══════════════════════════════════════════════════════════════════════
    // CONSTRUCTOR
    // ══════════════════════════════════════════════════════════════════════
    public DriveSessionManager(String uid, String gid, Context context) {
        this.uid        = uid;
        this.gid        = gid;
        this.appContext = context.getApplicationContext(); // safe — never leaks
    }

    // setGid — called when the user switches to a different group
    public void setGid(String gid) {
        this.gid = gid;
    }

    // setListener — optional; attach to receive start/end callbacks
    public void setListener(SessionListener listener) {
        this.listener = listener;
    }

    // isSessionActive — true while we're recording a drive
    public boolean isSessionActive() {
        return sessionActive;
    }

    // getCurrentSpeedKmh — the most recent smoothed speed in km/h
    public float getCurrentSpeedKmh() {
        return currentSpeedKmh;
    }


    // ══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINTS — called externally
    // ══════════════════════════════════════════════════════════════════════

    // onLocationUpdate — called by MapFragment on every GPS fix (on the main thread).
    // gpsSpeedMs is the RAW GPS speed in m/s (may be 0 if the device doesn't report speed).
    public void onLocationUpdate(double lat, double lng, float gpsSpeedMs) {

        // ── Calculate speed from position delta ──────────────────────────
        // This is the most reliable method across all Android devices.
        // GPS speed (location.getSpeed()) is often 0 or absent, especially when
        // the phone first acquires a fix. Position-based speed is always computable.
        float positionSpeedMs = 0f;
        long  nowMs           = System.currentTimeMillis();

        boolean hasPreviousPosition = (lastLat != 0 || lastLng != 0) && lastUpdateMs > 0;
        if (hasPreviousPosition) {
            double distanceKm  = haversineKm(lastLat, lastLng, lat, lng);
            double distanceM   = distanceKm * 1000.0;           // convert km → meters
            double elapsedSec  = (nowMs - lastUpdateMs) / 1000.0; // convert ms → seconds
            if (elapsedSec > 0.5 && elapsedSec < 60) {
                // Only use the result if the time gap is sensible (> 0.5s and < 60s).
                // A gap over 60s probably means the GPS lost signal — skip it.
                positionSpeedMs = (float)(distanceM / elapsedSec);
            }
        }

        // Use whichever speed is higher: GPS-reported or position-calculated.
        // If the GPS reports a valid speed AND position confirms it, we get the larger value.
        // If GPS speed is unreliable (0 or missing), position speed takes over.
        float effectiveSpeedMs = Math.max(gpsSpeedMs, positionSpeedMs);

        // Clamp to a reasonable maximum (80 m/s = 288 km/h) to ignore GPS noise spikes.
        if (effectiveSpeedMs > 80f) effectiveSpeedMs = 0f;

        // Convert m/s → km/h for storage and display
        currentSpeedKmh = effectiveSpeedMs * 3.6f;
        String timestamp = getCurrentTimestamp();

        if (!sessionActive) {
            // ── Not yet driving — wait for confirmation ──────────────────
            if (effectiveSpeedMs >= DRIVE_START_SPEED_MS) {
                drivingConfirmations++; // another fast reading — getting closer to start
                if (drivingConfirmations >= START_CONFIRMATIONS) {
                    startSession(lat, lng, timestamp); // confirmed driving → start the session
                }
            } else {
                drivingConfirmations = 0; // speed dropped — reset, must start over
            }

        } else {
            // ── Active session — record the point and watch for stop ─────
            // recordPoint uses lastLat/lastLng to compute distance — update them AFTER.
            recordPoint(lat, lng, currentSpeedKmh, timestamp);

            if (effectiveSpeedMs < DRIVE_STOP_SPEED_MS) {
                // Speed is very low — start the stop countdown
                if (stoppedSinceMs == 0) {
                    stoppedSinceMs = nowMs; // mark when we first went slow
                } else if (nowMs - stoppedSinceMs >= STOP_DEBOUNCE_MS) {
                    endSession(lat, lng, timestamp); // slow for too long → end the trip
                }
            } else {
                stoppedSinceMs = 0; // still moving — reset the stop countdown
            }
        }

        // Update "previous position" at the very end, AFTER recordPoint has used them.
        // recordPoint calls haversineKm(lastLat, lastLng, lat, lng) — if we updated
        // lastLat/lastLng first, it would compare the point to itself and return 0.
        lastUpdateMs = nowMs;
        lastLat      = lat;
        lastLng      = lng;
    }

    // onPhoneTouch — called by MainActivity whenever the user taps the screen.
    // We only count it if a driving session is currently active.
    public void onPhoneTouch() {
        if (sessionActive) {
            phoneTouches++;
        }
    }

    // forceEndSession — called when the app goes to the background (onPause).
    // We don't want to keep recording after the user leaves the app.
    public void forceEndSession() {
        if (sessionActive) {
            endSession(lastLat, lastLng, getCurrentTimestamp());
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // PRIVATE — session lifecycle
    // ══════════════════════════════════════════════════════════════════════

    // startSession — called when driving has been confirmed.
    // Resets all stats and begins recording.
    private void startSession(double lat, double lng, String timestamp) {
        sessionActive = true;
        sessionId     = UUID.randomUUID().toString(); // e.g. "a3f5c2d1-…"
        startTime     = timestamp;
        startLat      = lat;
        startLng      = lng;
        lastLat       = lat;
        lastLng       = lng;
        sessionStartMs = System.currentTimeMillis();

        // Reset all per-trip counters
        points.clear();
        pointSequence = 0;
        maxSpeedKmh   = 0;
        speedSumKmh   = 0;
        speedSamples  = 0;
        distanceKm    = 0;
        phoneTouches  = 0;
        stoppedSinceMs = 0;

        if (listener != null) listener.onSessionStarted();
    }

    // recordPoint — saves one GPS reading to the in-memory points list.
    // Also updates running stats (max speed, avg speed, total distance).
    private void recordPoint(double lat, double lng, float speedKmh, String timestamp) {
        // Update max speed
        if (speedKmh > maxSpeedKmh) maxSpeedKmh = speedKmh;

        // Update average speed (we'll divide at the end)
        speedSumKmh += speedKmh;
        speedSamples++;

        // Add distance from the last recorded position to this one
        if (pointSequence > 0) {
            distanceKm += haversineKm(lastLat, lastLng, lat, lng);
        }

        // Save this point for replay
        points.add(new ReplayPoint(pointSequence++, timestamp, lat, lng, speedKmh));
        // Note: lastLat and lastLng are updated by onLocationUpdate AFTER this method returns.
    }

    // endSession — called when the drive has ended (or force-ended).
    // Calculates final stats, then saves everything to the database.
    private void endSession(double endLat, double endLng, String endTime) {
        sessionActive  = false;
        stoppedSinceMs = 0;
        drivingConfirmations = 0;

        // Ignore trips shorter than 3 points (~3 seconds) — probably not a real drive
        if (points.size() < 3) {
            points.clear();
            sessionId = null;
            return;
        }

        // ── Calculate final stats ─────────────────────────────────────────
        float avgSpeedKmh  = speedSamples > 0 ? (float)(speedSumKmh / speedSamples) : 0;
        int   durationSecs = (int)((System.currentTimeMillis() - sessionStartMs) / 1000);

        // ── Capture everything into final local variables ──────────────────
        // Variables used inside a Thread must NOT change after being captured.
        final String capturedSessionId   = sessionId;
        final String capturedGid         = gid;
        final List<ReplayPoint> capturedPoints = new ArrayList<>(points);
        final String capturedStartTime   = startTime;
        final double capturedStartLat    = startLat;
        final double capturedStartLng    = startLng;
        final float  capturedMaxSpeed    = maxSpeedKmh;
        final float  capturedAvgSpeed    = avgSpeedKmh;
        final double capturedDistance    = distanceKm;
        final int    capturedPhoneTouches = phoneTouches;
        final int    capturedDuration    = durationSecs;
        final double capturedEndLat      = endLat;
        final double capturedEndLng      = endLng;
        final String capturedEndTime     = endTime;

        // Reset live data — new session can start immediately
        points.clear();
        sessionId = null;

        // ── Save to Turso in a background thread ───────────────────────────
        // Network is never allowed on the main thread.
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Try to convert lat/lng to a readable street address
                    String startLoc = getAddressText(capturedStartLat, capturedStartLng);
                    String endLoc   = getAddressText(capturedEndLat, capturedEndLng);

                    // Step 1: Insert the session summary into driving_reports
                    tursoQuery(
                        "INSERT INTO driving_reports " +
                        "(session_id, user_id, gid, driving, avg_speed, max_speed, " +
                        "phone_distraction, start_time, end_time, start_location, end_location, " +
                        "distance_km, duration_seconds) " +
                        "VALUES (?, ?, ?, 0, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        new Object[]{
                            capturedSessionId, uid, capturedGid,
                            capturedAvgSpeed, capturedMaxSpeed, capturedPhoneTouches,
                            capturedStartTime, capturedEndTime,
                            startLoc, endLoc,
                            capturedDistance, capturedDuration
                        }
                    );

                    // Step 2: Insert all replay points in batches of 50.
                    // One HTTP request per 50 points is MUCH faster than one per point.
                    int batchSize = 50;
                    for (int i = 0; i < capturedPoints.size(); i += batchSize) {
                        int end = Math.min(i + batchSize, capturedPoints.size());
                        List<ReplayPoint> batch = capturedPoints.subList(i, end);
                        insertPointsBatch(capturedSessionId, batch);
                    }

                } catch (Exception e) {
                    e.printStackTrace(); // log to Android console for debugging
                }
            }
        }).start();

        if (listener != null) listener.onSessionEnded();
    }


    // ══════════════════════════════════════════════════════════════════════
    // BATCH POINT INSERT
    // Sends up to 50 INSERT statements in ONE HTTP request.
    // This is critical for performance — 1800 points = 36 requests vs 1800.
    // ══════════════════════════════════════════════════════════════════════
    private void insertPointsBatch(String sessionId, List<ReplayPoint> batch) throws Exception {
        URL url = new URL(TURSO_URL + "/v2/pipeline");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + TURSO_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(15000);

        // Build a Turso pipeline with one "execute" per point in this batch
        JSONArray requests = new JSONArray();

        for (ReplayPoint point : batch) {
            // Build the args array for this INSERT
            JSONArray args = new JSONArray();
            args.put(makeTextArg(sessionId));
            args.put(makeIntArg(point.seq));
            args.put(makeTextArg(point.timestamp));
            args.put(makeTextArg(String.valueOf(point.lat)));
            args.put(makeTextArg(String.valueOf(point.lng)));
            args.put(makeTextArg(String.valueOf(point.speedKmh)));

            JSONObject stmt = new JSONObject();
            stmt.put("sql",
                "INSERT INTO driving_points " +
                "(session_id, sequence_num, timestamp, lat, lng, speed_kmh) " +
                "VALUES (?, ?, ?, ?, ?, ?)");
            stmt.put("args", args);

            JSONObject exec = new JSONObject();
            exec.put("type", "execute");
            exec.put("stmt", stmt);

            requests.put(exec);
        }

        // The pipeline must end with a "close" request
        JSONObject close = new JSONObject();
        close.put("type", "close");
        requests.put(close);

        JSONObject body = new JSONObject();
        body.put("requests", requests);

        // Send the batch
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.close();

        // Read + drain the response (we don't need the body, just check the code)
        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (is != null) {
            byte[] buf = new byte[4096];
            while (is.read(buf) != -1) { /* drain */ }
        }
        if (code >= 400) throw new Exception("Batch insert failed — HTTP " + code);
    }


    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    // makeTextArg / makeIntArg — build the JSON object Turso expects for one SQL argument.
    // e.g. { "type": "text", "value": "hello" }
    private JSONObject makeTextArg(String value) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("type", "text");
        obj.put("value", value);
        return obj;
    }

    private JSONObject makeIntArg(int value) throws Exception {
        JSONObject obj = new JSONObject();
        obj.put("type", "integer");
        obj.put("value", String.valueOf(value));
        return obj;
    }

    // getAddressText — converts lat/lng to a human-readable address using the device's
    // built-in Geocoder. If that fails (no network, no result), falls back to coordinates.
    // This runs in a background thread so it's safe to block briefly.
    private String getAddressText(double lat, double lng) {
        try {
            Geocoder geocoder = new Geocoder(appContext, Locale.getDefault());
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);

            if (addresses != null && !addresses.isEmpty()) {
                Address addr = addresses.get(0);
                StringBuilder sb = new StringBuilder();

                // Try to build "Street Name, City" format
                if (addr.getThoroughfare() != null) {
                    sb.append(addr.getThoroughfare()); // e.g. "Rothschild Blvd"
                }
                if (addr.getLocality() != null) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(addr.getLocality()); // e.g. "Tel Aviv"
                }

                if (sb.length() > 0) return sb.toString();
            }
        } catch (Exception ignored) {}

        // Fallback: show the raw coordinates to 5 decimal places (~1 meter precision)
        return String.format(Locale.US, "%.5f, %.5f", lat, lng);
    }

    // getCurrentTimestamp — returns the current UTC time as a DB-friendly string.
    // Format: "2025-01-15 14:30:01"
    private static String getCurrentTimestamp() {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
        sdf.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return sdf.format(new Date());
    }

    // haversineKm — calculates the straight-line distance (in km) between two GPS coordinates
    // using the Haversine formula, which accounts for Earth's curvature.
    private static double haversineKm(double lat1, double lng1, double lat2, double lng2) {
        final double EARTH_RADIUS_KM = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLng = Math.toRadians(lng2 - lng1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                 + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                 * Math.sin(dLng / 2) * Math.sin(dLng / 2);
        return EARTH_RADIUS_KM * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }


    // ══════════════════════════════════════════════════════════════════════
    // TURSO DATABASE QUERY (single-statement version)
    // Used for the driving_reports INSERT.
    // See DrivingReportsFragment for detailed comments on how this works.
    // ══════════════════════════════════════════════════════════════════════
    private JSONObject tursoQuery(String sql, Object[] args) throws Exception {
        URL url = new URL(TURSO_URL + "/v2/pipeline");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + TURSO_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);

        JSONArray argsArray = new JSONArray();
        if (args != null) {
            for (Object arg : args) {
                JSONObject argObj = new JSONObject();
                if (arg == null) {
                    argObj.put("type", "null");
                } else if (arg instanceof Integer) {
                    argObj.put("type", "integer");
                    argObj.put("value", String.valueOf(arg));
                } else if (arg instanceof Double || arg instanceof Float) {
                    // Store decimal numbers as text to preserve precision
                    argObj.put("type", "text");
                    argObj.put("value", String.valueOf(arg));
                } else {
                    argObj.put("type", "text");
                    argObj.put("value", String.valueOf(arg));
                }
                argsArray.put(argObj);
            }
        }

        JSONObject stmt = new JSONObject();
        stmt.put("sql", sql);
        stmt.put("args", argsArray);

        JSONObject executeRequest = new JSONObject();
        executeRequest.put("type", "execute");
        executeRequest.put("stmt", stmt);

        JSONObject closeRequest = new JSONObject();
        closeRequest.put("type", "close");

        JSONArray requestsList = new JSONArray();
        requestsList.put(executeRequest);
        requestsList.put(closeRequest);

        JSONObject body = new JSONObject();
        body.put("requests", requestsList);

        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream outputStream = conn.getOutputStream();
        outputStream.write(requestBytes);
        outputStream.close();

        int responseCode = conn.getResponseCode();
        InputStream inputStream = responseCode >= 400
                ? conn.getErrorStream()
                : conn.getInputStream();

        StringBuilder responseText = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            responseText.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }

        if (responseCode >= 400) {
            throw new Exception("Turso error " + responseCode + ": " + responseText);
        }

        return new JSONObject(responseText.toString());
    }
}
