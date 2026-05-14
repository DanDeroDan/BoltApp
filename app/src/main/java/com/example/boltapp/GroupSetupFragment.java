package com.example.boltapp;

import android.graphics.Color;          // lets us use colors like Color.parseColor(...)
import android.os.Bundle;              // used to pass data between screens
import android.view.LayoutInflater;    // builds Views from XML layout files
import android.view.View;              // the base class of every UI element
import android.view.ViewGroup;         // a container that holds other views
import android.widget.Button;          // a clickable button
import android.widget.TextView;        // a view that shows text

import androidx.annotation.NonNull;    // marks that a parameter can never be null
import androidx.annotation.Nullable;   // marks that something can be null
import androidx.fragment.app.Fragment; // base class for a screen piece inside an Activity

import com.google.android.material.textfield.TextInputEditText; // a fancy styled text field

import org.json.JSONArray;   // represents a JSON list  e.g. [ ... ]
import org.json.JSONObject;  // represents a JSON object e.g. { "key": "value" }

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection to a server
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 text encoding
import java.util.Random;               // generates random numbers (used for invite codes)
import java.util.UUID;                 // generates a universally unique ID (for group IDs)

// ══════════════════════════════════════════════════════════════════════════════
// GroupSetupFragment
//
// This screen is shown when the user doesn't belong to any group yet.
// They can either:
//   • Create a new group  → the app creates a DB entry and makes them admin
//   • Join an existing group → the app looks up the invite code and adds them
// ══════════════════════════════════════════════════════════════════════════════
public class GroupSetupFragment extends Fragment {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── User info (received from MainActivity) ──────────────────────────
    private String uid;         // the logged-in user's unique ID
    private String displayName; // the logged-in user's display name

    // ── UI elements ─────────────────────────────────────────────────────
    private View layoutButtons;        // the screen with "Create" and "Join" buttons
    private View layoutCreate;         // the form shown when "Create" is tapped
    private View layoutJoin;           // the form shown when "Join" is tapped
    private TextInputEditText etGroupName;  // input field for the group name
    private TextInputEditText etInviteCode; // input field for the invite code
    private Button btnCreate;           // "Create a Group" button
    private Button btnJoin;             // "Join a Group" button
    private Button btnConfirmCreate;    // confirm button inside the create form
    private Button btnConfirmJoin;      // confirm button inside the join form
    private TextView tvStatus;          // shows success/error messages to the user


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE METHODS
    // ══════════════════════════════════════════════════════════════════════

