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


/* package */ class GeckoViewScrollHandler extends GeckoViewHandler {

    private static final String LOGTAG = "GeckoViewScrollHandler";
    private static final boolean DEBUG = true;

    private static final String[] sEvents = {
        "GeckoView:ScrollChanged",
    };

    /* package */ final GeckoView.ScrollListener mListener;

    GeckoViewScrollHandler(final GeckoView view,
                           final GeckoView.ScrollListener listener) {
        super("GeckoViewScrollContent", view);
        mListener = listener;
    }

    @Override
    protected String[] getEvents() {
        return sEvents;
    }

    @Override
    protected GeckoBundle getEventData() {
        final GeckoBundle data = new GeckoBundle(1);
        data.putBoolean("scroll", true);
        return data;
    }

    @Override
    public void handleMessage(final String event, final GeckoBundle message,
                              final EventCallback callback) {
        if (DEBUG) {
            Log.d(LOGTAG, "handleMessage: event = " + event);
        }

        if ("GeckoView:ScrollChanged".equals(event)) {
            mListener.onScrollChanged(getGeckoView(),
                                      message.getInt("scrollX"),
                                      message.getInt("scrollY"));
        }
    }
}
