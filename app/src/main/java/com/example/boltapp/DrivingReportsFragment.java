package com.example.boltapp;

import android.graphics.Color;           // color constants and parseColor()
import android.graphics.Typeface;        // makes text bold
import android.os.Bundle;               // data passed between screens
import android.os.Handler;              // schedules work on the main thread
import android.os.Looper;              // main thread's message loop
import android.view.Gravity;           // aligns views (CENTER, START, END, etc.)
import android.view.LayoutInflater;    // builds Views from XML
import android.view.View;              // base UI element
import android.view.ViewGroup;         // container for other views
import android.widget.HorizontalScrollView; // a row that can scroll sideways
import android.widget.LinearLayout;    // arranges children in a row or column
import android.widget.ScrollView;      // a vertically scrollable container
import android.widget.TextView;        // shows text

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

// ══════════════════════════════════════════════════════════════════════════════
// DrivingReportsFragment  (Life360-style driving history tab)
//
// Shows a list of past driving sessions for the current group, newest first.
// Each session card displays:
//   • Driver name + date/time
//   • From location → To location
//   • Duration, Distance, Top Speed, Avg Speed, Phone touches
//   • "▶ Replay" button that opens DriveReplayFragment
//
// A member filter row at the top lets you see all members or one specific driver.
// ══════════════════════════════════════════════════════════════════════════════
public class DrivingReportsFragment extends Fragment {

    // ── Database connection ──────────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── State ────────────────────────────────────────────────────────────
    private String gid;       // current group ID (updated when user switches groups)
    private String groupName; // current group name
    private String filterUid = null; // null = show all members; non-null = show only that user

    // ── Root view (built programmatically — no XML layout needed) ────────
    private LinearLayout sessionList;   // the vertical list we add session cards into
    private LinearLayout filterRow;    // the horizontal member filter chips

    // ── Auto-refresh ─────────────────────────────────────────────────────
    // Auto-refresh every 30 seconds so newly completed drives appear without reloading
    private static final int REFRESH_INTERVAL_MS = 30000;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreateView — builds the entire UI programmatically.
    // We return a ScrollView that contains the filter row + session card list.
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // ── Root: full-screen dark container ─────────────────────────────
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117")); // very dark background
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // ── Header ───────────────────────────────────────────────────────
        TextView header = new TextView(requireContext());
        header.setText("Driving Reports");
        header.setTextColor(Color.parseColor("#E6EDF3"));
        header.setTextSize(18);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(dpToPx(16), dpToPx(18), dpToPx(16), dpToPx(4));
        root.addView(header);

        // ── Member filter chips (horizontal scroll) ───────────────────────
        // Each "chip" is a TextView; tapping it filters the list to that member.
        HorizontalScrollView filterScroll = new HorizontalScrollView(requireContext());
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterRow = new LinearLayout(requireContext());
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        filterScroll.addView(filterRow);
        root.addView(filterScroll);

