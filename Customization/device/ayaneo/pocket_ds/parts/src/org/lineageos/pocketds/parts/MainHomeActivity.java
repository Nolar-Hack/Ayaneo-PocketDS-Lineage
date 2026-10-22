/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.app.Activity;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.View;

/**
 * Home of the main (top) screen: nothing is drawn, the wallpaper is the screen: the one chosen
 * for that panel in the settings of the app, else the wallpaper of the system. The apps are
 * opened from the list of the bottom screen ({@link SecondHomeActivity}).
 */
public class MainHomeActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new View(this));
    }

    private boolean mCustom;

    @Override
    protected void onResume() {
        super.onResume();
        int panel = -1;
        try {
            if (getDisplay() != null) panel = Screens.panelOf(this, getDisplay().getDisplayId());
        } catch (LinkageError | RuntimeException e) {
            // the panel is not known: no wallpaper of its own
        }
        final Drawable wallpaper = Wallpapers.load(this, panel);
        if (wallpaper != null) {
            getWindow().setBackgroundDrawable(wallpaper);
            mCustom = true;
        } else if (mCustom) {
            // The picture was removed: the theme (the wallpaper of the system) comes back with a
            // new window. A null background would leave the screen plain black.
            mCustom = false;
            recreate();
        }
    }
}
