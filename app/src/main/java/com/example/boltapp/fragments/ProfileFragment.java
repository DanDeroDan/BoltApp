package com.example.boltapp.fragments;

import android.content.SharedPreferences; // key-value storage for small local data
import android.os.Bundle;               // holds data passed between screens
import android.os.Handler;              // lets us schedule code on the main thread
import android.os.Looper;              // provides the main thread's message loop
import android.view.LayoutInflater;    // builds Views from XML layout files
import android.view.View;              // the base class of every UI element
import android.view.ViewGroup;         // a container that holds other views
import android.widget.Button;          // a clickable button
import android.widget.TextView;        // a view that shows text

import androidx.annotation.NonNull;    // marks that a parameter can never be null
import androidx.annotation.Nullable;   // marks that a value can be null
import androidx.fragment.app.Fragment; // base class for a "screen piece"

import org.json.JSONArray;   // represents a JSON list
import org.json.JSONObject;  // represents a JSON object

import java.io.InputStream;              // reads bytes from the network
import java.io.OutputStream;            // writes bytes to the network
import java.net.HttpURLConnection;      // opens an HTTP connection
import java.net.URL;                    // represents a web address
import java.nio.charset.StandardCharsets; // gives us the UTF-8 charset

import static android.content.Context.MODE_PRIVATE; // constant used when opening SharedPreferences

import com.example.boltapp.MainActivity;
import com.example.boltapp.R;

// ══════════════════════════════════════════════════════════════════════════════
// ProfileFragment
//
// This screen shows the logged-in user's profile information:
// their avatar initial, display name, first/last name, email, phone, age, and bio.
//
// It also has a "Join Another Group" button that sends the user to GroupSetupFragment.
// The data is fetched from the Turso database once when the screen opens.
// ══════════════════════════════════════════════════════════════════════════════
public class ProfileFragment extends Fragment {

    // ── Database connection info ────────────────────────────────────────
    private static final String TURSO_URL   = "https://boltapp-danderodan.aws-eu-west-1.turso.io";
    private static final String TURSO_TOKEN = "eyJhbGciOiJFZERTQSIsInR5cCI6IkpXVCJ9.eyJhIjoicnciLCJnaWQiOiI2ODEyOTAyZS00MzRmLTQzZTMtYjM1Ny02N2IyMTk2YmJiY2YiLCJpYXQiOjE3NzU5MDgyNzQsInJpZCI6Ijc1MjQ2ODgyLTYyZTItNDU5Yy1iM2U4LTFmYjNjNzQ4ZDY1ZiJ9.1aaDGiJCblc2Cft3atzWSB2jEaWlsIsVN71mVyxesiVNEhsXnSbQm-I-WLzV4CNwCJickPli972rW7abVnfxDA";

    // ── UI elements ─────────────────────────────────────────────────────
    private TextView tvAvatar;       // shows the first letter of the display name (e.g. "D")
    private TextView tvDisplayName;  // shows the full display name (e.g. "Dan")
    private TextView tvFirstName;    // shows first name
    private TextView tvLastName;     // shows last name
    private TextView tvEmail;        // shows email address
    private TextView tvPhone;        // shows phone number
    private TextView tvAge;          // shows age
    private TextView tvBio;          // shows the user's bio text
    private Button btnJoinGroup;     // button that takes the user to the group setup screen

    // ── User data ───────────────────────────────────────────────────────
    private String uid; // the logged-in user's unique ID (read from SharedPreferences)


    // ══════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ══════════════════════════════════════════════════════════════════════

