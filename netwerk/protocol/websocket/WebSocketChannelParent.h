/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set sw=2 ts=8 et tw=80 : */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#ifndef mozilla_net_WebSocketChannelParent_h
#define mozilla_net_WebSocketChannelParent_h

#include "mozilla/net/PWebSocketParent.h"
#include "mozilla/net/NeckoParent.h"
#include "nsIInterfaceRequestor.h"
#include "nsIWebSocketListener.h"
#include "nsIWebSocketChannel.h"
#include "nsILoadContext.h"
#include "nsCOMPtr.h"
#include "nsString.h"
#include "OfflineObserver.h"

class nsIAuthPromptProvider;

namespace mozilla {
namespace net {

class WebSocketChannelParent : public PWebSocketParent,
                               public nsIWebSocketListener,
                               public DisconnectableParent,
                               public nsIInterfaceRequestor
{
  ~WebSocketChannelParent();
 public:
  NS_DECL_THREADSAFE_ISUPPORTS
  NS_DECL_NSIWEBSOCKETLISTENER
  NS_DECL_NSIINTERFACEREQUESTOR

  WebSocketChannelParent(nsIAuthPromptProvider* aAuthProvider,
                         nsILoadContext* aLoadContext,
                         PBOverrideStatus aOverrideStatus);

 private:
  bool RecvAsyncOpen(const URIParams& aURI,
                     const nsCString& aOrigin,
                     const nsCString& aProtocol,
                     const bool& aSecure,
                     const uint32_t& aPingInterval,
                     const bool& aClientSetPingInterval,
                     const uint32_t& aPingTimeout,
                     const bool& aClientSetPingTimeout,
                     const OptionalLoadInfoArgs& aLoadInfoArgs) override;
  bool RecvClose(const uint16_t & code, const nsCString & reason) override;
  bool RecvSendMsg(const nsCString& aMsg) override;
  bool RecvSendBinaryMsg(const nsCString& aMsg) override;
  bool RecvSendBinaryStream(const InputStreamParams& aStream,
                            const uint32_t& aLength) override;
  bool RecvDeleteSelf() override;

  void ActorDestroy(ActorDestroyReason why) override;

  void OfflineDisconnect() override;
  uint32_t GetAppId() override;
  RefPtr<OfflineObserver> mObserver;

  nsCOMPtr<nsIAuthPromptProvider> mAuthProvider;
  nsCOMPtr<nsIWebSocketChannel> mChannel;
  nsCOMPtr<nsILoadContext> mLoadContext;
  bool mIPCOpen;

};

} // namespace net
} // namespace mozilla

#endif // mozilla_net_WebSocketChannelParent_h
