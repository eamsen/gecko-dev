/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/. */

/**
 * Asynchronous front-end for OS.File.
 *
 * This front-end is meant to be imported from the main thread. In turn,
 * it spawns one worker (perhaps more in the future) and delegates all
 * disk I/O to this worker.
 *
 * Documentation note: most of the functions and methods in this module
 * return promises. For clarity, we denote as follows a promise that may resolve
 * with type |A| and some value |value| or reject with type |B| and some
 * reason |reason|
 * @resolves {A} value
 * @rejects {B} reason
 */

"use strict";

this.EXPORTED_SYMBOLS = ["OS"];

const Cu = Components.utils;
const Ci = Components.interfaces;

let SharedAll = {};
Cu.import("resource://gre/modules/osfile/osfile_shared_allthreads.jsm", SharedAll);


// Boilerplate, to simplify the transition to require()
let LOG = SharedAll.LOG.bind(SharedAll, "Controller");

let Path = {};
Cu.import("resource://gre/modules/osfile/ospath.jsm", Path);

// It's possible for osfile.jsm to get imported before the profile is
// set up. In this case, some path constants aren't yet available.
// Here, we make them lazy loaders.

function lazyPathGetter(constProp, dirKey) {
  return function () {
    let path;
    try {
      Cu.import("resource://gre/modules/Services.jsm", this);
      path = Services.dirsvc.get(dirKey, Ci.nsIFile).path;
      delete SharedAll.Constants.Path[constProp];
      SharedAll.Constants.Path[constProp] = path;
    } catch (ex) {
      // Ignore errors if the value still isn't available. Hopefully
      // the next access will return it.
    }

    return path;
  };
}

for (let [constProp, dirKey] of [
  ["localProfileDir", "ProfLD"],
  ["profileDir", "ProfD"],
  ["userApplicationDataDir", "UAppData"],
  ["winAppDataDir", "AppData"],
  ["winStartMenuProgsDir", "Progs"],
  ]) {

  if (constProp in SharedAll.Constants.Path) {
    continue;
  }

  LOG("Installing lazy getter for OS.Constants.Path." + constProp +
      " because it isn't defined and profile may not be loaded.");
  Object.defineProperty(SharedAll.Constants.Path, constProp, {
    get: lazyPathGetter(constProp, dirKey),
  });
}

this.OS = {};
this.OS.Constants = SharedAll.Constants;
this.OS.Path = Path;

// Returns a resolved promise when all the queued operation have been completed.
