package com.example.boltapp;

import android.Manifest;                // gives us the names of Android permissions
import android.annotation.SuppressLint; // suppresses certain warnings from the compiler
import android.app.PendingIntent;       // a wrapped Intent that another app/service can trigger later
import android.content.BroadcastReceiver; // receives system-wide broadcast messages
import android.content.Context;         // gives access to app resources and services
import android.content.Intent;          // used to communicate between Android components
import android.content.IntentFilter;   // describes which broadcast messages to receive
import android.content.pm.PackageManager; // used to check if a permission is granted
import android.graphics.Bitmap;        // represents an image in memory
import android.graphics.Canvas;        // lets us draw on a Bitmap
import android.graphics.Paint;         // defines how text/shapes are drawn (size, color, etc.)
import android.graphics.PorterDuff;    // defines how colors are blended together
import android.graphics.drawable.Drawable; // the base class of things you can draw
import android.location.Location;      // holds a GPS location (lat, lng, speed, etc.)
import android.os.Build;              // lets us check the Android version at runtime
import android.os.Bundle;             // holds saved state / data passed between screens
import android.os.Handler;            // schedules code to run on the main thread
import android.os.Looper;             // provides the main thread's message loop
import android.view.LayoutInflater;   // builds Views from XML layout files
import android.view.View;             // the base class of every UI element
import android.view.ViewGroup;        // a container that holds other views

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
import java.util.List;                 // the List interface
import java.util.Map;                  // the Map interface

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
    private static final int MEMBER_REFRESH_MS = 40;

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

        // Ask the user for GPS permission (or start tracking if already granted)
        requestLocationPermissions();
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
    // MARKER ICON BUILDER
    // Creates a custom map marker icon from a vector drawable + optional emoji.
    // ══════════════════════════════════════════════════════════════════════

    // getMarkerIconWithStatus — builds a Bitmap from a vector drawable and optionally
    // adds a 🚶 or 🚗 emoji next to the pin to indicate the movement status.
    private BitmapDescriptor getMarkerIconWithStatus(Context context, int color, String status) {
        // Load the pin drawable from the res/drawable folder
        Drawable vectorDrawable = ContextCompat.getDrawable(context, R.drawable.ic_user_pin);
        if (vectorDrawable == null) {
            return BitmapDescriptorFactory.defaultMarker(); // fall back to default red pin
        }

        int width  = vectorDrawable.getIntrinsicWidth();  // the drawable's natural width
        int height = vectorDrawable.getIntrinsicHeight(); // the drawable's natural height
        vectorDrawable.setBounds(0, 0, width, height);    // tell it where to draw itself

        // Tint (colorize) the drawable with the status color
        Drawable tintedDrawable = DrawableCompat.wrap(vectorDrawable).mutate();
        DrawableCompat.setTint(tintedDrawable, color);
        DrawableCompat.setTintMode(tintedDrawable, PorterDuff.Mode.MULTIPLY);

        // Pick the emoji to show next to the pin (empty string = no emoji for "still")
        String emoji = "";
        if ("walking".equals(status)) emoji = "\uD83D\uDEB6"; // 🚶
        else if ("driving".equals(status)) emoji = "\uD83D\uDE97"; // 🚗

        // If there's an emoji, make the bitmap wider to fit it
        int finalWidth = emoji.isEmpty() ? width : width + (width / 2);
        Bitmap bitmap = Bitmap.createBitmap(finalWidth, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);

        // Draw the pin onto the canvas
        tintedDrawable.draw(canvas);

        // Draw the emoji to the right of the pin
        if (!emoji.isEmpty()) {
            Paint paint = new Paint();
            paint.setTextSize(width / 2f);              // font size proportional to pin size
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(emoji, width, height / 3f, paint); // draw emoji in upper right
        }

        return BitmapDescriptorFactory.fromBitmap(bitmap);
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
    // We clear the old members' markers and update our group ID/name.
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

        memberMarkers.clear(); // clear our record of the old markers

        // The next refresh cycle will automatically fetch the new group's members
    }


    // ══════════════════════════════════════════════════════════════════════
    // TURSO DATABASE QUERY (see DrivingReportsFragment for full comments)
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

        return new JSONObject(responseText.toString());
    }
}
