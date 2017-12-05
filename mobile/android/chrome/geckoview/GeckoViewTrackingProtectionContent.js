/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*- */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

const { classes: Cc, interfaces: Ci, utils: Cu } = Components;

Cu.import("resource://gre/modules/GeckoViewContentModule.jsm");
Cu.import("resource://gre/modules/XPCOMUtils.jsm");

XPCOMUtils.defineLazyGetter(this, "dump", () =>
    Cu.import("resource://gre/modules/AndroidLog.jsm",
              {}).AndroidLog.d.bind(null, "ViewTrackingProtection"));

function debug(aMsg) {
  // dump(aMsg);
}

class GeckoViewTrackingProtectionContent extends GeckoViewContentModule {
  register() {
    debug("register");

    addEventListener("MozTrackingProtection:Blocked", this, false);
  }

  unregister() {
    debug("unregister");

    removeEventListener("MozTrackingProtection:Blocked", this);
  }

  handleEvent(aEvent) {
    debug("handleEvent " + aEvent.type);

    switch (aEvent.type) {
      case "MozTrackingProtection:Blocked":
        let nodes = content.document.blockedTrackingNodes;
        if (nodes.length < 1) {
          break;
        }
        let lastNode = nodes.item(nodes.length - 1);
        this.eventDispatcher.sendRequest({
          type: "GeckoView:TrackingProtection:Blocked",
          src: lastNode.src,
          totalBlocked: nodes.length
        });
        break;
    }
  }
}

var tpListener = new GeckoViewTrackingProtectionContent(
                 "GeckoViewTrackingProtection", this);
