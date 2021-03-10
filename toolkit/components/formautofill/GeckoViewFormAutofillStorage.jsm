/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

"use strict";

// We expose a singleton from this module. Some tests may import the
// constructor via a backstage pass.
this.EXPORTED_SYMBOLS = ["formAutofillStorage"];

const { XPCOMUtils } = ChromeUtils.import(
  "resource://gre/modules/XPCOMUtils.jsm"
);

const {
  FormAutofillStorageBase,
  CreditCardsBase,
  AddressesBase,
} = ChromeUtils.import("resource://gre/modules/FormAutofillStorageBase.jsm");

XPCOMUtils.defineLazyModuleGetters(this, {
  GeckoViewAutocomplete: "resource://gre/modules/GeckoViewAutocomplete.jsm",
  CreditCard: "resource://gre/modules/GeckoViewAutocomplete.jsm",
  Address: "resource://gre/modules/GeckoViewAutocomplete.jsm",
  JSONFile: "resource://gre/modules/JSONFile.jsm",
  FormAutofill: "resource://gre/modules/FormAutofill.jsm",
});

class GVStorage extends JSONFile {
  constructor() {
    super({ path: null });
  }

  async load() {
    const creditCards = await GeckoViewAutocomplete.fetchCreditCards().then(
      results => results.map(r => CreditCard.parse(r).toGecko())
    );
    const addresses = await GeckoViewAutocomplete.fetchAddresses().then(
      results => results.map(r => Address.parse(r).toGecko())
    );
    super.data = { creditCards, addresses };
  }

  ensureDataReady() {
    if (this.dataReady) {
      return;
    }
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _save() {
    // TODO
  }
}

class Addresses extends AddressesBase {
  constructor(store) {
    super(store);
  }

  async computeFields(address) {
    return super.computeFields(address);
  }

  async mergeIfPossible(guid, address, strict) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  // Override AutofillRecords methods.

  _initialize() {
    this.log.debug(`autofill storage _initialize ok`);
    this._initializePromise = Promise.resolve();
  }

  /**
   * Gets the data of this collection.
   *
   * @returns {array}
   *          The data object.
   */
  _getData() {
    this.log.debug(
      `autofill get ${this._collectionName} _data: ${JSON.stringify(
        this._store.data[this._collectionName]
      )}`
    );

    return super._getData();
  }

  /**
   * Adds a new record.
   *
   * @param {Object} record
   *        The new record for saving.
   * @param {boolean} [options.sourceSync = false]
   *        Did sync generate this addition?
   * @returns {Promise<string>}
   *          The GUID of the newly added item..
   */
  async add(record, { sourceSync = false } = {}) {
    return super.add(record, { sourceSync });
  }

  async _saveRecord(record, { sourceSync = false } = {}) {
    this.log.debug(
      `GeckoViewFormAutofillStorage _saveRecord ${JSON.stringify(record)}`
    );
    GeckoViewAutocomplete.onAddressSave(Address.fromGecko(record));
  }

  /**
   * Update the specified record.
   *
   * @param  {string} guid
   *         Indicates which record to update.
   * @param  {Object} record
   *         The new record used to overwrite the old one.
   * @param  {Promise<boolean>} [preserveOldProperties = false]
   *         Preserve old record's properties if they don't exist in new record.
   */
  async update(guid, record, preserveOldProperties = false) {
    return super.update(guid, record, preserveOldProperties);
  }

  /**
   * Notifies the storage of the use of the specified record, so we can update
   * the metadata accordingly. This does not bump the Sync change counter, since
   * we don't sync `timesUsed` or `timeLastUsed`.
   *
   * @param  {string} guid
   *         Indicates which record to be notified.
   */
  notifyUsed(guid) {
    return super.notifyUsed(guid);
  }

  /**
   * Removes the specified record. No error occurs if the record isn't found.
   *
   * @param  {string} guid
   *         Indicates which record to remove.
   * @param  {boolean} [options.sourceSync = false]
   *         Did Sync generate this removal?
   */
  remove(guid, { sourceSync = false } = {}) {
    return super.remove(guid, { sourceSync });
  }

