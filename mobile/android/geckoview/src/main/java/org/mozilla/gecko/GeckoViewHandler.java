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


/* package */ abstract class GeckoViewHandler implements BundleEventListener {

    private static final String LOGTAG = "GeckoView:Handler";
    private static final boolean DEBUG = true;

    private final GeckoView mGeckoView;
    private final String mModuleName;

    GeckoViewHandler(final String module, final GeckoView view) {
        mModuleName = module;
        mGeckoView = view;

        register();
    }

    protected String[] getEvents() {
        return null;
    }

    protected GeckoBundle getEventData() {
        return null;
    }

    protected GeckoView getGeckoView() {
        return mGeckoView;
    }

    private void register() {
        final EventDispatcher eventDispatcher = mGeckoView.getEventDispatcher();
        eventDispatcher.dispatch(mModuleName + ":Register", getEventData());

        for (final String event: getEvents()) {
            eventDispatcher.registerUiThreadListener(this, event);
        }
    }

    /* package */ void unregister() {
        final EventDispatcher eventDispatcher = mGeckoView.getEventDispatcher();
        eventDispatcher.dispatch(mModuleName + ":Unregister", getEventData());

        for (final String event: getEvents()) {
            eventDispatcher.unregisterUiThreadListener(this, event);
        }
    }
}
