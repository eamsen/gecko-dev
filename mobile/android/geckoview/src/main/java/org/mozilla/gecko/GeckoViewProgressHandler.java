/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko;

import org.mozilla.gecko.util.BundleEventListener;
import org.mozilla.gecko.util.EventCallback;
import org.mozilla.gecko.util.GeckoBundle;

import android.util.Log;


/* package */ class GeckoViewProgressHandler extends GeckoViewHandler {

    private static final String LOGTAG = "GeckoViewProgressHandler";
    private static final boolean DEBUG = true;

    private static final String[] sEvents = {
        "GeckoView:PageStart",
        "GeckoView:PageStop",
        "GeckoView:SecurityChanged",
    };

    /* package */ final GeckoView.ProgressListener mListener;

    GeckoViewProgressHandler(final GeckoView view,
                             final GeckoView.ProgressListener listener) {
        super("GeckoViewProgress", view);
        mListener = listener;
    }

    @Override
    protected String[] getEvents() {
        return sEvents;
    }

    @Override
    public void handleMessage(final String event, final GeckoBundle message,
                              final EventCallback callback) {
        if (DEBUG) {
            Log.d(LOGTAG, "handleMessage: event = " + event);
        }

        if ("GeckoView:PageStart".equals(event)) {
            mListener.onPageStart(getGeckoView(), message.getString("uri"));
        } else if ("GeckoView:PageStop".equals(event)) {
            mListener.onPageStop(getGeckoView(), message.getBoolean("success"));
        } else if ("GeckoView:SecurityChanged".equals(event)) {
            int state = message.getInt("status") &
                        GeckoView.ProgressListener.STATE_ALL;
            mListener.onSecurityChange(getGeckoView(), state);
        }
    }
}