  /**
   * Returns the record with the specified GUID.
   *
   * @param   {string} guid
   *          Indicates which record to retrieve.
   * @param   {boolean} [options.rawData = false]
   *          Returns a raw record without modifications and the computed fields
   *          (this includes private fields)
   * @returns {Promise<Object>}
   *          A clone of the record.
   */
  async get(guid, { rawData = false } = {}) {
    return super.get(guid, { rawData });
  }

  /**
   * Returns all records.
   *
   * @param   {boolean} [options.rawData = false]
   *          Returns raw records without modifications and the computed fields.
   * @param   {boolean} [options.includeDeleted = false]
   *          Also return any tombstone records.
   * @returns {Promise<Array.<Object>>}
   *          An array containing clones of all records.
   */
  async getAll({ rawData = false, includeDeleted = false } = {}) {
    return super.getAll({ rawData, includeDeleted });
  }

  /**
   * Return all saved field names in the collection. This method
   * has to be sync because its caller _updateSavedFieldNames() needs
   * to dispatch content message synchronously.
   *
   * @returns {Set} Set containing saved field names.
   */
  async getSavedFieldNames() {
    return super.getSavedFieldNames();
  }

  _maybeStoreLastSyncedField(record, field, lastSyncedValue) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _mergeSyncedRecords(strippedLocalRecord, remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _replaceRecordAt(
    index,
    remoteRecord,
    { keepSyncMetadata = false } = {}
  ) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _forkLocalRecord(strippedLocalRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async reconcile(remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _removeSyncedRecord(guid) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  pullSyncChanges() {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  pushSyncChanges(changes) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  resetSync() {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  changeGUID(oldID, newID) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _getSyncMetaData(record, forceCreate = false) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async findDuplicateGUID(remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _migrateRecord(record, index) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async mergeToStorage(targetRecord, strict = false) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  removeAll({ sourceSync = false } = {}) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _computeMigratedRecord(record) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _stripComputedFields(record) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }
}

class CreditCards extends CreditCardsBase {
  constructor(store) {
    super(store);
  }

  async computeFields(creditCard) {
    super.computeFields(creditCard);
  }

  async _encryptNumber(creditCard) {
    // Don't encrypt or obfuscate for GV, since we don't store or show
    // the number. The API has to always provide the original number.
  }

  async mergeIfPossible(guid, creditCard) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  // Override AutofillRecords methods.

  _initialize() {
    this.log.debug(`autofill storage _initialize ok`);
    this._initializePromise = Promise.resolve();
  }

  /**
   * Gets the data of this collection.
   *
   * @returns {array}
   *          The data object.
   */
  _getData() {
    this.log.debug(
      `autofill get ${this._collectionName} _data: ${JSON.stringify(
        this._store.data[this._collectionName]
      )}`
    );

    return super._getData();
  }

  /**
   * Adds a new record.
   *
   * @param {Object} record
   *        The new record for saving.
   * @param {boolean} [options.sourceSync = false]
   *        Did sync generate this addition?
   * @returns {Promise<string>}
   *          The GUID of the newly added item..
   */
  async add(record, { sourceSync = false } = {}) {
    return super.add(record, { sourceSync });
  }

  async _saveRecord(record, { sourceSync = false } = {}) {
    this.log.debug(
      `GeckoViewFormAutofillStorage _saveRecord ${JSON.stringify(record)}`
    );
    GeckoViewAutocomplete.onCreditCardSave(CreditCard.fromGecko(record));
  }

  /**
   * Update the specified record.
   *
   * @param  {string} guid
   *         Indicates which record to update.
   * @param  {Object} record
   *         The new record used to overwrite the old one.
   * @param  {Promise<boolean>} [preserveOldProperties = false]
   *         Preserve old record's properties if they don't exist in new record.
   */
  async update(guid, record, preserveOldProperties = false) {
    return super.update(guid, record, preserveOldProperties);
  }

  /**
   * Notifies the storage of the use of the specified record, so we can update
   * the metadata accordingly. This does not bump the Sync change counter, since
   * we don't sync `timesUsed` or `timeLastUsed`.
   *
   * @param  {string} guid
   *         Indicates which record to be notified.
   */
  notifyUsed(guid) {
    return super.notifyUsed(guid);
  }

  /**
   * Removes the specified record. No error occurs if the record isn't found.
   *
   * @param  {string} guid
   *         Indicates which record to remove.
   * @param  {boolean} [options.sourceSync = false]
   *         Did Sync generate this removal?
   */
  remove(guid, { sourceSync = false } = {}) {
    return super.remove(guid, { sourceSync });
  }

  /**
   * Returns the record with the specified GUID.
   *
   * @param   {string} guid
   *          Indicates which record to retrieve.
   * @param   {boolean} [options.rawData = false]
   *          Returns a raw record without modifications and the computed fields
   *          (this includes private fields)
   * @returns {Promise<Object>}
   *          A clone of the record.
   */
  async get(guid, { rawData = false } = {}) {
    return super.get(guid, { rawData });
  }

  /**
   * Returns all records.
   *
   * @param   {boolean} [options.rawData = false]
   *          Returns raw records without modifications and the computed fields.
   * @param   {boolean} [options.includeDeleted = false]
   *          Also return any tombstone records.
   * @returns {Promise<Array.<Object>>}
   *          An array containing clones of all records.
   */
  async getAll({ rawData = false, includeDeleted = false } = {}) {
    return super.getAll({ rawData, includeDeleted });
  }

  /**
   * Return all saved field names in the collection. This method
   * has to be sync because its caller _updateSavedFieldNames() needs
   * to dispatch content message synchronously.
   *
   * @returns {Set} Set containing saved field names.
   */
  async getSavedFieldNames() {
    return super.getSavedFieldNames();
  }

  _maybeStoreLastSyncedField(record, field, lastSyncedValue) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _mergeSyncedRecords(strippedLocalRecord, remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _replaceRecordAt(
    index,
    remoteRecord,
    { keepSyncMetadata = false } = {}
  ) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _forkLocalRecord(strippedLocalRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async reconcile(remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _removeSyncedRecord(guid) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  pullSyncChanges() {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  pushSyncChanges(changes) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  resetSync() {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  changeGUID(oldID, newID) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  _getSyncMetaData(record, forceCreate = false) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async findDuplicateGUID(remoteRecord) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _migrateRecord(record, index) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async mergeToStorage(targetRecord, strict = false) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  removeAll({ sourceSync = false } = {}) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _computeMigratedRecord(record) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }

  async _stripComputedFields(record) {
    throw Components.Exception("", Cr.NS_ERROR_NOT_IMPLEMENTED);
  }
}

class FormAutofillStorage extends FormAutofillStorageBase {
  constructor() {
    super(null);
  }

  getAddresses() {
    if (!this._addresses) {
      this._store.ensureDataReady();
      this._addresses = new Addresses(this._store);
    }
    return this._addresses;
  }

  getCreditCards() {
    if (!this._creditCards) {
      this._store.ensureDataReady();
      this._creditCards = new CreditCards(this._store);
    }
    return this._creditCards;
  }

  /**
   * Loads the profile data from file to memory.
   *
   * @returns {Promise}
   * @resolves When the operation finished successfully.
   * @rejects  JavaScript exception.
   */
  initialize() {
    if (!this._initializePromise) {
      this._store = new GVStorage();
      this._initializePromise = this._store.load().then(() => {
        let initializeAutofillRecords = [this.addresses.initialize()];
        if (FormAutofill.isAutofillCreditCardsAvailable) {
          initializeAutofillRecords.push(this.creditCards.initialize());
        } else {
          // Make creditCards records unavailable to other modules
          // because we never initialize it.
          Object.defineProperty(this, "creditCards", {
            get() {
              throw new Error(
                "CreditCards is not initialized. " +
                  "Please restart if you flip the pref manually."
              );
            },
          });
        }
        return Promise.all(initializeAutofillRecords);
      });
    }
    return this._initializePromise;
  }
}

// The singleton exposed by this module.
this.formAutofillStorage = new FormAutofillStorage();
