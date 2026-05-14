package com.example.boltapp;

import android.content.Intent;         // used to start a new screen (Activity)
import android.content.SharedPreferences; // a simple key-value store for saving small data locally
import android.graphics.Color;         // lets us use colors
import android.os.Bundle;             // holds saved state when the screen rotates
import android.view.View;             // the base class of every UI element
import android.widget.ArrayAdapter;   // connects a list of strings to a Spinner dropdown
import android.widget.Button;         // a clickable button
import android.widget.Spinner;        // a dropdown menu
import android.widget.TextView;       // a view that shows text
import android.widget.Toast;          // a short pop-up message at the bottom of the screen

import androidx.appcompat.app.AppCompatActivity; // base class for all activities in this app

import com.google.android.material.textfield.TextInputEditText; // a styled text input field

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset
import java.security.MessageDigest;    // used to hash the password with SHA-256
import java.util.UUID;                 // generates a unique ID for each new user

// ══════════════════════════════════════════════════════════════════════════════
// LoginActivity
//
// This is the first screen the user sees. It handles two modes:
//   • Login mode   → enter email + password to sign in
//   • Register mode → enter all fields to create a new account
//
// The user can tap a toggle link to switch between the two modes.
// ══════════════════════════════════════════════════════════════════════════════
public class LoginActivity extends AppCompatActivity {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── State ───────────────────────────────────────────────────────────
    // isLoginMode: true = showing login form, false = showing register form
    private boolean isLoginMode = true;

    // ── UI elements ─────────────────────────────────────────────────────
    private TextInputEditText etEmail;       // email field
    private TextInputEditText etPassword;    // password field
    private TextInputEditText etFirstName;   // first name field (register only)
    private TextInputEditText etLastName;    // last name field  (register only)
    private TextInputEditText etDisplayName; // display name field (register only)
    private TextInputEditText etPhone;       // phone field (register only)
    private TextInputEditText etAge;         // age field (register only)
    private TextInputEditText etBio;         // bio field (register only)
    private Spinner spinnerStatus;           // dropdown: "still" / "walking" / "driving"
    private Button btnMain;                  // main action button ("Login" or "Register")
    private TextView tvToggle;              // link to switch between login and register
    private TextView tvSubtitle;            // subtitle text under the logo
    private TextView tvStatus;             // shows error or info messages
    private View layoutRegisterFields;      // the extra fields shown only when registering


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called once when the activity first appears.
    // We set up the layout and all the click listeners here.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login); // load the XML layout for this screen

        // Link each variable to its matching XML element by ID
        etEmail              = findViewById(R.id.etEmail);
        etPassword           = findViewById(R.id.etPassword);
        etFirstName          = findViewById(R.id.etFirstName);
        etLastName           = findViewById(R.id.etLastName);
        etDisplayName        = findViewById(R.id.etDisplayName);
        etPhone              = findViewById(R.id.etPhone);
        etAge                = findViewById(R.id.etAge);
        etBio                = findViewById(R.id.etBio);
        spinnerStatus        = findViewById(R.id.spinnerStatus);
        btnMain              = findViewById(R.id.btnMain);
        tvToggle             = findViewById(R.id.tvToggle);
        tvSubtitle           = findViewById(R.id.tvSubtitle);
        tvStatus             = findViewById(R.id.tvStatus);
        layoutRegisterFields = findViewById(R.id.layoutRegisterFields);

        // ── Set up the status dropdown (Spinner) ────────────────────────
        // We create a custom adapter so the text is light-colored on the dark background
        ArrayAdapter<String> statusAdapter = new ArrayAdapter<String>(
                this,
                android.R.layout.simple_spinner_item,        // built-in layout for each row
                new String[]{"still", "walking", "driving"} // the three status options
        ) {
            // getView — called for the selected-item view (what you see when dropdown is closed)
            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getView(position, convertView, parent);
                ((TextView) v).setTextColor(Color.parseColor("#E6EDF3"));   // light grey text
                ((TextView) v).setBackgroundColor(Color.parseColor("#161B22")); // dark background
                return v;
            }

