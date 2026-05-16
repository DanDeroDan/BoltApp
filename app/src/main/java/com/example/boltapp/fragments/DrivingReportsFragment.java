package com.example.boltapp.fragments;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.example.boltapp.MainActivity;
import com.example.boltapp.services.LocationTrackingService;
import com.example.boltapp.fragments.DriveReplayFragment;

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
// The session list is powered by a RecyclerView with SessionAdapter/SessionViewHolder,
// which efficiently recycles card views as the user scrolls — instead of building
// every card from scratch and holding it all in memory at once.
//
// A member filter row at the top lets you see all members or one specific driver.
// ══════════════════════════════════════════════════════════════════════════════
public class DrivingReportsFragment extends Fragment {

    // ── Database connection ──────────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── State ────────────────────────────────────────────────────────────
    private String gid;
    private String groupName;
    private String filterUid = null; // null = show all; non-null = filter to one user

    // ── UI ───────────────────────────────────────────────────────────────
    private LinearLayout filterRow;   // horizontal chip row at the top
    private RecyclerView recyclerView; // the session list
    private SessionAdapter adapter;    // RecyclerView adapter
    private TextView emptyView;        // shown when there are no sessions

    // ── Auto-refresh ─────────────────────────────────────────────────────
    private static final int REFRESH_INTERVAL_MS = 30000;
    private final Handler refreshHandler = new Handler(Looper.getMainLooper());
    private Runnable refreshRunnable;


    // ══════════════════════════════════════════════════════════════════════
    // DATA CLASS — one row in the session list
    // ══════════════════════════════════════════════════════════════════════

    // SessionItem holds all fields for a single driving session.
    // Separating data from views is a core RecyclerView pattern: the Adapter
    // keeps a List<SessionItem> and binds each item into a reused ViewHolder.
    static class SessionItem {
        final String sessionId, driverName, startTime, endTime, startLoc, endLoc;
        final float  maxSpeedKmh, avgSpeedKmh, distanceKm;
        final int    phoneTouches, durationSeconds;

