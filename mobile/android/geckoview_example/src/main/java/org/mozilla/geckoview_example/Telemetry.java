/* -*- Mode: Java; c-basic-offset: 4; tab-width: 20; indent-tabs-mode: nil; -*-
 * vim: ts=4 sw=4 expandtab:
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.geckoview;

import org.mozilla.gecko.PrefsHelper;

/**
 * Gecko telemetry controller.
 **/
public class Telemetry {
    private static final String TELEMTRY_PREF = "toolkit.telemetry.enabled";
    private static final String TELEMTRY_FHR_PREF = "datareporting.healthreport.uploadEnabled";

    /**
     * Control Gecko telemetry reporting.
     * @param enable Whether telemetry reporting should be enanbled.
     **/
    public static void enableReporting(boolean enable) {
        PrefsHelper.setPref(TELEMTRY_PREF, enable);
        PrefsHelper.setPref(TELEMTRY_FHR_PREF, enable);
    }
}
