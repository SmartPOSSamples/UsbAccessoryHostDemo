package com.cloudpos.usbaccessoryhostdemo;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.util.HashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Refer to:
 * https://github.com/androidyaohui/HostChart
 * https://github.com/androidyaohui/AccessoryChart
 */
public class MainActivity extends Activity implements OpenDeviceReceiver.OpenDeviceListener, UsbDetachedReceiver.UsbDetachedListener, View.OnClickListener {

    public static final String TAG = "TRACE";
    private static final String ACTION_OPEN_ACCESSORY = "ACTION_OPEN_ACCESSORY";
    private static final int CONNECTED_SUCCESS = 0;
    private static final int SEND_MESSAGE_SUCCESS = 1;
    private static final int RECEIVE_MESSAGE_SUCCESS = 2;

    private int counter = 0;
    private volatile boolean mToggle = false;
    private volatile boolean mIsReceiving = false;
    private Context mContext;
    private TextView mLog;
    private EditText mMessage;
    private TextView mError;
    private Button mSend;
    private CheckBox mAutoConnect;
    private ExecutorService mThreadPool;
    private UsbManager mUsbManager;
    private OpenDeviceReceiver mOpenDeviceReceiver;
    private UsbDetachedReceiver mUsbDetachedReceiver;
    private UsbDeviceConnection mUsbDeviceConnection;
    private UsbEndpoint mUsbEndpointOut;
    private UsbEndpoint mUsbEndpointIn;
    private UsbInterface mUsbInterface;

    private Runnable receiver = new Runnable() {
        @Override
        public void run() {
            readFromUsb();
        }
    };

    private final Handler mHandler = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case CONNECTED_SUCCESS:
                    mError.setText("");
                    log("=Attached");
                    mSend.setEnabled(true);
                    mThreadPool.execute(receiver);
                    break;

                case RECEIVE_MESSAGE_SUCCESS:
                    log((CharSequence) msg.obj);
                    break;

