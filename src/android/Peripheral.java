// (c) 2104 Don Coleman
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.megster.cordova.ble.central;

import android.app.Activity;

import android.bluetooth.*;
import android.os.Build;
import android.util.Base64;
import org.apache.cordova.CallbackContext;
import org.apache.cordova.LOG;
import org.apache.cordova.PluginResult;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.os.Handler;
                         import android.os.Looper;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Peripheral wraps the BluetoothDevice and provides methods to convert to JSON.
 */
public class Peripheral extends BluetoothGattCallback {

    // 0x2902 org.bluetooth.descriptor.gatt.client_characteristic_configuration.xml
    //public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB");
    public final static UUID CLIENT_CHARACTERISTIC_CONFIGURATION_UUID = UUIDHelper.uuidFromString("2902");
    private static final String TAG = "Peripheral";

    private BluetoothDevice device;
    private byte[] advertisingData;
    private int advertisingRSSI;
    private boolean connected = false;
    private boolean connecting = false;
    private ConcurrentLinkedQueue<BLECommand> commandQueue = new ConcurrentLinkedQueue<BLECommand>();
    private boolean bleProcessing;

    BluetoothGatt gatt;

    private CallbackContext connectCallback;
    private CallbackContext readCallback;
    private CallbackContext writeCallback;

    private Map<String, CallbackContext> notificationCallbacks = new HashMap<String, CallbackContext>();

    private Runnable timeOutCallback;
    private Runnable discoveringTimeoutCallback;
    private Runnable readingRssiCallback;
    private Runnable readingCallback;
    private Runnable writeToDescriptorCallback;
    private Runnable writingCallback;
    private boolean discovering;
    private boolean isSuccessWritingToDescriptor;
    private boolean isSuccessReadingCommand;
    private boolean isSuccessReadingRssiCommand;
    private boolean isSuccessWriteCommand;

    public Peripheral(BluetoothDevice device, int advertisingRSSI, byte[] scanRecord) {

        this.device = device;
        this.advertisingRSSI = advertisingRSSI;
        this.advertisingData = scanRecord;

    }

    public void connect(CallbackContext callbackContext, Activity activity) {
        BluetoothDevice device = getDevice();
        connecting = true;

        LOG.d("TEST", "Connect to device " + device);
        connectCallback = callbackContext;
        if (Build.VERSION.SDK_INT < 23) {
            gatt = device.connectGatt(activity, false, this);
        } else {
            gatt = device.connectGatt(activity, false, this, BluetoothDevice.TRANSPORT_LE);
        }

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        callbackContext.sendPluginResult(result);

        if (timeOutCallback != null) {
          LOG.d("TEST", "clear timeOutCallback");
          TimeOutHelper.clearTimeOut(timeOutCallback);
        }

        timeOutCallback = new Runnable() {
           @Override
           public void run() {
              LOG.d("TEST", "Inside TimeOutHelper");
               if (!isConnected()) {
                  LOG.d("TEST", "Device is not connected, so disconnect it");
                     disconnectAfterTimeout();
                   }
                   else {
                      LOG.d("TEST", "Device is connected");
                     }
                 }
          };

        TimeOutHelper.setTimeout(timeOutCallback, 15000);
    }

    private void disconnectAfterTimeout() {
      LOG.d("TEST", "disconnectAfterTimeout -> disconnecting");
      if (connectCallback != null) {
          LOG.d("TEST", "disconnectAfterTimeout -> execute callback");
          connectCallback.error(this.asJSONObject("Peripheral Disconnected after timeout"));
      }
      disconnect();
      LOG.d("TEST", "disconnectAfterTimeout -> disconnected");
    }

    public void disconnect() {
        LOG.d("TEST", "disconnecting from device");
        //connectCallback = null;
        connected = false;
        connecting = false;
        bleProcessing = false;
        commandQueue.clear();

        if (gatt != null) {
            gatt.disconnect();
            gatt.close();
            gatt = null;
              LOG.d("TEST", "device disconnected");
        }
        else {
            LOG.d("TEST", "disconnect gatt is null");
        }
    }

