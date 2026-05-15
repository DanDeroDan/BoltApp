package com.example.boltapp;

import android.Manifest;                // gives us the names of Android permissions
import android.annotation.SuppressLint; // suppresses certain warnings from the compiler
import android.app.AlertDialog;        // the standard dialog box
import android.app.PendingIntent;       // a wrapped Intent that another app/service can trigger later
import android.content.BroadcastReceiver; // receives system-wide broadcast messages
import android.content.Context;         // gives access to app resources and services
import android.app.NotificationChannel;    // defines a notification category (API 26+)
import android.app.NotificationManager;    // system service that posts notifications
import android.location.Location;          // utility class used for distanceBetween()
import android.content.Intent;          // used to communicate between Android components
import android.content.IntentFilter;   // describes which broadcast messages to receive
import android.content.pm.PackageManager; // used to check if a permission is granted
import android.graphics.Bitmap;        // represents an image in memory
import android.graphics.Canvas;        // lets us draw on a Bitmap
import android.graphics.Color;         // parses "#RRGGBB" strings and holds color constants
import android.graphics.Paint;         // defines how text/shapes are drawn (size, color, etc.)
import android.graphics.Path;          // lets us draw arbitrary shapes (used for the pin triangle)
import android.graphics.PorterDuff;    // defines how colors are blended together
import android.graphics.Typeface;      // bold / italic text style
import android.graphics.drawable.ColorDrawable;    // a solid-color Drawable (used for transparent dialog bg)
import android.graphics.drawable.Drawable;         // the base class of things you can draw
import android.graphics.drawable.GradientDrawable; // draws rounded rectangles and ovals
import android.os.Build;              // lets us check the Android version at runtime
import android.os.Bundle;             // holds saved state / data passed between screens
import android.os.Handler;            // schedules code to run on the main thread
import android.os.Looper;             // provides the main thread's message loop
import android.text.InputType;        // constants for keyboard input types
import android.view.Gravity;          // alignment constants (END, CENTER, etc.)
import android.view.LayoutInflater;   // builds Views from XML layout files
import android.view.View;             // the base class of every UI element
import android.view.ViewGroup;        // a container that holds other views
import android.widget.EditText;       // a text input field
import android.widget.LinearLayout;   // a view that stacks children in a row or column
import android.widget.SeekBar;        // a draggable progress slider
import android.widget.TextView;       // displays text
import android.widget.Toast;          // a small popup message

import androidx.activity.result.ActivityResultLauncher;           // handles permission request results
import androidx.activity.result.contract.ActivityResultContracts; // contract for requesting permissions
import androidx.annotation.NonNull;    // marks that a parameter can never be null
import androidx.annotation.Nullable;   // marks that something can be null
import androidx.core.content.ContextCompat;        // backward-compatible version of Context helpers
import androidx.core.graphics.drawable.DrawableCompat; // backward-compatible Drawable tinting

import com.google.android.gms.location.ActivityRecognition;          // Google's activity recognition service
import com.google.android.gms.location.ActivityRecognitionClient;    // client for requesting activity updates
import com.google.android.gms.location.ActivityTransition;           // describes one activity transition to watch
import com.google.android.gms.location.ActivityTransitionEvent;      // one detected activity transition
import com.google.android.gms.location.ActivityTransitionRequest;    // a list of transitions to monitor
import com.google.android.gms.location.ActivityTransitionResult;     // the result containing transition events
import com.google.android.gms.location.DetectedActivity;             // constants for activity types (WALKING, DRIVING, etc.)
import com.google.android.gms.location.FusedLocationProviderClient;  // Google's GPS client (battery-efficient)
import com.google.android.gms.location.LocationCallback;             // called every time we get a new GPS location
import com.google.android.gms.location.LocationRequest;             // describes how often to get GPS updates
import com.google.android.gms.location.LocationResult;              // holds one or more GPS locations
import com.google.android.gms.location.LocationServices;            // entry point to Google location services
import com.google.android.gms.location.Priority;                    // defines GPS accuracy level
import com.google.android.gms.maps.CameraUpdateFactory;             // creates camera movements on the map
import com.google.android.gms.maps.GoogleMap;                       // the main Google Maps object
import com.google.android.gms.maps.model.MapStyleOptions;           // loads a custom visual style for the map
import com.google.android.gms.maps.OnMapReadyCallback;              // callback fired when the map is ready to use
import com.google.android.gms.maps.SupportMapFragment;              // a Fragment that shows a Google Map
import com.google.android.gms.maps.model.BitmapDescriptor;          // wraps a Bitmap for use as a map marker icon
import com.google.android.gms.maps.model.BitmapDescriptorFactory;   // creates BitmapDescriptors from images
import com.google.android.gms.maps.model.Circle;                    // a circle drawn on the map
import com.google.android.gms.maps.model.CircleOptions;             // settings used when drawing a Circle
import com.google.android.gms.maps.model.LatLng;                    // a latitude + longitude coordinate pair
import com.google.android.gms.maps.model.Marker;                    // a pin on the map
import com.google.android.gms.maps.model.MarkerOptions;             // settings used when creating a new Marker

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset
import java.util.ArrayList;            // a resizable list
import java.util.HashMap;              // a key-value map
import java.util.HashSet;
import java.util.List;                 // the List interface
import java.util.Map;                  // the Map interface
import java.util.Set;
import java.util.UUID;                 // generates unique IDs for saved locations

// ══════════════════════════════════════════════════════════════════════════════
// MapFragment
//
// This is the main map screen. It:
//   1. Shows a Google Map with a dark custom style
//   2. Tracks the user's GPS location and uploads it to the database
//   3. Detects whether the user is still / walking / driving via speed + sensors
//   4. Shows other group members as colored pins on the map (refreshed frequently)
//   5. Adjusts GPS polling frequency based on motion (driving = fast, still = slow)
// ══════════════════════════════════════════════════════════════════════════════
public class MapFragment extends SupportMapFragment implements OnMapReadyCallback {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // How often (in ms) we refresh other members' positions on the map.
    // 40ms is very fast — this just moves the existing pins smoothly (no DB call on every tick).
    // The actual DB fetch is triggered by GPS updates, not this timer.
    private static final int  MEMBER_REFRESH_MS      = 40;
    private static final long LOCATION_SYNC_MS       = 15_000; // re-sync saved pins every 15 s
    private long              lastLocationSyncMs      = 0;

    // The broadcast action name for activity transition events.
    // This string must match exactly between sender and receiver.
    private static final String TRANSITION_ACTION = "com.example.boltapp.ACTIVITY_TRANSITION";

    // ── Map & Location ──────────────────────────────────────────────────
    private GoogleMap mMap;                             // the Google Map object
    private FusedLocationProviderClient fusedLocationClient; // handles GPS location updates
    private LocationCallback locationCallback;           // called every time we get a new GPS fix
    private Marker userMarker;                           // the pin on the map representing the user

    // ── Speed smoothing ─────────────────────────────────────────────────
    // Raw GPS speed bounces around even when driving at constant speed.
    // We keep the last 5 readings and average them to get a stable value.
    private final float[] speedBuffer    = new float[5]; // ring buffer of last 5 speeds (m/s)
    private int           speedBufIndex  = 0;            // where to write next in the ring
    private boolean       speedBufFull   = false;        // becomes true once we have 5 readings

    // ── Driving session manager ─────────────────────────────────────────
    // Tracks the current drive (start, points, end) and saves to the database.
    private DriveSessionManager sessionManager;

    // ── Activity Recognition ────────────────────────────────────────────
    // Google can detect whether the phone is in a moving vehicle, walking, or still.
    private ActivityRecognitionClient activityRecognitionClient; // the service client
    private BroadcastReceiver activityTransitionReceiver;        // listens for activity change events
    private PendingIntent activityTransitionPendingIntent;       // delivered to the activity service

    // ── Motion state tracking ───────────────────────────────────────────
    // We use two sources: GPS speed (primary) + activity recognition (secondary).
    // "Debounce" means we wait 5 seconds before declaring the user is "still"
    // to avoid the icon flickering when they briefly stop at a red light.
    private String lastActiveStatus   = "still"; // last non-still status ("walking" or "driving")
    private String lastLocationStatus = "still"; // status used to decide GPS polling speed
    private String lastRenderedStatus = null;    // the status currently drawn on the user's marker
    private long lastActiveTimeMs     = 0;       // timestamp (ms) when we last saw movement
    // NOTE: 'long' is needed here because System.currentTimeMillis() returns a very large number
    // (milliseconds since 1970) that doesn't fit in an 'int'.
    private long lastLocationUpdateMs = 0;       // timestamp when GPS last fired

    // How long (in ms) we wait before declaring the user "still" after speed drops to 0
    private static final int STILL_DEBOUNCE_MS = 5000; // 5 seconds

    // ── Marker icon cache ───────────────────────────────────────────────
    // Building a marker icon from a vector drawable is slow.
    // We build each icon once and reuse it every time — one per status.
    private final Map<String, BitmapDescriptor> userIconCache = new HashMap<>();

    // ── Other members' markers ──────────────────────────────────────────
    // Key = member's uid, Value = their Marker on the map
    private final Map<String, Marker> memberMarkers = new HashMap<>();

    // ── Saved group location pins ───────────────────────────────────────
    // Key = location_id from the database
    private final Map<String, Marker> locationMarkers = new HashMap<>(); // the circular pin
    private final Map<String, Circle> locationCircles = new HashMap<>(); // the radius circle
    private final Map<Integer, BitmapDescriptor> locationPinCache = new HashMap<>(); // keyed by color
    private final Map<String, LocationData> locationDataMap = new HashMap<>(); // locId -> data
    // Geofence / notification state
    private final Set<String> activelyInsideLocIds = new HashSet<>(); // currently inside these radii
    private Set<String>       mutedLocationIds      = new HashSet<>(); // dont receive notifs for these
    private Set<String>       hiddenAnnounceLocIds  = new HashSet<>(); // dont announce MY arrival here
    private static final String NOTIF_CHANNEL_ID    = "bolt_arrival_channel";
    private static final int    NOTIF_ID_BASE       = 9000;
    private static final long   ARRIVAL_SYNC_MS     = 20_000; // poll others arrivals every 20s
    private long                lastArrivalSyncMs   = 0;
    private String              lastArrivalCheckTime = "1970-01-01 00:00:00";


    // ── Auto-refresh ────────────────────────────────────────────────────
    private Handler refreshHandler;   // runs code on the main thread
    private Runnable refreshRunnable; // the repeating piece of code

    // ── Data passed in by MainActivity ─────────────────────────────────
    private String uid, displayName, gid, groupName;
    private boolean cameraMovedToUser = false; // true after we've zoomed the camera to the user once