        SessionItem(String sessionId, String driverName,
                    String startTime, String endTime,
                    float maxSpeedKmh, float avgSpeedKmh,
                    int phoneTouches, String startLoc, String endLoc,
                    float distanceKm, int durationSeconds) {
            this.sessionId      = sessionId;
            this.driverName     = driverName;
            this.startTime      = startTime;
            this.endTime        = endTime;
            this.maxSpeedKmh    = maxSpeedKmh;
            this.avgSpeedKmh    = avgSpeedKmh;
            this.phoneTouches   = phoneTouches;
            this.startLoc       = startLoc;
            this.endLoc         = endLoc;
            this.distanceKm     = distanceKm;
            this.durationSeconds = durationSeconds;
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // RECYCLERVIEW ADAPTER
    //
    // RecyclerView never builds more card Views than fit on screen.
    // As the user scrolls, it calls onBindViewHolder() to fill an already-
    // created ViewHolder with new data — much cheaper than addView() each time.
    // ══════════════════════════════════════════════════════════════════════

    // SessionAdapter is a non-static inner class so it can call Fragment
    // helper methods (formatDateTime, formatDuration, dpToPx) directly.
    class SessionAdapter extends RecyclerView.Adapter<SessionAdapter.SessionViewHolder> {

        private final Context ctx;
        private List<SessionItem> items = new ArrayList<>();

        SessionAdapter(Context context) {
            this.ctx = context;
        }

        // Replace the entire dataset and redraw the list.
        void setData(List<SessionItem> newItems) {
            this.items = new ArrayList<>(newItems);
            notifyDataSetChanged();
        }

        // ── onCreateViewHolder ──────────────────────────────────────────
        // Called only when a new card View needs to be created (i.e. on first
        // fill or when more unique cards come into view). Builds the card
        // structure once; onBindViewHolder() fills it with data later.
        @NonNull
        @Override
        public SessionViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return buildViewHolder();
        }

        // ── onBindViewHolder ────────────────────────────────────────────
        // Called every time a card scrolls into view.
        // Only updates text/visibility — never creates new Views.
        @Override
        public void onBindViewHolder(@NonNull SessionViewHolder holder, int position) {
            SessionItem item = items.get(position);

            // Row 1: driver name + date
            holder.tvName.setText(item.driverName != null ? item.driverName : "Unknown");
            holder.tvDate.setText(formatDateTime(item.startTime));

            // Row 2: route  (hide if both locations are null)
            if (item.startLoc != null || item.endLoc != null) {
                String from = item.startLoc != null ? item.startLoc : "—";
                String to   = item.endLoc   != null ? item.endLoc   : "—";
                holder.tvRoute.setText(from + "  →  " + to);
                holder.tvRoute.setVisibility(View.VISIBLE);
            } else {
                holder.tvRoute.setVisibility(View.GONE);
            }

            // Row 3: duration | distance
            holder.tvLeft1.setText("⏱  " + formatDuration(item.durationSeconds));
            holder.tvRight1.setText("📍  " + String.format(Locale.US, "%.1f km", item.distanceKm));

            // Row 4: top speed | avg speed
            holder.tvLeft2.setText("🔺  " + String.format(Locale.US, "%.0f km/h top", item.maxSpeedKmh));
            holder.tvRight2.setText("≈  " + String.format(Locale.US, "%.0f km/h avg", item.avgSpeedKmh));

            // Row 5: phone distractions (hidden if none)
            if (item.phoneTouches > 0) {
                holder.tvPhone.setText("📱  Phone touched " + item.phoneTouches
                        + " time" + (item.phoneTouches == 1 ? "" : "s") + " while driving");
                holder.tvPhone.setVisibility(View.VISIBLE);
            } else {
                holder.tvPhone.setVisibility(View.GONE);
            }

            // Replay button — pass the session ID to MainActivity
            final String capturedId = item.sessionId;
            holder.btnReplay.setOnClickListener(v -> {
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).openDriveReplay(capturedId);
                }
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        // ── buildViewHolder ─────────────────────────────────────────────
        // Builds one complete card view and captures references to every
        // dynamic child in a new SessionViewHolder. Called only from
        // onCreateViewHolder — never for data updates.
        private SessionViewHolder buildViewHolder() {
            int dp4  = dpToPx(4);
            int dp6  = dpToPx(6);
            int dp8  = dpToPx(8);
            int dp10 = dpToPx(10);
            int dp14 = dpToPx(14);

            // Card container
            LinearLayout card = new LinearLayout(ctx);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackgroundColor(Color.parseColor("#161B22"));
            card.setPadding(dp14, dp14, dp14, dp14);
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, dp6, 0, dp6);
            card.setLayoutParams(cardLp);

            // Row 1: name (left) + date (right)
            LinearLayout nameRow = new LinearLayout(ctx);
            nameRow.setOrientation(LinearLayout.HORIZONTAL);

            TextView tvName = new TextView(ctx);
            tvName.setTextColor(Color.parseColor("#E6EDF3"));
            tvName.setTextSize(15);
            tvName.setTypeface(null, Typeface.BOLD);
            tvName.setLayoutParams(new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            nameRow.addView(tvName);

            TextView tvDate = new TextView(ctx);
            tvDate.setTextColor(Color.parseColor("#8B949E"));
            tvDate.setTextSize(12);
            nameRow.addView(tvDate);

            card.addView(nameRow);

            // Row 2: from → to route
            TextView tvRoute = new TextView(ctx);
            tvRoute.setTextColor(Color.parseColor("#58A6FF"));
            tvRoute.setTextSize(12);
            tvRoute.setPadding(0, dp4, 0, dp8);
            card.addView(tvRoute);

            // Row 3: duration | distance
            LinearLayout row1 = makeStatRowLayout();
            TextView tvLeft1  = (TextView) row1.getChildAt(0);
            TextView tvRight1 = (TextView) row1.getChildAt(1);
            card.addView(row1);

            // Row 4: top speed | avg speed
            LinearLayout row2 = makeStatRowLayout();
            TextView tvLeft2  = (TextView) row2.getChildAt(0);
            TextView tvRight2 = (TextView) row2.getChildAt(1);
            card.addView(row2);

            // Row 5: phone distractions
            TextView tvPhone = new TextView(ctx);
            tvPhone.setTextColor(Color.parseColor("#E3792A"));
            tvPhone.setTextSize(12);
            tvPhone.setPadding(0, dp6, 0, 0);
            tvPhone.setVisibility(View.GONE); // hidden until data says otherwise
            card.addView(tvPhone);

            // Replay button
            TextView btnReplay = new TextView(ctx);
            btnReplay.setText("▶  Replay Drive");
            btnReplay.setTextColor(Color.parseColor("#58A6FF"));
            btnReplay.setTextSize(13);
            btnReplay.setTypeface(null, Typeface.BOLD);
            btnReplay.setPadding(dp14, dp8, dp14, dp8);
            btnReplay.setBackgroundColor(Color.parseColor("#21262D"));
            btnReplay.setGravity(Gravity.CENTER);
            LinearLayout.LayoutParams replayLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            replayLp.setMargins(0, dp10, 0, 0);
            btnReplay.setLayoutParams(replayLp);
            card.addView(btnReplay);

            return new SessionViewHolder(card, tvName, tvDate, tvRoute,
                    tvLeft1, tvRight1, tvLeft2, tvRight2, tvPhone, btnReplay);
        }

        // Builds a horizontal row with two equal-weight TextViews (for stat pairs).
        private LinearLayout makeStatRowLayout() {
            LinearLayout row = new LinearLayout(ctx);
            row.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, dpToPx(4), 0, 0);
            row.setLayoutParams(rowLp);

            for (int i = 0; i < 2; i++) {
                TextView tv = new TextView(ctx);
                tv.setTextColor(Color.parseColor("#C9D1D9"));
                tv.setTextSize(13);
                tv.setLayoutParams(new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
                row.addView(tv);
            }
            return row;
        }


        // ── ViewHolder ──────────────────────────────────────────────────
        // Stores direct references to every dynamic view inside one card.
        // RecyclerView reuses these objects — we never call findViewById() again.
        class SessionViewHolder extends RecyclerView.ViewHolder {
            final TextView tvName, tvDate, tvRoute;
            final TextView tvLeft1, tvRight1;   // duration | distance
            final TextView tvLeft2, tvRight2;   // top speed | avg speed
            final TextView tvPhone;
            final TextView btnReplay;

            SessionViewHolder(@NonNull View card,
                              TextView tvName, TextView tvDate, TextView tvRoute,
                              TextView tvLeft1, TextView tvRight1,
                              TextView tvLeft2, TextView tvRight2,
                              TextView tvPhone, TextView btnReplay) {
                super(card);
                this.tvName   = tvName;
                this.tvDate   = tvDate;
                this.tvRoute  = tvRoute;
                this.tvLeft1  = tvLeft1;
                this.tvRight1 = tvRight1;
                this.tvLeft2  = tvLeft2;
                this.tvRight2 = tvRight2;
                this.tvPhone  = tvPhone;
                this.btnReplay = btnReplay;
            }
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        // Root: full-screen dark container
        LinearLayout root = new LinearLayout(requireContext());
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#0D1117"));
        root.setLayoutParams(new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        // Header
        TextView header = new TextView(requireContext());
        header.setText("Driving Reports");
        header.setTextColor(Color.parseColor("#E6EDF3"));
        header.setTextSize(18);
        header.setTypeface(null, Typeface.BOLD);
        header.setPadding(dpToPx(16), dpToPx(18), dpToPx(16), dpToPx(4));
        root.addView(header);

        // Member filter chips (horizontal scroll row)
        HorizontalScrollView filterScroll = new HorizontalScrollView(requireContext());
        filterScroll.setHorizontalScrollBarEnabled(false);
        filterRow = new LinearLayout(requireContext());
        filterRow.setOrientation(LinearLayout.HORIZONTAL);
        filterRow.setPadding(dpToPx(12), dpToPx(8), dpToPx(12), dpToPx(8));
        filterScroll.addView(filterRow);
        root.addView(filterScroll);

        // Empty state view (shown when there are no sessions to display)
        emptyView = new TextView(requireContext());
        emptyView.setTextColor(Color.parseColor("#8B949E"));
        emptyView.setTextSize(14);
        emptyView.setGravity(Gravity.CENTER);
        emptyView.setPadding(dpToPx(24), dpToPx(60), dpToPx(24), 0);
        emptyView.setVisibility(View.GONE);
        root.addView(emptyView);

        // RecyclerView — replaces the old ScrollView + LinearLayout + addView() approach.
        // RecyclerView only creates as many card Views as fit on screen, then reuses them
        // as the user scrolls (via onBindViewHolder), making it far more memory-efficient.
        recyclerView = new RecyclerView(requireContext());
        LinearLayout.LayoutParams rvLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        recyclerView.setLayoutParams(rvLp);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        recyclerView.setPadding(dpToPx(12), 0, dpToPx(12), dpToPx(16));
        recyclerView.setClipToPadding(false);

        adapter = new SessionAdapter(requireContext());
        recyclerView.setAdapter(adapter);
        root.addView(recyclerView);

        return root;
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

    public void triggerRefresh() {
        if (gid != null) loadSessions();
    }

    public void updateGroup(String gid, String groupName) {
        this.gid       = gid;
        this.groupName = groupName;
        this.filterUid = null;

        if (adapter != null) {
            adapter.setData(new ArrayList<>()); // clear the old group's data immediately
            loadMemberFilter();
            loadSessions();
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // AUTO-REFRESH
    // ══════════════════════════════════════════════════════════════════════

    private void startRefresh() {
        stopRefresh();
        if (filterRow != null && filterRow.getChildCount() == 0 && gid != null) {
            loadMemberFilter();
        }
        refreshRunnable = new Runnable() {
            @Override
            public void run() {
                if (gid != null) loadSessions();
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

    private void loadMemberFilter() {
        if (gid == null) return;
        new Thread(() -> {
            try {
                JSONObject result = tursoQuery(
                        "SELECT u.uid, u.display_name " +
                        "FROM group_members gm " +
                        "JOIN users u ON gm.uid = u.uid " +
                        "WHERE gm.gid = ? ORDER BY u.display_name ASC",
                        new Object[]{gid});

                JSONArray rows = result
                        .getJSONArray("results").getJSONObject(0)
                        .getJSONObject("response").getJSONObject("result")
                        .getJSONArray("rows");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    buildFilterChips(rows);
                });
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void buildFilterChips(JSONArray rows) {
        filterRow.removeAllViews();
        filterRow.addView(makeChip("All", null));
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row = rows.getJSONArray(i);
                String uid  = row.getJSONObject(0).getString("value");
                String name = row.getJSONObject(1).getString("value");
                filterRow.addView(makeChip(name, uid));
            } catch (Exception ignored) {}
        }
    }

    private TextView makeChip(String label, String uid) {
        TextView chip = new TextView(requireContext());
        chip.setText(label);
        chip.setTextSize(13);
        chip.setPadding(dpToPx(14), dpToPx(6), dpToPx(14), dpToPx(6));
        chip.setTag(uid);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMarginEnd(dpToPx(8));
        chip.setLayoutParams(lp);

        boolean isActive = (uid == null && filterUid == null)
                        || (uid != null && uid.equals(filterUid));
        chip.setTextColor(isActive ? Color.parseColor("#0D1117") : Color.parseColor("#8B949E"));
        chip.setBackgroundColor(isActive ? Color.parseColor("#58A6FF") : Color.parseColor("#21262D"));

        chip.setOnClickListener(v -> {
            filterUid = uid;
            rebuildFilterHighlight();
            loadSessions();
        });
        return chip;
    }

    private void rebuildFilterHighlight() {
        for (int i = 0; i < filterRow.getChildCount(); i++) {
            View child = filterRow.getChildAt(i);
            if (!(child instanceof TextView)) continue;
            TextView chip = (TextView) child;
            Object tag = chip.getTag();
            boolean isActive = (tag == null && filterUid == null)
                             || (tag != null && tag.equals(filterUid));
            chip.setTextColor(isActive ? Color.parseColor("#0D1117") : Color.parseColor("#8B949E"));
            chip.setBackgroundColor(isActive ? Color.parseColor("#58A6FF") : Color.parseColor("#21262D"));
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // SESSION LIST
    // ══════════════════════════════════════════════════════════════════════

    private void loadSessions() {
        if (gid == null) return;
        new Thread(() -> {
            try {
                String sql;
                Object[] args;

                if (filterUid != null) {
                    sql  = "SELECT dr.session_id, u.display_name, dr.start_time, dr.end_time, " +
                           "dr.max_speed, dr.avg_speed, dr.phone_distraction, " +
                           "dr.start_location, dr.end_location, dr.distance_km, dr.duration_seconds " +
                           "FROM driving_reports dr " +
                           "JOIN users u ON dr.user_id = u.uid " +
                           "WHERE dr.gid = ? AND dr.user_id = ? " +
                           "ORDER BY dr.start_time DESC LIMIT 50";
                    args = new Object[]{gid, filterUid};
                } else {
                    sql  = "SELECT dr.session_id, u.display_name, dr.start_time, dr.end_time, " +
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
                        .getJSONArray("results").getJSONObject(0)
                        .getJSONObject("response").getJSONObject("result")
                        .getJSONArray("rows");

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    buildSessionList(rows);
                });

            } catch (Exception e) {
                e.printStackTrace();
                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    showEmptyMessage("No driving reports yet.\nComplete a drive to see it here.");
                });
            }
        }).start();
    }

    // buildSessionList — converts raw JSON rows into SessionItem objects and
    // hands them to the adapter. The RecyclerView then calls onBindViewHolder()
    // for each visible card automatically.
    private void buildSessionList(JSONArray rows) {
        if (rows.length() == 0) {
            showEmptyMessage("No drives found.\nComplete a drive and it will appear here.");
            return;
        }

        List<SessionItem> items = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            try {
                JSONArray row = rows.getJSONArray(i);
                items.add(new SessionItem(
                        getVal(row, 0),           // session_id
                        getVal(row, 1),           // display_name
                        getVal(row, 2),           // start_time
                        getVal(row, 3),           // end_time
                        parseFloat(getVal(row, 4)),  // max_speed
                        parseFloat(getVal(row, 5)),  // avg_speed
                        parseInt(getVal(row, 6)),    // phone_distraction
                        getVal(row, 7),           // start_location
                        getVal(row, 8),           // end_location
                        parseFloat(getVal(row, 9)),  // distance_km
                        parseInt(getVal(row, 10))    // duration_seconds
                ));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        emptyView.setVisibility(View.GONE);
        recyclerView.setVisibility(View.VISIBLE);
        adapter.setData(items); // hands data to adapter → RecyclerView redraws
    }

    private void showEmptyMessage(String message) {
        emptyView.setText(message);
        emptyView.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        adapter.setData(new ArrayList<>());
    }


    // ══════════════════════════════════════════════════════════════════════
    // FORMATTING HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private String formatDateTime(String dbTime) {
        if (dbTime == null || dbTime.length() < 16) return dbTime != null ? dbTime : "—";
        try {
            String[] parts = dbTime.substring(0, 10).split("-");
            int month = Integer.parseInt(parts[1]);
            int day   = Integer.parseInt(parts[2]);
            String[] months = {"Jan","Feb","Mar","Apr","May","Jun",
                               "Jul","Aug","Sep","Oct","Nov","Dec"};
            String m = (month >= 1 && month <= 12) ? months[month - 1] : parts[1];
            return m + " " + day + "  •  " + dbTime.substring(11, 16);
        } catch (Exception e) {
            return dbTime;
        }
    }

    private String formatDuration(int totalSeconds) {
        if (totalSeconds <= 0) return "—";
        int hours = totalSeconds / 3600;
        int mins  = (totalSeconds % 3600) / 60;
        return hours > 0 ? hours + "h " + mins + "min" : mins + " min";
    }

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

    private float parseFloat(String s) {
        try { return Float.parseFloat(s != null ? s : "0"); } catch (Exception e) { return 0f; }
    }

    private int parseInt(String s) {
        try { return Integer.parseInt(s != null ? s : "0"); } catch (Exception e) { return 0; }
    }

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

        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(bytes);
        os.close();

        int code = conn.getResponseCode();
        InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();

        StringBuilder sb = new StringBuilder();
        byte[] buf = new byte[4096];
        int n;
        while ((n = is.read(buf)) != -1) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));

        if (code >= 400) throw new Exception("Turso error " + code + ": " + sb);
        return new JSONObject(sb.toString());
    }
}
