package com.example.boltapp.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.boltapp.MainActivity;
import com.example.boltapp.R;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationCallback;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.example.boltapp.managers.DriveSessionManager;

/**
 * LocationTrackingService — Android Foreground Service
 *
 * Keeps running even when the app is in the background or closed.
 * Responsibilities:
 *   1. GPS fix every 2 seconds (smooth replay, good geofence accuracy)
 *   2. Uploads the user's position to the DB (UPDATE users ...)
 *   3. Checks geofences against saved group locations
 *   4. Announces the user's arrival to the group (INSERT location_arrivals)
 *   5. Polls for other members' arrivals every 20 s and fires notifications
 *   6. Owns the shared DriveSessionManager so drives survive app closure
 *
 * NOTE: We intentionally do NOT use activity recognition here. The service
 * needs to be bulletproof — activity recognition requires a runtime permission
 * that may not be granted when the service first starts, which would crash
 * onStartCommand. MapFragment already handles dynamic GPS interval switching
 * (1 s driving / 4 s walking / 10 s still) while the app is in the foreground.
 * The service uses a fixed 2 s interval as a reliable middle ground.
 *
 * Started by MainActivity after login; stopped by SettingsActivity on logout.
 * Declared in AndroidManifest.xml with foregroundServiceType="location".
 */
public class LocationTrackingService extends Service {

    /**
     * Shared drive session — static so MapFragment can access the same instance.
     * Service creates it on start and ends it on stop.
     */
    public static DriveSessionManager driveSession = null;

    private static final String TAG = "BoltApp/Service";

    // ── Notification channels ─────────────────────────────────────────────
    private static final String CHANNEL_TRACKING    = "bolt_tracking_channel";
    private static final String CHANNEL_ARRIVALS    = "bolt_arrival_channel";
    private static final int    NOTIF_ID_FOREGROUND = 1;
    private static final int    NOTIF_ID_BASE       = 9000;

    // ── Turso database ────────────────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── Timing ────────────────────────────────────────────────────────────
    /**
     * GPS interval used by the service.
     * 2 s is a reliable middle ground:
     *   - At 60 km/h → ~33 m between points (smooth replay, no teleporting)
     *   - Much better than the old 10 s (167 m per point)
     *   - Safe for a foreground service — won't trigger battery optimisation
     */
    private static final long GPS_INTERVAL_MS     = 2_000;
    private static final long GPS_MIN_INTERVAL_MS = 1_000;

    private static final long LOCATIONS_REFRESH_MS = 15_000;
    private static final long ARRIVAL_POLL_MS      = 20_000;

    // ── Runtime state ─────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedClient;
    private LocationCallback            locationCallback;
    private Handler                     handler;

    private final List<LocationData> groupLocations       = new ArrayList<>();
    private final Set<String>        activelyInsideLocIds = new HashSet<>();
    private       Set<String>        mutedLocationIds     = new HashSet<>();
    private       Set<String>        hiddenAnnounceLocIds = new HashSet<>();

    private String lastArrivalCheckTime  = "1970-01-01 00:00:00";
    private long   lastLocationsRefreshMs = 0;
    private long   lastArrivalPollMs      = 0;

    // ── User session ──────────────────────────────────────────────────────
    private String uid;
    private String displayName;
    private String gid;

    // ─────────────────────────────────────────────────────────────────────
    // SERVICE LIFECYCLE
    // ─────────────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        handler     = new Handler(Looper.getMainLooper());
        fusedClient = LocationServices.getFusedLocationProviderClient(this);

        createNotificationChannels();
        loadUserSession();
        loadMutedLocations();