    public JSONObject asJSONObject()  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("advertising", byteArrayToJSON(advertisingData));
            // TODO real RSSI if we have it, else
            json.put("rssi", advertisingRSSI);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(String errorMessage)  {

        JSONObject json = new JSONObject();

        try {
            json.put("name", device.getName());
            json.put("id", device.getAddress()); // mac address
            json.put("errorMessage", errorMessage);
        } catch (JSONException e) { // this shouldn't happen
            e.printStackTrace();
        }

        return json;
    }

    public JSONObject asJSONObject(BluetoothGatt gatt) {

        JSONObject json = asJSONObject();

        try {
            JSONArray servicesArray = new JSONArray();
            JSONArray characteristicsArray = new JSONArray();
            json.put("services", servicesArray);
            json.put("characteristics", characteristicsArray);

            if (connected && gatt != null) {
                for (BluetoothGattService service : gatt.getServices()) {
                    servicesArray.put(UUIDHelper.uuidToString(service.getUuid()));

                    for (BluetoothGattCharacteristic characteristic : service.getCharacteristics()) {
                        JSONObject characteristicsJSON = new JSONObject();
                        characteristicsArray.put(characteristicsJSON);

                        characteristicsJSON.put("service", UUIDHelper.uuidToString(service.getUuid()));
                        characteristicsJSON.put("characteristic", UUIDHelper.uuidToString(characteristic.getUuid()));
                        //characteristicsJSON.put("instanceId", characteristic.getInstanceId());

                        characteristicsJSON.put("properties", Helper.decodeProperties(characteristic));
                            // characteristicsJSON.put("propertiesValue", characteristic.getProperties());

                        if (characteristic.getPermissions() > 0) {
                            characteristicsJSON.put("permissions", Helper.decodePermissions(characteristic));
                            // characteristicsJSON.put("permissionsValue", characteristic.getPermissions());
                        }

                        JSONArray descriptorsArray = new JSONArray();

                        for (BluetoothGattDescriptor descriptor: characteristic.getDescriptors()) {
                            JSONObject descriptorJSON = new JSONObject();
                            descriptorJSON.put("uuid", UUIDHelper.uuidToString(descriptor.getUuid()));
                            descriptorJSON.put("value", descriptor.getValue()); // always blank

                            if (descriptor.getPermissions() > 0) {
                                descriptorJSON.put("permissions", Helper.decodePermissions(descriptor));
                                // descriptorJSON.put("permissionsValue", descriptor.getPermissions());
                            }
                            descriptorsArray.put(descriptorJSON);
                        }
                        if (descriptorsArray.length() > 0) {
                            characteristicsJSON.put("descriptors", descriptorsArray);
                        }
                    }
                }
            }
        } catch (JSONException e) { // TODO better error handling
            e.printStackTrace();
        }

        return json;
    }

