/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.pocketds.parts;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** The folders of the list of applications, kept in the preferences as JSON. */
final class Folders {
    private static final String KEY = "launcher_folders";

    static final class Folder {
        String id;
        String name;
        final List<String> apps = new ArrayList<>(); // component, flattenToShortString
    }

    private final SharedPreferences mPrefs;

    Folders(Context context) {
        mPrefs = PartsPrefs.get(context);
    }

    List<Folder> load() {
        final List<Folder> folders = new ArrayList<>();
        try {
            final JSONArray array = new JSONArray(mPrefs.getString(KEY, "[]"));
            for (int i = 0; i < array.length(); i++) {
                final JSONObject object = array.getJSONObject(i);
                final Folder folder = new Folder();
                folder.id = object.getString("id");
                folder.name = object.getString("name");
                final JSONArray apps = object.getJSONArray("apps");
                for (int k = 0; k < apps.length(); k++) folder.apps.add(apps.getString(k));
                folders.add(folder);
            }
        } catch (JSONException e) {
            // unreadable: no folder
        }
        return folders;
    }

    void save(List<Folder> folders) {
        try {
            final JSONArray array = new JSONArray();
            for (Folder folder : folders) {
                final JSONObject object = new JSONObject();
                object.put("id", folder.id);
                object.put("name", folder.name);
                object.put("apps", new JSONArray(folder.apps));
                array.put(object);
            }
            mPrefs.edit().putString(KEY, array.toString()).apply();
        } catch (JSONException e) {
            // not saved
        }
    }

    /** The folder that holds the app, or null. */
    Folder folderOf(List<Folder> folders, String app) {
        for (Folder folder : folders) if (folder.apps.contains(app)) return folder;
        return null;
    }

    Folder create(List<Folder> folders, String name) {
        final Folder folder = new Folder();
        folder.id = UUID.randomUUID().toString();
        folder.name = name.isEmpty() ? "Folder" : name;
        folders.add(folder);
        return folder;
    }

    /** Puts an app in a folder (and takes it out of any other). */
    void add(List<Folder> folders, Folder target, String app) {
        for (Folder folder : folders) folder.apps.remove(app);
        target.apps.add(app);
    }

    void remove(List<Folder> folders, String app) {
        for (Folder folder : folders) folder.apps.remove(app);
        // an empty folder goes away
        folders.removeIf(folder -> folder.apps.isEmpty());
    }

    void clear() {
        mPrefs.edit().remove(KEY).apply();
    }
}