        if (uid != null) {
            driveSession = new DriveSessionManager(uid, gid, this);
        }
    }

    /**
     * Called every time startForegroundService() is invoked.
     * Returns START_STICKY so Android restarts us if we are killed.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Re-read session (uid/gid may have changed e.g. after group switch)
        loadUserSession();
        loadMutedLocations();

        if (driveSession == null && uid != null) {
            driveSession = new DriveSessionManager(uid, gid, this);
        }

        // Must promote to foreground within 5 s on Android 12+
        startForeground(NOTIF_ID_FOREGROUND, buildForegroundNotification());

        startLocationUpdates();
        handler.post(pollingRunnable);

        Log.d(TAG, "Service started — uid=" + uid + "  gid=" + gid);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        // End drive session cleanly before GPS stops
        if (driveSession != null) {
            driveSession.forceEndSession();
            driveSession = null;
        }

        if (fusedClient != null && locationCallback != null) {
            fusedClient.removeLocationUpdates(locationCallback);
        }
        handler.removeCallbacksAndMessages(null);
        Log.d(TAG, "Service stopped");
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    // ─────────────────────────────────────────────────────────────────────
    // FOREGROUND NOTIFICATION
    // ─────────────────────────────────────────────────────────────────────

    private void createNotificationChannels() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;

        // Silent persistent notification (shows the app is tracking)
        NotificationChannel tracking = new NotificationChannel(
                CHANNEL_TRACKING, "Location Tracking", NotificationManager.IMPORTANCE_LOW);
        tracking.setDescription("Keeps BoltApp running in the background");
        tracking.setShowBadge(false);
        nm.createNotificationChannel(tracking);

        // High-priority arrival alerts
        NotificationChannel arrivals = new NotificationChannel(
                CHANNEL_ARRIVALS, "Arrival Alerts", NotificationManager.IMPORTANCE_HIGH);
        arrivals.setDescription("Notifies you when group members arrive at saved locations");
        nm.createNotificationChannel(arrivals);
    }

    private Notification buildForegroundNotification() {
        Intent openApp = new Intent(this, MainActivity.class);
        openApp.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, openApp,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_TRACKING)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("BoltApp is active")
                .setContentText("Tracking your location in the background")
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setOngoing(true)
                .setSilent(true)
                .setContentIntent(pi)
                .build();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GPS — fixed 2-second interval
    // ─────────────────────────────────────────────────────────────────────

    @SuppressWarnings("MissingPermission") // permissions declared in AndroidManifest.xml
    private void startLocationUpdates() {
        LocationRequest request = new LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY, GPS_INTERVAL_MS)
                .setMinUpdateIntervalMillis(GPS_MIN_INTERVAL_MS)
                .build();

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(LocationResult result) {
                if (result == null) return;
                android.location.Location loc = result.getLastLocation();
                if (loc == null) return;

                double lat = loc.getLatitude();
                double lng = loc.getLongitude();
                float  spd = loc.getSpeed(); // m/s

                uploadMyLocation(lat, lng, spd);
                checkGeofences(lat, lng);

                // Feed the shared drive session so recording continues in the background
                if (driveSession != null) {
                    driveSession.onLocationUpdate(lat, lng, spd);
                    driveSession.tickWatchdog();
                }
            }
        };

        try {
            fusedClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
            Log.d(TAG, "GPS started at " + GPS_INTERVAL_MS + " ms interval");
        } catch (SecurityException e) {
            Log.e(TAG, "Location permission not granted", e);
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // POLLING LOOP
    // ─────────────────────────────────────────────────────────────────────

    private final Runnable pollingRunnable = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();

            if (now - lastLocationsRefreshMs >= LOCATIONS_REFRESH_MS) {
                lastLocationsRefreshMs = now;
                refreshGroupLocations();
            }
            if (now - lastArrivalPollMs >= ARRIVAL_POLL_MS) {
                lastArrivalPollMs = now;
                checkGroupArrivals();
            }

            handler.postDelayed(this, 5_000);
        }
    };

    // ─────────────────────────────────────────────────────────────────────
    // DATABASE
    // ─────────────────────────────────────────────────────────────────────

    private void uploadMyLocation(double lat, double lng, float speedMs) {
        if (uid == null) return;
        new Thread(() -> {
            try {
                tursoQuery(
                        "UPDATE users SET last_lat=?, last_lng=?, last_seen=CURRENT_TIMESTAMP, last_speed=? WHERE uid=?",
                        new Object[]{ String.valueOf(lat), String.valueOf(lng), String.valueOf(speedMs), uid });
            } catch (Exception e) {
                Log.w(TAG, "uploadMyLocation: " + e.getMessage());
            }
        }).start();
    }

    private void refreshGroupLocations() {
        if (gid == null) { loadUserSession(); if (gid == null) return; }
        new Thread(() -> {
            try {
                JSONObject result = tursoQuery(
                        "SELECT location_id, name, lat, lng, radius FROM group_locations WHERE gid=?",
                        new Object[]{ gid });

                JSONArray rows = result
                        .getJSONArray("results").getJSONObject(0)
                        .getJSONObject("response").getJSONObject("result")
                        .getJSONArray("rows");

                List<LocationData> fresh = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray row = rows.getJSONArray(i);
                    fresh.add(new LocationData(
                            row.getJSONObject(0).getString("value"),
                            row.getJSONObject(1).getString("value"),
                            Double.parseDouble(row.getJSONObject(2).getString("value")),
                            Double.parseDouble(row.getJSONObject(3).getString("value")),
                            Double.parseDouble(row.getJSONObject(4).getString("value"))));
                }

                handler.post(() -> {
                    groupLocations.clear();
                    groupLocations.addAll(fresh);
                    Set<String> ids = new HashSet<>();
                    for (LocationData ld : fresh) ids.add(ld.locId);
                    activelyInsideLocIds.retainAll(ids);
                });
            } catch (Exception e) {
                Log.w(TAG, "refreshGroupLocations: " + e.getMessage());
            }
        }).start();
    }

    // ─────────────────────────────────────────────────────────────────────
    // GEOFENCING
    // ─────────────────────────────────────────────────────────────────────

    private void checkGeofences(double userLat, double userLng) {
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("notifications_enabled", true)) return;

        float[] dist = new float[1];
        for (LocationData loc : groupLocations) {
            android.location.Location.distanceBetween(userLat, userLng, loc.lat, loc.lng, dist);
            if (dist[0] <= loc.radius) {
                if (!activelyInsideLocIds.contains(loc.locId)) {
                    activelyInsideLocIds.add(loc.locId);
                    announceMyArrival(loc.locId);
                    if (!mutedLocationIds.contains(loc.locId)) {
                        sendSelfArrivalNotification(loc.name, loc.locId);
                    }
                }
            } else {
                activelyInsideLocIds.remove(loc.locId);
            }
        }
    }

    private void announceMyArrival(String locId) {
        if (uid == null || gid == null) return;
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("announce_arrivals", true)) return;
        if (hiddenAnnounceLocIds.contains(locId)) return;

        new Thread(() -> {
            try {
                tursoQuery(
                        "INSERT OR REPLACE INTO location_arrivals " +
                        "(location_id, gid, uid, display_name, arrived_at) VALUES (?,?,?,?,datetime('now'))",
                        new Object[]{ locId, gid, uid, displayName });
            } catch (Exception e) {
                Log.w(TAG, "announceMyArrival: " + e.getMessage());
            }
        }).start();
    }

    private void sendSelfArrivalNotification(String locationName, String locId) {
        try {
            NotificationManagerCompat.from(this).notify(
                    NOTIF_ID_BASE + locId.hashCode(),
                    new NotificationCompat.Builder(this, CHANNEL_ARRIVALS)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle("Arrived at " + locationName)
                            .setContentText((displayName != null ? displayName : "You")
                                    + " arrived at " + locationName)
                            .setPriority(NotificationCompat.PRIORITY_HIGH)
                            .setAutoCancel(true).build());
        } catch (SecurityException ignored) {}
    }

    private void checkGroupArrivals() {
        if (gid == null || uid == null) return;
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        if (!prefs.getBoolean("notifications_enabled", true)) return;

        final String since = lastArrivalCheckTime;
        lastArrivalCheckTime = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date());

        new Thread(() -> {
            try {
                JSONObject result = tursoQuery(
                        "SELECT la.location_id, la.display_name, gl.name, la.uid " +
                        "FROM location_arrivals la " +
                        "JOIN group_locations gl ON la.location_id = gl.location_id " +
                        "WHERE la.gid=? AND la.uid!=? AND la.arrived_at>?",
                        new Object[]{ gid, uid, since });

                JSONArray rows = result
                        .getJSONArray("results").getJSONObject(0)
                        .getJSONObject("response").getJSONObject("result")
                        .getJSONArray("rows");

                loadMutedLocations();
                for (int i = 0; i < rows.length(); i++) {
                    JSONArray row      = rows.getJSONArray(i);
                    String locId       = row.getJSONObject(0).getString("value");
                    String arrivedName = row.getJSONObject(1).optString("value", "Someone");
                    String locName     = row.getJSONObject(2).getString("value");
                    String arrivedUid  = row.getJSONObject(3).optString("value", "");
                    if (!mutedLocationIds.contains(locId)) {
                        sendOtherArrivalNotification(arrivedName, locName, locId, arrivedUid);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "checkGroupArrivals: " + e.getMessage());
            }
        }).start();
    }

    private void sendOtherArrivalNotification(
            String userName, String locationName, String locId, String arrivedUid) {
        // Build a PendingIntent that opens the app focused on the arriving member
        Intent tapIntent = new Intent(this, MainActivity.class);
        tapIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        tapIntent.putExtra("focus_uid", arrivedUid);
        int reqCode = Math.abs((locId + arrivedUid).hashCode());
        PendingIntent pi = PendingIntent.getActivity(this, reqCode, tapIntent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        try {
            NotificationManagerCompat.from(this).notify(
                    NOTIF_ID_BASE + locId.hashCode() + userName.hashCode(),
                    new NotificationCompat.Builder(this, CHANNEL_ARRIVALS)
                            .setSmallIcon(R.mipmap.ic_launcher)
                            .setContentTitle(userName + " arrived")
                            .setContentText(userName + " arrived at " + locationName)
                            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                            .setAutoCancel(true)
                            .setContentIntent(pi)
                            .build());
        } catch (SecurityException ignored) {}
    }

    // ─────────────────────────────────────────────────────────────────────
    // HELPERS
    // ─────────────────────────────────────────────────────────────────────

    private void loadUserSession() {
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        uid         = prefs.getString("uid", null);
        displayName = prefs.getString("display_name", "");
        String newGid = prefs.getString("current_gid", null);
        if (driveSession != null && newGid != null && !newGid.equals(gid)) {
            driveSession.setGid(newGid);
        }
        gid = newGid;
    }

    private void loadMutedLocations() {
        if (uid == null) return;
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        Set<String> recv = prefs.getStringSet("muted_locs_" + uid, null);
        mutedLocationIds = recv != null ? new HashSet<>(recv) : new HashSet<>();
        Set<String> send = prefs.getStringSet("hidden_announce_" + uid, null);
        hiddenAnnounceLocIds = send != null ? new HashSet<>(send) : new HashSet<>();
    }

    // ─────────────────────────────────────────────────────────────────────
    // TURSO QUERY
    // ─────────────────────────────────────────────────────────────────────

    private JSONObject tursoQuery(String sql, Object[] args) throws Exception {
        URL url = new URL(TURSO_URL + "/v2/pipeline");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + TURSO_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(8_000);
        conn.setReadTimeout(8_000);

        JSONArray argArray = new JSONArray();
        if (args != null) {
            for (Object arg : args) {
                JSONObject p = new JSONObject();
                if (arg == null) { p.put("type", "null"); p.put("value", JSONObject.NULL); }
                else             { p.put("type", "text"); p.put("value", String.valueOf(arg)); }
                argArray.put(p);
            }
        }

        JSONObject stmt = new JSONObject();
        stmt.put("sql", sql);
        stmt.put("args", argArray);
        stmt.put("named_args", new JSONArray());

        JSONObject requestBody = new JSONObject();
        requestBody.put("requests", new JSONArray()
                .put(new JSONObject().put("type", "execute").put("stmt", stmt))
                .put(new JSONObject().put("type", "close")));

        byte[] bodyBytes = requestBody.toString().getBytes("UTF-8");
        conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
        try (OutputStream os = conn.getOutputStream()) { os.write(bodyBytes); }

        if (conn.getResponseCode() >= 400) {
            throw new Exception("HTTP " + conn.getResponseCode() + " from Turso");
        }

        StringBuilder sb = new StringBuilder();
        java.io.InputStream is = conn.getInputStream();
        int b; while ((b = is.read()) != -1) sb.append((char) b);
        is.close();

        JSONObject parsed = new JSONObject(sb.toString());
        JSONArray results = parsed.optJSONArray("results");
        if (results != null && results.length() > 0) {
            JSONObject first = results.getJSONObject(0);
            if ("error".equals(first.optString("type"))) {
                String msg = first.optJSONObject("error") != null
                        ? first.getJSONObject("error").optString("message") : "unknown";
                throw new Exception("Turso error: " + msg + " | SQL: " + sql);
            }
        }
        return parsed;
    }

    // ─────────────────────────────────────────────────────────────────────
    // MODEL
    // ─────────────────────────────────────────────────────────────────────

    private static class LocationData {
        final String locId, name;
        final double lat, lng, radius;

        LocationData(String locId, String name, double lat, double lng, double radius) {
            this.locId = locId; this.name = name;
            this.lat = lat; this.lng = lng; this.radius = radius;
        }
    }
}