            // getDropDownView — called for each item inside the open dropdown list
            @Override
            public View getDropDownView(int position, View convertView, android.view.ViewGroup parent) {
                View v = super.getDropDownView(position, convertView, parent);
                ((TextView) v).setTextColor(Color.parseColor("#E6EDF3"));
                ((TextView) v).setBackgroundColor(Color.parseColor("#161B22"));
                ((TextView) v).setPadding(32, 24, 32, 24); // add some breathing room around text
                return v;
            }
        };

        statusAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerStatus.setAdapter(statusAdapter); // attach the adapter to the spinner

        // ── Button click listeners ───────────────────────────────────────

        // Main button: either login or register depending on current mode
        btnMain.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isLoginMode) {
                    handleLogin();    // run login if we're in login mode
                } else {
                    handleRegister(); // run register if we're in register mode
                }
            }
        });

        // Toggle link: switch between login and register modes
        tvToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                toggleMode();
            }
        });
    }

    // toggleMode — flips between Login mode and Register mode.
    private void toggleMode() {
        isLoginMode = !isLoginMode; // flip the boolean (true → false, false → true)

        if (isLoginMode) {
            // Switched TO login mode
            btnMain.setText("Login");
            tvSubtitle.setText("Sign in to continue");
            tvToggle.setText("Don't have an account? Register");
            layoutRegisterFields.setVisibility(View.GONE); // hide the extra register fields
        } else {
            // Switched TO register mode
            btnMain.setText("Register");
            tvSubtitle.setText("Create your account");
            tvToggle.setText("Already have an account? Login");
            layoutRegisterFields.setVisibility(View.VISIBLE); // show the extra register fields
        }

        tvStatus.setText(""); // clear any previous error or info message
    }


    // ══════════════════════════════════════════════════════════════════════
    // LOGIN
    // ══════════════════════════════════════════════════════════════════════
    private void handleLogin() {
        String email    = getText(etEmail);    // read the email field
        String password = getText(etPassword); // read the password field

        // Stop if either field is empty
        if (email.isEmpty() || password.isEmpty()) {
            showError("Please enter your email and password.");
            return;
        }

        // Hash the password with SHA-256 before sending it to the server.
        // We never store plain-text passwords — only the hash.
        String hashedPassword = sha256(password);

        btnMain.setEnabled(false); // disable button while waiting for the server
        showInfo("Logging in...");

        // Run the network request on a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Look up the user by email and hashed password
                    JSONObject result = tursoQuery(
                            "SELECT uid, display_name FROM users WHERE email = ? AND password = ?",
                            new Object[]{email, hashedPassword}
                    );

                    // Navigate the JSON structure to find the rows
                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // Back to the main thread to update the UI
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnMain.setEnabled(true); // re-enable the button

                            if (rows.length() > 0) {
                                // Login successful — extract the user's data
                                try {
                                    String uid         = rows.getJSONArray(0).getJSONObject(0).getString("value");
                                    String displayName = rows.getJSONArray(0).getJSONObject(1).getString("value");

                                    // Save the user's session so they stay logged in after closing the app.
                                    // SharedPreferences works like a small local settings file.
                                    SharedPreferences prefs = getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
                                    prefs.edit()
                                            .putString("uid", uid)
                                            .putString("display_name", displayName)
                                            .apply(); // apply() saves it in the background

                                    // Show a quick welcome pop-up
                                    Toast.makeText(LoginActivity.this, "Welcome, " + displayName + "!", Toast.LENGTH_SHORT).show();

                                    // Navigate to MainActivity and clear the back stack
                                    // (so the user can't press Back to return to the login screen)
                                    Intent intent = new Intent(LoginActivity.this, MainActivity.class);
                                    intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                                    startActivity(intent);
                                    finish(); // close LoginActivity completely

                                } catch (Exception e) {
                                    showError("Login error: " + e.getMessage());
                                }

                            } else {
                                // No matching user found → wrong credentials
                                showError("Invalid email or password.");
                            }
                        }
                    });

                } catch (Exception e) {
                    // Network/connection error
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnMain.setEnabled(true);
                            showError("Connection error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // REGISTER
    // ══════════════════════════════════════════════════════════════════════
    private void handleRegister() {
        // Read all the input fields
        String email       = getText(etEmail);
        String password    = getText(etPassword);
        String firstName   = getText(etFirstName);
        String lastName    = getText(etLastName);
        String displayName = getText(etDisplayName);
        String phone       = getText(etPhone);
        String ageText     = getText(etAge);
        String bio         = getText(etBio);
        String status      = spinnerStatus.getSelectedItem().toString(); // selected dropdown option

        // The four required fields — stop if any are empty
        if (email.isEmpty() || password.isEmpty() || firstName.isEmpty() || lastName.isEmpty()) {
            showError("Email, password, first and last name are required.");
            return;
        }

        // Generate a unique ID for this new user
        String uid = UUID.randomUUID().toString();

        // Hash the password before saving it (we never save plain-text passwords)
        String hashedPassword = sha256(password);

        // Convert the age text to a number; default to 0 if it's not a valid number
        int age = 0;
        if (!ageText.isEmpty()) {
            try {
                age = Integer.parseInt(ageText);
            } catch (NumberFormatException ignored) {
                // If parsing fails, we just use 0
            }
        }
        final int finalAge = age; // must be final to be used inside the Thread

        btnMain.setEnabled(false);
        showInfo("Creating account...");

        // Run the registration in a background thread
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // First, check if this email is already registered
                    JSONObject check = tursoQuery(
                            "SELECT uid FROM users WHERE email = ?",
                            new Object[]{email}
                    );

                    JSONArray existingRows = check
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // If we found a match, the email is already taken
                    if (existingRows.length() > 0) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                btnMain.setEnabled(true);
                                showError("Email already registered.");
                            }
                        });
                        return; // stop here — don't create a duplicate
                    }

                    // Insert the new user into the database.
                    // Note: creation_time uses a DB default, so we don't send it.
                    tursoQuery(
                            "INSERT INTO users (uid, display_name, email, first_name, last_name, age, bio, phone, password, status) " +
                                    "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                            new Object[]{
                                    uid,
                                    displayName,
                                    email,
                                    firstName,
                                    lastName,
                                    finalAge,       // int — stored as an integer in the DB
                                    bio,
                                    phone,
                                    hashedPassword,
                                    status
                            }
                    );

                    // Registration succeeded — go back to the main thread and show success
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnMain.setEnabled(true);
                            Toast.makeText(LoginActivity.this, "Account created! Please log in.", Toast.LENGTH_SHORT).show();
                            toggleMode(); // switch back to login mode automatically
                        }
                    });

                } catch (Exception e) {
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            btnMain.setEnabled(true);
                            showError("Registration error: " + e.getMessage());
                        }
                    });
                }
            }
        }).start();
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


    // ══════════════════════════════════════════════════════════════════════
    // UTILITY METHODS
    // ══════════════════════════════════════════════════════════════════════

    // getText — safely reads and trims text from a TextInputEditText field.
    // Returns "" (empty string) if the field has no content.
    private String getText(TextInputEditText field) {
        if (field.getText() != null) {
            return field.getText().toString().trim();
        }
        return "";
    }

    // showError — displays a red error message in tvStatus
    private void showError(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvStatus.setTextColor(Color.parseColor("#FF6B6B")); // red
                tvStatus.setText(message);
            }
        });
    }

    // showInfo — displays a blue info message in tvStatus (e.g. "Logging in...")
    private void showInfo(String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tvStatus.setTextColor(Color.parseColor("#4DA6FF")); // blue
                tvStatus.setText(message);
            }
        });
    }

    // sha256 — hashes the input string using the SHA-256 algorithm.
    // This converts a password like "hello" into a fixed-length string of hex characters.
    // The same input always produces the same output, but you can't reverse it.
    private String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); // get the SHA-256 hasher

            // Convert the input string to bytes, then hash them
            byte[] hashBytes = digest.digest(input.getBytes(StandardCharsets.UTF_8));

            // Convert each byte to a two-character hex string and join them
            StringBuilder hexString = new StringBuilder();
            for (byte b : hashBytes) {
                hexString.append(String.format("%02x", b)); // e.g. byte 255 → "ff"
            }

            return hexString.toString(); // return the full hex hash

        } catch (Exception e) {
            // If SHA-256 is somehow unavailable (extremely rare), return the input as-is
            return input;
        }
    }
}
