package com.example.boltapp;

import android.content.Intent;         // used to start a new Activity (screen)
import android.content.SharedPreferences; // key-value storage for saving small data on the device
import android.os.Bundle;             // holds state when the screen rotates
import android.view.Menu;             // represents the options menu
import android.view.MenuItem;         // represents one item inside a menu
import android.view.MotionEvent;      // describes a finger-touch event (down, move, up)
import android.view.View;             // the base class of every UI element
import android.widget.AdapterView;    // listener for when the user selects an item from a list
import android.widget.ArrayAdapter;   // connects a list of strings to a Spinner dropdown
import android.widget.ImageButton;    // a button that shows an image instead of text
import android.widget.LinearLayout;   // a container that stacks views in a row or column
import android.widget.Spinner;        // a dropdown (select) menu
import android.widget.TextView;       // a view that shows text

import androidx.activity.result.ActivityResultLauncher;            // handles results returned from another Activity
import androidx.activity.result.contract.ActivityResultContracts;  // the contract for launching an activity and getting a result back
import androidx.appcompat.app.AppCompatActivity;                   // base class for all Activities in this app
import androidx.fragment.app.Fragment;                             // a reusable "screen piece"
import androidx.fragment.app.FragmentTransaction;                  // used to add/remove/swap fragments

import com.google.android.material.bottomnavigation.BottomNavigationView; // the tab bar at the bottom

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset
import java.util.ArrayList;            // a resizable list (like an array that can grow)
import java.util.List;                 // the interface that ArrayList implements

// ══════════════════════════════════════════════════════════════════════════════
// MainActivity
//
// This is the main hub of the app. It:
//   1. Checks if the user is logged in (redirects to Login if not)
//   2. Loads all groups the user belongs to
//   3. Shows a bottom navigation bar with 3 tabs: Profile, Map, Reports
//   4. Manages switching between the three fragment "screens"
//   5. Shows a dropdown (Spinner) to switch between groups
// ══════════════════════════════════════════════════════════════════════════════
public class MainActivity extends AppCompatActivity {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── User session data ───────────────────────────────────────────────
    // These are public so that fragments like MapFragment can read them
    public String uid;         // the logged-in user's unique ID
    public String displayName; // the logged-in user's display name

    // ── UI elements ─────────────────────────────────────────────────────
    private LinearLayout topBar;               // the top bar containing the group spinner + settings button
    private BottomNavigationView bottomNav;    // the tab bar at the bottom of the screen
    private Spinner spinnerGroups;             // dropdown to switch between groups

    // ── Fragments ───────────────────────────────────────────────────────
    // We create each fragment once and show/hide them instead of recreating them.
    // This keeps the map alive (with its markers) when the user switches tabs.
    private ProfileFragment profileFragment;
    private MapFragment mapFragment;
    private DrivingReportsFragment reportsFragment;

    // ── Group data ──────────────────────────────────────────────────────
    private List<String> groupNames = new ArrayList<>(); // display names of all groups
    private List<String> groupIds   = new ArrayList<>(); // unique IDs of all groups
    private int selectedGroupIndex  = 0;    // which group is currently selected
    private boolean spinnerReady    = false; // prevents spinner from firing on first load