    static JSONObject byteArrayToJSON(byte[] bytes) throws JSONException {
        JSONObject object = new JSONObject();
        object.put("CDVType", "ArrayBuffer");
        object.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP));
        return object;
    }

    public boolean isConnected() {
        return connected;
    }

    public boolean isConnecting() {
        return connecting;
    }

    private boolean isDiscovering() {
        return discovering;
    }

    public BluetoothDevice getDevice() {
        return device;
    }

    @Override
    public void onServicesDiscovered(BluetoothGatt gatt, int status) {
        LOG.d("TEST", "onServicesDiscovered -> before super method" + status);
        super.onServicesDiscovered(gatt, status);

        LOG.d("TEST", "onServicesDiscovered -> services discovered with status " + status);
        discovering = false;

        if (status == BluetoothGatt.GATT_SUCCESS) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, this.asJSONObject(gatt));
            result.setKeepCallback(true);
            connectCallback.sendPluginResult(result);
        } else {
            LOG.e(TAG, "Service discovery failed. status = " + status);
            connectCallback.error(this.asJSONObject("Service discovery failed"));
            LOG.d("TEST", "onServicesDiscovered disconnect from device");
            disconnect();
        }
    }

    @Override
    public void onConnectionStateChange(BluetoothGatt gatt, int status, int newState) {

        LOG.d("TEST", "onConnectionStateChange method " + newState);
                        this.gatt = gatt;

                        if (newState == BluetoothGatt.STATE_CONNECTED) {
                            LOG.d("TEST", "device connected state");

                            if (timeOutCallback != null) {
                               LOG.d("TEST", "clear discoveringTimeoutCallback");
                                TimeOutHelper.clearTimeOut(discoveringTimeoutCallback);
                            }
                            connected = true;
                            connecting = false;
                            discovering = true;

                            boolean ans = startDiscovering();
                            LOG.d("TEST", "Discover Services started: " + ans);

                             discoveringTimeoutCallback = new Runnable() {
                                   @Override
                                   public void run() {
                                      LOG.d("TEST", "discoveringTimeoutCallback");
                                      if (isDiscovering()) {
                                          LOG.d("TEST", "Device is still discovering, so disconnect it");
                                          disconnectAfterTimeout();
                                      }
                                      else {
                                          LOG.d("TEST", "Device is not discovering");
                                      }
                                   }
                             };

                             TimeOutHelper.setTimeout(discoveringTimeoutCallback, 15000);

                        } else {
            LOG.d("TEST", "device disconnected state");
            if (connectCallback != null) {
                connectCallback.error(this.asJSONObject("Peripheral Disconnected"));
            }
            disconnect();
        }

    }

    private boolean startDiscovering() {
      return gatt.discoverServices();
    }


    @Override
    public void onCharacteristicChanged(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic) {
        super.onCharacteristicChanged(gatt, characteristic);
        LOG.d(TAG, "onCharacteristicChanged " + characteristic);

        CallbackContext callback = notificationCallbacks.get(generateHashKey(characteristic));

        if (callback != null) {
            PluginResult result = new PluginResult(PluginResult.Status.OK, characteristic.getValue());
            result.setKeepCallback(true);
            callback.sendPluginResult(result);
        }
    }

    @Override
    public void onCharacteristicRead(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        super.onCharacteristicRead(gatt, characteristic, status);
        LOG.d(TAG, "onCharacteristicRead " + characteristic);

        isSuccessReadingCommand = true;
        LOG.d("TEST", "onCharacteristicRead " + characteristic);

        if (readCallback != null) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                LOG.d("TEST", "onCharacteristicRead success");
                readCallback.success(characteristic.getValue());
            } else {
                LOG.d("TEST", "onCharacteristicRead failed with status: " + status);
                readCallback.error("Error reading " + characteristic.getUuid() + " status=" + status);
            }

            readCallback = null;

        }
        LOG.d("TEST", "onCharacteristicRead finished");
        commandCompleted();
    }

    @Override
    public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        isSuccessWriteCommand = true;
        super.onCharacteristicWrite(gatt, characteristic, status);
        LOG.d("TEST", "onCharacteristicWrite " + characteristic);
        if (writeCallback != null) {

            if (status == BluetoothGatt.GATT_SUCCESS) {
                writeCallback.success();
            } else {
                writeCallback.error(status);
            }

            writeCallback = null;
        }

        commandCompleted();
    }

    @Override
    public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status) {
    LOG.d("TEST", "onDescriptorWrite -> start");
        isSuccessWritingToDescriptor = true;
        super.onDescriptorWrite(gatt, descriptor, status);
         LOG.d("TEST", "onDescriptorWrite -> finished");
        commandCompleted();
    }


    @Override
    public void onReadRemoteRssi(BluetoothGatt gatt, int rssi, int status) {
        isSuccessReadingRssiCommand = true;
        super.onReadRemoteRssi(gatt, rssi, status);
        if (readCallback != null) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                updateRssi(rssi);
                readCallback.success(rssi);
            } else {
                readCallback.error("Error reading RSSI status=" + status);
            }

            readCallback = null;
        }
        commandCompleted();
    }

    // Update rssi and scanRecord.
    public void update(int rssi, byte[] scanRecord) {
        this.advertisingRSSI = rssi;
        this.advertisingData = scanRecord;
    }

    public void updateRssi(int rssi) {
        advertisingRSSI = rssi;
    }

    // This seems way too complicated
    private void registerNotifyCallback(final CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
         LOG.d("TEST", "registerNotifyCallback -> start");
        boolean success = false;
         isSuccessWritingToDescriptor = false;

               if (writeToDescriptorCallback != null) {
                  LOG.d("TEST", "clear writeToDescriptorCallback");
                  TimeOutHelper.clearTimeOut(writeToDescriptorCallback);
                }

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);
        String key = generateHashKey(serviceUUID, characteristic);

        if (characteristic != null) {

            notificationCallbacks.put(key, callbackContext);

            if (gatt.setCharacteristicNotification(characteristic, true)) {

                // Why doesn't setCharacteristicNotification write the descriptor?
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                if (descriptor != null) {

                    // prefer notify over indicate
                    if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE);
                    } else if ((characteristic.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) {
                        descriptor.setValue(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE);
                    } else {
                        LOG.w("TEST", "Characteristic " + characteristicUUID + " does not have NOTIFY or INDICATE property set");
                    }

                    if (gatt.writeDescriptor(descriptor)) {
                        writeToDescriptorCallback = new Runnable() {
                                   @Override
                                   public void run() {
                                      LOG.d("TEST", "Inside writeToDescriptorCallback");
                                      checkForDescriptorValue(callbackContext);
                                  };
                        };

                        TimeOutHelper.setTimeout(writeToDescriptorCallback, 1500);
                        LOG.d("TEST", "registerNotifyCallback -> success");
                        success = true;
                    } else {
                        callbackContext.error("Failed to set client characteristic notification for " + characteristicUUID);
                    }

                } else {
                    callbackContext.error("Set notification failed for " + characteristicUUID);
                }

            } else {
                callbackContext.error("Failed to register notification for " + characteristicUUID);
            }

        } else {
            callbackContext.error("Characteristic " + characteristicUUID + " not found");
        }

        if (!success) {
            isSuccessWritingToDescriptor = true;
            commandCompleted();
            return;
        }

    }

    private void checkForDescriptorValue(CallbackContext callbackContext) {
      LOG.d("TEST", "checkForDescriptorValue -> start");
      if (!isSuccessWritingToDescriptor) {

          //commandCompleted();

          callbackContext.error("time for notify command execution expired");
          if (connectCallback != null) {
             connectCallback.error(this.asJSONObject("Peripheral Disconnected after timeout"));
          }
          disconnect();
          LOG.d("TEST", "checkForDescriptorValue -> descriptor disabled, so finish notification command");
      }
      LOG.d("TEST", "checkForDescriptorValue -> finished");
    }

    private void removeNotifyCallback(final CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        LOG.d("TEST", "removeNotifyCallback -> start");
          boolean success = false;
                 isSuccessWritingToDescriptor = false;

                       if (writeToDescriptorCallback != null) {
                          LOG.d("TEST", "clear writeToDescriptorCallback");
                          TimeOutHelper.clearTimeOut(writeToDescriptorCallback);
                        }

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findNotifyCharacteristic(service, characteristicUUID);
        String key = generateHashKey(serviceUUID, characteristic);

        if (characteristic != null) {

            notificationCallbacks.remove(key);

            if (gatt.setCharacteristicNotification(characteristic, false)) {
                BluetoothGattDescriptor descriptor = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIGURATION_UUID);
                if (descriptor != null) {
                    descriptor.setValue(BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE);
                     if (gatt.writeDescriptor(descriptor)) {
                     success = true;
                                            writeToDescriptorCallback = new Runnable() {
                                                       @Override
                                                       public void run() {
                                                          LOG.d("TEST", "Inside writeToDescriptorCallback");
                                                          checkForDescriptorValue(callbackContext);
                                                      };
                                            };

                                            TimeOutHelper.setTimeout(writeToDescriptorCallback, 1500);
                                            LOG.d("TEST", "removeNotifyCallback -> success");
                                            success = true;
                                        } else {
                                            callbackContext.error("Failed to set client characteristic notification for " + characteristicUUID);
                                        }
                }
                callbackContext.success();
            } else {
                // TODO we can probably ignore and return success anyway since we removed the notification callback
                callbackContext.error("Failed to stop notification for " + characteristicUUID);
            }

        } else {
            callbackContext.error("Characteristic " + characteristicUUID + " not found");
        }

          if (!success) {
                   isSuccessWritingToDescriptor = true;
                   commandCompleted();
                   return;
               }

    }

    // Some devices reuse UUIDs across characteristics, so we can't use service.getCharacteristic(characteristicUUID)
    // instead check the UUID and properties for each characteristic in the service until we find the best match
    // This function prefers Notify over Indicate
    private BluetoothGattCharacteristic findNotifyCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        // Check for Notify first
        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        if (characteristic != null) return characteristic;

        // If there wasn't Notify Characteristic, check for Indicate
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    private void readCharacteristic(final CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {

        LOG.d("TEST", "readCharacteristic -> start");
        boolean success = false;
        isSuccessReadingCommand = false;

               if (readingCallback != null) {
                  LOG.d("TEST", "clear readingCallback");
                  TimeOutHelper.clearTimeOut(readingCallback);
                }

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findReadableCharacteristic(service, characteristicUUID);

        if (characteristic == null) {
            callbackContext.error("Characteristic " + characteristicUUID + " not found.");
        } else {
            readCallback = callbackContext;
            if (gatt.readCharacteristic(characteristic)) {
                success = true;
                LOG.d("TEST", "readCharacteristic -> success");
                 readingCallback = new Runnable() {
                                                   @Override
                                                   public void run() {
                                                      LOG.d("TEST", "Inside readingCallback");
                                                      checkForSuccessfulReadCommand(callbackContext);
                                                  };
                                        };

                 TimeOutHelper.setTimeout(readingCallback, 1500);

            } else {
                readCallback = null;
                callbackContext.error("Read failed");
            }
        }

        if (!success) {
            LOG.d("TEST", "readCharacteristic -> error");
            isSuccessReadingCommand = true;
            commandCompleted();
        }
        LOG.d("TEST", "readCharacteristic -> finished");
    }

    private void readRSSI(final CallbackContext callbackContext) {

        boolean success = false;
        isSuccessReadingRssiCommand = false;
                       if (readingRssiCallback != null) {
                          LOG.d("TEST", "clear readingRssiCallback");
                          TimeOutHelper.clearTimeOut(readingRssiCallback);
                        }

        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        readCallback = callbackContext;

        if (gatt.readRemoteRssi()) {
            success = true;
                 readingRssiCallback = new Runnable() {
                                                               @Override
                                                               public void run() {
                                                                  LOG.d("TEST", "Inside readingRssiCallback");
                                                                  checkForSuccessfulReadRssiCommand(callbackContext);
                                                              };
                                                    };

                             TimeOutHelper.setTimeout(readingRssiCallback, 500);
        } else {
            readCallback = null;
            callbackContext.error("Read RSSI failed");
        }

        if (!success) {
            isSuccessReadingRssiCommand = true;
            commandCompleted();
        }

    }

        private void checkForSuccessfulReadCommand(CallbackContext callbackContext) {
          LOG.d("TEST", "checkForSuccessfulReadCommand -> start");
          if (!isSuccessReadingCommand) {

              //commandCompleted();
              callbackContext.error("time for read command execution expired");
                   if (connectCallback != null) {
                          connectCallback.error(this.asJSONObject("Peripheral Disconnected after timeout"));
                       }
                       disconnect();
              LOG.d("TEST", "checkForSuccessfulReadCommand -> onReadCallback is not called");
          }
          LOG.d("TEST", "checkForSuccessfulReadCommand -> finished");
        }

     private void checkForSuccessfulReadRssiCommand(CallbackContext callbackContext) {
              LOG.d("TEST", "checkForSuccessfulReadRssiCommand -> start");
              if (!isSuccessReadingRssiCommand) {

                  //commandCompleted();

                  callbackContext.error("time for readRssi command execution expired");
                              if (connectCallback != null) {
                                     connectCallback.error(this.asJSONObject("Peripheral Disconnected after timeout"));
                                  }
                                  disconnect();
                  LOG.d("TEST", "checkForSuccessfulReadRssiCommand -> onReadRssiCallback is not called");
              }
              LOG.d("TEST", "checkForSuccessfulReadRssiCommand -> finished");
            }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findReadableCharacteristic(BluetoothGattService service, UUID characteristicUUID) {
        BluetoothGattCharacteristic characteristic = null;

        int read = BluetoothGattCharacteristic.PROPERTY_READ;

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & read) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    private void writeCharacteristic(final CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
          LOG.d("TEST", "writeCharacteristic -> start" + characteristicUUID);
        boolean success = false;
        isSuccessWriteCommand = false;
         if (writingCallback != null) {
              LOG.d("TEST", "clear writingCallback");
              TimeOutHelper.clearTimeOut(writingCallback);
         }


        if (gatt == null) {
            callbackContext.error("BluetoothGatt is null");
            return;
        }

        BluetoothGattService service = gatt.getService(serviceUUID);
        BluetoothGattCharacteristic characteristic = findWritableCharacteristic(service, characteristicUUID, writeType);

        if (characteristic == null) {
            callbackContext.error("Characteristic " + characteristicUUID + " not found.");
        } else {
            characteristic.setValue(data);
            characteristic.setWriteType(writeType);
            writeCallback = callbackContext;

            if (gatt.writeCharacteristic(characteristic)) {
                success = true;
                  writingCallback = new Runnable() {
                        @Override
                         public void run() {
                            LOG.d("TEST", "Inside writingCallback");
                            checkForSuccessfulWriteCommand(callbackContext);
                         };
                  };

                TimeOutHelper.setTimeout(writingCallback, 1500);
            } else {
                writeCallback = null;
                callbackContext.error("Write failed");
            }
        }

        if (!success) {
        isSuccessWriteCommand = true;
            commandCompleted();
        }
      LOG.d("TEST", "writeCharacteristic -> finish" + characteristicUUID);
    }

            private void checkForSuccessfulWriteCommand(CallbackContext callbackContext) {
              LOG.d("TEST", "checkForSuccessfulWriteCommand -> start");
              if (!isSuccessWriteCommand) {

                 // commandCompleted();
                  callbackContext.error("time for write command execution expired");
      if (connectCallback != null) {
             connectCallback.error(this.asJSONObject("Peripheral Disconnected after timeout"));
          }
          disconnect();
                  LOG.d("TEST", "checkForSuccessfulWriteCommand -> onWriteCallback is not called");
              }
              LOG.d("TEST", "checkForSuccessfulWriteCommand -> finished");
            }

    // Some peripherals re-use UUIDs for multiple characteristics so we need to check the properties
    // and UUID of all characteristics instead of using service.getCharacteristic(characteristicUUID)
    private BluetoothGattCharacteristic findWritableCharacteristic(BluetoothGattService service, UUID characteristicUUID, int writeType) {
        BluetoothGattCharacteristic characteristic = null;

        // get write property
        int writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE;
        if (writeType == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
            writeProperty = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE;
        }

        List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
        for (BluetoothGattCharacteristic c : characteristics) {
            if ((c.getProperties() & writeProperty) != 0 && characteristicUUID.equals(c.getUuid())) {
                characteristic = c;
                break;
            }
        }

        // As a last resort, try and find ANY characteristic with this UUID, even if it doesn't have the correct properties
        if (characteristic == null) {
            characteristic = service.getCharacteristic(characteristicUUID);
        }

        return characteristic;
    }

    public void queueRead(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        LOG.d("TEST", "queueRead -> service: " + serviceUUID + " characteristic: "  + characteristicUUID);
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.READ);
        queueCommand(command);
    }

    public void queueWrite(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID, byte[] data, int writeType) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, data, writeType);
        queueCommand(command);
    }

    public void queueRegisterNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REGISTER_NOTIFY);
        queueCommand(command);
    }

    public void queueRemoveNotifyCallback(CallbackContext callbackContext, UUID serviceUUID, UUID characteristicUUID) {
        LOG.d("TEST", "queueRemoveNotifyCallback -> service: " + serviceUUID + " characteristic: "  + characteristicUUID);
        BLECommand command = new BLECommand(callbackContext, serviceUUID, characteristicUUID, BLECommand.REMOVE_NOTIFY);
        queueCommand(command);
    }


    public void queueReadRSSI(CallbackContext callbackContext) {
        LOG.d("TEST", "queueReadRSSI ->");
        BLECommand command = new BLECommand(callbackContext, null, null, BLECommand.READ_RSSI);
        queueCommand(command);
    }

    // add a new command to the queue
    private void queueCommand(BLECommand command) {
        LOG.d("TEST","Queuing Command " + command.getType());
        commandQueue.add(command);

        PluginResult result = new PluginResult(PluginResult.Status.NO_RESULT);
        result.setKeepCallback(true);
        command.getCallbackContext().sendPluginResult(result);

        if (!bleProcessing) {
        LOG.d("TEST","Queuing Command -> processCommands" + command.getType());

                            processCommands();

        }
        else {
          LOG.d("TEST","Queuing Command -> bleProcessing is true");
        }
    }

    // command finished, queue the next command
    private void commandCompleted() {
        LOG.d("TEST","Processing Complete");
        bleProcessing = false;

                processCommands();

    }

    // process the queue
    private void processCommands() {
        LOG.d(TAG,"Processing Commands" + bleProcessing);

        if (bleProcessing) { return; }

        BLECommand command = commandQueue.poll();
        if (command != null) {
            if (command.getType() == BLECommand.READ) {
                LOG.d("TEST","Read " + command.getCharacteristicUUID());
                bleProcessing = true;
                readCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) {
                LOG.d(TAG,"Write " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) {
                LOG.d(TAG,"Write No Response " + command.getCharacteristicUUID());
                bleProcessing = true;
                writeCharacteristic(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID(), command.getData(), command.getType());
            } else if (command.getType() == BLECommand.REGISTER_NOTIFY) {
                LOG.d("TEST","Register Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                registerNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BLECommand.REMOVE_NOTIFY) {
                LOG.d("TEST","Remove Notify " + command.getCharacteristicUUID());
                bleProcessing = true;
                removeNotifyCallback(command.getCallbackContext(), command.getServiceUUID(), command.getCharacteristicUUID());
            } else if (command.getType() == BLECommand.READ_RSSI) {
                LOG.d(TAG,"Read RSSI");
                bleProcessing = true;
                readRSSI(command.getCallbackContext());
            } else {
                // this shouldn't happen
                throw new RuntimeException("Unexpected BLE Command type " + command.getType());
            }
        } else {
            LOG.d(TAG, "Command Queue is empty.");
        }

    }

    private String generateHashKey(BluetoothGattCharacteristic characteristic) {
        return generateHashKey(characteristic.getService().getUuid(), characteristic);
    }

    private String generateHashKey(UUID serviceUUID, BluetoothGattCharacteristic characteristic) {
        return String.valueOf(serviceUUID) + "|" + characteristic.getUuid() + "|" + characteristic.getInstanceId();
    }

}
