package com.example.boltapp;

import android.graphics.Color;           // lets us set colors using hex codes like Color.WHITE
import android.os.Bundle;               // holds arguments passed to the fragment
import android.os.Handler;              // lets us schedule repeated tasks on the main thread
import android.os.Looper;              // gives us the main thread's message loop
import android.view.Gravity;           // positions views (e.g. top-left, top-right, bottom-center)
import android.view.View;              // the base class of all UI elements
import android.view.ViewGroup;         // a View that can hold child views
import android.widget.FrameLayout;     // a container that stacks views on top of each other
import android.widget.LinearLayout;    // a container that arranges views in a row or column
import android.widget.SeekBar;         // a slider the user can drag to scrub through the replay
import android.widget.TextView;        // a view that shows text on screen

import androidx.annotation.NonNull;            // marks a parameter that must never be null
import androidx.annotation.Nullable;           // marks a parameter that is allowed to be null
import androidx.fragment.app.Fragment;         // base class for all fragments

import com.google.android.gms.maps.CameraUpdateFactory; // moves/zooms the map camera
import com.google.android.gms.maps.GoogleMap;           // the actual map object
import com.google.android.gms.maps.OnMapReadyCallback;  // called when the map is ready to use
import com.google.android.gms.maps.SupportMapFragment;  // hosts the map inside a Fragment
import com.google.android.gms.maps.model.BitmapDescriptorFactory; // lets us tint markers
import com.google.android.gms.maps.model.LatLng;        // a latitude + longitude pair
import com.google.android.gms.maps.model.Marker;        // a pin on the map
import com.google.android.gms.maps.model.MarkerOptions; // settings for creating a marker
import com.google.android.gms.maps.model.PolylineOptions; // settings for creating a polyline
import com.google.android.gms.maps.model.RoundCap;     // makes the ends of the polyline rounded

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset
import java.util.ArrayList;            // a resizable list
import java.util.List;                 // the list interface

// ══════════════════════════════════════════════════════════════════════════════
// DriveReplayFragment
//
// This fragment shows an animated replay of a single past driving session.
// It is opened from DrivingReportsFragment when the user presses "Replay".
//
// What it does:
//   1. Loads all GPS points for a session from the `driving_points` table
//   2. Draws the full route as a grey polyline on a clean Google Map
//   3. Animates a car marker along the route at ~8x real-time speed
//   4. Shows the current speed and elapsed time as overlaid text
//   5. Shows a SeekBar (scrubber) so the user can jump to any point
//   6. Has a Back button to close and return to the reports list
//
// The fragment is launched via MainActivity.openDriveReplay(sessionId).
// ══════════════════════════════════════════════════════════════════════════════
public class DriveReplayFragment extends Fragment implements OnMapReadyCallback {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── Replay speed constants ──────────────────────────────────────────
    // PLAYBACK_MULTIPLIER: how many times faster than real life the replay runs.
    // 8x means 1 second of replay = 8 seconds of the original drive.
    private static final int PLAYBACK_MULTIPLIER = 8;

    // ── Argument key ───────────────────────────────────────────────────
    // When creating this fragment, the caller puts the session ID into a Bundle
    // with this key so we know which session to load.
    public static final String ARG_SESSION_ID = "session_id";

    // ── State ──────────────────────────────────────────────────────────
    private String sessionId;              // the session we are replaying
    private GoogleMap googleMap;           // the live Google Map object
    private SupportMapFragment mapFragment; // the fragment that holds the map view

    // ── GPS replay data ─────────────────────────────────────────────────
    // Each ReplayPoint holds one recorded GPS reading: position + speed.
    private final List<ReplayPoint> points = new ArrayList<>();

    // ── Playback state ──────────────────────────────────────────────────
    private int  currentIndex  = 0;    // which point in `points` we are currently showing
    private boolean isPlaying  = false; // true = replay is running; false = paused/stopped

    // ── UI references ───────────────────────────────────────────────────
    private TextView  tvSpeed;    // shows "XX km/h" in the top-right corner
    private TextView  tvTime;     // shows "00:00 / 12:34" elapsed/total time in top-left
    private SeekBar   seekBar;    // the scrubber at the bottom
    private TextView  btnPlayPause; // "▶" or "⏸" play/pause button
    private Marker    carMarker;  // the animated marker that moves along the route