    // ── Settings result launcher ────────────────────────────────────────
    // This handles the result that comes back when the user closes SettingsActivity.
    // If they left a group, we refresh the group list.
    private final ActivityResultLauncher<Intent> settingsLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        // result.getResultCode() == RESULT_OK means SettingsActivity returned successfully
                        if (result.getResultCode() == RESULT_OK) {
                            Intent data = result.getData();
                            // Check if Settings sent back a "left_group" flag
                            if (data != null && data.getBooleanExtra("left_group", false)) {
                                // User left their current group — clear the data and reload
                                groupIds.clear();
                                groupNames.clear();
                                showChrome(false);                     // hide the top bar and bottom nav
                                swapSetupFragment(new LoadingFragment()); // show loading screen
                                loadAllGroups();                        // re-fetch groups from DB
                            }
                        }
                    }
            );


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called once when the activity first launches.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main); // load the XML layout

        // Link variables to XML views
        topBar        = findViewById(R.id.topBar);
        bottomNav     = findViewById(R.id.bottomNav);
        spinnerGroups = findViewById(R.id.spinnerGroups);

        // Load the user's session from SharedPreferences (saved at login)
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        uid         = prefs.getString("uid", null);         // null means not logged in
        displayName = prefs.getString("display_name", "");

        // If there's no saved uid, the user is not logged in → redirect to LoginActivity
        if (uid == null) {
            startActivity(new Intent(this, LoginActivity.class));
            finish(); // close MainActivity so the user can't press Back to return here
            return;
        }

        // ── Settings button in the top bar ───────────────────────────────
        ImageButton btnSettings = findViewById(R.id.btnSettings);
        btnSettings.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Save the current group ID before opening Settings
                // (so Settings knows which group to leave if the user requests it)
                if (!groupIds.isEmpty()) {
                    getSharedPreferences("BoltAppPrefs", MODE_PRIVATE)
                            .edit()
                            .putString("current_gid", groupIds.get(selectedGroupIndex))
                            .apply();
                }

                // Open SettingsActivity and wait for a result
                Intent intent = new Intent(MainActivity.this, SettingsActivity.class);
                settingsLauncher.launch(intent);
            }
        });

        // ── Bottom navigation tab listener ───────────────────────────────
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_profile) { showTab(0); return true; } // Profile tab
            if (id == R.id.nav_map)     { showTab(1); return true; } // Map tab
            if (id == R.id.nav_reports) { showTab(2); return true; } // Reports tab
            return false;
        });

        // Show loading screen while we fetch groups from the database
        showChrome(false);
        swapSetupFragment(new LoadingFragment());
        loadAllGroups();
    }


    // ══════════════════════════════════════════════════════════════════════
    // GROUP LOADING
    // ══════════════════════════════════════════════════════════════════════

    // loadAllGroups — fetches all groups the user belongs to from the database.
    private void loadAllGroups() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Query: find all groups that this user (uid) is a member of
                    JSONObject result = tursoQuery(
                            "SELECT gm.gid, g.group_name FROM group_members gm " +
                            "JOIN groups g ON gm.gid = g.gid " +
                            "WHERE gm.uid = ? ORDER BY g.group_name ASC",
                            new Object[]{uid}
                    );

                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // Build two parallel lists: one for IDs and one for names
                    List<String> ids   = new ArrayList<>();
                    List<String> names = new ArrayList<>();

                    for (int i = 0; i < rows.length(); i++) {
                        JSONArray row = rows.getJSONArray(i);
                        ids.add(row.getJSONObject(0).getString("value"));   // gid
                        names.add(row.getJSONObject(1).getString("value")); // group_name
                    }

                    // Update the UI on the main thread
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            if (ids.isEmpty()) {
                                // User has no groups → send them to the group setup screen
                                goToGroupSetup();
                            } else {
                                // Update our stored group lists
                                groupIds.clear();
                                groupNames.clear();
                                groupIds.addAll(ids);
                                groupNames.addAll(names);

                                // Restore the last-used group (so the user sees the same group they left)
                                SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
                                String savedGid = prefs.getString("current_gid", null);
                                int startIndex  = 0; // default to the first group

                                if (savedGid != null) {
                                    int idx = groupIds.indexOf(savedGid);
                                    if (idx >= 0) startIndex = idx; // restore to saved group if found
                                }

                                selectedGroupIndex = startIndex;

                                // Create the three fragment screens and show the map tab
                                initFragments();
                                updateSpinner();
                                showChrome(true);
                                bottomNav.setSelectedItemId(R.id.nav_map); // highlight the Map tab
                            }
                        }
                    });

                } catch (Exception e) {
                    // On any error, just go to group setup as a fallback
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            goToGroupSetup();
                        }
                    });
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // FRAGMENT MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════

    // initFragments — creates the three tab fragments and adds them all to the screen.
    // All three are added at once; two of them are hidden until the user taps their tab.
    private void initFragments() {
        String gid  = groupIds.get(selectedGroupIndex);   // current group ID
        String name = groupNames.get(selectedGroupIndex); // current group name

        // Create the Profile fragment (no arguments needed — it reads from SharedPreferences)
        profileFragment = new ProfileFragment();

        // Create the Map fragment and pass it the user + group data via a Bundle
        mapFragment = new MapFragment();
        Bundle mapArgs = new Bundle();
        mapArgs.putString("uid", uid);
        mapArgs.putString("display_name", displayName);
        mapArgs.putString("gid", gid);
        mapArgs.putString("group_name", name);
        mapFragment.setArguments(mapArgs);

        // Create the Reports fragment and immediately tell it which group to show
        reportsFragment = new DrivingReportsFragment();
        reportsFragment.updateGroup(gid, name);

        // A FragmentTransaction lets us make multiple fragment changes at once
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();
        transaction.add(R.id.fragment_container, profileFragment, "profile"); // add to container
        transaction.add(R.id.fragment_container, mapFragment,     "map");
        transaction.add(R.id.fragment_container, reportsFragment, "reports");

        // Hide profile and reports; only show the map at first
        transaction.hide(profileFragment);
        transaction.hide(reportsFragment);
        transaction.show(mapFragment);

        transaction.commit(); // apply all the changes
    }

    // showTab — shows the selected tab's fragment and hides the others.
    // tab 0 = Profile, tab 1 = Map, tab 2 = Reports
    private void showTab(int tab) {
        if (profileFragment == null) return; // fragments not ready yet — do nothing

        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        // Hide all three
        transaction.hide(profileFragment);
        transaction.hide(mapFragment);
        transaction.hide(reportsFragment);

        // Show only the one matching the selected tab
        switch (tab) {
            case 0: transaction.show(profileFragment);  break;
            case 1: transaction.show(mapFragment);      break;
            case 2:
                transaction.show(reportsFragment);
                // Reload immediately when the tab is opened — don't make the user wait 30s
                if (reportsFragment != null) reportsFragment.triggerRefresh();
                break;
        }

        transaction.commit();
    }

    // switchToGroup — called when the user picks a different group from the spinner.
    // Saves the selection and tells both MapFragment and ReportsFragment to update.
    private void switchToGroup(int index) {
        String gid  = groupIds.get(index);
        String name = groupNames.get(index);

        // Remember this group so next time the app opens it restores the same group
        getSharedPreferences("BoltAppPrefs", MODE_PRIVATE)
                .edit()
                .putString("current_gid", gid)
                .apply();

        // Tell the map and reports fragments to reload with the new group's data
        if (mapFragment != null)     mapFragment.updateGroup(gid, name);
        if (reportsFragment != null) reportsFragment.updateGroup(gid, name);
    }


    // ══════════════════════════════════════════════════════════════════════
    // GROUP SPINNER (DROPDOWN)
    // ══════════════════════════════════════════════════════════════════════

    // updateSpinner — populates the group dropdown with the current group list.
    private void updateSpinner() {
        spinnerReady = false; // prevent the listener from firing while we're setting up

        // Create a custom adapter so the text looks right on our dark background
        ArrayAdapter<String> adapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,
                groupNames  // the list of group names to display
        ) {
            // getView — the appearance of the closed spinner (just the selected item)
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(0xFFE6EDF3); // light grey text (0xFF = fully opaque)
                ((TextView) v).setTextSize(16);
                return v;
            }

            // getDropDownView — the appearance of each item inside the open dropdown list
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                v.setBackgroundColor(0xFF161B22);          // dark background
                ((TextView) v).setTextColor(0xFFE6EDF3);  // light text
                ((TextView) v).setPadding(32, 24, 32, 24);
                return v;
            }
        };

        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerGroups.setAdapter(adapter);
        spinnerGroups.setSelection(selectedGroupIndex, false); // show the currently selected group without animation

        // Listen for when the user picks a different group
        spinnerGroups.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                // The first time this fires (right after setAdapter), we just mark it as ready
                // and ignore the event — otherwise we'd call switchToGroup before anything changes.
                if (!spinnerReady) {
                    spinnerReady = true;
                    return;
                }

                // Only switch if the user actually picked a different group
                if (position != selectedGroupIndex) {
                    selectedGroupIndex = position;
                    switchToGroup(position);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
                // Nothing to do here — required by the interface but not used
            }
        });
    }


    // ══════════════════════════════════════════════════════════════════════
    // CHROME (TOP BAR + BOTTOM NAV)
    // ══════════════════════════════════════════════════════════════════════

    // showChrome — shows or hides the top bar and the bottom navigation.
    // We hide them while showing the loading screen or the group setup screen.
    private void showChrome(boolean show) {
        topBar.setVisibility(show ? View.VISIBLE : View.GONE);
        bottomNav.setVisibility(show ? View.VISIBLE : View.GONE);
    }


    // ══════════════════════════════════════════════════════════════════════
    // TOUCH INTERCEPTION — phone distraction detection
    // ══════════════════════════════════════════════════════════════════════

    // dispatchTouchEvent — called for EVERY touch event anywhere in the app,
    // before any specific view handles it. This is the ideal place to count
    // touches for the distraction metric, because it fires regardless of
    // which view the user actually taps.
    //
    // We only count ACTION_DOWN (finger first touches the screen) — not
    // ACTION_MOVE or ACTION_UP — so that a single tap counts as one touch,
    // not many events.
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        // ev.getAction() tells us what kind of touch event this is
        if (ev.getAction() == MotionEvent.ACTION_DOWN) {
            // Tell the MapFragment about this touch; it forwards it to DriveSessionManager
            if (mapFragment != null) {
                mapFragment.reportPhoneTouch();
            }
        }
        // Always pass the event on so the UI still responds normally
        return super.dispatchTouchEvent(ev);
    }


    // ══════════════════════════════════════════════════════════════════════
    // OPTIONS MENU (the ⋮ overflow menu in the action bar)
    // ══════════════════════════════════════════════════════════════════════

    // onCreateOptionsMenu — inflates menu_main.xml so Android knows what items to show.
    // Called automatically by the system the first time the menu needs to be displayed.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true; // returning true tells Android to display the menu
    }

    // onOptionsItemSelected — called whenever the user taps an item in the overflow menu.
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_settings) {
            // Save the current group ID so SettingsActivity knows which group is active
            if (!groupIds.isEmpty()) {
                getSharedPreferences("BoltAppPrefs", MODE_PRIVATE)
                        .edit()
                        .putString("current_gid", groupIds.get(selectedGroupIndex))
                        .apply();
            }
            // Open SettingsActivity (same launcher as the ImageButton)
            Intent intent = new Intent(this, SettingsActivity.class);
            settingsLauncher.launch(intent);
            return true; // event consumed — don't pass it up the chain
        }
        return super.onOptionsItemSelected(item);
    }


    // ══════════════════════════════════════════════════════════════════════
    // PUBLIC METHODS — called by other fragments
    // ══════════════════════════════════════════════════════════════════════

    // onGroupJoined — called by GroupSetupFragment after the user joins or creates a group.
    // We reload everything from scratch.
    public void onGroupJoined(String gid, String groupName) {
        groupIds.clear();
        groupNames.clear();
        showChrome(false);
        swapSetupFragment(new LoadingFragment());
        loadAllGroups();
    }

    // goToMap — kept for backward compatibility; GroupSetupFragment may call this.
    public void goToMap(String gid, String groupName) {
        onGroupJoined(gid, groupName);
    }

    // goToGroupSetupFromProfile — called from ProfileFragment's "Join Another Group" button.
    public void goToGroupSetupFromProfile() {
        showChrome(false);
        goToGroupSetup();
    }

    // openDriveReplay — called by DrivingReportsFragment when the user presses "Replay"
    // on a session card. It creates a DriveReplayFragment for that session and slides it
    // in on top of everything else, hiding the top bar and bottom nav so only the map shows.
    //
    // Pressing Back inside the replay calls popBackStack(), which removes the replay fragment
    // and triggers our back-stack listener below — which re-shows the top bar and bottom nav.
    public void openDriveReplay(String sessionId) {
        // Create the replay fragment with the session ID baked in
        DriveReplayFragment replayFragment = DriveReplayFragment.newInstance(sessionId);

        // Hide the top bar + bottom nav so the replay fills the whole screen
        showChrome(false);

        // Add the replay fragment on top of everything else.
        // addToBackStack("replay") means pressing Back will pop it and re-show the previous state.
        getSupportFragmentManager()
                .beginTransaction()
                .add(R.id.fragment_container, replayFragment, "replay")
                .addToBackStack("replay") // name the back-stack entry so we can find it
                .commit();

        // Listen for when the replay is closed (popped from the back stack).
        // When it is, we re-show the top bar and bottom nav.
        getSupportFragmentManager().addOnBackStackChangedListener(
                new androidx.fragment.app.FragmentManager.OnBackStackChangedListener() {
                    @Override
                    public void onBackStackChanged() {
                        // If the replay entry is gone, the user pressed Back and exited the replay
                        boolean replayIsOpen = getSupportFragmentManager()
                                .findFragmentByTag("replay") != null;
                        if (!replayIsOpen) {
                            // Replay closed — restore the chrome
                            showChrome(true);
                            // Unregister this listener so it doesn't keep firing
                            getSupportFragmentManager().removeOnBackStackChangedListener(this);
                        }
                    }
                }
        );
    }


    // ══════════════════════════════════════════════════════════════════════
    // SETUP SCREEN
    // ══════════════════════════════════════════════════════════════════════

    // goToGroupSetup — shows the GroupSetupFragment so the user can create or join a group.
    public void goToGroupSetup() {
        GroupSetupFragment fragment = new GroupSetupFragment();

        // Pass the user's info to the fragment so it can create the group under their account
        Bundle args = new Bundle();
        args.putString("uid", uid);
        args.putString("display_name", displayName);
        fragment.setArguments(args);

        swapSetupFragment(fragment);
    }

    // swapSetupFragment — replaces whatever is on screen with a single setup fragment
    // (used for both LoadingFragment and GroupSetupFragment).
    // It also removes the three main tab fragments if they exist.
    private void swapSetupFragment(Fragment fragment) {
        FragmentTransaction transaction = getSupportFragmentManager().beginTransaction();

        // Remove the three tab fragments if they're currently active
        if (profileFragment != null) transaction.remove(profileFragment);
        if (mapFragment != null)     transaction.remove(mapFragment);
        if (reportsFragment != null) transaction.remove(reportsFragment);

        // Clear our references so we know they're gone
        profileFragment = null;
        mapFragment     = null;
        reportsFragment = null;

        // Place the new fragment (Loading or GroupSetup) into the container
        transaction.replace(R.id.fragment_container, fragment);
        transaction.commit();
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
