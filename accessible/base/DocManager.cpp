/* -*- Mode: C++; tab-width: 8; indent-tabs-mode: nil; c-basic-offset: 2 -*- */
/* vim: set ts=8 sts=2 et sw=2 tw=80: */
/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

#include "DocManager.h"

#include "ApplicationAccessible.h"
#include "ARIAMap.h"
#include "DocAccessible-inl.h"
#include "DocAccessibleChild.h"
#include "DocAccessibleParent.h"
#include "nsAccessibilityService.h"
#include "Platform.h"
#include "RootAccessibleWrap.h"
#include "xpcAccessibleDocument.h"

#ifdef A11Y_LOG
#include "Logging.h"
#endif

#include "mozilla/EventListenerManager.h"
#include "mozilla/dom/Event.h" // for nsIDOMEvent::InternalDOMEvent()
#include "nsCURILoader.h"
#include "nsDocShellLoadTypes.h"
#include "nsIChannel.h"
#include "nsIDOMDocument.h"
#include "nsIDOMWindow.h"
#include "nsIInterfaceRequestorUtils.h"
#include "nsIWebNavigation.h"
#include "nsServiceManagerUtils.h"
#include "nsIWebProgress.h"
#include "nsCoreUtils.h"
#include "nsXULAppAPI.h"
#include "mozilla/dom/TabChild.h"

using namespace mozilla;
using namespace mozilla::a11y;
using namespace mozilla::dom;

StaticAutoPtr<nsTArray<DocAccessibleParent*>> DocManager::sRemoteDocuments;

////////////////////////////////////////////////////////////////////////////////
// DocManager
////////////////////////////////////////////////////////////////////////////////

DocManager::DocManager()
  : mDocAccessibleCache(2), mXPCDocumentCache(0)
{
}

////////////////////////////////////////////////////////////////////////////////
// DocManager public

DocAccessible*
DocManager::GetDocAccessible(nsIDocument* aDocument)
{
  if (!aDocument)
    return nullptr;

  // Ensure CacheChildren is called before we query cache.
  ApplicationAcc()->EnsureChildren();

  DocAccessible* docAcc = GetExistingDocAccessible(aDocument);
  if (docAcc)
    return docAcc;

  return CreateDocOrRootAccessible(aDocument);
}

Accessible*
DocManager::FindAccessibleInCache(nsINode* aNode) const
{
  for (auto iter = mDocAccessibleCache.ConstIter(); !iter.Done(); iter.Next()) {
    DocAccessible* docAccessible = iter.UserData();
    NS_ASSERTION(docAccessible,
                 "No doc accessible for the object in doc accessible cache!");

    if (docAccessible) {
      Accessible* accessible = docAccessible->GetAccessible(aNode);
      if (accessible) {
        return accessible;
      }
    }
  }
  return nullptr;
}

void
DocManager::NotifyOfDocumentShutdown(DocAccessible* aDocument,
                                     nsIDocument* aDOMDocument)
{
  xpcAccessibleDocument* xpcDoc = mXPCDocumentCache.GetWeak(aDocument);
  if (xpcDoc) {
    xpcDoc->Shutdown();
    mXPCDocumentCache.Remove(aDocument);
  }

  mDocAccessibleCache.Remove(aDOMDocument);
  RemoveListeners(aDOMDocument);
}

xpcAccessibleDocument*
DocManager::GetXPCDocument(DocAccessible* aDocument)
{
  if (!aDocument)
    return nullptr;

  xpcAccessibleDocument* xpcDoc = mXPCDocumentCache.GetWeak(aDocument);
  if (!xpcDoc) {
    xpcDoc = new xpcAccessibleDocument(aDocument);
    mXPCDocumentCache.Put(aDocument, xpcDoc);
  }
  return xpcDoc;
}

#ifdef DEBUG
bool
DocManager::IsProcessingRefreshDriverNotification() const
{
  for (auto iter = mDocAccessibleCache.ConstIter(); !iter.Done(); iter.Next()) {
    DocAccessible* docAccessible = iter.UserData();
    NS_ASSERTION(docAccessible,
                 "No doc accessible for the object in doc accessible cache!");

    if (docAccessible && docAccessible->mNotificationController &&
        docAccessible->mNotificationController->IsUpdating()) {
      return true;
    }
  }
  return false;
}
#endif


////////////////////////////////////////////////////////////////////////////////
// DocManager protected

bool
DocManager::Init()
{
  nsCOMPtr<nsIWebProgress> progress =
    do_GetService(NS_DOCUMENTLOADER_SERVICE_CONTRACTID);

  if (!progress)
    return false;

  progress->AddProgressListener(static_cast<nsIWebProgressListener*>(this),
                                nsIWebProgress::NOTIFY_STATE_DOCUMENT);

  return true;
}