    // onCreateView — builds the fragment's layout from XML and returns it
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_group_setup, container, false);
    }

    // onViewCreated — called right after the layout is ready.
    // Here we connect our variables to the XML views, and set up button click listeners.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Read the uid and display_name that MainActivity passed to us
        if (getArguments() != null) {
            uid         = getArguments().getString("uid");
            displayName = getArguments().getString("display_name");
        }

        // Link each variable to its matching XML view by ID
        layoutButtons    = view.findViewById(R.id.layoutButtons);
        layoutCreate     = view.findViewById(R.id.layoutCreate);
        layoutJoin       = view.findViewById(R.id.layoutJoin);
        etGroupName      = view.findViewById(R.id.etGroupName);
        etInviteCode     = view.findViewById(R.id.etInviteCode);
        btnCreate        = view.findViewById(R.id.btnCreate);
        btnJoin          = view.findViewById(R.id.btnJoin);
        btnConfirmCreate = view.findViewById(R.id.btnConfirmCreate);
        btnConfirmJoin   = view.findViewById(R.id.btnConfirmJoin);
        tvStatus         = view.findViewById(R.id.tvStatus);

        // When "Create a Group" is tapped: hide the main buttons and show the create form
        btnCreate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutButtons.setVisibility(View.GONE);    // hide main menu
                layoutCreate.setVisibility(View.VISIBLE);  // show create form
                layoutJoin.setVisibility(View.GONE);       // hide join form
            }
        });

        // When "Join a Group" is tapped: hide the main buttons and show the join form
        btnJoin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                layoutButtons.setVisibility(View.GONE);   // hide main menu
                layoutCreate.setVisibility(View.GONE);    // hide create form
                layoutJoin.setVisibility(View.VISIBLE);   // show join form
            }
        });

        // "Confirm" in the create form → run the create-group logic
        btnConfirmCreate.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleCreateGroup();
            }
        });

        // "Confirm" in the join form → run the join-group logic
        btnConfirmJoin.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                handleJoinGroup();
            }
        });
    }


    // ══════════════════════════════════════════════════════════════════════
    // CREATE GROUP
    // ══════════════════════════════════════════════════════════════════════
    private void handleCreateGroup() {

        // Read and trim the text from the group name field
        String groupName = "";
        if (etGroupName.getText() != null) {
            groupName = etGroupName.getText().toString().trim();
        }

        // Don't continue if the user left the field empty
        if (groupName.isEmpty()) {
            showError("Please enter a group name.");
            return;
        }

        // Generate a unique group ID (looks like "a3f5c2d1-...")
        // 'final' means this variable will never be reassigned — required to use it inside inner classes
        final String gid = UUID.randomUUID().toString();

        // Generate a short invite code like "AB3K7Q"
        // Also 'final' so it can be used inside the anonymous Runnable below
        final String inviteCode = generateInviteCode();
        final String finalGroupName = groupName; // copy into a final variable for use in inner classes

        // Disable the button so the user can't tap it twice while waiting
        btnConfirmCreate.setEnabled(false);
        showInfo("Creating group...");

        // Run the database work in a background thread (network is not allowed on the main thread)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Insert the new group into the "groups" table
                    tursoQuery(
                            "INSERT INTO groups (gid, group_name, invite_code, owner_id) VALUES (?, ?, ?, ?)",
                            new Object[]{gid, finalGroupName, inviteCode, uid}
                    );

                    // Add the current user as a member with the role "admin"
                    tursoQuery(
                            "INSERT INTO group_members (gid, uid, role) VALUES (?, ?, 'admin')",
                            new Object[]{gid, uid}
                    );

                    // Go back to the main thread to update the UI
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                // Show the invite code for 2 seconds, then navigate to the map
                                showInfo("Group created! Invite code: " + inviteCode);

                                // postDelayed waits 2000ms (2 seconds) then runs the code inside
                                btnConfirmCreate.postDelayed(new Runnable() {
                                    @Override
                                    public void run() {
                                        // Tell MainActivity to switch to the map screen
                                        ((MainActivity) getActivity()).goToMap(gid, finalGroupName);
                                    }
                                }, 2000);
                            }
                        });
                    }

                } catch (Exception e) {
                    // If something went wrong, re-enable the button and show the error
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnConfirmCreate.setEnabled(true);
                                showError("Error: " + e.getMessage());
                            }
                        });
                    }
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // JOIN GROUP
    // ══════════════════════════════════════════════════════════════════════
    private void handleJoinGroup() {

        // Read the invite code and convert it to uppercase (so "ab3k" works the same as "AB3K")
        String code = "";
        if (etInviteCode.getText() != null) {
            code = etInviteCode.getText().toString().trim().toUpperCase();
        }

        // Don't continue if the field is empty
        if (code.isEmpty()) {
            showError("Please enter an invite code.");
            return;
        }

        btnConfirmJoin.setEnabled(false);
        showInfo("Looking up group...");

        // Look up the invite code in the database on a background thread
        final String finalCode = code; // variables used in a Thread must be "final"
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Ask the database for the group that matches this invite code
                    JSONObject result = tursoQuery(
                            "SELECT gid, group_name FROM groups WHERE invite_code = ?",
                            new Object[]{finalCode}
                    );

                    // Navigate through the JSON response to get the rows
                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // If no rows were found, the code is invalid
                    if (rows.length() == 0) {
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    btnConfirmJoin.setEnabled(true);
                                    showError("Invalid invite code.");
                                }
                            });
                        }
                        return; // stop here — don't try to join
                    }

                    // Extract the group ID and group name from the first (only) row
                    String gid       = rows.getJSONArray(0).getJSONObject(0).getString("value");
                    String groupName = rows.getJSONArray(0).getJSONObject(1).getString("value");

                    // ── Check if the user is already a member ────────────────
                    JSONObject memberCheck = tursoQuery(
                            "SELECT id FROM group_members WHERE gid = ? AND uid = ?",
                            new Object[]{gid, uid}
                    );

                    JSONArray existingRows = memberCheck
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // Only insert if they're NOT already a member
                    if (existingRows.length() == 0) {
                        tursoQuery(
                                "INSERT INTO group_members (gid, uid, role) VALUES (?, ?, 'member')",
                                new Object[]{gid, uid}
                        );
                    }

                    // Go back to the main thread and navigate to the map
                    if (getActivity() != null) {
                        final String finalGid       = gid;
                        final String finalGroupName = groupName;
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                ((MainActivity) getActivity()).goToMap(finalGid, finalGroupName);
                            }
                        });
                    }

                } catch (Exception e) {
                    if (getActivity() != null) {
                        getActivity().runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnConfirmJoin.setEnabled(true);
                                showError("Error: " + e.getMessage());
                            }
                        });
                    }
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════

    // generateInviteCode — creates a random 6-character code like "AB3K7Q".
    // We skip letters O and I to avoid confusion with 0 and 1.
    private String generateInviteCode() {
        String allowedChars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"; // no I, O to avoid confusion
        StringBuilder code = new StringBuilder();
        Random random = new Random();

        for (int i = 0; i < 6; i++) {
            // Pick a random character from allowedChars and add it to the code
            int randomIndex = random.nextInt(allowedChars.length());
            code.append(allowedChars.charAt(randomIndex));
        }

        return code.toString();
    }

    // showError — displays a red error message below the form
    private void showError(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvStatus.setTextColor(Color.parseColor("#FF6B6B")); // red
                    tvStatus.setText(message);
                }
            });
        }
    }

    // showInfo — displays a blue info message below the form (e.g. "Creating group...")
    private void showInfo(String message) {
        if (getActivity() != null) {
            getActivity().runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    tvStatus.setTextColor(Color.parseColor("#4DA6FF")); // blue
                    tvStatus.setText(message);
                }
            });
        }
    }


    // ══════════════════════════════════════════════════════════════════════
    // TURSO DATABASE QUERY
    // Sends an SQL query to the Turso cloud database and returns the result.
    // (Same pattern used in all other fragments — see DrivingReportsFragment
    //  for detailed comments on how this works.)
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

        // Build the args array — each arg becomes { "type": "text", "value": "..." }
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

        // Build the full request body
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

        // Send the JSON to the server
        byte[] requestBytes = body.toString().getBytes(StandardCharsets.UTF_8);
        OutputStream outputStream = conn.getOutputStream();
        outputStream.write(requestBytes);
        outputStream.close();

        // Read the server's response
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