        // ── Session list (scrollable) ─────────────────────────────────────
        ScrollView scroll = new ScrollView(requireContext());
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f); // takes all remaining height
        scroll.setLayoutParams(scrollParams);

        sessionList = new LinearLayout(requireContext());
        sessionList.setOrientation(LinearLayout.VERTICAL);
        sessionList.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(16));
        scroll.addView(sessionList);

        root.addView(scroll);
        return root;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        // Data is loaded once the group is set via updateGroup()
    }

    @Override
    public void onResume() {
        super.onResume();
        startRefresh();
    }

    @Override
    public void onPause() {
        super.onPause();
        stopRefresh();
    }


    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC API — called by MainActivity
    // ══════════════════════════════════════════════════════════════════════

    // updateGroup — called when the user switches to a different group
    public void updateGroup(String gid, String groupName) {
        this.gid       = gid;
        this.groupName = groupName;
        this.filterUid = null; // reset filter when switching groups

        if (sessionList != null) {
            sessionList.removeAllViews();
            loadMemberFilter();
            loadSessions();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // AUTO-REFRESH
    // ══════════════════════════════════════════════════════════════════════

    private void startRefresh() {
        stopRefresh();
        // Load the member filter chips if they haven't been built yet
        // (this happens when the tab is first opened after initFragments sets the group).
        if (filterRow != null && filterRow.getChildCount() == 0 && gid != null) {
            loadMemberFilter();
        }
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (gid != null) {
                    loadSessions();
                }
                refreshHandler.postDelayed(this, REFRESH_INTERVAL_MS);
            }
        };
        refreshHandler.post(refreshRunnable);
    }

    private void stopRefresh() {
        if (refreshRunnable != null) {
            refreshHandler.removeCallbacks(refreshRunnable);
            refreshRunnable = null;
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // MEMBER FILTER CHIPS
    // ══════════════════════════════════════════════════════════════════════

    // loadMemberFilter — fetches all group members and builds the filter chip row.
    private void loadMemberFilter() {
        if (gid == null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Get all members of the current group
                    JSONObject result = tursoQuery(
                            "SELECT u.uid, u.display_name " +
                            "FROM group_members gm " +
                            "JOIN users u ON gm.uid = u.uid " +
                            "WHERE gm.gid = ? " +
                            "ORDER BY u.display_name ASC",
                            new Object[]{gid}
                    );

                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded()) return;
                            buildFilterChips(rows);
                        }
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    // buildFilterChips — draws "All" + one chip per member into the filter row.
    private void buildFilterChips(JSONArray rows) {
        filterRow.removeAllViews();

        // "All" chip — shows everyone's sessions
        filterRow.addView(makeChip("All", null));

        // One chip per group member
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row    = rows.getJSONArray(i);
                String memberUid = row.getJSONObject(0).getString("value");
                String name      = row.getJSONObject(1).getString("value");
                filterRow.addView(makeChip(name, memberUid));
            } catch (Exception ignored) {}
        }
    }

    // makeChip — creates one filter button (TextView styled as a pill/chip).
    private TextView makeChip(String label, String uid) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));

        // Store the uid in the chip's tag so rebuildFilterHighlight() can read it later
        chip.setTag(uid); // null for "All", non-null for a specific member

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dpToPx(8));
        chip.setLayoutParams(lp);

        // Highlight the currently active chip
        boolean isActive = (uid == null && filterUid == null)
                        || (uid != null && uid.equals(filterUid));
        chip.setTextColor(isActive ? Color.parseColor("#0D1117") : Color.parseColor("#8B949E"));
        chip.setBackgroundColor(isActive
                ? Color.parseColor("#58A6FF")   // blue = selected
                : Color.parseColor("#21262D")); // dark = not selected

        // Tapping a chip updates the filter and reloads the list
        chip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                filterUid = uid; // null = "All"
                // Rebuild all chips so selected state updates
                rebuildFilterHighlight();
                loadSessions();
            }
        });

        return chip;
    }

    // rebuildFilterHighlight — refreshes chip colours after a new one is selected.
    private void rebuildFilterHighlight() {
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View child = filterRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView chip = (TextView) child;
            // The tag stores the uid (or null for "All")
            Object tag = chip.getTag();
            boolean isActive = (tag == null && filterUid == null)
                             || (tag != null && tag.equals(filterUid));
            chip.setTextColor(isActive ? Color.parseColor("#0D1117") : Color.parseColor("#8B949E"));
            chip.setBackgroundColor(isActive
                    ? Color.parseColor("#58A6FF")
                    : Color.parseColor("#21262D"));
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // SESSION LIST
    // ══════════════════════════════════════════════════════════════════════

    // loadSessions — queries driving_reports for the current group (and optional member filter).
    private void loadSessions() {
        if (gid == null) return;

        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Build the SQL depending on whether we're filtering by member
                    String sql;
                    Object[] args;

                    if (filterUid != null) {
                        // Filter to one specific member
                        sql = "SELECT dr.session_id, u.display_name, dr.start_time, dr.end_time, " +
                              "dr.max_speed, dr.avg_speed, dr.phone_distraction, " +
                              "dr.start_location, dr.end_location, dr.distance_km, dr.duration_seconds " +
                              "FROM driving_reports dr " +
                              "JOIN users u ON dr.user_id = u.uid " +
                              "WHERE dr.gid = ? AND dr.user_id = ? " +
                              "ORDER BY dr.start_time DESC LIMIT 50";
                        args = new Object[]{gid, filterUid};
                    } else {
                        // Show all members
                        sql = "SELECT dr.session_id, u.display_name, dr.start_time, dr.end_time, " +
                              "dr.max_speed, dr.avg_speed, dr.phone_distraction, " +
                              "dr.start_location, dr.end_location, dr.distance_km, dr.duration_seconds " +
                              "FROM driving_reports dr " +
                              "JOIN users u ON dr.user_id = u.uid " +
                              "WHERE dr.gid = ? " +
                              "ORDER BY dr.start_time DESC LIMIT 50";
                        args = new Object[]{gid};
                    }

                    JSONObject result = tursoQuery(sql, args);

                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded()) return;
                            buildSessionList(rows);
                        }
                    });

                } catch (Exception e) {
                    e.printStackTrace();
                    // Gracefully handle the case where driving_reports table doesn't exist yet
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            if (!isAdded()) return;
                            showEmptyMessage("No driving reports yet.\nComplete a drive to see it here.");
                        }
                    });
                }
            }
        }).start();
    }

    // buildSessionList — clears the list and adds a card for each session row.
    private void buildSessionList(JSONArray rows) {
        sessionList.removeAllViews();

        if (rows.length() == 0) {
            showEmptyMessage("No drives found.\nComplete a drive and it will appear here.");
            return;
        }

        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row = rows.getJSONArray(i);

                // Extract each column by its position in the SELECT statement
                String sessionId   = getVal(row, 0);
                String driverName  = getVal(row, 1);
                String startTime   = getVal(row, 2); // "2025-01-15 14:30:00"
                String endTime     = getVal(row, 3);
                String maxSpeedStr = getVal(row, 4); // stored as text (km/h)
                String avgSpeedStr = getVal(row, 5);
                String phoneStr    = getVal(row, 6); // phone distraction count
                String startLoc    = getVal(row, 7); // "Rothschild Blvd, Tel Aviv"
                String endLoc      = getVal(row, 8);
                String distStr     = getVal(row, 9); // km
                String durStr      = getVal(row, 10); // seconds

                // Parse numbers with safe fallbacks
                float  maxSpeed  = parseFloat(maxSpeedStr);
                float  avgSpeed  = parseFloat(avgSpeedStr);
                int    phones    = parseInt(phoneStr);
                float  distance  = parseFloat(distStr);
                int    durationS = parseInt(durStr);

                sessionList.addView(buildSessionCard(
                        sessionId, driverName, startTime, endTime,
                        maxSpeed, avgSpeed, phones, startLoc, endLoc,
                        distance, durationS));

                // Thin divider between cards
                if (i < rows.length() - 1) {
                    View divider = new View(requireContext());
                    LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT, dpToPx(1));
                    dp.setMargins(0, dpToPx(4), 0, dpToPx(4));
                    divider.setLayoutParams(dp);
                    divider.setBackgroundColor(Color.parseColor("#21262D"));
                    sessionList.addView(divider);
                }

            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // SESSION CARD BUILDER
    // Each card is a dark rounded box showing all stats for one trip.
    // ══════════════════════════════════════════════════════════════════════
    private View buildSessionCard(String sessionId, String driverName,
                                  String startTime, String endTime,
                                  float maxSpeedKmh, float avgSpeedKmh,
                                  int phoneTouches, String startLoc, String endLoc,
                                  float distanceKm, int durationSeconds) {

        // ── Card container ────────────────────────────────────────────────
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Color.parseColor("#161B22")); // dark card background
        int cardPad = dpToPx(14);
        card.setPadding(cardPad, cardPad, cardPad, cardPad);

        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, dpToPx(6), 0, dpToPx(6));
        card.setLayoutParams(cardParams);

        // ── Row 1: Driver name (left) + Date/time (right) ─────────────────
        LinearLayout nameRow = new LinearLayout(requireContext());
        nameRow.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvName = new TextView(requireContext());
        tvName.setText(driverName != null ? driverName : "Unknown");
        tvName.setTextColor(Color.parseColor("#E6EDF3")); // bright white
        tvName.setTextSize(15);
        tvName.setTypeface(null, Typeface.BOLD);
        LinearLayout.LayoutParams nameLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tvName.setLayoutParams(nameLp);
        nameRow.addView(tvName);

        // Show date formatted as "Jan 15 • 14:30"
        TextView tvDate = new TextView(requireContext());
        tvDate.setText(formatDateTime(startTime));
        tvDate.setTextColor(Color.parseColor("#8B949E")); // dimmer grey
        tvDate.setTextSize(12);
        nameRow.addView(tvDate);

        card.addView(nameRow);

        // ── Row 2: From → To location ─────────────────────────────────────
        if (startLoc != null || endLoc != null) {
            TextView tvRoute = new TextView(requireContext());
            String fromText = startLoc != null ? startLoc : "—";
            String toText   = endLoc   != null ? endLoc   : "—";
            tvRoute.setText(fromText + "  →  " + toText); // → arrow between locations
            tvRoute.setTextColor(Color.parseColor("#58A6FF")); // blue links feel
            tvRoute.setTextSize(12);
            tvRoute.setPadding(0, dpToPx(4), 0, dpToPx(8));
            card.addView(tvRoute);
        }

        // ── Row 3: Duration | Distance ────────────────────────────────────
        card.addView(makeStatRow(
                "⏱  " + formatDuration(durationSeconds),
                "📍  " + String.format(Locale.US, "%.1f km", distanceKm)
        ));

        // ── Row 4: Max Speed | Avg Speed ─────────────────────────────────
        card.addView(makeStatRow(
                "🔺  " + String.format(Locale.US, "%.0f km/h top", maxSpeedKmh),
                "≈  " + String.format(Locale.US, "%.0f km/h avg", avgSpeedKmh)
        ));

        // ── Row 5: Phone distractions (only shown if > 0) ─────────────────
        if (phoneTouches > 0) {
            TextView tvPhone = new TextView(requireContext());
            tvPhone.setText("📱  Phone touched " + phoneTouches + " time" + (phoneTouches == 1 ? "" : "s") + " while driving");
            tvPhone.setTextColor(Color.parseColor("#E3792A")); // orange warning color
            tvPhone.setTextSize(12);
            tvPhone.setPadding(0, dpToPx(6), 0, 0);
            card.addView(tvPhone);
        }

        // ── Replay button ─────────────────────────────────────────────────
        TextView btnReplay = new TextView(requireContext());
        btnReplay.setText("▶  Replay Drive");
        btnReplay.setTextColor(Color.parseColor("#58A6FF")); // blue
        btnReplay.setTextSize(13);
        btnReplay.setTypeface(null, Typeface.BOLD);
        btnReplay.setPadding(dpToPx(14), dpToPx(8), dpToPx(14), dpToPx(8));
        btnReplay.setBackgroundColor(Color.parseColor("#21262D")); // slightly lighter card

        LinearLayout.LayoutParams replayLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        replayLp.setMargins(0, dpToPx(10), 0, 0);
        btnReplay.setLayoutParams(replayLp);
        btnReplay.setGravity(Gravity.CENTER);

        // Capture sessionId for use inside the click listener
        final String capturedSessionId = sessionId;
        btnReplay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Tell MainActivity to open the replay screen for this session
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openDriveReplay(capturedSessionId);
                }
            }
        });

        card.addView(btnReplay);
        return card;
    }


    // ══════════════════════════════════════════════════════════════════════
    // HELPER UI BUILDERS
    // ══════════════════════════════════════════════════════════════════════

    // makeStatRow — creates a horizontal row with two equally-spaced stat labels.
    // Example: "⏱ 23 min"  on the left  |  "📍 12.3 km"  on the right
    private LinearLayout makeStatRow(String leftText, String rightText) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowLp.setMargins(0, dpToPx(4), 0, 0);
        row.setLayoutParams(rowLp);

        TextView tvLeft = new TextView(requireContext());
        tvLeft.setText(leftText);
        tvLeft.setTextColor(Color.parseColor("#C9D1D9"));
        tvLeft.setTextSize(13);
        tvLeft.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvLeft);

        TextView tvRight = new TextView(requireContext());
        tvRight.setText(rightText);
        tvRight.setTextColor(Color.parseColor("#C9D1D9"));
        tvRight.setTextSize(13);
        tvRight.setLayoutParams(new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(tvRight);

        return row;
    }

    // showEmptyMessage — displays a centered message when there are no sessions.
    private void showEmptyMessage(String message) {
        sessionList.removeAllViews();
        TextView tv = new TextView(requireContext());
        tv.setText(message);
        tv.setTextColor(Color.parseColor("#8B949E"));
        tv.setTextSize(14);
        tv.setGravity(Gravity.CENTER);
        tv.setPadding(dpToPx(24), dpToPx(60), dpToPx(24), 0);
        sessionList.addView(tv);
    }


    // ══════════════════════════════════════════════════════════════════════
    // FORMATTING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    // formatDateTime — turns "2025-01-15 14:30:00" into "Jan 15  •  14:30"
    private String formatDateTime(String dbTime) {
        if (dbTime == null || dbTime.length() < 16) return dbTime != null ? dbTime : "—";
        try {
            // dbTime format: "YYYY-MM-DD HH:MM:SS"
            String datePart = dbTime.substring(0, 10); // "2025-01-15"
            String timePart = dbTime.substring(11, 16); // "14:30"

            String[] dateParts = datePart.split("-");
            int month = Integer.parseInt(dateParts[1]);
            int day   = Integer.parseInt(dateParts[2]);

            // Month abbreviations
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun",
                               "Jul","Aug","Sep","Oct","Nov","Dec"};
            String monthName = month >= 1 && month <= 12 ? months[month - 1] : dateParts[1];

            return monthName + " " + day + "  •  " + timePart;

        } catch (Exception e) {
            return dbTime;
        }
    }

    // formatDuration — converts seconds to a "23 min" or "1h 5min" string
    private String formatDuration(int totalSeconds) {
        if (totalSeconds <= 0) return "—";
        int hours = totalSeconds / 3600;
        int mins  = (totalSeconds % 3600) / 60;
        if (hours > 0) {
            return hours + "h " + mins + "min";
        }
        return mins + " min";
    }

    // getVal — safely reads one text value from a JSON row cell. Returns null if missing.
    private String getVal(JSONArray row, int index) {
        try {
            JSONObject cell = row.getJSONObject(index);
            if ("null".equals(cell.optString("type"))) return null;
            String val = cell.optString("value", "");
            return val.isEmpty() ? null : val;
        } catch (Exception e) {
            return null;
        }
    }

    // parseFloat / parseInt — safely parse numeric strings with 0 as fallback
    private float parseFloat(String s) {
        try { return Float.parseFloat(s != null ? s : "0"); }
        catch (Exception e) { return 0f; }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s != null ? s : "0"); }
        catch (Exception e) { return 0; }
    }

    // dpToPx — converts density-independent pixels to real screen pixels
    private int dpToPx(int dp) {
        return (int)(dp * requireContext().getResources().getDisplayMetrics().density);
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
                JSONObject argObj = new JSONObject();
                if (arg == null) {
                    argObj.put("type", "null");
                } else if (arg instanceof Integer) {
                    argObj.put("type", "integer");
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
