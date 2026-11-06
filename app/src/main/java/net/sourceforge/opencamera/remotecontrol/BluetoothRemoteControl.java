package net.sourceforge.opencamera.remotecontrol;

import static android.content.Context.RECEIVER_NOT_EXPORTED;

import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.util.Log;

import androidx.annotation.RequiresApi;

import net.sourceforge.opencamera.MainActivity;
import net.sourceforge.opencamera.MyApplicationInterface;
import net.sourceforge.opencamera.MyDebug;
import net.sourceforge.opencamera.PreferenceKeys;
import net.sourceforge.opencamera.preview.Preview;
import net.sourceforge.opencamera.ui.MainUI;

/** Class for handling the Bluetooth LE remote control functionality.
 */
public class BluetoothRemoteControl {
    private final static String TAG = "BluetoothRemoteControl";

    private final MainActivity main_activity;

    private BluetoothLeService bluetoothLeService;
    private String remoteDeviceAddress;
    private String remoteDeviceType;
    private boolean is_connected;

    public BluetoothRemoteControl(MainActivity main_activity) {
        this.main_activity = main_activity;
    }

    // class to manage the Service lifecycle for remote control.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            if( MyDebug.LOG )
                Log.d(TAG, "onServiceConnected");
            if( main_activity.isAppPaused() ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "but app is now paused");
                // Unclear if this could happen - possibly if app pauses immediately after starting
                // the service, but before we connect? In theory we should then unbind the service,
                // but seems safer not to try to call initialize or connect.
                // This will mean the BluetoothLeService still thinks it's unbound (is_bound will
                // be left false), but find, that just means we'll enforce not trying to connect at
                // a later stage).
                return;
            }
            if( !DeviceScanner.useAndroid12BluetoothPermissions() ) {
                if( MyDebug.LOG )
                    Log.e(TAG, "bluetooth remote control requires Android 12+");
                // in theory not needed as mServiceConnection is not used if remoteEnabled() returns
                // false (which will be the case if not on Android 12+), but just to be safe
                // also needed to avoid lint warnings for BluetoothLeService requiring Android 12+
                return;
            }
            bluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if( !bluetoothLeService.initialize() ) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                stopRemoteControl();
            }
            // set the type before connecting, as connect() and service discovery depend on it
            bluetoothLeService.setRemoteDeviceType(remoteDeviceType);
            // connect to the device
            bluetoothLeService.connect(remoteDeviceAddress);
        }

        /** Called when a connection to the Service has been lost. This typically happens when the
         * process hosting the service has crashed or been killed.
         * So in particular, note this isn't the inverse to onServiceConnected() - whilst
         * onServiceConnected is always called (after the service receives onBind()), upon normal
         * disconnection (after we call unbindService()), the service receives onUnbind(), but
         * onServiceDisconnected is not called under normal operation.
         */
        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            if( MyDebug.LOG )
                Log.d(TAG, "onServiceDisconnected");
            if( !DeviceScanner.useAndroid12BluetoothPermissions() ) {
                if( MyDebug.LOG )
                    Log.e(TAG, "bluetooth remote control requires Android 12+");
                // in theory not needed as mServiceConnection is not used if remoteEnabled() returns
                // false (which will be the case if not on Android 12+), but just to be safe
                return;
            }
            Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                public void run() {
                    bluetoothLeService.connect(remoteDeviceAddress);
                }
            }, 5000);

        }

    };

    /**
     * Receives event from the remote command handler through intents
     * Handles various events fired by the Service.
     */
    private final BroadcastReceiver remoteControlCommandReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if( !DeviceScanner.useAndroid12BluetoothPermissions() ) {
                // shouldn't be here if not on Android 12+, but to fix Android lint warning
                if( MyDebug.LOG )
                    Log.e(TAG, "bluetooth remote control requires Android 12+");
                return;
            }
            final String action = intent.getAction();
            MyApplicationInterface applicationInterface = main_activity.getApplicationInterface();
            MainUI mainUI = main_activity.getMainUI();
            if( BluetoothLeService.ACTION_GATT_CONNECTED.equals(action) ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote connected");
                // Tell the Bluetooth service what type of remote we want to use
                bluetoothLeService.setRemoteDeviceType(remoteDeviceType);
                main_activity.setBrightnessForCamera(false);
            }
            else if( BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action) ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote disconnected");
                is_connected = false;
                stopCamKeypadStatus();
                if( !isCamKeypad() ) {
                    // camkeypad has no sensors, so don't show the Kraken OSD lines
                    applicationInterface.getDrawPreview().onExtraOSDValuesChanged("-- \u00B0C", "-- m");
                }
                mainUI.updateRemoteConnectionIcon();
                main_activity.setBrightnessToMinimumIfWanted();
                if (mainUI.isExposureUIOpen())
                    mainUI.toggleExposureUI();
            }
            else if( BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action) ) {
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote services discovered");
                // We let the BluetoothLEService subscribe to what is relevant, so we
                // do nothing here, but we wait until this is done to update the UI
                // icon
                is_connected = true;
                mainUI.updateRemoteConnectionIcon();
                if( isCamKeypad() )
                    startCamKeypadStatus();
            }
            else if( BluetoothLeService.ACTION_SENSOR_VALUE.equals(action) ) {
                double temp = intent.getDoubleExtra(BluetoothLeService.SENSOR_TEMPERATURE, -1);
                double depth = intent.getDoubleExtra(BluetoothLeService.SENSOR_DEPTH, -1) / main_activity.getWaterDensity();
                depth = (Math.round(depth* 10)) / 10.0; // Round to 1 decimal
                if( MyDebug.LOG )
                    Log.d(TAG, "Sensor values: depth: " + depth + " - temp: " + temp);
                // Create two OSD lines
                String line1 = temp + " \u00B0C";
                String line2 = depth + " m";
                applicationInterface.getDrawPreview().onExtraOSDValuesChanged(line1, line2);
            }
            else if( BluetoothLeService.ACTION_REMOTE_COMMAND.equals(action) ) {
                int command = intent.getIntExtra(BluetoothLeService.EXTRA_DATA, -1);
                // TODO: we could abstract this into a method provided by each remote control model
                switch( command ) {
                    case BluetoothLeService.COMMAND_SHUTTER:
                        main_activity.triggerRemoteControlAction();
                        break;
                    case BluetoothLeService.COMMAND_MODE:
                        // "Mode" key :either toggles photo/video mode, or
                        // closes the settings screen that is currently open
                        if( mainUI.popupIsOpen() ) {
                            mainUI.togglePopupSettings();
                        }
                        else if( mainUI.isExposureUIOpen() ) {
                            mainUI.toggleExposureUI();
                        }
                        else {
                            main_activity.clickedSwitchVideo(null);
                        }
                        break;
                    case BluetoothLeService.COMMAND_MENU:
                        // Open the exposure UI (ISO/Exposure) or
                        // select the current line on an open UI or
                        // select the current option on a button on a selected line
                        if( !mainUI.popupIsOpen() ) {
                            if( !mainUI.isExposureUIOpen() ) {
                                mainUI.toggleExposureUI();
                            }
                            else {
                                mainUI.commandMenuExposure();
                            }
                        }
                        else {
                            mainUI.commandMenuPopup();
                        }
                        break;
                    case BluetoothLeService.COMMAND_UP:
                        if( !mainUI.processRemoteUpButton() ) {
                            // Default up behaviour:
                            // - if we are on manual focus, then adjust focus.
                            // - if we are on autofocus, then adjust zoom.
                            if( main_activity.getPreview().getCurrentFocusValue() != null && main_activity.getPreview().getCurrentFocusValue().equals("focus_mode_manual2") ) {
                                main_activity.changeFocusDistance(-25, false);
                            }
                            else {
                                // Adjust zoom
                                main_activity.zoomIn();
                            }
                        }
                        break;
                    case BluetoothLeService.COMMAND_DOWN:
                        if( !mainUI.processRemoteDownButton() ) {
                            if( main_activity.getPreview().getCurrentFocusValue() != null && main_activity.getPreview().getCurrentFocusValue().equals("focus_mode_manual2") ) {
                                main_activity.changeFocusDistance(25, false);
                            }
                            else {
                                // Adjust zoom
                                main_activity.zoomOut();
                            }
                        }
                        break;
                    case BluetoothLeService.COMMAND_AFMF:
                        // Open the camera settings popup menu (not the app settings)
                        // or selects the current line/icon in the popup menu, and finally
                        // clicks the icon
                        //if( !mainUI.popupIsOpen() ) {
                        mainUI.togglePopupSettings();
                        //}
                        break;
                    default:
                        break;
                }
            }
            else {
                if( MyDebug.LOG )
                    Log.d(TAG, "Other remote event");
            }
        }
    };

    public boolean remoteConnected() {
		/*if( true )
			return true; // test*/
        return is_connected;
    }

    private boolean isCamKeypad() {
        return BluetoothLeService.REMOTE_TYPE_CAMKEYPAD.equals(remoteDeviceType);
    }

    /* camkeypad status push. Rather than hooking every place video can start, stop or pause
     * (including stops Open Camera makes on its own, e.g. max filesize or errors), we poll the
     * Preview's state and write as soon as it changes, plus a heartbeat so the pad can tell a
     * live link from a stale one.
     */
    private static final long CAMKEYPAD_POLL_MS = 250;
    private final Handler camkeypadHandler = new Handler(Looper.getMainLooper());
    private int camkeypad_sent_summary; // hash of what was last successfully written, apart from the time
    private long camkeypad_sent_time; // System.currentTimeMillis() of the last successful write, or 0 for none

    private final Runnable camkeypadStatusTick = new Runnable() {
        @Override
        public void run() {
            pushCamKeypadStatus();
            camkeypadHandler.postDelayed(this, CAMKEYPAD_POLL_MS);
        }
    };

    private void startCamKeypadStatus() {
        if( MyDebug.LOG )
            Log.d(TAG, "startCamKeypadStatus");
        camkeypadHandler.removeCallbacks(camkeypadStatusTick);
        camkeypad_sent_time = 0;
        camkeypadHandler.post(camkeypadStatusTick);
    }

    private void stopCamKeypadStatus() {
        camkeypadHandler.removeCallbacks(camkeypadStatusTick);
    }

    private void pushCamKeypadStatus() {
        if( bluetoothLeService == null || !DeviceScanner.useAndroid12BluetoothPermissions() )
            return;
        Preview preview = main_activity.getPreview();
        if( preview == null )
            return;

        int state;
        long elapsed_ms = 0;
        if( !preview.isVideoRecording() ) {
            state = CamKeypadProtocol.STATE_IDLE;
        }
        else {
            state = preview.isVideoRecordingPaused() ? CamKeypadProtocol.STATE_PAUSED : CamKeypadProtocol.STATE_RECORDING;
            elapsed_ms = preview.getVideoTime(false);
        }

        int mode = preview.isVideo() ? CamKeypadProtocol.MODE_VIDEO : CamKeypadProtocol.MODE_PHOTO;

        int focus = "focus_mode_manual2".equals(preview.getCurrentFocusValue()) ? CamKeypadProtocol.FOCUS_MANUAL : CamKeypadProtocol.FOCUS_AUTO;
        int focus_distance_cm = getFocusDistanceCm(preview);
        int exposure_tenths = Math.round(preview.getCurrentExposure() * preview.getExposureStep() * 10.0f);
        int exposure_locked = preview.isExposureLocked() ? 1 : 0;

        int capture_rate_tenths = preview.getCameraController() == null ? 10 : Math.round(main_activity.getApplicationInterface().getVideoCaptureRateFactor() * 10.0f);

        int summary = java.util.Arrays.hashCode(new int[]{state, mode, focus, focus_distance_cm, exposure_tenths, exposure_locked, capture_rate_tenths});
        long now = System.currentTimeMillis();
        if( summary == camkeypad_sent_summary && now - camkeypad_sent_time < CamKeypadProtocol.HEARTBEAT_MS )
            return;
        // if the write fails (e.g., stack busy), we leave the sent state alone so the next tick retries
        if( bluetoothLeService.writeCamKeypadStatus(CamKeypadProtocol.encodeStatus(state, elapsed_ms, mode, getPhoneBattery(), focus, focus_distance_cm, exposure_tenths, exposure_locked, capture_rate_tenths)) ) {
            camkeypad_sent_summary = summary;
            camkeypad_sent_time = now;
        }
    }

    /** Returns the manual focus distance in centimetres, or CamKeypadProtocol.FOCUS_DISTANCE_CM_INFINITY. */
    private int getFocusDistanceCm(Preview preview) {
        if( preview.getCameraController() == null )
            return CamKeypadProtocol.FOCUS_DISTANCE_CM_INFINITY;
        float dioptres = preview.getCameraController().getFocusDistance();
        if( dioptres <= 0.0f )
            return CamKeypadProtocol.FOCUS_DISTANCE_CM_INFINITY;
        return Math.max(1, Math.min(65535, Math.round(100.0f / dioptres)));
    }

    /** Returns the phone's charge as a percentage, or CamKeypadProtocol.PHONE_BATTERY_UNKNOWN. */
    private int getPhoneBattery() {
        BatteryManager batteryManager = (BatteryManager) main_activity.getSystemService(Context.BATTERY_SERVICE);
        if( batteryManager == null )
            return CamKeypadProtocol.PHONE_BATTERY_UNKNOWN;
        int percent = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
        return percent >= 0 && percent <= 100 ? percent : CamKeypadProtocol.PHONE_BATTERY_UNKNOWN;
    }

    // TODO: refactor for a filter than receives generic remote control intents
    @RequiresApi(api = Build.VERSION_CODES.S)
    private static IntentFilter makeRemoteCommandIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        intentFilter.addAction(BluetoothLeService.ACTION_REMOTE_COMMAND);
        intentFilter.addAction(BluetoothLeService.ACTION_SENSOR_VALUE);
        return intentFilter;
    }

    /**
     * Starts or stops the remote control layer
     */
    public void startRemoteControl() {
        if( MyDebug.LOG )
            Log.d(TAG, "BLE Remote control service start check...");
        // Check isAppPaused() just to be safe - in theory shouldn't be needed, but don't want to
        // start up the service if we're in background! (And we might as well then try to stop the
        // service instead.)
        if( !DeviceScanner.useAndroid12BluetoothPermissions() ) {
            // bluetooth remote control requires Android 12+
        }
        else if( !main_activity.isAppPaused() && remoteEnabled() ) {
            if( MyDebug.LOG )
                Log.d(TAG, "Remote enabled, starting service");
            Intent gattServiceIntent = new Intent(main_activity, BluetoothLeService.class);
            main_activity.bindService(gattServiceIntent, mServiceConnection, Context.BIND_AUTO_CREATE);
            // For Android 14 (UPSIDE_DOWN_CAKE) onwards, a flag of RECEIVER_EXPORTED or RECEIVER_NOT_EXPORTED must be specified when using
            // registerReceiver with non-system intents, otherwise a SecurityException will be thrown.
            // The if condition is for TIRAMISU as there seems no harm doing this for earlier versions too, but RECEIVER_NOT_EXPORTED
            // requires Android 13.
            if( Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ) {
                main_activity.registerReceiver(remoteControlCommandReceiver, makeRemoteCommandIntentFilter(), RECEIVER_NOT_EXPORTED);
            }
            else {
                // n.b., this gets an Android lint warning, even though this can only be fixed for TIRAMISU onwards (as
                // RECEIVER_NOT_EXPORTED not available on older versions)!
                main_activity.registerReceiver(remoteControlCommandReceiver, makeRemoteCommandIntentFilter());
            }
        }
        else {
            if( MyDebug.LOG )
                Log.d(TAG, "Remote disabled, stopping service");
            // Stop the service if necessary
            try {
                main_activity.unregisterReceiver(remoteControlCommandReceiver);
                main_activity.unbindService(mServiceConnection);
                is_connected = false; // Unbinding closes the connection, of course
                stopCamKeypadStatus();
                main_activity.getMainUI().updateRemoteConnectionIcon();
            }
            catch(IllegalArgumentException e){
                if( MyDebug.LOG )
                    Log.d(TAG, "Remote Service was not running, that's fine");
            }
        }
    }

    public void stopRemoteControl() {
        if( MyDebug.LOG )
            Log.d(TAG, "BLE Remote control service shutdown...");
        if( remoteEnabled()) {
            // Stop the service if necessary
            try {
                main_activity.unregisterReceiver(remoteControlCommandReceiver);
                main_activity.unbindService(mServiceConnection);
                is_connected = false; // Unbinding closes the connection, of course
                stopCamKeypadStatus();
                main_activity.getMainUI().updateRemoteConnectionIcon();
            }
            catch(IllegalArgumentException e){
                MyDebug.logStackTrace(TAG, "Remote Service was not running, that's strange", e);
            }
        }
    }

    /**
     * Checks if remote control is enabled in the settings, and the remote control address
     * is also defined
     * @return true if this is the case
     */
    public boolean remoteEnabled() {
        if( !DeviceScanner.useAndroid12BluetoothPermissions() ) {
            // bluetooth remote control requires Android 12+
            return false;
        }
        SharedPreferences sharedPreferences = PreferenceManager.getDefaultSharedPreferences(main_activity);
        boolean remote_enabled = sharedPreferences.getBoolean(PreferenceKeys.EnableRemote, false);
        remoteDeviceType = sharedPreferences.getString(PreferenceKeys.RemoteType, "undefined");
        remoteDeviceAddress = sharedPreferences.getString(PreferenceKeys.RemoteName, "undefined");
        //return remote_enabled; // test - if using this, also need to enable test code in BluetoothLeService.connect()
        return remote_enabled && !remoteDeviceAddress.equals("undefined");
    }
}