void
DocManager::Shutdown()
{
  nsCOMPtr<nsIWebProgress> progress =
    do_GetService(NS_DOCUMENTLOADER_SERVICE_CONTRACTID);

  if (progress)
    progress->RemoveProgressListener(static_cast<nsIWebProgressListener*>(this));

  ClearDocCache();
}

////////////////////////////////////////////////////////////////////////////////
// nsISupports

NS_IMPL_ISUPPORTS(DocManager,
                  nsIWebProgressListener,
                  nsIDOMEventListener,
                  nsISupportsWeakReference)

////////////////////////////////////////////////////////////////////////////////
// nsIWebProgressListener

NS_IMETHODIMP
DocManager::OnStateChange(nsIWebProgress* aWebProgress,
                          nsIRequest* aRequest, uint32_t aStateFlags,
                          nsresult aStatus)
{
  NS_ASSERTION(aStateFlags & STATE_IS_DOCUMENT, "Other notifications excluded");

  if (nsAccessibilityService::IsShutdown() || !aWebProgress ||
      (aStateFlags & (STATE_START | STATE_STOP)) == 0)
    return NS_OK;

  nsCOMPtr<nsIDOMWindow> DOMWindow;
  aWebProgress->GetDOMWindow(getter_AddRefs(DOMWindow));
  NS_ENSURE_STATE(DOMWindow);

  nsCOMPtr<nsIDOMDocument> DOMDocument;
  DOMWindow->GetDocument(getter_AddRefs(DOMDocument));
  NS_ENSURE_STATE(DOMDocument);

  nsCOMPtr<nsIDocument> document(do_QueryInterface(DOMDocument));

  // Document was loaded.
  if (aStateFlags & STATE_STOP) {
#ifdef A11Y_LOG
    if (logging::IsEnabled(logging::eDocLoad))
      logging::DocLoad("document loaded", aWebProgress, aRequest, aStateFlags);
#endif

    // Figure out an event type to notify the document has been loaded.
    uint32_t eventType = nsIAccessibleEvent::EVENT_DOCUMENT_LOAD_STOPPED;

    // Some XUL documents get start state and then stop state with failure
    // status when everything is ok. Fire document load complete event in this
    // case.
    if (NS_SUCCEEDED(aStatus) || !nsCoreUtils::IsContentDocument(document))
      eventType = nsIAccessibleEvent::EVENT_DOCUMENT_LOAD_COMPLETE;

    // If end consumer has been retargeted for loaded content then do not fire
    // any event because it means no new document has been loaded, for example,
    // it happens when user clicks on file link.
    if (aRequest) {
      uint32_t loadFlags = 0;
      aRequest->GetLoadFlags(&loadFlags);
      if (loadFlags & nsIChannel::LOAD_RETARGETED_DOCUMENT_URI)
        eventType = 0;
    }

    HandleDOMDocumentLoad(document, eventType);
    return NS_OK;
  }

  // Document loading was started.
#ifdef A11Y_LOG
  if (logging::IsEnabled(logging::eDocLoad))
    logging::DocLoad("start document loading", aWebProgress, aRequest, aStateFlags);
#endif

  DocAccessible* docAcc = GetExistingDocAccessible(document);
  if (!docAcc)
    return NS_OK;

  nsCOMPtr<nsIWebNavigation> webNav(do_GetInterface(DOMWindow));
  nsCOMPtr<nsIDocShell> docShell(do_QueryInterface(webNav));
  NS_ENSURE_STATE(docShell);

  bool isReloading = false;
  uint32_t loadType;
  docShell->GetLoadType(&loadType);
  if (loadType == LOAD_RELOAD_NORMAL ||
      loadType == LOAD_RELOAD_BYPASS_CACHE ||
      loadType == LOAD_RELOAD_BYPASS_PROXY ||
      loadType == LOAD_RELOAD_BYPASS_PROXY_AND_CACHE ||
      loadType == LOAD_RELOAD_ALLOW_MIXED_CONTENT) {
    isReloading = true;
  }

  docAcc->NotifyOfLoading(isReloading);
  return NS_OK;
}

NS_IMETHODIMP
DocManager::OnProgressChange(nsIWebProgress* aWebProgress,
                             nsIRequest* aRequest,
                             int32_t aCurSelfProgress,
                             int32_t aMaxSelfProgress,
                             int32_t aCurTotalProgress,
                             int32_t aMaxTotalProgress)
{
  NS_NOTREACHED("notification excluded in AddProgressListener(...)");
  return NS_OK;
}