    // ── Handler for the animation loop ─────────────────────────────────
    // The Handler lets us post a Runnable to run on the main thread after a delay.
    // We use it to advance the marker one step at a time.
    private final Handler handler = new Handler(Looper.getMainLooper());

    // The Runnable that advances the replay by one point each tick.
    // It re-posts itself with a calculated delay to match the recorded time gaps.
    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            if (!isPlaying) return; // if paused, stop the loop (it will be restarted on play)
            if (currentIndex >= points.size()) {
                // We've reached the end of the replay — stop automatically
                isPlaying = false;
                btnPlayPause.setText("▶");
                return;
            }

            // Move the marker to the current point and update the overlays
            moveTo(currentIndex);

            // Calculate how long to wait before the next step.
            // We look at the real time gap between consecutive GPS points
            // and divide by the playback multiplier to speed it up.
            long delayMs = 1000; // default 1 second between steps
            if (currentIndex + 1 < points.size()) {
                long realGapMs = getTimeDiffMs(points.get(currentIndex).timestamp,
                        points.get(currentIndex + 1).timestamp);
                if (realGapMs > 0) {
                    delayMs = realGapMs / PLAYBACK_MULTIPLIER;
                }
                // Clamp between 50ms (very fast) and 2000ms (very slow) to avoid edge cases
                if (delayMs < 50)   delayMs = 50;
                if (delayMs > 2000) delayMs = 2000;
            }

            currentIndex++; // advance to the next point
            seekBar.setProgress(currentIndex); // keep the scrubber in sync

