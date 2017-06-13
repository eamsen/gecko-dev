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


/* package */ class GeckoViewNavigationHandler extends GeckoViewHandler {

    private static final String LOGTAG = "GeckoViewNavigationHandler";
    private static final boolean DEBUG = true;

    private static final String[] sEvents = {
        "GeckoView:LocationChange",
    };

    /* package */ final GeckoView.NavigationListener mListener;

    GeckoViewNavigationHandler(final GeckoView view,
                               final GeckoView.NavigationListener listener) {
        super("GeckoViewNavigation", view);
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

        if ("GeckoView:LocationChange".equals(event)) {
            mListener.onLocationChange(getGeckoView(), message.getString("uri"));
            mListener.onCanGoBack(getGeckoView(), 
                                  message.getBoolean("canGoBack"));
            mListener.onCanGoForward(getGeckoView(),
                                     message.getBoolean("canGoForward"));
        }
    }
}