                case SEND_MESSAGE_SUCCESS:
                    mMessage.setText("");
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initView();
        initData();
        if (mAutoConnect.isChecked())
            tryOpenDevice();
    }

    private void initView() {
        mLog = (TextView) findViewById(R.id.log);
        mError = (TextView) findViewById(R.id.error);
        mMessage = (EditText) findViewById(R.id.message);
        mAutoConnect = (CheckBox) findViewById(R.id.autoConnect);
        mSend = (Button) findViewById(R.id.send);
    }

    private void initData() {
        mContext = getApplicationContext();
        mThreadPool = Executors.newFixedThreadPool(5);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mUsbDetachedReceiver = new UsbDetachedReceiver(this);
        registerReceiver(mUsbDetachedReceiver, new IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED));
        mOpenDeviceReceiver = new OpenDeviceReceiver(this);
        registerReceiver(mOpenDeviceReceiver, new IntentFilter(ACTION_OPEN_ACCESSORY));
    }

    private void tryOpenDevice() {
        mLog.setText("");
        HashMap<String, UsbDevice> deviceList = mUsbManager.getDeviceList();
        int devCnt = deviceList == null ? -1 : deviceList.size();
        String msg = "+tryOpenDevice[" + ++counter + "] Devices=" + devCnt;
        if (devCnt <= 0) {
            log("!" + msg.substring(1));
        } else {
            log(msg);
            for (UsbDevice usbDevice : deviceList.values()) {
                int productId = usbDevice.getProductId();
                if (productId == 377 || productId == 7205) {
                    log("=Ignore Pid A: " + productId);
                } else if (!mUsbManager.hasPermission(usbDevice)) {
                    log("=requestPermission A: Pid=" + usbDevice.getProductId());
                    requestPermission(usbDevice);
                } else {
                    initDevice(usbDevice, "tryOpenDevice");
                }
            }
            log("-tryOpenDevice");
        }
    }

    private void log(CharSequence msg) {
        Log.d(TAG, msg.toString());
        mLog.append(msg);
        mLog.append("\n");
    }

    private void requestPermission(UsbDevice usbDevice) {
        mUsbManager.requestPermission(usbDevice, PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_OPEN_ACCESSORY), 0));
    }

    private void initDevice(final UsbDevice usbDevice, String caller) {
        log("+initDevice Pid=" + usbDevice.getProductId());
        if (controlTransfer(usbDevice, caller)) {
            log("=Pid: " + usbDevice.getProductId());
            closeUsbConnection();
            mToggle = true;
            mThreadPool.execute(new Runnable() {
                @Override
                public void run() {
                    while (mToggle) {
                        String msg = openAccessory(usbDevice);
                        if (msg != null) {
                            logA(msg + ", Pid=" + usbDevice.getProductId());
                        } else {
                            mToggle = false;
                            mHandler.sendEmptyMessage(CONNECTED_SUCCESS);
                        }
                        SystemClock.sleep(5000);
                    }
                }
            });
        }
        log("-initDevice");
    }

    private boolean controlTransfer(UsbDevice usbDevice, String caller) {
        UsbDeviceConnection connection = mUsbManager.openDevice(usbDevice);
        if (connection == null) {
            log("!controlTransfer: No Connection@" + caller);
            return false;
        }

        initStringControlTransfer(connection);
        connection.controlTransfer(0x40, 53, 0, 0, new byte[]{}, 0, 100);
        connection.close();
        log("=controlTransfer@" + caller);
        return true;
    }

    private void closeUsbConnection() {
        mToggle = false;
        mIsReceiving = false;
        releaseUsbConnection();
    }

    private String openAccessory(final UsbDevice device) {
        int productId = device.getProductId();
        String err = null;
        // 0x2D00 - accessory; 0x2D01 - accessory + adb; 0x2D02 - audio; 0x2D03 - audio + adb; 0x2D04 - accessory + audio; 0x2D05 - accessory + audio + adb
        if (productId != 0x2D00 && productId != 0x2D01) {
            err = "!Ignore Pid B";
        } else if (!mUsbManager.hasPermission(device)) {
            err = "=requestPermission B";
            requestPermission(device);
        } else {
            mUsbDeviceConnection = mUsbManager.openDevice(device);
            if (mUsbDeviceConnection == null) {
                err = "!No connection B";
            } else if (!setEndpoints(device)) {
                err = "!setEndpoints failed";
                releaseUsbConnection();    // Better to closeUsbConnection() ?
            } else if (!mUsbDeviceConnection.claimInterface(mUsbInterface, true)) {
                err = "!claimIntf failed";
            }
        }
        return err;
    }

    private void logA(CharSequence msg) {
        mHandler.obtainMessage(RECEIVE_MESSAGE_SUCCESS, msg).sendToTarget();
    }

    private void initStringControlTransfer(UsbDeviceConnection connection) {
        final String[] data = {
                "Google, Inc.", // MANUFACTURER
                "AccessoryChat", // MODEL
                "Accessory Chat", // DESCRIPTION
                "1.0", // VERSION
                "http://www.android.com", // URI
                "1123456789", // SERIAL
        };

        for (int index = 0; index < data.length; ++index) {
            byte[] bytes = data[index].getBytes();
            connection.controlTransfer(0x40, 52, 0, index, bytes, bytes.length, 100);
        }
    }

    private void releaseUsbConnection() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection.releaseInterface(mUsbInterface);
            mUsbDeviceConnection.close();
            mUsbDeviceConnection = null;
        }
    }

    private boolean setEndpoints(UsbDevice usbDevice) {
        int intfCnt = usbDevice.getInterfaceCount();
        for (int intf = 0; intf < intfCnt; ++intf) {
            mUsbInterface = usbDevice.getInterface(intf);
            Log.d(TAG, "Intf[" + intf + "]: " + mUsbInterface);
            int epCnt = mUsbInterface.getEndpointCount();
            if (epCnt == 2) {
                mUsbEndpointOut = mUsbEndpointIn = null;
                for (int ep = 0; ep < epCnt; ep++) {
                    UsbEndpoint usbEndpoint = mUsbInterface.getEndpoint(ep);
                    if (usbEndpoint.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) {
                        switch (usbEndpoint.getDirection()) {
                            case UsbConstants.USB_DIR_IN:
                                mUsbEndpointIn = usbEndpoint;
                                break;
                            case UsbConstants.USB_DIR_OUT:
                                mUsbEndpointOut = usbEndpoint;
                                break;
                        }
                    }
                }
                if (mUsbEndpointIn != null && mUsbEndpointOut != null) {
                    return true;
                }
            }
        }
        mUsbEndpointIn = mUsbEndpointOut = null;
        return false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        log("=onDestroy");
        mThreadPool.shutdownNow();
        unregisterReceiver(mUsbDetachedReceiver);
        unregisterReceiver(mOpenDeviceReceiver);
        closeUsbConnection();
    }

    private void readFromUsb() {
        logA("+readFromUsb");
        byte[] bytes = new byte[mUsbEndpointIn.getMaxPacketSize()];
        mIsReceiving = true;
        while (mIsReceiving) {
            if (mUsbDeviceConnection == null || mUsbEndpointIn == null) {
                SystemClock.sleep(1000);
                continue;
            }
            int i = mUsbDeviceConnection.bulkTransfer(mUsbEndpointIn, bytes, bytes.length, 3000);
            if (i > 0) {
                mHandler.obtainMessage(RECEIVE_MESSAGE_SUCCESS, new String(bytes, 0, i)).sendToTarget();
            }
        }
        logA("-readFromUsb");
    }

    @Override
    public void usbDetached() {
        logA("=onDetached");
        closeUsbConnection();
    }

    @Override
    public void openAccessoryMode(UsbDevice usbDevice) {
        initDevice(usbDevice, "OpenDeviceReceiver");
    }

    @Override
    public void openAccessoryError() {
        logA("!openAccessoryError");
    }

    @Override
    public void onClick(View v) {
        if (v.getId() == R.id.connect) {
            tryOpenDevice();
            return;
        }
        final String messageContent = mMessage.getText().toString();
        if (TextUtils.isEmpty(messageContent) || mUsbDeviceConnection == null || mUsbEndpointOut == null) {
            Toast.makeText(this, "Not ready!", Toast.LENGTH_LONG).show();
            return;
        }
        mThreadPool.execute(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = messageContent.getBytes();
                int i = mUsbDeviceConnection.bulkTransfer(mUsbEndpointOut, bytes, bytes.length, 3000);
                if (i > 0) {
                    mHandler.sendEmptyMessage(SEND_MESSAGE_SUCCESS);
                }
            }
        });
    }
}

class OpenDeviceReceiver extends BroadcastReceiver {

    private OpenDeviceListener mOpenDeviceListener;

    public OpenDeviceReceiver(OpenDeviceListener openDeviceListener) {
        mOpenDeviceListener = openDeviceListener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(MainActivity.TAG, "=onReceiver: " + intent.getAction());
        UsbDevice usbDevice = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE);
        if (usbDevice != null && intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
            mOpenDeviceListener.openAccessoryMode(usbDevice);
        } else {
            mOpenDeviceListener.openAccessoryError();
        }
    }

    public interface OpenDeviceListener {

        void openAccessoryMode(UsbDevice usbDevice);

        void openAccessoryError();
    }
}

class UsbDetachedReceiver extends BroadcastReceiver {

    private UsbDetachedListener mUsbDetachedListener;

    public UsbDetachedReceiver(UsbDetachedListener usbDetachedListener) {
        mUsbDetachedListener = usbDetachedListener;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        mUsbDetachedListener.usbDetached();
    }

    public interface UsbDetachedListener {

        void usbDetached();
    }
}
