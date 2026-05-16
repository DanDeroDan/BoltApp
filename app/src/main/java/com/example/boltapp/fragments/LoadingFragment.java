package com.example.boltapp.fragments;

import android.os.Bundle;           // used to save/restore state when the screen rotates
import android.view.LayoutInflater; // builds a View from an XML layout file
import android.view.View;           // the base class of every UI element
import android.view.ViewGroup;      // a container that can hold other views

import androidx.annotation.NonNull;    // marks that a parameter can never be null
import androidx.annotation.Nullable;   // marks that a return value or parameter can be null
import androidx.fragment.app.Fragment; // the base class for a "screen piece"

import com.example.boltapp.R;

// ══════════════════════════════════════════════════════════════════════════════
// LoadingFragment
//
// This is a simple loading screen shown while the app is fetching data
// (for example, while checking which groups the user belongs to).
//
// It has no logic — it just displays the XML layout and waits.
// MainActivity replaces it with a real screen once the data is ready.
// ══════════════════════════════════════════════════════════════════════════════
public class LoadingFragment extends Fragment {

    // onCreateView — called when Android wants to draw this fragment on screen.
    // We just inflate (build) the loading layout from XML and return it.
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        // R.layout.fragment_loading is the XML file that defines the loading spinner / logo
        return inflater.inflate(R.layout.fragment_loading, container, false);
    }
}
