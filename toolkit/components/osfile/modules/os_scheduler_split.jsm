"use strict";

const Cu = Components.utils;
const Ci = Components.interfaces;

Cu.import("resource://gre/modules/Timer.jsm", this);

let SharedAll = {};
Cu.import("resource://gre/modules/osfile/osfile_shared_allthreads.jsm", SharedAll);

// Boilerplate, to simplify the transition to require()
let LOG = SharedAll.LOG.bind(SharedAll, "Controller");
let isTypedArray = SharedAll.isTypedArray;

// The constructor for file errors.
let SysAll = {};
if (SharedAll.Constants.Win) {
  Cu.import("resource://gre/modules/osfile/osfile_win_allthreads.jsm", SysAll);
} else if (SharedAll.Constants.libc) {
  Cu.import("resource://gre/modules/osfile/osfile_unix_allthreads.jsm", SysAll);
} else {
  throw new Error("I am neither under Windows nor under a Posix system");
}
let OSError = SysAll.Error;

Cu.import("resource://gre/modules/Promise.jsm", this);
Cu.import("resource://gre/modules/Task.jsm", this);

Cu.import("resource://gre/modules/PromiseWorker.jsm", this);
Cu.import("resource://gre/modules/Services.jsm", this);

/**
 * Extract a shortened version of an object, fit for logging.
 *
 * This function returns a copy of the original object in which all
 * long strings, Arrays, TypedArrays, ArrayBuffers are removed and
 * replaced with placeholders. Use this function to sanitize objects
 * if you wish to log them or to keep them in memory.
 *
 * @param {*} obj The obj to shorten.
 * @return {*} array A shorter object, fit for logging.
 */
function summarizeObject(obj) {
  if (!obj) {
    return null;
  }
  if (typeof obj == "string") {
    if (obj.length > 1024) {
      return {"Long string": obj.length};
    }
    return obj;
  }
  if (typeof obj == "object") {
    if (Array.isArray(obj)) {
      if (obj.length > 32) {
        return {"Long array": obj.length};
      }
      return [summarizeObject(k) for (k of obj)];
    }
    if ("byteLength" in obj) {
      // Assume TypedArray or ArrayBuffer
      return {"Binary Data": obj.byteLength};
    }
    let result = {};
    for (let k of Object.keys(obj)) {
      result[k] = summarizeObject(obj[k]);
    }
    return result;
  }
  return obj;
}

