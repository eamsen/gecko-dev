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


/* package */ class GeckoViewContentHandler
    extends GeckoViewHandler {

    private static final String LOGTAG = "GeckoView:ContentHandler";
    private static final boolean DEBUG = true;

    private static final String[] sEvents = {
        "GeckoView:DOMTitleChanged",
        "GeckoView:FullScreenEnter",
        "GeckoView:FullScreenExit",
    };

    /* package */ final GeckoView.ContentListener mListener;

    GeckoViewContentHandler(final GeckoView view,
                            final GeckoView.ContentListener listener) {
        super("GeckoViewContent", view);
        mListener = listener;
    }

    @Override
    protected String[] getEvents() {
        return sEvents;
    }

    @Override
    protected GeckoBundle getEventData() {
        final GeckoBundle data = new GeckoBundle(2);
        data.putBoolean("fullscreen", true);
        data.putBoolean("title", true);
        return data;
    }

    @Override
    public void handleMessage(final String event, final GeckoBundle message,
                              final EventCallback callback) {
        if (DEBUG) {
            Log.d(LOGTAG, "handleMessage: event = " + event);
        }

        if ("GeckoView:DOMTitleChanged".equals(event)) {
            mListener.onTitleChange(getGeckoView(), message.getString("title"));
        } else if ("GeckoView:FullScreenEnter".equals(event)) {
            mListener.onFullScreen(getGeckoView(), true);
        } else if ("GeckoView:FullScreenExit".equals(event)) {
            mListener.onFullScreen(getGeckoView(), false);
        }
    }
}
