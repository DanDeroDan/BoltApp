package com.example.boltapp;

import android.content.Intent;         // used to start a new Activity (screen)
import android.content.SharedPreferences; // key-value storage for saving small data locally
import android.os.Bundle;             // holds saved state when the screen is created
import android.os.Handler;            // lets us schedule code on the main thread
import android.os.Looper;            // provides the main thread's message loop
import android.widget.ImageButton;    // a button that shows an image (the back arrow)
import android.widget.LinearLayout;   // a layout used as a clickable row/item
import android.widget.TextView;       // a view that shows text
import android.widget.Toast;          // a short pop-up message at the bottom of the screen
import android.widget.Switch;          // a toggle switch widget

import androidx.appcompat.app.AlertDialog;       // a pop-up dialog box with buttons
import androidx.appcompat.app.AppCompatActivity; // base class for all activities

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset

// ══════════════════════════════════════════════════════════════════════════════
// SettingsActivity
//
// This screen lets the user:
//   • See who is currently logged in
//   • Leave the current group (removes them from the group in the database)
//   • Log out (clears all saved session data and returns to the login screen)
//
// When the user leaves a group, this Activity sends a result back to
// MainActivity so it can refresh the group list automatically.
// ══════════════════════════════════════════════════════════════════════════════
public class SettingsActivity extends AppCompatActivity {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── Session data ────────────────────────────────────────────────────
    private String uid;        // the logged-in user's unique ID
    private String currentGid; // the ID of the group the user is currently in


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called when the settings screen first appears.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings); // load the XML layout

        // Read the user's session data from SharedPreferences
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        uid        = prefs.getString("uid", null);
        currentGid = prefs.getString("current_gid", null);

        // Show the display name of the logged-in user (e.g. "Dan")
        TextView tvLoggedInAs = findViewById(R.id.tvLoggedInAs);
        String displayName = prefs.getString("display_name", "");
        tvLoggedInAs.setText(displayName);

        // ── Back button ──────────────────────────────────────────────────
        // Tapping the back arrow closes this screen and returns to MainActivity
        ImageButton btnBack = findViewById(R.id.btnBack);
        btnBack.setOnClickListener(v -> finish()); // finish() closes the current Activity

        // ── Notifications toggle ─────────────────────────────────────────
        // Persists the user's preference; MapFragment reads it before sending notifications.
        Switch switchNotifications = findViewById(R.id.switchNotifications);
        switchNotifications.setChecked(
            prefs.getBoolean("notifications_enabled", true));
        switchNotifications.setOnCheckedChangeListener((btn, isChecked) ->
            prefs.edit().putBoolean("notifications_enabled", isChecked).apply());

        // ── Share my arrivals toggle ─────────────────────────────────
        Switch switchAnnounce = findViewById(R.id.switchAnnounce);
        switchAnnounce.setChecked(
            prefs.getBoolean("announce_arrivals", true));
        switchAnnounce.setOnCheckedChangeListener((btn, isChecked) ->
            prefs.edit().putBoolean("announce_arrivals", isChecked).apply());

        // ── Leave Current Group ──────────────────────────────────────────
        // We use a LinearLayout as a clickable row (styled like a button in the XML)
        LinearLayout btnLeaveGroup = findViewById(R.id.btnLeaveGroup);
        btnLeaveGroup.setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                confirmLeaveGroup(); // show a confirmation dialog first
            }
        });

        // ── Log Out ──────────────────────────────────────────────────────
        findViewById(R.id.btnLogout).setOnClickListener(new android.view.View.OnClickListener() {
            @Override
            public void onClick(android.view.View v) {
                confirmLogout(); // show a confirmation dialog first
            }
        });
    }


    // ══════════════════════════════════════════════════════════════════════
    // LEAVE GROUP
    // ══════════════════════════════════════════════════════════════════════

    // confirmLeaveGroup — shows a "Are you sure?" dialog before leaving the group.
    private void confirmLeaveGroup() {
        // If we don't have both a uid and a group ID, we can't do anything
        if (uid == null || currentGid == null) {
            Toast.makeText(this, "No active group", Toast.LENGTH_SHORT).show();
            return;
        }

        // AlertDialog.Builder builds the pop-up dialog step by step
        new AlertDialog.Builder(this)
                .setTitle("Leave Group")
                .setMessage("Are you sure you want to leave the current group?")
                .setPositiveButton("Leave", (dialog, which) -> leaveGroup()) // "Leave" → run leaveGroup()
                .setNegativeButton("Cancel", null) // "Cancel" → just close the dialog
                .show(); // display the dialog
    }

    // leaveGroup — deletes the user's membership from the database, then closes Settings.
    private void leaveGroup() {
        if (uid == null || currentGid == null) return;

        // Run the DELETE query in a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Remove this user from the group_members table
                    tursoQuery(
                            "DELETE FROM group_members WHERE uid = ? AND gid = ?",
                            new Object[]{uid, currentGid}
                    );

                    // Back to the main thread to update the UI
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            // Remove the saved group ID from SharedPreferences
                            SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
                            prefs.edit().remove("current_gid").apply();

                            Toast.makeText(SettingsActivity.this, "Left the group", Toast.LENGTH_SHORT).show();

                            // Send a result back to MainActivity telling it the user left a group.
                            // MainActivity is listening for this and will refresh the group list.
                            Intent resultIntent = new Intent();
                            resultIntent.putExtra("left_group", true); // add a flag to the result
                            setResult(RESULT_OK, resultIntent);

                            finish(); // close SettingsActivity and return to MainActivity
                        }
                    });

                } catch (Exception e) {
                    // If the database delete failed, show an error toast
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(SettingsActivity.this, "Error leaving group", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // LOG OUT
    // ══════════════════════════════════════════════════════════════════════

    // confirmLogout — shows a "Are you sure?" dialog before logging out.
    private void confirmLogout() {
        new AlertDialog.Builder(this)
                .setTitle("Log Out")
                .setMessage("Are you sure you want to log out?")
                .setPositiveButton("Log Out", (dialog, which) -> logout()) // confirm → logout
                .setNegativeButton("Cancel", null) // cancel → do nothing
                .show();
    }

    // logout — clears all saved session data and sends the user back to the login screen.
    private void logout() {
        // Clear everything stored in SharedPreferences (uid, display_name, current_gid, etc.)
        SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        prefs.edit().clear().apply();

        // Navigate to LoginActivity and clear the entire back stack.
        // FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK means the user can't press
        // Back to return to any previous screen — they start fresh.
        Intent intent = new Intent(this, LoginActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        startActivity(intent);

        finish(); // close SettingsActivity
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
