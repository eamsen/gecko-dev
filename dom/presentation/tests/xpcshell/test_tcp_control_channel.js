/* -*- indent-tabs-mode: nil; js-indent-level: 2 -*- */
/* Any copyright is dedicated to the Public Domain.
 * http://creativecommons.org/publicdomain/zero/1.0/ */

'use strict';

const { classes: Cc, interfaces: Ci, utils: Cu, results: Cr } = Components;

Cu.import('resource://gre/modules/XPCOMUtils.jsm');
Cu.import('resource://gre/modules/Services.jsm');

var tps;

// Call |run_next_test| if all functions in |names| are called
function makeJointSuccess(names) {
  let funcs = {}, successCount = 0;
  names.forEach(function(name) {
    funcs[name] = function() {
      do_print('got expected: ' + name);
      if (++successCount === names.length)
        run_next_test();
    };
  });
  return funcs;
}

function TestDescription(aType, aTcpAddress, aTcpPort) {
  this.type = aType;
  this.tcpAddress = Cc["@mozilla.org/array;1"]
                      .createInstance(Ci.nsIMutableArray);
  for (let address of aTcpAddress) {
    let wrapper = Cc["@mozilla.org/supports-cstring;1"]
                    .createInstance(Ci.nsISupportsCString);
    wrapper.data = address;
    this.tcpAddress.appendElement(wrapper, false);
  }
  this.tcpPort = aTcpPort;
}

TestDescription.prototype = {
  QueryInterface: XPCOMUtils.generateQI([Ci.nsIPresentationChannelDescription]),
}

const CONTROLLER_CONTROL_CHANNEL_PORT = 36777;
const PRESENTER_CONTROL_CHANNEL_PORT = 36888;

// presenter's presentation channel description
const OFFER_ADDRESS = '192.168.123.123';
const OFFER_PORT = 123;

// controller's presentation channel description
const ANSWER_ADDRESS = '192.168.321.321';
const ANSWER_PORT = 321;

function loopOfferAnser() {
  tps = Cc["@mozilla.org/presentation-device/tcp-presentation-server;1"]
        .createInstance(Ci.nsITCPPresentationServer);
  tps.id = 'controllerID';
  tps.startService(PRESENTER_CONTROL_CHANNEL_PORT);

  testPresentationServer();
}