    // ── Permission launcher ─────────────────────────────────────────────
    // This handles the result of asking the user for GPS / activity permissions.
    private final ActivityResultLauncher<String[]> locationPermissionRequest =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    result -> {
                        // Check if we got fine or coarse location permission
                        Boolean fineGranted   = result.getOrDefault(Manifest.permission.ACCESS_FINE_LOCATION, false);
                        Boolean coarseGranted = result.getOrDefault(Manifest.permission.ACCESS_COARSE_LOCATION, false);

                        boolean hasLocation = (fineGranted != null && fineGranted)
                                           || (coarseGranted != null && coarseGranted);

                        if (hasLocation) {
                            enableMyLocation();     // start GPS tracking
                            startMemberRefresh();   // start refreshing other members' positions
                        }

                        // Check if we got activity recognition permission (Android 10+)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            Boolean activityGranted = result.getOrDefault(
                                    Manifest.permission.ACTIVITY_RECOGNITION, false);
                            if (activityGranted != null && activityGranted) {
                                registerActivityTransitions();
                            }
                        } else {
                            // On Android 9 and below, no special permission needed for activity recognition
                            registerActivityTransitions();
                        }
                    }
            );


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called when the fragment is first created.
    // We read our arguments, set up the location client, and start loading the map.
    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);

        // Read the data that MainActivity passed to us
        if (getArguments() != null) {
            uid         = getArguments().getString("uid");
            displayName = getArguments().getString("display_name");
            gid         = getArguments().getString("gid");
            groupName   = getArguments().getString("group_name");
        }

        // Set up the GPS client
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(requireActivity());

        // Set up the activity recognition client
        activityRecognitionClient = ActivityRecognition.getClient(requireActivity());

        // A Handler that will let us schedule work on the main thread
        refreshHandler = new Handler(Looper.getMainLooper());

        // Create the session manager — it will auto-detect drives and save them
        if (uid != null && gid != null) {
            sessionManager = new DriveSessionManager(uid, gid, requireContext());
        }

        // Tell the SupportMapFragment to call onMapReady() when the map is loaded
        getMapAsync(this);
    }

    // onMapReady — called automatically when the Google Map has finished loading.
    @Override
    public void onMapReady(@NonNull GoogleMap googleMap) {
        mMap = googleMap; // save a reference to the map

        // Apply our custom dark theme (defined in res/raw/map_style.json)
        try {
            MapStyleOptions style = MapStyleOptions.loadRawResourceStyle(
                    requireContext(), R.raw.map_style);
            mMap.setMapStyle(style);
        } catch (Exception ignored) {}

        mMap.getUiSettings().setZoomControlsEnabled(true); // show + / - zoom buttons

        // Long-press anywhere on the map to save a new group location
        mMap.setOnMapLongClickListener(new GoogleMap.OnMapLongClickListener() {
            @Override
            public void onMapLongClick(@NonNull LatLng latLng) {
                showAddLocationDialog(latLng);
            }
        });

        // Double-tap a saved location pin to open the edit dialog
        // Tap a saved location pin → show a styled info window with the name.
        // Tap the info window itself → open the edit dialog.
        mMap.setOnMarkerClickListener(new GoogleMap.OnMarkerClickListener() {
            @Override
            public boolean onMarkerClick(@NonNull Marker marker) {
                Object tag = marker.getTag();
                if (!(tag instanceof String)) return false; // member pin — default behaviour
                // Show the info window (name popup); returning false lets Maps handle it
                marker.showInfoWindow();
                return true;
            }
        });

        // Custom dark-themed info window for saved location pins
        mMap.setInfoWindowAdapter(new GoogleMap.InfoWindowAdapter() {
            @Override
            public android.view.View getInfoWindow(@NonNull Marker marker) {
                // Only style our location pins (they carry a String tag)
                if (!(marker.getTag() instanceof String)) return null;

                android.widget.LinearLayout bubble = new android.widget.LinearLayout(requireContext());
                bubble.setOrientation(android.widget.LinearLayout.VERTICAL);
                bubble.setPadding(dpToPx(14), dpToPx(10), dpToPx(14), dpToPx(10));
                android.graphics.drawable.GradientDrawable bg =
                    new android.graphics.drawable.GradientDrawable();
                bg.setColor(0xFF161B22);
                bg.setCornerRadius(dpToPx(10));
                bg.setStroke(dpToPx(1), 0xFF30363D);
                bubble.setBackground(bg);

                android.widget.TextView nameTv = new android.widget.TextView(requireContext());
                nameTv.setText(marker.getTitle());
                nameTv.setTextColor(0xFFFFFFFF);
                nameTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
                nameTv.setTypeface(null, android.graphics.Typeface.BOLD);
                bubble.addView(nameTv);

                android.widget.TextView hint = new android.widget.TextView(requireContext());
                hint.setText("Tap to edit");
                hint.setTextColor(0xFF8B949E);
                hint.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 11);
                bubble.addView(hint);

                return bubble;
            }
            @Override
            public android.view.View getInfoContents(@NonNull Marker marker) {
                return null; // use getInfoWindow instead
            }
        });

        // Tapping the info window opens the edit dialog
        mMap.setOnInfoWindowClickListener(marker -> {
            Object tag = marker.getTag();
            if (!(tag instanceof String)) return;
            LocationData data = locationDataMap.get((String) tag);
            if (data != null) showEditLocationDialog(data);
        });

        // Create the group_locations table if it doesn't exist, then load all pins
        ensureGroupLocationsTable();

        // Ask the user for GPS permission (or start tracking if already granted)
        requestLocationPermissions();

        // Set up the arrival-notification channel and load per-user preferences
        createNotificationChannel();
        loadMutedLocations();
        requestNotificationPermission();
    }

    // onCreateView — we override this to add a custom overlay on top of the map.
    // Because MapFragment extends SupportMapFragment (not Fragment), we can't use a custom XML root.
    // Instead, we wrap the map's view in a FrameLayout and add our overlay on top.
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View mapView = super.onCreateView(inflater, container, savedInstanceState); // the map itself

        // Wrap the map view in a FrameLayout so we can stack views on top of it
        android.widget.FrameLayout wrapper = new android.widget.FrameLayout(requireContext());
        wrapper.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        wrapper.addView(mapView); // add the map as the bottom layer

        // Inflate our custom overlay (the legend / key) and add it on top
        View overlay = inflater.inflate(R.layout.fragment_map_overlay, wrapper, false);
        wrapper.addView(overlay);

        return wrapper;
    }

    // onPause — called when the user leaves this screen (screen lock, app switch, etc.).
    // We stop GPS updates and remove listeners to save battery.
    //
    // If the car has already stopped (session is in stop-debounce), we save the session
    // immediately — the user probably left the app after parking.
    // If a drive is still actively in progress, we leave the session open so it survives
    // a brief screen lock and resumes when the user comes back.
    // The internal stop watchdog (Handler inside DriveSessionManager) keeps ticking
    // independently of this fragment's lifecycle, so the session will save itself
    // after 20s of low speed regardless of whether the app is in the foreground.
    @Override
    public void onPause() {
        super.onPause();

        // If the drive has already stopped and is in the 20s debounce window, save now.
        // (The internal watchdog would also save it, but this is faster and more reliable
        // when the user leaves the app right after parking.)
        if (sessionManager != null && sessionManager.isInStopDebounce()) {
            sessionManager.forceEndSession();
        }

        // Stop GPS updates
        if (fusedLocationClient != null && locationCallback != null) {
            fusedLocationClient.removeLocationUpdates(locationCallback);
        }

        // Stop the member refresh timer
        if (refreshHandler != null && refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
        }

        // Unregister the activity transition receiver (stops sensor monitoring)
        if (activityTransitionReceiver != null) {
            try {
                requireContext().unregisterReceiver(activityTransitionReceiver);
            } catch (Exception ignored) {}
            activityTransitionReceiver = null;
        }

        // Stop activity recognition updates from the Google service
        if (activityRecognitionClient != null && activityTransitionPendingIntent != null) {
            activityRecognitionClient.removeActivityTransitionUpdates(activityTransitionPendingIntent);
        }
    }

    // onResume — called when the user comes back to this screen.
    // We restart everything we stopped in onPause.
    @Override
    public void onResume() {
        super.onResume();

        // Only restart if the map was already ready (i.e., not the very first launch)
        if (mMap != null && locationCallback != null) {
            enableMyLocation();
            startMemberRefresh();

            // Re-register activity transitions if we have permission
            boolean hasActivityPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    || ContextCompat.checkSelfPermission(requireContext(),
                       Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;

            if (hasActivityPermission) {
                registerActivityTransitions();
            }
        }
    }

    // onDestroy — called when the fragment is permanently torn down (app killed, user navigates away).
    // This is the right place to end an active drive session — not onPause, which fires on every
    // screen lock and app switch and would cut drives short unnecessarily.
    @Override
    public void onDestroy() {
        super.onDestroy();

        if (sessionManager != null) {
            sessionManager.forceEndSession();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // PERMISSIONS
    // ══════════════════════════════════════════════════════════════════════

    // requestLocationPermissions — checks which permissions we already have,
    // starts the relevant features, and asks the user for any we're missing.
    private void requestLocationPermissions() {
        boolean hasLocation = ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED;

        boolean hasActivity = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                || ContextCompat.checkSelfPermission(requireContext(),
                   Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED;

        // Start features we already have permission for
        if (hasLocation) {
            enableMyLocation();
            startMemberRefresh();
        }
        if (hasActivity) {
            registerActivityTransitions();
        }

        // If anything is missing, ask the user
        if (!hasLocation || !hasActivity) {
            List<String> missingPermissions = new ArrayList<>();

            if (!hasLocation) {
                missingPermissions.add(Manifest.permission.ACCESS_FINE_LOCATION);
                missingPermissions.add(Manifest.permission.ACCESS_COARSE_LOCATION);
            }

            if (!hasActivity && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                missingPermissions.add(Manifest.permission.ACTIVITY_RECOGNITION);
            }

            // Show the system permission dialog
            locationPermissionRequest.launch(missingPermissions.toArray(new String[0]));
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // GPS LOCATION TRACKING
    // ══════════════════════════════════════════════════════════════════════

    // enableMyLocation — starts GPS tracking with a callback that fires on every location update.
    @SuppressLint("MissingPermission") // we already checked permission before calling this
    private void enableMyLocation() {
        if (mMap == null) return;

        // Disable the built-in blue dot — we draw our own custom marker
        mMap.setMyLocationEnabled(false);

        // Define what happens each time we get a new GPS location
        locationCallback = new LocationCallback() {
            @Override
            public void onLocationResult(@NonNull LocationResult locationResult) {
                for (Location location : locationResult.getLocations()) {
                    // Record when the last GPS update happened (for the still-debounce timer)
                    lastLocationUpdateMs = System.currentTimeMillis();

                    // Get the raw GPS speed and smooth it through our 5-reading buffer.
                    // Raw GPS speed can jump ±2 m/s even at constant speed — smoothing fixes that.
                    float rawSpeed      = location.hasSpeed() ? location.getSpeed() : 0f;
                    float smoothedSpeed = getSmoothedSpeed(rawSpeed);

                    // Determine the display status using smoothed speed (stable, avoids flicker).
                    String status = getStatusFromSpeed(smoothedSpeed);

                    // Update our marker on the map using the smoothed speed for display.
                    updateUserMarker(location, smoothedSpeed, status);

                    // Upload our latest position + status to the Turso database.
                    uploadMyLocation(location.getLatitude(), location.getLongitude(), status, smoothedSpeed);

                    // Check whether the user just entered any saved location radius
                    checkGeofences(location.getLatitude(), location.getLongitude());

                    // ── SESSION DETECTION uses RAW speed, not smoothed ──────────────────
                    // IMPORTANT: We pass rawSpeed (not smoothedSpeed) to the session manager.
                    // Why: the smoothing buffer starts full of zeros when the user was sitting
                    // still, so the first few driving readings get averaged down to well below
                    // the detection threshold — causing the session to never start. Raw speed
                    // reflects actual movement immediately on the very first GPS update.
                    if (sessionManager != null) {
                        sessionManager.onLocationUpdate(
                                location.getLatitude(), location.getLongitude(), rawSpeed);
                    }

                    // ── GPS INTERVAL uses the HIGHER of raw or smoothed speed ──────────
                    // When raw speed clearly indicates driving, switch to 1-second GPS polling
                    // immediately — don't wait for the smoothed value to catch up.
                    // This prevents the 10–20 second lag where GPS stays at 10s intervals
                    // because the buffer is dragging the smoothed speed down.
                    String intervalStatus;
                    if (rawSpeed > 2.8f || smoothedSpeed > 5.5f) {
                        intervalStatus = "driving"; // either signal is enough to go fast
                    } else if (rawSpeed >= 0.8f || smoothedSpeed >= 0.8f) {
                        intervalStatus = "walking";
                    } else {
                        intervalStatus = status; // both low → trust the debounced status
                    }

                    if (!intervalStatus.equals(lastLocationStatus)) {
                        lastLocationStatus = intervalStatus;
                        final String finalIntervalStatus = intervalStatus;
                        // postDelayed(0) defers this until after the current callback finishes
                        refreshHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                restartLocationUpdatesForStatus(finalIntervalStatus);
                            }
                        });
                    }
                }
            }
        };

        // Begin receiving location updates at the default (still) interval
        restartLocationUpdatesForStatus(lastLocationStatus);
    }

    // restartLocationUpdatesForStatus — cancels the current GPS subscription and starts a new one
    // at the appropriate frequency for the given status.
    // Driving needs updates every second; being still only needs them every 10 seconds.
    @SuppressLint("MissingPermission")
    private void restartLocationUpdatesForStatus(String status) {
        if (fusedLocationClient == null || locationCallback == null) return;

        int intervalMs;    // desired update interval in milliseconds
        int minIntervalMs; // minimum time between updates (prevents bursts)

        switch (status) {
            case "driving":
                intervalMs    = 1000; // every 1 second while driving (need frequent updates)
                minIntervalMs = 500;  // no faster than every 0.5 seconds
                break;
            case "walking":
                intervalMs    = 4000; // every 4 seconds while walking
                minIntervalMs = 2000; // no faster than every 2 seconds
                break;
            default: // "still"
                intervalMs    = 10000; // every 10 seconds while still (saves battery)
                minIntervalMs = 7000;
                break;
        }

        // Cancel the current GPS subscription
        fusedLocationClient.removeLocationUpdates(locationCallback);

        // Start a new GPS subscription with the updated settings
        LocationRequest request = new LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
                .setMinUpdateIntervalMillis(minIntervalMs)
                .build();

        fusedLocationClient.requestLocationUpdates(request, locationCallback, Looper.getMainLooper());
    }


    // ══════════════════════════════════════════════════════════════════════
    // ACTIVITY RECOGNITION (walking / driving / still detection via sensors)
    // ══════════════════════════════════════════════════════════════════════

    // registerActivityTransitions — asks Google to notify us when the phone
    // transitions between STILL, WALKING, and IN_VEHICLE activities.
    @SuppressLint("MissingPermission")
    private void registerActivityTransitions() {
        // List all the transitions we want to watch
        List<ActivityTransition> transitions = new ArrayList<>();
        int[] activityTypes = {
                DetectedActivity.STILL,
                DetectedActivity.WALKING,
                DetectedActivity.IN_VEHICLE
        };

        for (int activityType : activityTypes) {
            // We only care about entering an activity, not leaving it
            transitions.add(new ActivityTransition.Builder()
                    .setActivityType(activityType)
                    .setActivityTransition(ActivityTransition.ACTIVITY_TRANSITION_ENTER)
                    .build());
        }

        ActivityTransitionRequest request = new ActivityTransitionRequest(transitions);

        // Create a PendingIntent that delivers a broadcast when a transition happens
        Intent intent = new Intent(TRANSITION_ACTION);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
        activityTransitionPendingIntent = PendingIntent.getBroadcast(
                requireContext(), 100, intent, flags);

        // Create the BroadcastReceiver that handles the delivered broadcast
        activityTransitionReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!ActivityTransitionResult.hasResult(intent)) return;

                ActivityTransitionResult transitionResult = ActivityTransitionResult.extractResult(intent);
                if (transitionResult == null) return;

                // Process each detected transition event
                for (ActivityTransitionEvent event : transitionResult.getTransitionEvents()) {
                    switch (event.getActivityType()) {
                        case DetectedActivity.WALKING:
                        case DetectedActivity.IN_VEHICLE:
                            // Movement confirmed by hardware sensor — reset the debounce timer
                            lastActiveTimeMs = System.currentTimeMillis();
                            break;

                        case DetectedActivity.STILL:
                            // Confirmed still by hardware — allow "still" to be declared immediately
                            lastActiveTimeMs = 0;
                            break;
                    }
                    // The next GPS update will redraw the marker icon with the correct status
                }
            }
        };

        // Register the receiver so it starts listening for broadcasts
        // Android 13+ requires an extra flag for security
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requireContext().registerReceiver(
                    activityTransitionReceiver,
                    new IntentFilter(TRANSITION_ACTION),
                    Context.RECEIVER_NOT_EXPORTED); // only our own app can send this broadcast
        } else {
            requireContext().registerReceiver(
                    activityTransitionReceiver,
                    new IntentFilter(TRANSITION_ACTION));
        }

        // Tell the Google service to start sending transition broadcasts
        activityRecognitionClient.requestActivityTransitionUpdates(
                request, activityTransitionPendingIntent);
    }


    // ══════════════════════════════════════════════════════════════════════
    // USER'S MAP MARKER
    // ══════════════════════════════════════════════════════════════════════

    // updateUserMarker — places or moves the user's pin on the map.
    // Accepts pre-computed smoothedSpeed and status to avoid recalculating.
    private void updateUserMarker(Location location, float smoothedSpeed, String status) {
        if (location == null || mMap == null) return;

        LatLng position = new LatLng(location.getLatitude(), location.getLongitude());
        float speed     = smoothedSpeed;
        String snippet  = formatSpeed(status, speed); // e.g. "Driving  •  72 km/h"
        int userColor   = ContextCompat.getColor(requireContext(), R.color.blue);

        if (userMarker == null) {
            // First time — create the marker from scratch
            userMarker = mMap.addMarker(new MarkerOptions()
                    .position(position)
                    .title(displayName != null ? displayName : "Me")
                    .snippet(snippet)
                    .icon(getCachedUserIcon(userColor, status))
                    .anchor(0.5f, 1.0f)); // center the pin horizontally, bottom at the coordinate

            lastRenderedStatus = status;

            // Move the camera to the user's location only once (the first time)
            if (!cameraMovedToUser) {
                mMap.animateCamera(CameraUpdateFactory.newLatLngZoom(position, 15f));
                cameraMovedToUser = true;
            }

        } else {
            // Marker already exists — just update its position and popup text
            userMarker.setPosition(position);
            userMarker.setSnippet(snippet);

            // Rebuilding the icon is expensive — only do it if the status actually changed
            if (!status.equals(lastRenderedStatus)) {
                userMarker.setIcon(getCachedUserIcon(userColor, status));
                lastRenderedStatus = status;
            }

            // If the info window (popup) is open, refresh it so the text stays up to date
            if (userMarker.isInfoWindowShown()) {
                userMarker.showInfoWindow();
            }
        }
    }

    // getCachedUserIcon — returns the marker icon for a given status.
    // If we've never built it before, it builds it now and stores it for next time.
    private BitmapDescriptor getCachedUserIcon(int color, String status) {
        if (!userIconCache.containsKey(status)) {
            // Build the icon for this status and save it in the cache
            userIconCache.put(status, getMarkerIconWithStatus(requireContext(), color, status));
        }
        return userIconCache.get(status);
    }


    // ══════════════════════════════════════════════════════════════════════
    // STATUS & SPEED HELPERS
    // ══════════════════════════════════════════════════════════════════════

    // formatSpeed — returns a human-readable description of the current movement.
    private String formatSpeed(String status, float speedMs) {
        switch (status) {
            case "walking":
                return String.format("Walking  \u2022  %.1f m/s", speedMs);
            case "driving":
                return String.format("Driving  \u2022  %.0f km/h", speedMs * 3.6f);
            default:
                return "Still";
        }
    }

    // getSmoothedSpeed — adds a raw GPS speed reading to the ring buffer and returns
    // the average of all readings in the buffer. This removes GPS jitter so the
    // status doesn't flicker when driving at constant speed.
    private float getSmoothedSpeed(float rawSpeedMs) {
        speedBuffer[speedBufIndex] = rawSpeedMs;
        speedBufIndex = (speedBufIndex + 1) % speedBuffer.length; // advance ring pointer
        if (speedBufIndex == 0) speedBufFull = true; // we've wrapped around once

        // Average only the filled slots
        int count = speedBufFull ? speedBuffer.length : speedBufIndex;
        float sum = 0;
        for (int i = 0; i < count; i++) sum += speedBuffer[i];
        return count > 0 ? sum / count : 0;
    }

    // getStatusFromSpeed — takes the SMOOTHED speed (m/s) and returns "still",
    // "walking", or "driving". Includes a 5-second debounce before declaring "still"
    // so that brief stops (e.g. red lights) don't flicker the status.
    //
    // Thresholds:
    //   driving : smoothed speed > 5.5 m/s  (~20 km/h)
    //   walking : smoothed speed >= 0.8 m/s (~ 3 km/h)
    //   still   : smoothed speed <  0.8 m/s + 5-second debounce
    private String getStatusFromSpeed(float smoothedSpeedMs) {
        if (smoothedSpeedMs > 5.5f) {
            // Comfortably above walking pace → driving
            lastActiveTimeMs = System.currentTimeMillis();
            lastActiveStatus = "driving";
            return "driving";
        }

        if (smoothedSpeedMs >= 0.8f) {
            // Moving but not fast enough to be driving → walking
            lastActiveTimeMs = System.currentTimeMillis();
            lastActiveStatus = "walking";
            return "walking";
        }

        // Speed is very low.
        // Only switch to "still" after STILL_DEBOUNCE_MS of low speed,
        // so brief stops don't flicker the user's icon.
        boolean neverMoved      = lastActiveTimeMs == 0;
        long    msSinceLastMove = System.currentTimeMillis() - lastActiveTimeMs;
        boolean debounceExpired = msSinceLastMove > STILL_DEBOUNCE_MS;

        if (neverMoved || debounceExpired) {
            return "still";
        }

        // Within the debounce window — keep showing the last known active status
        return lastActiveStatus;
    }


    // ══════════════════════════════════════════════════════════════════════
    // DATABASE UPLOADS
    // ══════════════════════════════════════════════════════════════════════

    // uploadMyLocation — sends the user's current GPS position to the database.
    // This runs in a background thread because network calls block the UI thread.
    private void uploadMyLocation(double lat, double lng, String status, float speedMs) {
        if (uid == null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tursoQuery(
                            "UPDATE users SET last_lat = ?, last_lng = ?, last_seen = CURRENT_TIMESTAMP, status = ?, last_speed = ? WHERE uid = ?",
                            new Object[]{String.valueOf(lat), String.valueOf(lng), status, String.valueOf(speedMs), uid}
                    );
                } catch (Exception ignored) {
                    // Silently ignore upload failures — the next update will try again
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // OTHER MEMBERS' MARKERS
    // ══════════════════════════════════════════════════════════════════════

    // startMemberRefresh — starts a repeating loop that fetches and draws other members' markers.
    private void startMemberRefresh() {
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                fetchGroupMembers();            // fetch latest positions from the DB
                checkAndUpdateUserStatus();     // check if user's own marker needs updating

                // Re-sync saved locations periodically so edits/deletions
                // made by other group members appear live.
                long nowMs = System.currentTimeMillis();
                if (nowMs - lastLocationSyncMs >= LOCATION_SYNC_MS) {
                    lastLocationSyncMs = nowMs;
                    loadGroupLocations();
                }
                if (nowMs - lastArrivalSyncMs >= ARRIVAL_SYNC_MS) {
                    lastArrivalSyncMs = nowMs;
                    checkGroupArrivals();
                }

                // Watchdog: if a drive session is active but GPS has gone completely silent
                // (e.g. the route simulation ended, or the device lost signal), end the session
                // after STOP_DEBOUNCE_MS of no updates — same threshold as a normal stop.
                if (sessionManager != null) {
                    sessionManager.tickWatchdog();
                }

                refreshHandler.postDelayed(this, MEMBER_REFRESH_MS); // schedule next run
            }
        };
        refreshHandler.post(refreshRunnable); // start immediately
    }

    // checkAndUpdateUserStatus — forces the user's marker to "still" if GPS has gone quiet
    // and the debounce timer has expired. This handles the case where the device stops
    // sending GPS updates when the user isn't moving (common in some environments).
    private void checkAndUpdateUserStatus() {
        if (userMarker == null || mMap == null) return;
        if ("still".equals(lastActiveStatus)) return; // already still, nothing to do

        long now = System.currentTimeMillis();

        // Has GPS gone quiet for more than STILL_DEBOUNCE_MS?
        boolean gpsGoneQuiet  = lastLocationUpdateMs > 0
                && (now - lastLocationUpdateMs) > STILL_DEBOUNCE_MS;

        // Has the movement debounce expired?
        boolean debounceExpired = lastActiveTimeMs > 0
                && (now - lastActiveTimeMs) > STILL_DEBOUNCE_MS;

        if (gpsGoneQuiet || debounceExpired) {
            // Declare the user still and update the marker
            lastActiveStatus = "still";

            int color = ContextCompat.getColor(requireContext(), R.color.blue);
            BitmapDescriptor stillIcon = getMarkerIconWithStatus(requireContext(), color, "still");

            userMarker.setIcon(stillIcon);
            userMarker.setSnippet("Still");
            if (userMarker.isInfoWindowShown()) userMarker.showInfoWindow();

            // Also upload the updated status to the database
            LatLng pos = userMarker.getPosition();
            if (pos != null) {
                uploadMyLocation(pos.latitude, pos.longitude, "still", 0f);
                // Notify session manager that we're now still
                if (sessionManager != null) {
                    sessionManager.onLocationUpdate(pos.latitude, pos.longitude, 0f);
                }
            }

            // Drop back to slow GPS polling now that the user is still (saves battery)
            if (!"still".equals(lastLocationStatus)) {
                lastLocationStatus = "still";
                restartLocationUpdatesForStatus("still");
            }
        }
    }

    // fetchGroupMembers — downloads the latest positions for all other group members
    // and updates (or creates) their map markers.
    private void fetchGroupMembers() {
        if (gid == null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Query: get all group members (excluding the current user) who have a known position
                    JSONObject result = tursoQuery(
                            "SELECT u.uid, u.display_name, u.last_lat, u.last_lng, u.status, u.last_speed " +
                            "FROM users u " +
                            "JOIN group_members gm ON u.uid = gm.uid " +
                            "WHERE gm.gid = ? AND u.last_lat IS NOT NULL AND u.last_lng IS NOT NULL AND u.uid != ?",
                            new Object[]{gid, uid}
                    );

                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // UI updates must happen on the main thread
                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                for (int i = 0; i < rows.length(); i++) {
                                    JSONArray row = rows.getJSONArray(i);

                                    // Read each column from the row
                                    String memberId     = row.getJSONObject(0).getString("value");
                                    String memberName   = row.getJSONObject(1).getString("value");
                                    double lat          = Double.parseDouble(row.getJSONObject(2).getString("value"));
                                    double lng          = Double.parseDouble(row.getJSONObject(3).getString("value"));
                                    String memberStatus = row.getJSONObject(4).optString("value", "still");

                                    float memberSpeed = 0f;
                                    try {
                                        memberSpeed = Float.parseFloat(row.getJSONObject(5).optString("value", "0"));
                                    } catch (Exception ignored) {}

                                    String memberSnippet = formatSpeed(memberStatus, memberSpeed);
                                    LatLng position      = new LatLng(lat, lng);
                                    int color            = getMemberColor(memberStatus);
                                    BitmapDescriptor icon = getMarkerIconWithStatus(
                                            requireContext(), color, memberStatus);

                                    if (memberMarkers.containsKey(memberId)) {
                                        // Marker already exists — just move and update it
                                        Marker marker = memberMarkers.get(memberId);
                                        if (marker != null) {
                                            marker.setPosition(position);
                                            marker.setIcon(icon);
                                            marker.setSnippet(memberSnippet);
                                            if (marker.isInfoWindowShown()) marker.showInfoWindow();
                                        }
                                    } else {
                                        // First time we see this member — create a new marker
                                        Marker marker = mMap.addMarker(new MarkerOptions()
                                                .position(position)
                                                .title(memberName)
                                                .snippet(memberSnippet)
                                                .icon(icon)
                                                .anchor(0.5f, 1.0f));

                                        memberMarkers.put(memberId, marker); // save it in our map
                                    }
                                }
                            } catch (Exception e) {
                                e.printStackTrace();
                            }
                        }
                    });

                } catch (Exception ignored) {}
            }
        }).start();
    }

    // getMemberColor — returns the ARGB color for a member's status dot.
    // The format 0xFFRRGGBB means: FF=fully opaque, then red/green/blue in hex.
    private int getMemberColor(String status) {
        switch (status) {
            case "walking": return 0xFF4CAF50; // green
            case "driving": return 0xFFFF9800; // orange
            default:        return 0xFF2196F3; // blue (still)
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // MARKER ICON BUILDER — USER PINS (circular)
    // Users get a filled circle + small downward triangle ("round pin").
    // Saved locations get the balloon-style ic_user_pin (see getLocationPinIcon).
    // ══════════════════════════════════════════════════════════════════════

    // ══════════════════════════════════════════════════════════════════════
    // NOTIFICATIONS & GEOFENCING
    // ══════════════════════════════════════════════════════════════════════

    /** Creates the notification channel required on Android 8+ (API 26+). */
    private void createNotificationChannel() {
        NotificationChannel channel = new NotificationChannel(
            NOTIF_CHANNEL_ID,
            "Location Arrival Alerts",
            NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Notifies you when you enter a saved group location");
        NotificationManager nm =
            (NotificationManager) requireContext().getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm != null) nm.createNotificationChannel(channel);
    }

    /** Asks for POST_NOTIFICATIONS permission on Android 13+ (API 33). */
    private void requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(),
                    android.Manifest.permission.POST_NOTIFICATIONS)
                    != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                requestPermissions(
                    new String[]{ android.Manifest.permission.POST_NOTIFICATIONS }, 200);
            }
        }
    }

    /**
     * Called on every GPS update. For each saved location, checks whether the user
     * has just entered (or exited) its radius and fires a notification on entry.
     */
    private void checkGeofences(double userLat, double userLng) {
        // Respect the global toggle stored in SharedPreferences
        android.content.SharedPreferences prefs =
            requireContext().getSharedPreferences("BoltAppPrefs", android.content.Context.MODE_PRIVATE);
        if (!prefs.getBoolean("notifications_enabled", true)) return;

        float[] distResult = new float[1];
        for (Map.Entry<String, LocationData> entry : locationDataMap.entrySet()) {
            String locId = entry.getKey();
            LocationData loc = entry.getValue();

            android.location.Location.distanceBetween(
                userLat, userLng, loc.lat, loc.lng, distResult);
            float distance = distResult[0];

            if (distance <= loc.radius) {
                // User is inside this radius
                if (!activelyInsideLocIds.contains(locId)) {
                    // Just entered — record it
                    activelyInsideLocIds.add(locId);
                    // Write arrival to DB so other group members get notified
                    announceMyArrival(locId);
                    // Self-notification (only if user has not muted this location)
                    if (!mutedLocationIds.contains(locId)) {
                        sendArrivalNotification(loc.name, locId);
                    }
                }
            } else {
                // User has left this radius — reset so the next entry fires again
                activelyInsideLocIds.remove(locId);
            }
        }
    }

    /** Builds and posts the arrival notification. */
    private void sendArrivalNotification(String locationName, String locId) {
        String title   = "Arrived at " + locationName;
        String content = (displayName != null ? displayName : "You")
                         + " arrived at " + locationName;

        androidx.core.app.NotificationCompat.Builder builder =
            new androidx.core.app.NotificationCompat.Builder(requireContext(), NOTIF_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        androidx.core.app.NotificationManagerCompat nm =
            androidx.core.app.NotificationManagerCompat.from(requireContext());
        // Use locId hashCode so each location gets its own notification slot
        nm.notify(NOTIF_ID_BASE + locId.hashCode(), builder.build());
    }

    /** Writes this user's arrival into the DB so other group members can see it. */
    private void announceMyArrival(String locId) {
        if (uid == null || gid == null) return;
        android.content.SharedPreferences prefs =
            requireContext().getSharedPreferences("BoltAppPrefs", android.content.Context.MODE_PRIVATE);
        // Respect global "share my arrivals" toggle
        if (!prefs.getBoolean("announce_arrivals", true)) return;
        // Respect per-location "announce my arrival here" toggle
        if (hiddenAnnounceLocIds.contains(locId)) return;

        new Thread(() -> {
            try {
                tursoQuery(
                    "INSERT OR REPLACE INTO location_arrivals " +
                    "(location_id, gid, uid, display_name, arrived_at) " +
                    "VALUES (?, ?, ?, ?, datetime('now'))",
                    new Object[]{ locId, gid, uid, displayName });
            } catch (Exception e) {
                android.util.Log.e("BoltApp", "announceMyArrival failed", e);
            }
        }).start();
    }

    /** Polls the DB for arrivals by other group members and fires notifications. */
    private void checkGroupArrivals() {
        if (gid == null || uid == null) return;
        android.content.SharedPreferences prefs =
            requireContext().getSharedPreferences("BoltAppPrefs", android.content.Context.MODE_PRIVATE);
        // Respect the global "receive notifications" toggle
        if (!prefs.getBoolean("notifications_enabled", true)) return;

        final String since = lastArrivalCheckTime;
        // Advance the watermark NOW so we don't re-notify if the query is slow
        lastArrivalCheckTime = new java.text.SimpleDateFormat(
                "yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                .format(new java.util.Date());

        new Thread(() -> {
            try {
                JSONObject result = tursoQuery(
                    "SELECT la.location_id, la.display_name, gl.name " +
                    "FROM location_arrivals la " +
                    "JOIN group_locations gl ON la.location_id = gl.location_id " +
                    "WHERE la.gid = ? AND la.uid != ? AND la.arrived_at > ?",
                    new Object[]{ gid, uid, since });

                JSONArray rows = result
                    .getJSONArray("results")
                    .getJSONObject(0)
                    .getJSONObject("response")
                    .getJSONObject("result")
                    .getJSONArray("rows");

                if (getActivity() == null) return;
                getActivity().runOnUiThread(() -> {
                    try {
                        for (int i = 0; i < rows.length(); i++) {
                            JSONArray row      = rows.getJSONArray(i);
                            String locId       = row.getJSONObject(0).getString("value");
                            String arrivedName = row.getJSONObject(1).optString("value", "Someone");
                            String locName     = row.getJSONObject(2).getString("value");
                            if (!mutedLocationIds.contains(locId)) {
                                sendOtherArrivalNotification(arrivedName, locName, locId);
                            }
                        }
                    } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }).start();
    }

    /** Posts a notification when another group member arrives at a saved location. */
    private void sendOtherArrivalNotification(String userName, String locationName, String locId) {
        String title   = userName + " arrived";
        String content = userName + " arrived at " + locationName;

        androidx.core.app.NotificationCompat.Builder builder =
            new androidx.core.app.NotificationCompat.Builder(requireContext(), NOTIF_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(content)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
                .setAutoCancel(true);

        androidx.core.app.NotificationManagerCompat nm =
            androidx.core.app.NotificationManagerCompat.from(requireContext());
        nm.notify(NOTIF_ID_BASE + locId.hashCode() + userName.hashCode(), builder.build());
    }

    /** Loads per-user notification preferences from SharedPreferences. */
    private void loadMutedLocations() {
        if (uid == null) return;
        android.content.SharedPreferences prefs =
            requireContext().getSharedPreferences("BoltAppPrefs", android.content.Context.MODE_PRIVATE);
        java.util.Set<String> recv = prefs.getStringSet("muted_locs_" + uid, null);
        mutedLocationIds = recv != null ? new HashSet<>(recv) : new HashSet<>();
        java.util.Set<String> send = prefs.getStringSet("hidden_announce_" + uid, null);
        hiddenAnnounceLocIds = send != null ? new HashSet<>(send) : new HashSet<>();
    }

    /** Persists notification preferences to SharedPreferences. */
    private void saveMutedLocations() {
        if (uid == null) return;
        android.content.SharedPreferences prefs =
            requireContext().getSharedPreferences("BoltAppPrefs", android.content.Context.MODE_PRIVATE);
        prefs.edit()
            .putStringSet("muted_locs_" + uid, mutedLocationIds)
            .putStringSet("hidden_announce_" + uid, hiddenAnnounceLocIds)
            .apply();
    }

    // getMarkerIconWithStatus — draws a circular pin for a group member.
    // Optionally adds a walking/driving emoji to the right.
    // Anchor is (0.5, 1.0) so the triangle tip sits at the GPS coordinate.

    private BitmapDescriptor getMarkerIconWithStatus(Context context, int color, String status) {
        int r     = dpToPx(20); // circle radius
        int tailH = dpToPx(11); // tail height
        int pad   = dpToPx(2);  // anti-clip padding
        int halfB = dpToPx(6);  // triangle half-width

        String emoji = "";
        if ("walking".equals(status)) emoji = "\uD83D\uDEB6";
        else if ("driving".equals(status)) emoji = "\uD83D\uDE97";

        int circleW = (r + pad) * 2;
        int W       = emoji.isEmpty() ? circleW : circleW + circleW / 2;
        int H       = r * 2 + tailH + pad * 2;
        float cx    = circleW / 2f;
        float cy    = r + pad;

        Bitmap bmp    = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // ── Filled circle ────────────────────────────────────────────────
        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        canvas.drawCircle(cx, cy, r, fill);

        // ── White stroke ring ────────────────────────────────────────────
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dpToPx(2));
        stroke.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r - dpToPx(1), stroke);

        // ── Triangle tail ────────────────────────────────────────────────
        float baseY = cy + r - dpToPx(2);
        float tipY  = H - pad;
        Path tri = new Path();
        tri.moveTo(cx - halfB, baseY);
        tri.lineTo(cx + halfB, baseY);
        tri.lineTo(cx, tipY);
        tri.close();
        canvas.drawPath(tri, fill);

        // ── Profile avatar — head + body dome, clipped inside the circle ─
        Paint avatar = new Paint(Paint.ANTI_ALIAS_FLAG);
        avatar.setStyle(Paint.Style.FILL);
        avatar.setColor(0xFFFFFFFF);

        canvas.save();
        Path clip = new Path();
        clip.addCircle(cx, cy, r - dpToPx(2), Path.Direction.CW);
        canvas.clipPath(clip);

        // Head circle
        float headR  = r * 0.30f;
        float headCY = cy - r * 0.20f;
        canvas.drawCircle(cx, headCY, headR, avatar);

        // Body dome: large circle centred below — its top arc forms the shoulders
        float bodyCY = cy + r * 0.72f;
        float bodyR  = r * 0.65f;
        canvas.drawCircle(cx, bodyCY, bodyR, avatar);

        canvas.restore();

        // ── Emoji badge (walking / driving) ─────────────────────────────
        if (!emoji.isEmpty()) {
            Paint ep = new Paint(Paint.ANTI_ALIAS_FLAG);
            ep.setTextSize(r * 0.90f);
            ep.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emoji, cx + circleW / 2f, cy + r * 0.35f, ep);
        }

        return BitmapDescriptorFactory.fromBitmap(bmp);
    }

    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — called by MainActivity
    // ══════════════════════════════════════════════════════════════════════

    // reportPhoneTouch — called by MainActivity whenever the screen is tapped.
    // Forwards the event to the session manager so it can count phone-use-while-driving.
    public void reportPhoneTouch() {
        if (sessionManager != null) {
            sessionManager.onPhoneTouch();
        }
    }

    // updateGroup — called when the user switches to a different group in the dropdown.
    // Clears old member markers, old saved-location pins, then reloads everything.
    public void updateGroup(String newGid, String newGroupName) {
        this.gid       = newGid;
        this.groupName = newGroupName;

        // Tell the session manager about the new group so sessions are tagged correctly
        if (sessionManager != null) {
            sessionManager.setGid(newGid);
        }

        // Remove all of the old group's member markers from the map
        if (mMap != null) {
            for (Marker marker : memberMarkers.values()) {
                if (marker != null) marker.remove();
            }
        }
        memberMarkers.clear();

        // Remove saved location pins for the old group
        if (mMap != null) {
            for (Marker m : locationMarkers.values()) { if (m != null) m.remove(); }
            for (Circle c : locationCircles.values())  { if (c != null) c.remove(); }
        }
        locationMarkers.clear();
        locationCircles.clear();
        locationDataMap.clear();
        activelyInsideLocIds.clear(); // reset geofence state for new group

        // Load saved locations for the newly selected group
        loadGroupLocations();

        // The next refresh cycle will automatically fetch the new group's members
    }


    // ══════════════════════════════════════════════════════════════════════
    // SAVED GROUP LOCATIONS
    //
    // Flow:
    //   1. ensureGroupLocationsTable() — CREATE TABLE IF NOT EXISTS (once per map load)
    //   2. loadGroupLocations()        — SELECT all rows for current gid and draw pins
    //   3. drawSavedLocation()         — adds one Marker + Circle to the map
    //   4. showAddLocationDialog()     — styled dark dialog with SeekBar + color picker
    //   5. saveGroupLocation()         — INSERT into DB then call drawSavedLocation()
    //   6. getLocationPinIcon()        — returns the balloon-style pin tinted with color
    // ══════════════════════════════════════════════════════════════════════

    // ensureGroupLocationsTable — creates the table if it does not exist yet.
    // Also adds the color column to tables created before this version (ALTER TABLE).
    private void ensureGroupLocationsTable() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tursoQuery(
                        "CREATE TABLE IF NOT EXISTS group_locations (" +
                        "  location_id TEXT PRIMARY KEY," +
                        "  gid         TEXT NOT NULL," +
                        "  name        TEXT NOT NULL," +
                        "  lat         REAL NOT NULL," +
                        "  lng         REAL NOT NULL," +
                        "  radius      REAL DEFAULT 100," +
                        "  color       TEXT DEFAULT '#E53935'," +
                        "  created_by  TEXT," +
                        "  created_at  TEXT DEFAULT CURRENT_TIMESTAMP" +
                        ")",
                        null
                    );
                    // Migrate existing tables that lack the color column.
                    // SQLite throws if the column already exists — we just ignore that.
                    try {
                        tursoQuery(
                            "ALTER TABLE group_locations ADD COLUMN color TEXT DEFAULT '#E53935'",
                            null
                        );
                    } catch (Exception ignored) {}

                    // Table that records when a user enters a saved location.
                    // Other group members poll this to receive arrival notifications.
                    tursoQuery(
                        "CREATE TABLE IF NOT EXISTS location_arrivals (" +
                        "  location_id  TEXT NOT NULL," +
                        "  gid          TEXT NOT NULL," +
                        "  uid          TEXT NOT NULL," +
                        "  display_name TEXT," +
                        "  arrived_at   TEXT DEFAULT (datetime('now'))," +
                        "  PRIMARY KEY (location_id, uid)" +
                        ")",
                        null
                    );

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override public void run() { loadGroupLocations(); }
                        });
                    }
                } catch (Exception ignored) {}
            }
        }).start();
    }

    // loadGroupLocations — fetches all saved locations for the current group.
    // On every call it diffs the DB result against what is currently on the map:
    //   • new location   → draw it
    //   • changed data   → remove old pin, draw fresh
    //   • deleted in DB  → remove pin from map
    private void loadGroupLocations() {
        if (gid == null || mMap == null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    JSONObject result = tursoQuery(
                        "SELECT location_id, name, lat, lng, radius, color " +
                        "FROM group_locations WHERE gid = ?",
                        new Object[]{gid}
                    );

                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // Build the list of parsed locations on the background thread
                    java.util.List<LocationData> fresh = new java.util.ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row  = rows.getJSONArray(i);
                        String locId   = row.getJSONObject(0).getString("value");
                        String name    = row.getJSONObject(1).getString("value");
                        double lat     = Double.parseDouble(row.getJSONObject(2).getString("value"));
                        double lng     = Double.parseDouble(row.getJSONObject(3).getString("value"));
                        double radius  = Double.parseDouble(row.getJSONObject(4).getString("value"));
                        String colorStr = "null".equals(row.getJSONObject(5).optString("type","null"))
                                ? "#E53935"
                                : row.getJSONObject(5).optString("value","#E53935");
                        int color;
                        try { color = Color.parseColor(colorStr); }
                        catch (Exception ex) { color = 0xFFE53935; }
                        fresh.add(new LocationData(locId, name, lat, lng, radius, color));
                    }

                    if (getActivity() == null) return;
                    getActivity().runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            // ── Collect IDs that came back from the DB ───────
                            java.util.Set<String> dbIds = new HashSet<>();
                            for (LocationData ld : fresh) dbIds.add(ld.locId);

                            // ── Remove pins that were deleted from the DB ────
                            for (String localId : new HashSet<>(locationMarkers.keySet())) {
                                if (!dbIds.contains(localId)) {
                                    Marker m = locationMarkers.remove(localId);
                                    if (m != null) m.remove();
                                    Circle circle = locationCircles.remove(localId);
                                    if (circle != null) circle.remove();
                                    locationDataMap.remove(localId);
                                    activelyInsideLocIds.remove(localId);
                                }
                            }

                            // ── Add new pins; update changed pins ────────────
                            for (LocationData ld : fresh) {
                                LocationData existing = locationDataMap.get(ld.locId);
                                if (existing == null) {
                                    // Brand-new location — just draw it
                                    drawSavedLocation(ld.locId, ld.name,
                                            ld.lat, ld.lng, ld.radius, ld.color);
                                } else if (existing.color != ld.color
                                        || existing.radius != ld.radius
                                        || !existing.name.equals(ld.name)) {
                                    // Data changed (edit by another user) — redraw
                                    Marker old = locationMarkers.remove(ld.locId);
                                    if (old != null) old.remove();
                                    Circle oldC = locationCircles.remove(ld.locId);
                                    if (oldC != null) oldC.remove();
                                    locationDataMap.remove(ld.locId);
                                    drawSavedLocation(ld.locId, ld.name,
                                            ld.lat, ld.lng, ld.radius, ld.color);
                                }
                                // else: identical to what's on the map — skip
                            }
                        }
                    });

                } catch (Exception ignored) {}
            }
        }).start();
    }

    // drawSavedLocation — places a balloon-style marker and a translucent radius circle.
    // Skips silently if this location_id is already on the map.
    private void drawSavedLocation(String locId, String name,
                                   double lat, double lng, double radius, int color) {
        if (mMap == null || locationMarkers.containsKey(locId)) return;

        LatLng pos = new LatLng(lat, lng);

        Marker marker = mMap.addMarker(new MarkerOptions()
                .position(pos)
                .title(name)
                .icon(getLocationPinIcon(color))
                .anchor(0.5f, 1.0f)); // tip of balloon sits at the coordinate
        locationMarkers.put(locId, marker);
        if (marker != null) marker.setTag(locId);
        locationDataMap.put(locId, new LocationData(locId, name, lat, lng, radius, color));

        // Radius circle: transparent fill in the same color as the pin
        int fillColor = (color & 0x00FFFFFF) | 0x22000000; // 13% opacity
        Circle circle = mMap.addCircle(new CircleOptions()
                .center(pos)
                .radius(radius)
                .strokeColor(color)
                .strokeWidth(2f)
                .fillColor(fillColor));
        locationCircles.put(locId, circle);
    }

    // showAddLocationDialog — full styled dialog: dark card background, SeekBar for
    // radius, and a 6-color picker. Colors update the SeekBar tint and Save button live.
    private void showAddLocationDialog(LatLng latLng) {
        if (getActivity() == null) return;

        // ── Color palette ─────────────────────────────────────────────────
        final int[] palette = {
            0xFFE53935, // red
            0xFFFF9800, // orange
            0xFF4CAF50, // green
            0xFF2196F3, // blue
            0xFF9C27B0, // purple
            0xFFE91E63  // pink
        };
        final int[] selectedColor = {palette[0]};
        final View[] colorDots    = new View[palette.length];

        // ── Root card (dark rounded rectangle) ───────────────────────────
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dpToPx(20), dpToPx(18), dpToPx(20), dpToPx(8));
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setShape(GradientDrawable.RECTANGLE);
        cardBg.setCornerRadius(dpToPx(16));
        cardBg.setColor(0xFF161B22);
        root.setBackground(cardBg);

        // ── Title ─────────────────────────────────────────────────────────
        TextView tvTitle = new TextView(requireContext());
        tvTitle.setText("Save Location");
        tvTitle.setTextColor(0xFFE6EDF3);
        tvTitle.setTextSize(18);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setPadding(0, 0, 0, dpToPx(14));
        root.addView(tvTitle);

        // ── Name label ────────────────────────────────────────────────────
        TextView tvNameLabel = new TextView(requireContext());
        tvNameLabel.setText("Name");
        tvNameLabel.setTextColor(0xFF8B949E);
        tvNameLabel.setTextSize(11);
        root.addView(tvNameLabel);

        // ── Name input ────────────────────────────────────────────────────
        EditText etName = new EditText(requireContext());
        etName.setHint("e.g. Home, School, Office...");
        etName.setHintTextColor(0xFF484F58);
        etName.setTextColor(0xFFE6EDF3);
        etName.setSingleLine(true);
        GradientDrawable etBg = new GradientDrawable();
        etBg.setShape(GradientDrawable.RECTANGLE);
        etBg.setCornerRadius(dpToPx(8));
        etBg.setColor(0xFF0D1117);
        etBg.setStroke(dpToPx(1), 0xFF30363D);
        etName.setBackground(etBg);
        etName.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        LinearLayout.LayoutParams etLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        etLp.topMargin    = dpToPx(4);
        etLp.bottomMargin = dpToPx(18);
        etName.setLayoutParams(etLp);
        root.addView(etName);

        // ── Radius header row ─────────────────────────────────────────────
        LinearLayout radiusHeader = new LinearLayout(requireContext());
        radiusHeader.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvRadiusLabel = new TextView(requireContext());
        tvRadiusLabel.setText("Radius");
        tvRadiusLabel.setTextColor(0xFF8B949E);
        tvRadiusLabel.setTextSize(11);
        LinearLayout.LayoutParams rlLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tvRadiusLabel.setLayoutParams(rlLp);
        radiusHeader.addView(tvRadiusLabel);

        TextView tvRadiusValue = new TextView(requireContext());
        tvRadiusValue.setText("100 m");
        tvRadiusValue.setTextColor(selectedColor[0]);
        tvRadiusValue.setTextSize(12);
        radiusHeader.addView(tvRadiusValue);
        root.addView(radiusHeader);

        // ── SeekBar ───────────────────────────────────────────────────────
        SeekBar sbRadius = new SeekBar(requireContext());
        sbRadius.setMin(10);
        sbRadius.setMax(1000);
        sbRadius.setProgress(100);
        sbRadius.getProgressDrawable().setColorFilter(palette[0], PorterDuff.Mode.SRC_IN);
        sbRadius.getThumb().setColorFilter(palette[0], PorterDuff.Mode.SRC_IN);
        sbRadius.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int progress, boolean fromUser) {
                tvRadiusValue.setText(progress + " m");
            }
            @Override public void onStartTrackingTouch(SeekBar s) {}
            @Override public void onStopTrackingTouch(SeekBar s) {}
        });
        LinearLayout.LayoutParams sbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        sbLp.topMargin    = dpToPx(4);
        sbLp.bottomMargin = dpToPx(18);
        sbRadius.setLayoutParams(sbLp);
        root.addView(sbRadius);

        // ── Color picker label ────────────────────────────────────────────
        TextView tvColorLabel = new TextView(requireContext());
        tvColorLabel.setText("Pin Color");
        tvColorLabel.setTextColor(0xFF8B949E);
        tvColorLabel.setTextSize(11);
        tvColorLabel.setPadding(0, 0, 0, dpToPx(10));
        root.addView(tvColorLabel);

        // ── Color dot row ─────────────────────────────────────────────────
        LinearLayout colorRow = new LinearLayout(requireContext());
        colorRow.setOrientation(LinearLayout.HORIZONTAL);

        // Build Save button background reference now so color dots can update it
        GradientDrawable saveBtnBg = new GradientDrawable();
        saveBtnBg.setShape(GradientDrawable.RECTANGLE);
        saveBtnBg.setCornerRadius(dpToPx(8));
        saveBtnBg.setColor(selectedColor[0]);

        for (int i = 0; i < palette.length; i++) {
            final int thisColor = palette[i];
            View dot = new View(requireContext());
            colorDots[i] = dot;
            int dotSz = dpToPx(34);
            LinearLayout.LayoutParams dotLp = new LinearLayout.LayoutParams(dotSz, dotSz);
            dotLp.setMargins(0, 0, dpToPx(10), 0);
            dot.setLayoutParams(dotLp);

            GradientDrawable dotBg = new GradientDrawable();
            dotBg.setShape(GradientDrawable.OVAL);
            dotBg.setColor(thisColor);
            if (i == 0) dotBg.setStroke(dpToPx(3), 0xFFFFFFFF); // first selected by default
            dot.setBackground(dotBg);

            dot.setOnClickListener(v -> {
                selectedColor[0] = thisColor;
                // Restyle every dot — selected one gets a white ring
                for (int k = 0; k < palette.length; k++) {
                    GradientDrawable kBg = new GradientDrawable();
                    kBg.setShape(GradientDrawable.OVAL);
                    kBg.setColor(palette[k]);
                    if (palette[k] == selectedColor[0]) kBg.setStroke(dpToPx(3), 0xFFFFFFFF);
                    colorDots[k].setBackground(kBg);
                }
                // Tint SeekBar to match chosen color
                sbRadius.getProgressDrawable().setColorFilter(thisColor, PorterDuff.Mode.SRC_IN);
                sbRadius.getThumb().setColorFilter(thisColor, PorterDuff.Mode.SRC_IN);
                // Update radius value text color
                tvRadiusValue.setTextColor(thisColor);
                // Update Save button background
                saveBtnBg.setColor(thisColor);
            });
            colorRow.addView(dot);
        }

        LinearLayout.LayoutParams crLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        crLp.bottomMargin = dpToPx(20);
        colorRow.setLayoutParams(crLp);
        root.addView(colorRow);

        // ── Notification switches ──────────────────────────────────────────
        LinearLayout.LayoutParams swRowLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        swRowLp.bottomMargin = dpToPx(10);

        // Switch A: announce my arrival to the group
        LinearLayout announceRow = new LinearLayout(requireContext());
        announceRow.setOrientation(LinearLayout.HORIZONTAL);
        announceRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView announceLabel = new TextView(requireContext());
        announceLabel.setText("\uD83D\uDCE3  Announce my arrival");
        announceLabel.setTextColor(0xFFE6EDF3);
        announceLabel.setTextSize(14);
        announceRow.addView(announceLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch addAnnounceSwitch = new android.widget.Switch(requireContext());
        addAnnounceSwitch.setChecked(true);
        addAnnounceSwitch.setThumbTintList(
                android.content.res.ColorStateList.valueOf(0xFF58A6FF));
        addAnnounceSwitch.setTrackTintList(
                android.content.res.ColorStateList.valueOf(0xFF30363D));
        announceRow.addView(addAnnounceSwitch);
        root.addView(announceRow, swRowLp);

        // Switch B: notify me when others arrive here
        LinearLayout.LayoutParams swRowLp2 = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        swRowLp2.bottomMargin = dpToPx(20);
        LinearLayout receiveRow = new LinearLayout(requireContext());
        receiveRow.setOrientation(LinearLayout.HORIZONTAL);
        receiveRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView receiveLabel = new TextView(requireContext());
        receiveLabel.setText("\uD83D\uDD14  Notify me when others arrive");
        receiveLabel.setTextColor(0xFFE6EDF3);
        receiveLabel.setTextSize(14);
        receiveRow.addView(receiveLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        android.widget.Switch addNotifSwitch = new android.widget.Switch(requireContext());
        addNotifSwitch.setChecked(true);
        addNotifSwitch.setThumbTintList(
                android.content.res.ColorStateList.valueOf(0xFF58A6FF));
        addNotifSwitch.setTrackTintList(
                android.content.res.ColorStateList.valueOf(0xFF30363D));
        receiveRow.addView(addNotifSwitch);
        root.addView(receiveRow, swRowLp2);

        // ── Buttons ───────────────────────────────────────────────────────
        LinearLayout btnRow = new LinearLayout(requireContext());
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setGravity(Gravity.END);

        TextView btnCancel = new TextView(requireContext());
        btnCancel.setText("Cancel");
        btnCancel.setTextColor(0xFF8B949E);
        btnCancel.setTextSize(14);
        btnCancel.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));

        TextView btnSave = new TextView(requireContext());
        btnSave.setText("Save");
        btnSave.setTextColor(0xFFFFFFFF);
        btnSave.setTextSize(14);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));
        btnSave.setBackground(saveBtnBg);

        btnRow.addView(btnCancel);
        btnRow.addView(btnSave);
        root.addView(btnRow);

        // ── Show dialog with transparent background so card corners show ──
        final SeekBar finalSb      = sbRadius;
        final EditText finalEtName = etName;

        AlertDialog dialog = new AlertDialog.Builder(requireActivity())
                .setView(root)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }

        btnCancel.setOnClickListener(v -> dialog.dismiss());
        btnSave.setOnClickListener(v -> {
            String name = finalEtName.getText().toString().trim();
            if (name.isEmpty()) {
                Toast.makeText(requireContext(), "Please enter a name", Toast.LENGTH_SHORT).show();
                return;
            }
            // Generate the ID here so we can immediately apply the mute preference
            String newLocId = UUID.randomUUID().toString();
            boolean changed = false;
            if (!addAnnounceSwitch.isChecked()) {
                hiddenAnnounceLocIds.add(newLocId); changed = true;
            }
            if (!addNotifSwitch.isChecked()) {
                mutedLocationIds.add(newLocId); changed = true;
            }
            if (changed) saveMutedLocations();
            saveGroupLocation(newLocId, name, latLng, finalSb.getProgress(), selectedColor[0]);
            dialog.dismiss();
        });

        dialog.show();
    }

    // dpToPx — converts density-independent pixels to real screen pixels.
    private int dpToPx(int dp) {
        float density = requireContext().getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }

    // saveGroupLocation — inserts a new location into the DB and draws it immediately.
    private void saveGroupLocation(String locationId, String name, LatLng latLng, double radius, int color) {
        if (gid == null || gid.isEmpty()) {
            Toast.makeText(requireContext(), "No group selected", Toast.LENGTH_SHORT).show();
            return;
        }
        String colorHex   = String.format("#%06X", 0xFFFFFF & color);

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    tursoQuery(
                        "INSERT INTO group_locations " +
                        "(location_id, gid, name, lat, lng, radius, color, created_by) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                        new Object[]{
                            locationId, gid, name,
                            String.valueOf(latLng.latitude),
                            String.valueOf(latLng.longitude),
                            String.valueOf(radius),
                            colorHex, uid
                        }
                    );

                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                drawSavedLocation(locationId, name,
                                        latLng.latitude, latLng.longitude, radius, color);
                                Toast.makeText(requireContext(),
                                        "\"" + name + "\" saved", Toast.LENGTH_SHORT).show();
                            }
                        });
                    }
                } catch (Exception e) {
                    android.util.Log.e("BoltApp", "saveGroupLocation failed: " + e.getMessage(), e);
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                Toast.makeText(requireContext(),
                                        "Failed to save: " + e.getMessage(), Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            }
        }).start();
    }

    // getLocationPinIcon — returns (and caches) the balloon-style marker icon
    // used for saved locations, tinted with the given color.
    // Uses the same ic_user_pin vector drawable as the original user pins.
    // getLocationPinIcon — classic teardrop map-pin with a white inner circle.
    // The drop shape is drawn with quadratic bezier curves so it tapers
    // smoothly to a point at the bottom, just like a standard map marker.
    private BitmapDescriptor getLocationPinIcon(int color) {
        if (locationPinCache.containsKey(color)) return locationPinCache.get(color);

        int r      = dpToPx(20); // head radius
        int pad    = dpToPx(3);  // anti-clip padding
        int W      = (r + pad) * 2;
        int tailH  = (int)(r * 1.4f);
        int H      = r * 2 + tailH + pad * 2;
        float cx   = W / 2f;
        float cy   = r + pad;      // centre of the circular head
        float tipY = H - pad;      // sharp tip at the bottom

        Bitmap bmp    = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);

        // ── Classic pin shape ────────────────────────────────────────────
        // Two straight edges from the tip to the lower-left/right of the
        // circle (at the ±120° / ±60° positions), then a 300° arc over the top.
        android.graphics.RectF oval =
            new android.graphics.RectF(cx - r, cy - r, cx + r, cy + r);

        // Entry/exit points on the circle where the straight sides begin
        // 120° in Android canvas coords = lower-left (x = cx - r*0.5, y = cy + r*0.866)
        // 60°  in Android canvas coords = lower-right (x = cx + r*0.5, y = cy + r*0.866)
        float lx = cx - r * 0.50f;
        float ly = cy + r * 0.866f;
        float rx = cx + r * 0.50f;

        Path drop = new Path();
        drop.moveTo(cx, tipY);          // start at sharp tip
        drop.lineTo(lx, ly);            // straight left edge up to circle
        drop.arcTo(oval, 120f, 300f, false); // 300° clockwise arc over the top
        drop.lineTo(cx, tipY);          // straight right edge back to tip
        drop.close();

        Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(color);
        canvas.drawPath(drop, fill);

        // ── White stroke outline ─────────────────────────────────────────
        Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeWidth(dpToPx(2));
        stroke.setColor(0xFFFFFFFF);
        canvas.drawPath(drop, stroke);

        // ── Inner white circle (the dot in the middle of the head) ───────
        Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
        dot.setStyle(Paint.Style.FILL);
        dot.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, r * 0.35f, dot);

        BitmapDescriptor icon = BitmapDescriptorFactory.fromBitmap(bmp);
        locationPinCache.put(color, icon);
        return icon;
    }


    // ══════════════════════════════════════════════════════════════════════
    // TURSO DATABASE QUERY
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
                JSONObject argObject = new JSONObject();
                if (arg == null || (arg instanceof String && ((String) arg).isEmpty())) {
                    argObject.put("type", "null");
                } else if (arg instanceof Integer) {
                    argObject.put("type", "integer");
                    argObject.put("value", String.valueOf(arg));
                } else {
                    argObject.put("type", "text");
                    argObject.put("value", String.valueOf(arg));
                }
                argsArray.put(argObject);
            }
        }

        JSONObject statement = new JSONObject();
        statement.put("sql", sql);
        statement.put("args", argsArray);

        JSONObject executeRequest = new JSONObject();
        executeRequest.put("type", "execute");
        executeRequest.put("stmt", statement);

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
        InputStream inputStream = responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream();

        StringBuilder responseText = new StringBuilder();
        byte[] buffer = new byte[4096];
        int bytesRead;
        while ((bytesRead = inputStream.read(buffer)) != -1) {
            responseText.append(new String(buffer, 0, bytesRead, StandardCharsets.UTF_8));
        }

        if (responseCode >= 400) {
            throw new Exception("Turso error " + responseCode + ": " + responseText);
        }

        // Turso returns HTTP 200 even for logical errors — check the body too
        JSONObject parsed = new JSONObject(responseText.toString());
        JSONArray results = parsed.optJSONArray("results");
        if (results != null && results.length() > 0) {
            JSONObject first = results.getJSONObject(0);
            if ("error".equals(first.optString("type"))) {
                JSONObject err = first.optJSONObject("error");
                String msg = err != null ? err.optString("message", "unknown error") : "unknown error";
                throw new Exception("Turso query error: " + msg + " | SQL: " + sql);
            }
        }
        return parsed;
    }

    // ══════════════════════════════════════════════════════════════════════
    // EDIT / DELETE SAVED LOCATION
    // ══════════════════════════════════════════════════════════════════════

    /** Opens a pre-filled dark dialog to edit name, radius, and color of a saved location. */
    private void showEditLocationDialog(LocationData data) {
        if (getActivity() == null) return;

        // ── Color palette (same order as Add dialog) ──────────────────────
        final int[] palette = {
            0xFFE53935, 0xFFFF9800, 0xFF4CAF50,
            0xFF2196F3, 0xFF9C27B0, 0xFFE91E63
        };
        final int[] selectedColor = { data.color };
        final int[] selectedIdx   = { 0 };
        for (int i = 0; i < palette.length; i++) {
            if (palette[i] == data.color) { selectedIdx[0] = i; break; }
        }

        // ── Root card ─────────────────────────────────────────────────────
        android.widget.LinearLayout card = new android.widget.LinearLayout(getActivity());
        card.setOrientation(android.widget.LinearLayout.VERTICAL);
        card.setPadding(dpToPx(24), dpToPx(24), dpToPx(24), dpToPx(20));
        android.graphics.drawable.GradientDrawable cardBg = new android.graphics.drawable.GradientDrawable();
        cardBg.setColor(0xFF161B22);
        cardBg.setCornerRadius(dpToPx(16));
        card.setBackground(cardBg);

        // ── Title ─────────────────────────────────────────────────────────
        android.widget.TextView title = new android.widget.TextView(getActivity());
        title.setText("Edit Location");
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setPadding(0, 0, 0, dpToPx(16));
        card.addView(title);

        // ── Name field ────────────────────────────────────────────────────
        android.widget.EditText nameField = new android.widget.EditText(getActivity());
        nameField.setText(data.name);
        nameField.setHint("Location name");
        nameField.setHintTextColor(0xFF8B949E);
        nameField.setTextColor(0xFFFFFFFF);
        nameField.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15);
        nameField.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        nameField.setSingleLine(true);
        android.graphics.drawable.GradientDrawable fieldBg = new android.graphics.drawable.GradientDrawable();
        fieldBg.setColor(0xFF0D1117);
        fieldBg.setCornerRadius(dpToPx(8));
        fieldBg.setStroke(dpToPx(1), 0xFF30363D);
        nameField.setBackground(fieldBg);
        nameField.setPadding(dpToPx(12), dpToPx(10), dpToPx(12), dpToPx(10));
        android.widget.LinearLayout.LayoutParams fieldParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        fieldParams.bottomMargin = dpToPx(16);
        card.addView(nameField, fieldParams);

        // ── Radius label + value ──────────────────────────────────────────
        android.widget.LinearLayout radiusRow = new android.widget.LinearLayout(getActivity());
        radiusRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.TextView radiusLabel = new android.widget.TextView(getActivity());
        radiusLabel.setText("Radius: ");
        radiusLabel.setTextColor(0xFF8B949E);
        radiusLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        final android.widget.TextView radiusValue = new android.widget.TextView(getActivity());
        radiusValue.setText((int) data.radius + " m");
        radiusValue.setTextColor(selectedColor[0]);
        radiusValue.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        radiusRow.addView(radiusLabel);
        radiusRow.addView(radiusValue);
        android.widget.LinearLayout.LayoutParams rowParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = dpToPx(6);
        card.addView(radiusRow, rowParams);

        // ── SeekBar ───────────────────────────────────────────────────────
        final android.widget.SeekBar seekBar = new android.widget.SeekBar(getActivity());
        seekBar.setMin(10);
        seekBar.setMax(1000);
        seekBar.setProgress((int) data.radius);
        android.widget.LinearLayout.LayoutParams seekParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        seekParams.bottomMargin = dpToPx(20);
        seekBar.setOnSeekBarChangeListener(new android.widget.SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(android.widget.SeekBar sb, int p, boolean u) {
                radiusValue.setText(p + " m");
            }
            @Override public void onStartTrackingTouch(android.widget.SeekBar sb) {}
            @Override public void onStopTrackingTouch(android.widget.SeekBar sb) {}
        });
        card.addView(seekBar, seekParams);

        // ── Color picker ──────────────────────────────────────────────────
        android.widget.TextView colorLabel = new android.widget.TextView(getActivity());
        colorLabel.setText("Color");
        colorLabel.setTextColor(0xFF8B949E);
        colorLabel.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13);
        colorLabel.setPadding(0, 0, 0, dpToPx(8));
        card.addView(colorLabel);

        android.widget.LinearLayout colorRow = new android.widget.LinearLayout(getActivity());
        colorRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        android.widget.LinearLayout.LayoutParams colorRowParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        colorRowParams.bottomMargin = dpToPx(24);

        int dotSize = dpToPx(34);
        android.widget.ImageView[] dots = new android.widget.ImageView[palette.length];
        for (int i = 0; i < palette.length; i++) {
            final int idx = i;
            android.widget.ImageView dot = new android.widget.ImageView(getActivity());
            android.graphics.drawable.GradientDrawable dotBg = new android.graphics.drawable.GradientDrawable();
            dotBg.setShape(android.graphics.drawable.GradientDrawable.OVAL);
            dotBg.setColor(palette[i]);
            if (i == selectedIdx[0]) dotBg.setStroke(dpToPx(3), 0xFFFFFFFF);
            dot.setBackground(dotBg);
            android.widget.LinearLayout.LayoutParams dp =
                new android.widget.LinearLayout.LayoutParams(dotSize, dotSize);
            dp.rightMargin = dpToPx(10);
            dot.setOnClickListener(v2 -> {
                selectedColor[0] = palette[idx];
                selectedIdx[0]   = idx;
                radiusValue.setTextColor(palette[idx]);
                // Update SeekBar tint
                android.content.res.ColorStateList csl =
                    android.content.res.ColorStateList.valueOf(palette[idx]);
                seekBar.setProgressTintList(csl);
                seekBar.setThumbTintList(csl);
                // Re-draw dots
                for (int j = 0; j < dots.length; j++) {
                    android.graphics.drawable.GradientDrawable d2 =
                        new android.graphics.drawable.GradientDrawable();
                    d2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    d2.setColor(palette[j]);
                    if (j == idx) d2.setStroke(dpToPx(3), 0xFFFFFFFF);
                    dots[j].setBackground(d2);
                }
            });
            dots[i] = dot;
            colorRow.addView(dot, dp);
        }
        card.addView(colorRow, colorRowParams);

        // Apply initial SeekBar tint
        android.content.res.ColorStateList initCsl =
            android.content.res.ColorStateList.valueOf(selectedColor[0]);
        seekBar.setProgressTintList(initCsl);
        seekBar.setThumbTintList(initCsl);

        // ── Switch A: announce my arrival ────────────────────────────────
        android.widget.LinearLayout announceEditRow = new android.widget.LinearLayout(getActivity());
        announceEditRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        announceEditRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams announceEditLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        announceEditLp.bottomMargin = dpToPx(10);

        android.widget.TextView announceLbl = new android.widget.TextView(getActivity());
        announceLbl.setText("\uD83D\uDCE3  Announce my arrival");
        announceLbl.setTextColor(0xFFE6EDF3);
        announceLbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        announceEditRow.addView(announceLbl, new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch announceSwitch = new android.widget.Switch(getActivity());
        announceSwitch.setChecked(!hiddenAnnounceLocIds.contains(data.locId));
        android.content.res.ColorStateList sCsl =
            android.content.res.ColorStateList.valueOf(0xFF58A6FF);
        announceSwitch.setThumbTintList(sCsl);
        announceSwitch.setTrackTintList(
            android.content.res.ColorStateList.valueOf(0xFF30363D));
        announceEditRow.addView(announceSwitch);
        card.addView(announceEditRow, announceEditLp);

        // ── Switch B: notify me when others arrive ────────────────────────
        android.widget.LinearLayout notifEditRow = new android.widget.LinearLayout(getActivity());
        notifEditRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        notifEditRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        android.widget.LinearLayout.LayoutParams notifEditLp =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        notifEditLp.bottomMargin = dpToPx(20);

        android.widget.TextView notifLbl = new android.widget.TextView(getActivity());
        notifLbl.setText("\uD83D\uDD14  Notify me when others arrive");
        notifLbl.setTextColor(0xFFE6EDF3);
        notifLbl.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        notifEditRow.addView(notifLbl, new android.widget.LinearLayout.LayoutParams(
            0, android.widget.LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        android.widget.Switch notifySwitch = new android.widget.Switch(getActivity());
        notifySwitch.setChecked(!mutedLocationIds.contains(data.locId));
        notifySwitch.setThumbTintList(sCsl);
        notifySwitch.setTrackTintList(
            android.content.res.ColorStateList.valueOf(0xFF30363D));
        notifEditRow.addView(notifySwitch);
        card.addView(notifEditRow, notifEditLp);

        // ── Button row ────────────────────────────────────────────────────
        android.widget.LinearLayout btnRow = new android.widget.LinearLayout(getActivity());
        btnRow.setOrientation(android.widget.LinearLayout.HORIZONTAL);
        btnRow.setGravity(android.view.Gravity.END);

        // Delete button
        android.widget.TextView deleteBtn = new android.widget.TextView(getActivity());
        deleteBtn.setText("Delete");
        deleteBtn.setTextColor(0xFFFF5252);
        deleteBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        deleteBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        deleteBtn.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
        android.widget.LinearLayout.LayoutParams deleteBtnParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        deleteBtnParams.rightMargin = dpToPx(8);

        // Cancel button
        android.widget.TextView cancelBtn = new android.widget.TextView(getActivity());
        cancelBtn.setText("Cancel");
        cancelBtn.setTextColor(0xFF8B949E);
        cancelBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        cancelBtn.setPadding(dpToPx(16), dpToPx(10), dpToPx(16), dpToPx(10));
        android.widget.LinearLayout.LayoutParams cancelBtnParams =
            new android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT);
        cancelBtnParams.rightMargin = dpToPx(8);

        // Save button
        android.widget.TextView saveBtn = new android.widget.TextView(getActivity());
        saveBtn.setText("Save");
        saveBtn.setTextColor(0xFFFFFFFF);
        saveBtn.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14);
        saveBtn.setTypeface(null, android.graphics.Typeface.BOLD);
        saveBtn.setPadding(dpToPx(20), dpToPx(10), dpToPx(20), dpToPx(10));
        android.graphics.drawable.GradientDrawable saveBg = new android.graphics.drawable.GradientDrawable();
        saveBg.setColor(selectedColor[0]);
        saveBg.setCornerRadius(dpToPx(8));
        saveBtn.setBackground(saveBg);

        // Update save button color when color changes
        for (int i = 0; i < dots.length; i++) {
            final int idx = i;
            dots[i].setOnClickListener(v2 -> {
                selectedColor[0] = palette[idx];
                selectedIdx[0]   = idx;
                radiusValue.setTextColor(palette[idx]);
                android.content.res.ColorStateList csl2 =
                    android.content.res.ColorStateList.valueOf(palette[idx]);
                seekBar.setProgressTintList(csl2);
                seekBar.setThumbTintList(csl2);
                saveBg.setColor(palette[idx]);
                for (int j = 0; j < dots.length; j++) {
                    android.graphics.drawable.GradientDrawable d2 =
                        new android.graphics.drawable.GradientDrawable();
                    d2.setShape(android.graphics.drawable.GradientDrawable.OVAL);
                    d2.setColor(palette[j]);
                    if (j == idx) d2.setStroke(dpToPx(3), 0xFFFFFFFF);
                    dots[j].setBackground(d2);
                }
            });
        }

        btnRow.addView(deleteBtn, deleteBtnParams);
        btnRow.addView(cancelBtn, cancelBtnParams);
        btnRow.addView(saveBtn);
        card.addView(btnRow);

        // ── Show dialog ───────────────────────────────────────────────────
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(getActivity())
            .setView(card)
            .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        deleteBtn.setOnClickListener(v2 -> {
            dialog.dismiss();
            deleteGroupLocation(data.locId);
        });
        cancelBtn.setOnClickListener(v2 -> dialog.dismiss());
        saveBtn.setOnClickListener(v2 -> {
            String newName = nameField.getText().toString().trim();
            if (newName.isEmpty()) {
                android.widget.Toast.makeText(getActivity(),
                    "Please enter a name", android.widget.Toast.LENGTH_SHORT).show();
                return;
            }
            int newRadius = seekBar.getProgress();
            int newColor  = selectedColor[0];
            // Persist announce preference
            if (announceSwitch.isChecked()) {
                hiddenAnnounceLocIds.remove(data.locId);
            } else {
                hiddenAnnounceLocIds.add(data.locId);
            }
            // Persist receive-notification preference
            if (notifySwitch.isChecked()) {
                mutedLocationIds.remove(data.locId);
            } else {
                mutedLocationIds.add(data.locId);
            }
            saveMutedLocations();
            dialog.dismiss();
            updateGroupLocation(data.locId, newName, newRadius, newColor, data.lat, data.lng);
        });

        dialog.show();
    }

    /** Persists an edit to a saved location in the DB and refreshes the pin on the map. */
    private void updateGroupLocation(String locId, String name, int radius, int color,
                                     double lat, double lng) {
        String colorHex = String.format("#%06X", 0xFFFFFF & color);
        new Thread(() -> {
            try {
                tursoQuery(
                    "UPDATE group_locations SET name=?, radius=?, color=? WHERE location_id=?",
                    new Object[]{ name, String.valueOf(radius), colorHex, locId });
            } catch (Exception e) {
                android.util.Log.e("BoltApp", "updateGroupLocation failed", e);
            }
        }).start();

        // Refresh the pin on the main thread
        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            // Remove old marker + circle
            Marker oldMarker = locationMarkers.remove(locId);
            if (oldMarker != null) oldMarker.remove();
            Circle oldCircle = locationCircles.remove(locId);
            if (oldCircle != null) oldCircle.remove();
            locationDataMap.remove(locId);
            // Bust the pin icon cache for old color so it doesn't get reused incorrectly
            // (new color will just be generated fresh)
            drawSavedLocation(locId, name, lat, lng, radius, color);
        });
    }

    /** Deletes a saved location from the DB and removes its pin from the map. */
    private void deleteGroupLocation(String locId) {
        new Thread(() -> {
            try {
                tursoQuery(
                    "DELETE FROM group_locations WHERE location_id=?",
                    new Object[]{ locId });
            } catch (Exception e) {
                android.util.Log.e("BoltApp", "deleteGroupLocation failed", e);
            }
        }).start();

        if (getActivity() == null) return;
        getActivity().runOnUiThread(() -> {
            Marker m = locationMarkers.remove(locId);
            if (m != null) m.remove();
            Circle c = locationCircles.remove(locId);
            if (c != null) c.remove();
            locationDataMap.remove(locId);
        });
    }

    /**
     * Called when the user responds to the POST_NOTIFICATIONS permission prompt.
     * No extra action needed — if denied, notifications are simply suppressed.
     */
    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        // requestCode 200 = POST_NOTIFICATIONS (see requestNotificationPermission)
        // Nothing extra to do — NotificationManagerCompat will silently skip
        // posting if the permission was denied.
    }

    // ── LocationData — lightweight holder for a saved group location ──────────
    private static class LocationData {
        final String locId;
        final String name;
        final double lat, lng, radius;
        final int    color;

        LocationData(String locId, String name, double lat, double lng,
                     double radius, int color) {
            this.locId  = locId;
            this.name   = name;
            this.lat    = lat;
            this.lng    = lng;
            this.radius = radius;
            this.color  = color;
        }
    }
}