// In order to expose Scheduler to the unfiltered Cu.import return value variant
// on B2G we need to save it to `this`.  This does not make it public;
// EXPORTED_SYMBOLS still controls that in all cases.
let Scheduler = this.Scheduler = {

  /**
   * |true| once we have sent at least one message to the worker.
   * This field is unaffected by resetting the worker.
   */
  launched: false,

  /**
   * |true| once shutdown has begun i.e. we should reject any
   * message, including resets.
   */
  shutdown: false,

  /**
   * A promise resolved once all currently pending operations are complete.
   *
   * This promise is never rejected and the result is always undefined.
   */
  queue: Promise.resolve(),

  /**
   * A promise resolved once all currently pending `kill` operations
   * are complete.
   *
   * This promise is never rejected and the result is always undefined.
   */
  _killQueue: Promise.resolve(),

  /**
   * Miscellaneous debugging information
   */
  Debugging: {
    /**
     * The latest message sent and still waiting for a reply.
     */
    latestSent: undefined,

    /**
     * The latest reply received, or null if we are waiting for a reply.
     */
    latestReceived: undefined,

    /**
     * Number of messages sent to the worker. This includes the
     * initial SET_DEBUG, if applicable.
     */
    messagesSent: 0,

    /**
     * Total number of messages ever queued, including the messages
     * sent.
     */
    messagesQueued: 0,

    /**
     * Number of messages received from the worker.
     */
    messagesReceived: 0,
  },

  /**
   * A timer used to automatically shut down the worker after some time.
   */
  resetTimer: null,

  /**
   * The worker to which to send requests.
   *
   * If the worker has never been created or has been reset, this is a
   * fresh worker, initialized with osfile_async_worker.js.
   *
   * @type {PromiseWorker}
   */
  get worker() {
    if (!this._worker) {
      // Either the worker has never been created or it has been
      // reset.  In either case, it is time to instantiate the worker.
      this._worker = new BasePromiseWorker("resource://gre/modules/osfile/osfile_async_worker.js");
      this._worker.log = LOG;
      this._worker.ExceptionHandlers["OS.File.Error"] = OSError.fromMsg;
    }
    return this._worker;
  },

  _worker: null,

  /**
   * Prepare to kill the OS.File worker after a few seconds.
   */
  restartTimer: function(arg) {
    let delay;
    try {
      delay = Services.prefs.getIntPref("osfile.reset_worker_delay");
    } catch(e) {
      // Don't auto-shutdown if we don't have a delay preference set.
      return;
    }

    if (this.resetTimer) {
      clearTimeout(this.resetTimer);
    }
    this.resetTimer = setTimeout(
      () => Scheduler.kill({reset: true, shutdown: false}),
      delay
    );
  },

  /**
   * Shutdown OS.File.
   *
   * @param {*} options
   * - {boolean} shutdown If |true|, reject any further request. Otherwise,
   *   further requests will resurrect the worker.
   * - {boolean} reset If |true|, instruct the worker to shutdown if this
   *   would not cause leaks. Otherwise, assume that the worker will be shutdown
   *   through some other mean.
   */
  kill: function({shutdown, reset}) {
    // Grab the kill queue to make sure that we
    // cannot be interrupted by another call to `kill`.
    let killQueue = this._killQueue;

    // Deactivate the queue, to ensure that no message is sent
    // to an obsolete worker (we reactivate it in the `finally`).
    // This needs to be done right now so that we maintain relative
    // ordering with calls to post(), etc.
    let deferred = Promise.defer();
    let savedQueue = this.queue;
    this.queue = deferred.promise;

    return this._killQueue = Task.spawn(function*() {

      yield killQueue;
      // From this point, and until the end of the Task, we are the
      // only call to `kill`, regardless of any `yield`.

      yield savedQueue;

      try {
        // Enter critical section: no yield in this block
        // (we want to make sure that we remain the only
        // request in the queue).

        if (!this.launched || this.shutdown || !this._worker) {
          // Nothing to kill
          this.shutdown = this.shutdown || shutdown;
          this._worker = null;
          return null;
        }

        // Exit critical section

        let message = ["Meta_shutdown", [reset]];

        Scheduler.latestReceived = [];
        Scheduler.latestSent = [Date.now(),
          Task.Debugging.generateReadableStack(new Error().stack),
          ...message];

        // Wait for result
        let resources;
        try {
          resources = yield this._worker.post(...message);

          Scheduler.latestReceived = [Date.now(), message];
        } catch (ex) {
          LOG("Could not dispatch Meta_reset", ex);
          // It's most likely a programmer error, but we'll assume that
          // the worker has been shutdown, as it's less risky than the
          // opposite stance.
          resources = {openedFiles: [], openedDirectoryIterators: [], killed: true};

          Scheduler.latestReceived = [Date.now(), message, ex];
        }

        let {openedFiles, openedDirectoryIterators, killed} = resources;
        if (!reset
          && (openedFiles && openedFiles.length
            || ( openedDirectoryIterators && openedDirectoryIterators.length))) {
          // The worker still holds resources. Report them.

          let msg = "";
          if (openedFiles.length > 0) {
            msg += "The following files are still open:\n" +
              openedFiles.join("\n");
          }
          if (openedDirectoryIterators.length > 0) {
            msg += "The following directory iterators are still open:\n" +
              openedDirectoryIterators.join("\n");
          }

          LOG("WARNING: File descriptors leaks detected.\n" + msg);
        }

        // Make sure that we do not leave an invalid |worker| around.
        if (killed || shutdown) {
          this._worker = null;
        }

        this.shutdown = shutdown;

        return resources;

      } finally {
        // Resume accepting messages. If we have set |shutdown| to |true|,
        // any pending/future request will be rejected. Otherwise, any
        // pending/future request will spawn a new worker if necessary.
        deferred.resolve();
      }

    }.bind(this));
  },

  /**
   * Push a task at the end of the queue.
   *
   * @param {function} code A function returning a Promise.
   * This function will be executed once all the previously
   * pushed tasks have completed.
   * @return {Promise} A promise with the same behavior as
   * the promise returned by |code|.
   */
  push: function(code) {
    let promise = this.queue.then(code);
    // By definition, |this.queue| can never reject.
    this.queue = promise.then(null, () => undefined);
    // Fork |promise| to ensure that uncaught errors are reported
    return promise.then(null, null);
  },

  /**
   * Post a message to the worker thread.
   *
   * @param {string} method The name of the method to call.
   * @param {...} args The arguments to pass to the method. These arguments
   * must be clonable.
   * @return {Promise} A promise conveying the result/error caused by
   * calling |method| with arguments |args|.
   */
  post: function post(method, args = undefined, closure = undefined) {
    if (this.shutdown) {
      LOG("OS.File is not available anymore. The following request has been rejected.",
        method, args);
      return Promise.reject(new Error("OS.File has been shut down. Rejecting post to " + method));
    }
    let firstLaunch = !this.launched;
    this.launched = true;

    if (firstLaunch && SharedAll.Config.DEBUG) {
      // If we have delayed sending SET_DEBUG, do it now.
      this.worker.post("SET_DEBUG", [true]);
      Scheduler.Debugging.messagesSent++;
    }

    Scheduler.Debugging.messagesQueued++;
    return this.push(Task.async(function*() {
      if (this.shutdown) {
	LOG("OS.File is not available anymore. The following request has been rejected.",
	  method, args);
	throw new Error("OS.File has been shut down. Rejecting request to " + method);
      }

      // Update debugging information. As |args| may be quite
      // expensive, we only keep a shortened version of it.
      Scheduler.Debugging.latestReceived = null;
      Scheduler.Debugging.latestSent = [Date.now(), method, summarizeObject(args)];

      // Don't kill the worker just yet
      Scheduler.restartTimer();


      let reply;
      try {
        try {
          Scheduler.Debugging.messagesSent++;
          Scheduler.Debugging.latestSent = Scheduler.Debugging.latestSent.slice(0, 2);
          reply = yield this.worker.post(method, args, closure);
          Scheduler.Debugging.latestReceived = [Date.now(), summarizeObject(reply)];
          return reply;
        } finally {
          Scheduler.Debugging.messagesReceived++;
        }
      } catch (error) {
        Scheduler.Debugging.latestReceived = [Date.now(), error.message, error.fileName, error.lineNumber];
        throw error;
      } finally {
        if (firstLaunch) {
          Scheduler._updateTelemetry();
        }
        Scheduler.restartTimer();
      }
    }.bind(this)));
  },

  /**
   * Post Telemetry statistics.
   *
   * This is only useful on first launch.
   */
  _updateTelemetry: function() {
    let worker = this.worker;
    let workerTimeStamps = worker.workerTimeStamps;
    if (!workerTimeStamps) {
      // If the first call to OS.File results in an uncaught errors,
      // the timestamps are absent. As this case is a developer error,
      // let's not waste time attempting to extract telemetry from it.
      return;
    }
    let HISTOGRAM_LAUNCH = Services.telemetry.getHistogramById("OSFILE_WORKER_LAUNCH_MS");
    HISTOGRAM_LAUNCH.add(worker.workerTimeStamps.entered - worker.launchTimeStamp);

    let HISTOGRAM_READY = Services.telemetry.getHistogramById("OSFILE_WORKER_READY_MS");
    HISTOGRAM_READY.add(worker.workerTimeStamps.loaded - worker.launchTimeStamp);
  }
};