    // onCreate — called before the layout is built.
    // We read the user's uid from local storage here.
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // SharedPreferences is like a small file of saved settings on the device.
        // "BoltAppPrefs" is the name of the file; MODE_PRIVATE means only our app can read it.
        SharedPreferences prefs = requireActivity().getSharedPreferences("BoltAppPrefs", MODE_PRIVATE);
        uid = prefs.getString("uid", null); // null means the user isn't logged in
    }

    // onCreateView — builds the fragment's layout from XML and returns it.
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_profile, container, false);
    }

    // onViewCreated — called right after the layout is ready on screen.
    // We link variables to XML views and kick off the data load.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        // Link each variable to its matching XML element by ID
        tvAvatar      = view.findViewById(R.id.tvAvatar);
        tvDisplayName = view.findViewById(R.id.tvDisplayName);
        tvFirstName   = view.findViewById(R.id.tvFirstName);
        tvLastName    = view.findViewById(R.id.tvLastName);
        tvEmail       = view.findViewById(R.id.tvEmail);
        tvPhone       = view.findViewById(R.id.tvPhone);
        tvAge         = view.findViewById(R.id.tvAge);
        tvBio         = view.findViewById(R.id.tvBio);
        btnJoinGroup  = view.findViewById(R.id.btnJoinGroup);

        // When the "Join Another Group" button is tapped, navigate to the group setup screen
        btnJoinGroup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                // Make sure our parent Activity is a MainActivity before casting
                if (getActivity() instanceof MainActivity) {
                    ((MainActivity) getActivity()).goToGroupSetupFromProfile();
                }
            }
        });

        // Fetch the profile data from the database
        loadProfile();
    }


    // ══════════════════════════════════════════════════════════════════════
    // DATA LOADING
    // ══════════════════════════════════════════════════════════════════════

    // loadProfile — fetches the current user's data from Turso and populates the UI.
    private void loadProfile() {
        if (uid == null) return; // no user logged in — nothing to load

        // Run the network request in a background thread (not allowed on the main thread)
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // Ask the database for the user's profile columns
                    JSONObject result = tursoQuery(
                            "SELECT first_name, last_name, email, phone, age, bio, display_name " +
                            "FROM users WHERE uid = ?",
                            new Object[]{uid}
                    );

                    // Navigate through the nested JSON to get the data rows
                    JSONArray rows = result
                            .getJSONArray("results")
                            .getJSONObject(0)
                            .getJSONObject("response")
                            .getJSONObject("result")
                            .getJSONArray("rows");

                    // If the query returned no rows, the user wasn't found — do nothing
                    if (rows.length() == 0) return;

                    // The first (and only) row contains all our columns
                    JSONArray row = rows.getJSONArray(0);

                    // Extract each column by its index in the SELECT statement
                    String firstName   = getVal(row, 0); // first_name
                    String lastName    = getVal(row, 1); // last_name
                    String email       = getVal(row, 2); // email
                    String phone       = getVal(row, 3); // phone
                    String age         = getVal(row, 4); // age
                    String bio         = getVal(row, 5); // bio
                    String displayName = getVal(row, 6); // display_name

                    // Go back to the main thread to update the UI
                    new Handler(Looper.getMainLooper()).post(new Runnable() {
                        @Override
                        public void run() {
                            // isAdded() makes sure the fragment is still on screen
                            if (!isAdded()) return;

                            // Avatar: just the first letter of the display name, e.g. "D" for "Dan"
                            String initial = "?";
                            if (displayName != null && !displayName.isEmpty()) {
                                initial = String.valueOf(displayName.charAt(0)).toUpperCase();
                            }
                            tvAvatar.setText(initial);

                            // Fill in each text view — show "—" if the value is null/missing
                            tvDisplayName.setText(displayName != null ? displayName : "\u2014");
                            tvFirstName.setText(firstName  != null ? firstName  : "\u2014");
                            tvLastName.setText(lastName    != null ? lastName   : "\u2014");
                            tvEmail.setText(email          != null ? email      : "\u2014");
                            tvPhone.setText(phone          != null ? phone      : "\u2014");
                            tvAge.setText(age              != null ? age        : "\u2014");
                            tvBio.setText((bio != null && !bio.isEmpty()) ? bio : "\u2014");
                        }
                    });

                } catch (Exception e) {
                    e.printStackTrace(); // print the error to the Android log for debugging
                }
            }
        }).start();
    }


    // ══════════════════════════════════════════════════════════════════════
    // HELPER METHODS
    // ══════════════════════════════════════════════════════════════════════

    // getVal — safely reads one text value from a JSON row at the given column index.
    // Returns null if the cell is empty, missing, or contains a database NULL.
    private String getVal(JSONArray row, int index) {
        try {
            JSONObject cell = row.getJSONObject(index);

            // The database stores NULL as a cell with type "null"
            if ("null".equals(cell.optString("type"))) return null;

            String value = cell.optString("value", "");
            return value.isEmpty() ? null : value;

        } catch (Exception e) {
            return null; // if anything goes wrong, treat it as missing data
        }
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