            // Schedule this same Runnable to run again after the calculated delay
            handler.postDelayed(this, delayMs);
        }
    };


    // ══════════════════════════════════════════════════════════════════════
    // INNER CLASS — ReplayPoint
    // ══════════════════════════════════════════════════════════════════════

    // ReplayPoint holds the data for a single recorded GPS moment in the drive.
    private static class ReplayPoint {
        int    sequence;   // the order in which this point was recorded (1, 2, 3, …)
        String timestamp;  // when this point was recorded (e.g. "2025-01-15 14:30:00")
        double lat;        // GPS latitude
        double lng;        // GPS longitude
        float  speedKmh;  // speed at this moment, in km/h
    }


    // ══════════════════════════════════════════════════════════════════════
    // FACTORY METHOD — newInstance
    // ══════════════════════════════════════════════════════════════════════

    // newInstance — the standard Android way to create a Fragment with arguments.
    // Instead of calling `new DriveReplayFragment()` and then setting fields,
    // callers use this method. It packages the session ID into a Bundle so Android
    // can recreate the fragment (with its arguments) after a screen rotation.
    public static DriveReplayFragment newInstance(String sessionId) {
        DriveReplayFragment fragment = new DriveReplayFragment();
        Bundle args = new Bundle();
        args.putString(ARG_SESSION_ID, sessionId); // store the session ID
        fragment.setArguments(args);               // attach it to the fragment
        return fragment;
    }


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called first, before the view is created.
    // This is where we read our arguments (the session ID).
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getArguments() != null) {
            sessionId = getArguments().getString(ARG_SESSION_ID);
        }
    }

    // onCreateView — builds and returns the fragment's view tree programmatically.
    // We use a FrameLayout as the root so we can stack the map underneath the UI overlays.
    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // ── Root: FrameLayout fills the whole screen ─────────────────────
        // FrameLayout lets us layer children on top of each other (map first, then controls).
        FrameLayout root = new FrameLayout(requireContext());
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, // fill width
                ViewGroup.LayoutParams.MATCH_PARENT  // fill height
        ));
        root.setBackgroundColor(0xFF0D1117); // dark background (shown briefly before map loads)

        // ── Embed the Google Map ─────────────────────────────────────────
        // A SupportMapFragment needs a VIEW CONTAINER with an ID so the system knows
        // where to inflate its view. Without a container ID, the map's view is never
        // created and the screen stays blank.
        //
        // We create a FrameLayout, give it a generated ID, add it to root, and then
        // add the SupportMapFragment INTO that container.
        FrameLayout mapContainer = new FrameLayout(requireContext());
        int mapContainerId = View.generateViewId(); // generate a unique ID at runtime
        mapContainer.setId(mapContainerId);
        root.addView(mapContainer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        mapFragment = SupportMapFragment.newInstance();
        getChildFragmentManager()
                .beginTransaction()
                .add(mapContainerId, mapFragment) // attach to the container — view WILL be created
                .commit();

        // Ask the map to notify us when it is ready (calls onMapReady below)
        mapFragment.getMapAsync(this);

        // ── Top-left overlay: elapsed time ──────────────────────────────
        // Shows "0:00 / 12:34" — how far through the replay we are.
        tvTime = new TextView(requireContext());
        tvTime.setTextColor(Color.WHITE);
        tvTime.setTextSize(14);
        tvTime.setShadowLayer(4, 0, 0, Color.BLACK); // dark glow so it's readable over the map
        tvTime.setPadding(24, 24, 24, 24);
        FrameLayout.LayoutParams timeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        timeParams.gravity = Gravity.TOP | Gravity.START; // top-left
        timeParams.topMargin = 56; // below the back button
        timeParams.leftMargin = 16;
        root.addView(tvTime, timeParams);

        // ── Back button (top-left) ───────────────────────────────────────
        // A simple "←" text button that pops this fragment from the back stack.
        TextView btnBack = new TextView(requireContext());
        btnBack.setText("←");
        btnBack.setTextColor(Color.WHITE);
        btnBack.setTextSize(22);
        btnBack.setShadowLayer(4, 0, 0, Color.BLACK);
        btnBack.setPadding(20, 16, 20, 16);
        btnBack.setBackground(makeRoundedBackground(0xCC161B22, 12)); // semi-transparent dark pill
        btnBack.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                stopPlayback(); // stop the animation before leaving
                // popBackStack() removes this fragment and returns to whatever was below it
                requireActivity().getSupportFragmentManager().popBackStack();
            }
        });
        FrameLayout.LayoutParams backParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        backParams.gravity = Gravity.TOP | Gravity.START;
        backParams.topMargin = 16;
        backParams.leftMargin = 16;
        root.addView(btnBack, backParams);

        // ── Top-right overlay: current speed ───────────────────────────
        // Shows "72 km/h" in large text at the top-right corner.
        tvSpeed = new TextView(requireContext());
        tvSpeed.setText("0 km/h");
        tvSpeed.setTextColor(Color.WHITE);
        tvSpeed.setTextSize(22);
        tvSpeed.setTypeface(null, android.graphics.Typeface.BOLD);
        tvSpeed.setShadowLayer(6, 0, 0, Color.BLACK);
        tvSpeed.setPadding(20, 14, 20, 14);
        tvSpeed.setBackground(makeRoundedBackground(0xCC161B22, 12));
        FrameLayout.LayoutParams speedParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        speedParams.gravity = Gravity.TOP | Gravity.END; // top-right
        speedParams.topMargin = 16;
        speedParams.rightMargin = 16;
        root.addView(tvSpeed, speedParams);

        // ── Bottom control bar: SeekBar + play/pause ────────────────────
        // A vertical LinearLayout containing the time scrubber and play/pause button,
        // anchored at the bottom of the screen.
        LinearLayout bottomBar = new LinearLayout(requireContext());
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(0xDD0D1117); // nearly-opaque dark panel
        bottomBar.setPadding(24, 16, 24, 32);

        // SeekBar — the scrubber the user drags to jump to any moment in the replay
        seekBar = new SeekBar(requireContext());
        seekBar.setMax(0); // will be updated once we know how many points there are
        seekBar.getProgressDrawable().setColorFilter(
                new android.graphics.PorterDuffColorFilter(0xFF58A6FF, android.graphics.PorterDuff.Mode.SRC_IN)
        );
        seekBar.getThumb().setColorFilter(
                new android.graphics.PorterDuffColorFilter(0xFF58A6FF, android.graphics.PorterDuff.Mode.SRC_IN)
        );
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                // fromUser is true only when the user drags the thumb (not when we update it in code)
                if (fromUser) {
                    currentIndex = progress; // jump to the chosen point
                    if (!points.isEmpty()) {
                        moveTo(currentIndex); // update the marker + overlays immediately
                    }
                }
            }
            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
                stopPlayback(); // pause the animation while the user is scrubbing
            }
            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                // Do nothing — the user must press Play to resume after scrubbing
            }
        });
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // Play/pause button — below the seekbar, centred
        btnPlayPause = new TextView(requireContext());
        btnPlayPause.setText("▶");
        btnPlayPause.setTextSize(28);
        btnPlayPause.setTextColor(Color.WHITE);
        btnPlayPause.setGravity(Gravity.CENTER);
        btnPlayPause.setPadding(32, 12, 32, 12);
        btnPlayPause.setBackground(makeRoundedBackground(0xFF21262D, 16));
        btnPlayPause.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isPlaying) {
                    // Currently playing → pause it
                    stopPlayback();
                } else {
                    // Currently paused → start (or resume) playback
                    // If we're at the very end, restart from the beginning
                    if (currentIndex >= points.size()) {
                        currentIndex = 0;
                    }
                    startPlayback();
                }
            }
        });
        LinearLayout.LayoutParams playParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        playParams.gravity = Gravity.CENTER_HORIZONTAL;
        playParams.topMargin = 12;
        bottomBar.addView(btnPlayPause, playParams);

        // Add the bottom bar to the root, anchored to the bottom of the screen
        FrameLayout.LayoutParams bottomBarParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
        );
        bottomBarParams.gravity = Gravity.BOTTOM;
        root.addView(bottomBar, bottomBarParams);

        return root;
    }

    // onDestroyView — called when the fragment's view is being removed.
    // We must stop the animation handler here so it doesn't fire after the view is gone.
    @Override
    public void onDestroyView() {
        super.onDestroyView();
        stopPlayback(); // removes any pending tickRunnable callbacks from the handler
    }


    // ══════════════════════════════════════════════════════════════════════
    // MAP READY
    // ══════════════════════════════════════════════════════════════════════

    // onMapReady — called by the Maps SDK once the GoogleMap is fully initialised.
    // We style the map here and then kick off loading the GPS data from the database.
    @Override
    public void onMapReady(@NonNull GoogleMap map) {
        googleMap = map;

        // Apply a dark "night" map style so the route line stands out
        try {
            googleMap.setMapStyle(
                    com.google.android.gms.maps.model.MapStyleOptions.loadRawResourceStyle(
                            requireContext(), R.raw.map_style
                    )
            );
        } catch (Exception ignored) {
            // If the style file doesn't exist, the map will use its default style — that's fine
        }

        // Disable all map gestures during the replay to keep it simple.
        // The camera follows the moving marker automatically.
        googleMap.getUiSettings().setAllGesturesEnabled(false);
        googleMap.getUiSettings().setMyLocationButtonEnabled(false);
        googleMap.getUiSettings().setZoomControlsEnabled(false);
        googleMap.getUiSettings().setCompassEnabled(false);

        // Load the session's GPS points from the database
        loadPoints();
    }


    // ══════════════════════════════════════════════════════════════════════
    // DATA LOADING
    // ══════════════════════════════════════════════════════════════════════

    // loadPoints — fetches all GPS points for this session from Turso on a background thread.
    // Once loaded, it draws the full route and sets up the replay.
    private void loadPoints() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Fetch all points for this session, in order
                    JSONObject result = tursoQuery(
                            "SELECT sequence_num, timestamp, lat, lng, speed_kmh " +
                            "FROM driving_points " +
                            "WHERE session_id = ? " +
                            "ORDER BY sequence_num ASC",
                            new Object[]{sessionId}
                    );

                    // Parse the JSON response into ReplayPoint objects
                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    List<ReplayPoint> loaded = new ArrayList<>();
                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.getJSONArray(i);
                        ReplayPoint p = new ReplayPoint();
                        p.sequence  = row.getJSONObject(0).getInt("value");
                        p.timestamp = row.getJSONObject(1).getString("value");
                        p.lat       = row.getJSONObject(2).getDouble("value");
                        p.lng       = row.getJSONObject(3).getDouble("value");
                        p.speedKmh  = (float) row.getJSONObject(4).getDouble("value");
                        loaded.add(p);
                    }

                    // Update the UI on the main thread
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            points.clear();
                            points.addAll(loaded);
                            onPointsLoaded(); // draw the route and prepare the replay
                        }
                    });

                } catch (Exception e) {
                    // If loading fails, just show nothing — the screen stays blank
                }
            }
        }).start();
    }

    // onPointsLoaded — called on the main thread once all GPS points are in memory.
    // Draws the full route as a grey polyline and places the car marker at the start.
    private void onPointsLoaded() {
        if (googleMap == null || points.isEmpty()) return;

        // ── Draw the full route as a polyline ───────────────────────────
        // We collect all the LatLng coordinates and draw them as a single line.
        List<LatLng> routeCoords = new ArrayList<>();
        for (ReplayPoint p : points) {
            routeCoords.add(new LatLng(p.lat, p.lng));
        }

        // Light grey polyline — the "ghost" of the entire route the driver took
        PolylineOptions routeOptions = new PolylineOptions()
                .addAll(routeCoords)
                .color(0xFFAABBCC)  // soft grey-blue
                .width(6)            // 6 density-independent pixels wide
                .startCap(new RoundCap()) // rounded ends
                .endCap(new RoundCap());
        googleMap.addPolyline(routeOptions);

        // ── Place the car marker at the starting point ──────────────────
        ReplayPoint start = points.get(0);
        carMarker = googleMap.addMarker(
                new MarkerOptions()
                        .position(new LatLng(start.lat, start.lng))
                        .title("Driver")
                        .icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_AZURE))
                        .anchor(0.5f, 0.5f) // centre the marker on the GPS point
        );

        // ── Set the seek bar range ──────────────────────────────────────
        seekBar.setMax(points.size() - 1); // 0 = start, max = last point
        seekBar.setProgress(0);

        // ── Move the camera to show the whole route ─────────────────────
        // We calculate the bounding box of all points and zoom to fit.
        com.google.android.gms.maps.model.LatLngBounds.Builder boundsBuilder =
                new com.google.android.gms.maps.model.LatLngBounds.Builder();
        for (LatLng latLng : routeCoords) {
            boundsBuilder.include(latLng);
        }
        try {
            com.google.android.gms.maps.model.LatLngBounds bounds = boundsBuilder.build();
            googleMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80)); // 80px padding
        } catch (Exception ignored) {
            // If there's only one point, newLatLngBounds throws — fall back to a fixed zoom
            googleMap.moveCamera(CameraUpdateFactory.newLatLngZoom(
                    new LatLng(start.lat, start.lng), 15f
            ));
        }

        // ── Update the time display ─────────────────────────────────────
        updateTimeDisplay(0);
    }


    // ══════════════════════════════════════════════════════════════════════
    // PLAYBACK CONTROL
    // ══════════════════════════════════════════════════════════════════════

    // startPlayback — begins or resumes the animation.
    private void startPlayback() {
        if (points.isEmpty()) return; // can't play if no data loaded yet
        isPlaying = true;
        btnPlayPause.setText("⏸");
        // Post the first tick immediately; the Runnable will reschedule itself after each step
        handler.post(tickRunnable);
    }

    // stopPlayback — pauses the animation and removes any pending ticks.
    private void stopPlayback() {
        isPlaying = false;
        btnPlayPause.setText("▶");
        handler.removeCallbacks(tickRunnable); // cancel any scheduled future ticks
    }

    // moveTo — moves the car marker to the point at the given index
    // and updates the speed and time displays.
    private void moveTo(int index) {
        if (index < 0 || index >= points.size()) return;
        if (carMarker == null || googleMap == null) return;

        ReplayPoint p = points.get(index);
        LatLng position = new LatLng(p.lat, p.lng);

        // Move the marker to the new position
        carMarker.setPosition(position);

        // Smoothly pan the camera to follow the marker (without changing zoom level)
        googleMap.animateCamera(CameraUpdateFactory.newLatLng(position));

        // Update the speed label
        tvSpeed.setText(Math.round(p.speedKmh) + " km/h");

        // Colour the speed text based on how fast they were going
        if (p.speedKmh > 110) {
            tvSpeed.setTextColor(0xFFFF4444); // red — very fast
        } else if (p.speedKmh > 80) {
            tvSpeed.setTextColor(0xFFFFAA33); // orange — fast
        } else {
            tvSpeed.setTextColor(Color.WHITE); // white — normal
        }

        // Update the elapsed time display
        updateTimeDisplay(index);
    }

    // updateTimeDisplay — updates the "0:00 / 12:34" time label.
    // Calculates elapsed time from start to the current index.
    private void updateTimeDisplay(int index) {
        if (points.isEmpty()) return;

        // Elapsed = difference between the first point's timestamp and the current point's timestamp
        long elapsedMs = 0;
        if (index > 0) {
            elapsedMs = getTimeDiffMs(points.get(0).timestamp, points.get(index).timestamp);
        }

        // Total = difference between first and last point
        long totalMs = 0;
        if (points.size() > 1) {
            totalMs = getTimeDiffMs(points.get(0).timestamp, points.get(points.size() - 1).timestamp);
        }

        tvTime.setText(formatTime(elapsedMs) + " / " + formatTime(totalMs));
    }


    // ══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ══════════════════════════════════════════════════════════════════════

    // getTimeDiffMs — parses two "yyyy-MM-dd HH:mm:ss" strings and returns
    // the difference in milliseconds (end - start).
    // Returns 0 if the strings can't be parsed.
    private long getTimeDiffMs(String start, String end) {
        try {
            java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat(
                    "yyyy-MM-dd HH:mm:ss", java.util.Locale.US
            );
            long startMs = sdf.parse(start).getTime();
            long endMs   = sdf.parse(end).getTime();
            long diff    = endMs - startMs;
            return diff > 0 ? diff : 0; // never return a negative value
        } catch (Exception e) {
            return 0; // if parsing fails, act as if there's no gap
        }
    }

    // formatTime — converts a millisecond duration to "M:SS" or "H:MM:SS" string.
    // Examples: 90000ms → "1:30", 3661000ms → "1:01:01"
    private String formatTime(long ms) {
        long totalSeconds = ms / 1000;                // convert ms to full seconds
        long hours        = totalSeconds / 3600;       // whole hours
        long minutes      = (totalSeconds % 3600) / 60; // remaining minutes
        long seconds      = totalSeconds % 60;          // remaining seconds

        if (hours > 0) {
            return String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        } else {
            return String.format(java.util.Locale.US, "%d:%02d", minutes, seconds);
        }
    }

    // makeRoundedBackground — creates a simple rounded-corner background drawable programmatically.
    // Used for the speed label, back button, and play/pause button.
    private android.graphics.drawable.GradientDrawable makeRoundedBackground(int color, int radiusDp) {
        android.graphics.drawable.GradientDrawable drawable = new android.graphics.drawable.GradientDrawable();
        drawable.setShape(android.graphics.drawable.GradientDrawable.RECTANGLE);
        drawable.setColor(color);
        float radiusPx = radiusDp * getResources().getDisplayMetrics().density;
        drawable.setCornerRadius(radiusPx); // convert dp → pixels
        return drawable;
    }


    // ══════════════════════════════════════════════════════════════════════
    // TURSO DATABASE QUERY
    // ══════════════════════════════════════════════════════════════════════

    // tursoQuery — sends a SQL query to the Turso cloud database and returns the JSON response.
    // This must be called from a background thread (never the main thread).
    private JSONObject tursoQuery(String sql, Object[] args) throws Exception {
        URL url = new URL(TURSO_URL + "/v2/pipeline");
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Authorization", "Bearer " + TURSO_TOKEN);
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setDoOutput(true);
        conn.setConnectTimeout(10000); // wait up to 10 seconds to connect
        conn.setReadTimeout(10000);    // wait up to 10 seconds to receive data

        // Build the args array in the format Turso expects:
        // [{ "type": "text", "value": "..." }, { "type": "integer", "value": "42" }, ...]
        JSONArray argsArray = new JSONArray();
        if (args != null) {
            for (Object arg : args) {
                JSONObject argObject = new JSONObject();
                if (arg == null) {
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

        // Build the SQL statement object
        JSONObject statement = new JSONObject();
        statement.put("sql", sql);
        statement.put("args", argsArray);

        // Wrap in an "execute" request + a "close" request (required by Turso)
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

        // Send the request body
        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream outputStream = conn.getOutputStream();
        outputStream.write(requestBytes);
        outputStream.close();

        // Read the response
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