NS_IMETHODIMP
DocManager::OnLocationChange(nsIWebProgress* aWebProgress,
                             nsIRequest* aRequest, nsIURI* aLocation,
                             uint32_t aFlags)
{
  NS_NOTREACHED("notification excluded in AddProgressListener(...)");
  return NS_OK;
}

NS_IMETHODIMP
DocManager::OnStatusChange(nsIWebProgress* aWebProgress,
                           nsIRequest* aRequest, nsresult aStatus,
                           const char16_t* aMessage)
{
  NS_NOTREACHED("notification excluded in AddProgressListener(...)");
  return NS_OK;
}

NS_IMETHODIMP
DocManager::OnSecurityChange(nsIWebProgress* aWebProgress,
                             nsIRequest* aRequest,
                             uint32_t aState)
{
  NS_NOTREACHED("notification excluded in AddProgressListener(...)");
  return NS_OK;
}

////////////////////////////////////////////////////////////////////////////////
// nsIDOMEventListener

NS_IMETHODIMP
DocManager::HandleEvent(nsIDOMEvent* aEvent)
{
  nsAutoString type;
  aEvent->GetType(type);

  nsCOMPtr<nsIDocument> document =
    do_QueryInterface(aEvent->InternalDOMEvent()->GetTarget());
  NS_ASSERTION(document, "pagehide or DOMContentLoaded for non document!");
  if (!document)
    return NS_OK;

  if (type.EqualsLiteral("pagehide")) {
    // 'pagehide' event is registered on every DOM document we create an
    // accessible for, process the event for the target. This document
    // accessible and all its sub document accessible are shutdown as result of
    // processing.

#ifdef A11Y_LOG
    if (logging::IsEnabled(logging::eDocDestroy))
      logging::DocDestroy("received 'pagehide' event", document);
#endif

    // Shutdown this one and sub document accessibles.

    // We're allowed to not remove listeners when accessible document is
    // shutdown since we don't keep strong reference on chrome event target and
    // listeners are removed automatically when chrome event target goes away.
    DocAccessible* docAccessible = GetExistingDocAccessible(document);
    if (docAccessible)
      docAccessible->Shutdown();

    return NS_OK;
  }

  // XXX: handle error pages loading separately since they get neither
  // webprogress notifications nor 'pageshow' event.
  if (type.EqualsLiteral("DOMContentLoaded") &&
      nsCoreUtils::IsErrorPage(document)) {
#ifdef A11Y_LOG
    if (logging::IsEnabled(logging::eDocLoad))
      logging::DocLoad("handled 'DOMContentLoaded' event", document);
#endif

    HandleDOMDocumentLoad(document,
                          nsIAccessibleEvent::EVENT_DOCUMENT_LOAD_COMPLETE);
  }

  return NS_OK;
}

////////////////////////////////////////////////////////////////////////////////
// DocManager private

void
DocManager::HandleDOMDocumentLoad(nsIDocument* aDocument,
                                  uint32_t aLoadEventType)
{
  // Document accessible can be created before we were notified the DOM document
  // was loaded completely. However if it's not created yet then create it.
  DocAccessible* docAcc = GetExistingDocAccessible(aDocument);
  if (!docAcc) {
    docAcc = CreateDocOrRootAccessible(aDocument);
    if (!docAcc)
      return;
  }

  docAcc->NotifyOfLoad(aLoadEventType);
}

void
DocManager::AddListeners(nsIDocument* aDocument,
                         bool aAddDOMContentLoadedListener)
{
  nsPIDOMWindow* window = aDocument->GetWindow();
  EventTarget* target = window->GetChromeEventHandler();
  EventListenerManager* elm = target->GetOrCreateListenerManager();
  elm->AddEventListenerByType(this, NS_LITERAL_STRING("pagehide"),
                              TrustedEventsAtCapture());

#ifdef A11Y_LOG
  if (logging::IsEnabled(logging::eDocCreate))
    logging::Text("added 'pagehide' listener");
#endif

  if (aAddDOMContentLoadedListener) {
    elm->AddEventListenerByType(this, NS_LITERAL_STRING("DOMContentLoaded"),
                                TrustedEventsAtCapture());
#ifdef A11Y_LOG
    if (logging::IsEnabled(logging::eDocCreate))
      logging::Text("added 'DOMContentLoaded' listener");
#endif
  }
}

void
DocManager::RemoveListeners(nsIDocument* aDocument)
{
  nsPIDOMWindow* window = aDocument->GetWindow();
  if (!window)
    return;

  EventTarget* target = window->GetChromeEventHandler();
  if (!target)
    return;

  EventListenerManager* elm = target->GetOrCreateListenerManager();
  elm->RemoveEventListenerByType(this, NS_LITERAL_STRING("pagehide"),
                                 TrustedEventsAtCapture());

  elm->RemoveEventListenerByType(this, NS_LITERAL_STRING("DOMContentLoaded"),
                                 TrustedEventsAtCapture());
}