function testPresentationServer() {
  let yayFuncs = makeJointSuccess(['controllerControlChannelClose',
                                   'presenterControlChannelClose']);
  let controllerControlChannel;

  tps.listener = {

    onSessionRequest: function(deviceInfo, url, presentationId, controlChannel) {
      controllerControlChannel = controlChannel;
      Assert.equal(deviceInfo.id, tps.id, 'expected device id');
      Assert.equal(deviceInfo.address, '127.0.0.1', 'expected device address');
      Assert.equal(url, 'http://example.com', 'expected url');
      Assert.equal(presentationId, 'testPresentationId', 'expected presentation id');

      controllerControlChannel.listener = {
        status: 'created',
        onOffer: function(aOffer) {
          Assert.equal(this.status, 'opened', '1. controllerControlChannel: get offer, send answer');
          this.status = 'onOffer';

          let offer = aOffer.QueryInterface(Ci.nsIPresentationChannelDescription);
          Assert.strictEqual(offer.tcpAddress.queryElementAt(0,Ci.nsISupportsCString).data,
                             OFFER_ADDRESS,
                             'expected offer address array');
          Assert.equal(offer.tcpPort, OFFER_PORT, 'expected offer port');
          try {
            let tcpType = Ci.nsIPresentationChannelDescription.TYPE_TCP;
            let answer = new TestDescription(tcpType, [ANSWER_ADDRESS], ANSWER_PORT);
            controllerControlChannel.sendAnswer(answer);
          } catch (e) {
            Assert.ok(false, 'sending answer fails' + e);
          }
        },
        onAnswer: function(aAnswer) {
          Assert.ok(false, 'get answer');
        },
        notifyOpened: function() {
          Assert.equal(this.status, 'created', '0. controllerControlChannel: opened');
          this.status = 'opened';
        },
        notifyClosed: function(aReason) {
          Assert.equal(this.status, 'onOffer', '3. controllerControlChannel: closed');
          Assert.equal(aReason, Cr.NS_OK, 'presenterControlChannel notify closed NS_OK');
          this.status = 'closed';
          yayFuncs.controllerControlChannelClose();
        },
        QueryInterface: XPCOMUtils.generateQI([Ci.nsIPresentationControlChannelListener]),
      };
    },

    QueryInterface: XPCOMUtils.generateQI([Ci.nsITCPPresentationServerListener]),
  };

  let presenterDeviceInfo = {
    id: 'presentatorID',
    address: '127.0.0.1',
    port: PRESENTER_CONTROL_CHANNEL_PORT,
    QueryInterface: XPCOMUtils.generateQI([Ci.nsITCPDeviceInfo]),
  };

  let presenterControlChannel = tps.requestSession(presenterDeviceInfo,
                                                   'http://example.com',
                                                   'testPresentationId');

  presenterControlChannel.listener = {
    status: 'created',
    onOffer: function(offer) {
      Assert.ok(false, 'get offer');
    },
    onAnswer: function(aAnswer) {
      Assert.equal(this.status, 'opened', '2. presenterControlChannel: get answer, close channel');

      let answer = aAnswer.QueryInterface(Ci.nsIPresentationChannelDescription);
      Assert.strictEqual(answer.tcpAddress.queryElementAt(0,Ci.nsISupportsCString).data,
                         ANSWER_ADDRESS,
                         'expected answer address array');
      Assert.equal(answer.tcpPort, ANSWER_PORT, 'expected answer port');

      presenterControlChannel.close(Cr.NS_OK);
    },
    notifyOpened: function() {
      Assert.equal(this.status, 'created', '0. presenterControlChannel: opened, send offer');
      this.status = 'opened';
      try {
        let tcpType = Ci.nsIPresentationChannelDescription.TYPE_TCP;
        let offer = new TestDescription(tcpType, [OFFER_ADDRESS], OFFER_PORT)
        presenterControlChannel.sendOffer(offer);
      } catch (e) {
        Assert.ok(false, 'sending offer fails:' + e);
      }
    },
    notifyClosed: function(aReason) {
      this.status = 'closed';
      Assert.equal(aReason, Cr.NS_OK, '3. presenterControlChannel notify closed NS_OK');
      yayFuncs.presenterControlChannelClose();
    },
    QueryInterface: XPCOMUtils.generateQI([Ci.nsIPresentationControlChannelListener]),
  };
}

function setOffline() {
  let expectedReason;
  tps.listener = {
    onClose: function(aReason) {
      Assert.equal(aReason, Cr.NS_ERROR_ABORT, 'TCPPresentationServer close as expected');
      Services.io.offline = false;
      run_next_test();
    },
  }

  // Let the server socket be closed non-manually
  Services.io.offline = true;
}

function oneMoreLoop() {
  try {
    tps.startService(PRESENTER_CONTROL_CHANNEL_PORT);
    testPresentationServer();
  } catch (e) {
    Assert.ok(false, 'TCP presentation init fail:' + e);
    run_next_test();
  }
}


function shutdown()
{
  tps.listener = {
    onClose: function(aReason) {
      Assert.equal(aReason, Cr.NS_OK, 'TCPPresentationServer close success');
      run_next_test();
    },
  }
  tps.close();
}

add_test(loopOfferAnser);
add_test(setOffline);
add_test(oneMoreLoop);
add_test(shutdown);

function run_test() {
  Services.prefs.setBoolPref("dom.presentation.tcp_server.debug", true);

  do_register_cleanup(() => {
    Services.prefs.clearUserPref("dom.presentation.tcp_server.debug");
  });

  run_next_test();
}
