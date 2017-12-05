/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import java.util.EnumSet;
import java.util.HashSet;

import android.util.Log;

public class TrackingProtection {
    public enum Tracker {
        // Keep in sync with browser.safebrowsing.provider.mozilla.lists.
        // TODO: update tracker list mappings after base list split.
        AD("base-track-digest256"),
        ANALYTIC("base-track-digest256"),
        SOCIAL("base-track-digest256"),
        CONTENT("content-track-digest256");

        private final String mValue;

        private Tracker(final String value) {
            mValue = value;
        }

        @Override
        public String toString() {
            return mValue;
        }
    }

    private static final String LOGTAG = "GeckoViewTrackingProtection";
    private static final String TRACKERS_PREF = "urlclassifier.trackingTable";
    private static final String LISTS_PREF =
        "browser.safebrowsing.provider.mozilla.lists";

    private static String[] sAvailableTpLists = new String[0];
    private static HashSet<Tracker> sAvailableLists;

    public static void block(final EnumSet<Tracker> trackers) {
        HashSet used = new HashSet<String>();
        String prefValue = "test-track-simple";
        for (final Tracker tracker: trackers) {
            if (used.contains(tracker.toString())) {
                continue;
            }
            used.add(tracker.toString());
            prefValue += "," + tracker.toString();
        }

        Log.d(LOGTAG, "blocking " + used.toString());
        PrefsHelper.setPref(TRACKERS_PREF, prefValue);
    }
}