DocAccessible*
DocManager::CreateDocOrRootAccessible(nsIDocument* aDocument)
{
  // Ignore hiding, resource documents and documents without docshell.
  if (!aDocument->IsVisibleConsideringAncestors() ||
      aDocument->IsResourceDoc() || !aDocument->IsActive())
    return nullptr;

  // Ignore documents without presshell and not having root frame.
  nsIPresShell* presShell = aDocument->GetShell();
  if (!presShell || presShell->IsDestroying())
    return nullptr;

  bool isRootDoc = nsCoreUtils::IsRootDocument(aDocument);

  DocAccessible* parentDocAcc = nullptr;
  if (!isRootDoc) {
    // XXXaaronl: ideally we would traverse the presshell chain. Since there's
    // no easy way to do that, we cheat and use the document hierarchy.
    parentDocAcc = GetDocAccessible(aDocument->GetParentDocument());
    NS_ASSERTION(parentDocAcc,
                 "Can't create an accessible for the document!");
    if (!parentDocAcc)
      return nullptr;
  }

  // We only create root accessibles for the true root, otherwise create a
  // doc accessible.
  nsIContent *rootElm = nsCoreUtils::GetRoleContent(aDocument);
  RefPtr<DocAccessible> docAcc = isRootDoc ?
    new RootAccessibleWrap(aDocument, rootElm, presShell) :
    new DocAccessibleWrap(aDocument, rootElm, presShell);

  // Cache the document accessible into document cache.
  mDocAccessibleCache.Put(aDocument, docAcc);

  // Initialize the document accessible.
  docAcc->Init();
  docAcc->SetRoleMapEntry(aria::GetRoleMap(aDocument));

  // Bind the document to the tree.
  if (isRootDoc) {
    if (!ApplicationAcc()->AppendChild(docAcc)) {
      docAcc->Shutdown();
      return nullptr;
    }

    // Fire reorder event to notify new accessible document has been attached to
    // the tree. The reorder event is delivered after the document tree is
    // constructed because event processing and tree construction are done by
    // the same document.
    // Note: don't use AccReorderEvent to avoid coalsecense and special reorder
    // events processing.
    docAcc->FireDelayedEvent(nsIAccessibleEvent::EVENT_REORDER,
                             ApplicationAcc());

    if (IPCAccessibilityActive()) {
      nsIDocShell* docShell = aDocument->GetDocShell();
      if (docShell) {
        nsCOMPtr<nsITabChild> tabChild = do_GetInterface(docShell);

        // XXX We may need to handle the case that we don't have a tab child
        // differently.  It may be that this will cause us to fail to notify
        // the parent process about important accessible documents.
        if (tabChild) {
          DocAccessibleChild* ipcDoc = new DocAccessibleChild(docAcc);
          docAcc->SetIPCDoc(ipcDoc);
          static_cast<TabChild*>(tabChild.get())->
            SendPDocAccessibleConstructor(ipcDoc, nullptr, 0);
        }
      }
    }
  } else {
    parentDocAcc->BindChildDocument(docAcc);
  }

#ifdef A11Y_LOG
  if (logging::IsEnabled(logging::eDocCreate)) {
    logging::DocCreate("document creation finished", aDocument);
    logging::Stack();
  }
#endif

  AddListeners(aDocument, isRootDoc);
  return docAcc;
}

////////////////////////////////////////////////////////////////////////////////
// DocManager static

void
DocManager::ClearDocCache()
{
  // This unusual do-one-element-per-iterator approach is required because each
  // DocAccessible is removed elsewhere upon its Shutdown() method being
  // called, which invalidates the existing iterator.
  while (mDocAccessibleCache.Count() > 0) {
    auto iter = mDocAccessibleCache.Iter();
    MOZ_ASSERT(!iter.Done());
    DocAccessible* docAcc = iter.UserData();
    NS_ASSERTION(docAcc,
                 "No doc accessible for the object in doc accessible cache!");
    if (docAcc) {
      docAcc->Shutdown();
    }
  }
}

void
DocManager::RemoteDocAdded(DocAccessibleParent* aDoc)
{
  if (!sRemoteDocuments) {
    sRemoteDocuments = new nsTArray<DocAccessibleParent*>;
    ClearOnShutdown(&sRemoteDocuments);
  }

  MOZ_ASSERT(!sRemoteDocuments->Contains(aDoc),
      "How did we already have the doc!");
  sRemoteDocuments->AppendElement(aDoc);
  ProxyCreated(aDoc, Interfaces::DOCUMENT | Interfaces::HYPERTEXT);
}
