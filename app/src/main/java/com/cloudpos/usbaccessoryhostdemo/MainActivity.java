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

import com.cloudpos.usbaccessoryhostdemo.R;

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
                case CONNECTED_SUCCESS://车机和手机连接成功
                    mError.setText("");
                    log("=Attached");
                    mSend.setEnabled(true);
                    mThreadPool.execute(receiver);
                    break;

                case RECEIVE_MESSAGE_SUCCESS://成功接受到数据
                    log((CharSequence) msg.obj);
                    break;

                case SEND_MESSAGE_SUCCESS://成功发送数据
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

    /**
     * 打开设备 , 让车机和手机端连起来
     */
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
                    //这里说明下,这里的377 , 7205是我这台机子上独有,因为我这上面有多台设备,所以我在这里判断了下.只有一台设备的无需这一步.
                    log("=Ignore Pid A: " + productId);
                } else if (!mUsbManager.hasPermission(usbDevice)) {
                    log("=requestPermission A: Pid=" + usbDevice.getProductId());
                    requestPermission(usbDevice); // 否则 controlTransfer 可能失败?
                } else {
                    initDevice(usbDevice, "tryOpenDevice");
                }
            }
            log("-tryOpenDevice");
        }
    }

    /**
     * 发送命令 , 让手机进入Accessory模式
     *
     * @param usbDevice
     */
    private void initDevice(final UsbDevice usbDevice, String caller) {
        log("+initDevice Pid=" + usbDevice.getProductId());    // usbDevice 必非 null
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

    // 可参照 q3a7/cts/apps/cts-usb-accessory/cts-usb-accessory.c

    /**
     * USB Accessory设备（即Host端）和Android设备（即Device端）双方整个枚举识别工作过程如下：
     * <br>一、USB Accessory设备发起USB控制传输进行正常的USB设备枚举，获取设备描述符和配置描述符等信息，此时大部分Android设备上报的还只是普通的U盘或MTP设备；
     * <br>二、USB Accessory设备发起Vendor类型，request值为51（0x33）的控制传输命令（ACCESSORY_GET_PROTOCOL），看看该Android设备是否支持USB Accessory功能，如果支持的话会返回所支持的AOA协议版本；
     * <br>三、USB Accessory判断到该Android设备支持Accessory功能后，发起request值为52（0x34）的控制传输命令（ACCESSORY_SEND_STRING），并把该Accessory设备的相关信息（包括厂家，序列号等）告知Android设备；
     * <br>四、如果是USB Audio Accessory设备，还会发起request值为58（0x3A）的控制传输命令（SET_AUDIO_MODE命令），通知Android设备进入到Audio Accessory模式；
     * <br>五、最终，USB Accessory设备发起request值为53（0x35）的控制传输命令（ACCESSORY_START），通知Android设备切换到Accessory功能模式开始工作。接下来Android设备收到此信息后，会先把sys.usb.config设置为包含accessory功能；
     * <br>六、剩下的事情就是如前小节所述，按init.usb.rc的设置进行了，此时Android设备先断开与Accessory设备连接，/sys/class/android_usb/android0/functions节点写入accessory字符串，然后重新连接，使能Accessory接口，正式工作在USB Accessory模式；
     */
    private boolean controlTransfer(UsbDevice usbDevice, String caller) {
        UsbDeviceConnection connection = mUsbManager.openDevice(usbDevice);
        if (connection == null) {
            log("!controlTransfer: No Connection@" + caller);
            return false;
        }

        //根据AOA协议打开Accessory模式
        initStringControlTransfer(connection);
        connection.controlTransfer(0x40, 53, 0, 0, new byte[]{}, 0, 100);
        connection.close();
        log("=controlTransfer@" + caller);
        return true;
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

    /**
     * 初始化设备(手机) , 当手机进入Accessory模式后 , 手机的PID会变为Google定义的2个常量值其中的一个 ,
     */
    private String openAccessory(final UsbDevice device) {
        int productId = device.getProductId();
        String err = null;
        // 在Accessory模式下，USB Device端上报的VID和PID是Google指定的，VID固定为Google的官方VID -- 0x18D1，PID则在不同的模式下定义如下：
        // 0x2D00 - accessory; 0x2D01 - accessory + adb; 0x2D02 - audio; 0x2D03 - audio + adb; 0x2D04 - accessory + audio; 0x2D05 - accessory + audio + adb
        if (productId != 0x2D00 && productId != 0x2D01) {
            err = "!Ignore Pid B";
        } else if (!mUsbManager.hasPermission(device)) { // initDevice 前已验证过. 因为 productId 变了而重验？
            err = "=requestPermission B";
            requestPermission(device); // BUG: device 本轮将被跳过？
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

    private void requestPermission(UsbDevice usbDevice) {
        mUsbManager.requestPermission(usbDevice, PendingIntent.getBroadcast(mContext, 0, new Intent(ACTION_OPEN_ACCESSORY), 0));
    }

    /**
     * 接受消息线程 , 此线程在设备(手机)初始化完成后 , 就一直循环接受消息
     */
    private void readFromUsb() {
        logA("+readFromUsb");
        byte[] bytes = new byte[mUsbEndpointIn.getMaxPacketSize()];
        mIsReceiving = true;
        while (mIsReceiving) {
            if (mUsbDeviceConnection == null || mUsbEndpointIn == null) {
                SystemClock.sleep(1000);
                continue;
            }
            // 循环接受数据的地方 , 只接受byte数据类型的数据
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
            public void run() { // 发送数据的地方 , 只接受byte数据类型的数据
                byte[] bytes = messageContent.getBytes();
                int i = mUsbDeviceConnection.bulkTransfer(mUsbEndpointOut, bytes, bytes.length, 3000);
                if (i > 0) {
                    mHandler.sendEmptyMessage(SEND_MESSAGE_SUCCESS);
                }
            }
        });
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

    private void closeUsbConnection() {
        mToggle = false;
        mIsReceiving = false;
        releaseUsbConnection();
    }

    private void releaseUsbConnection() {
        if (mUsbDeviceConnection != null) {
            mUsbDeviceConnection.releaseInterface(mUsbInterface);
            mUsbDeviceConnection.close();
            mUsbDeviceConnection = null;
        }
    }

    private void logA(CharSequence msg) {
        mHandler.obtainMessage(RECEIVE_MESSAGE_SUCCESS, msg).sendToTarget();
    }

    private void log(CharSequence msg) {
        Log.d(TAG, msg.toString());
        mLog.append(msg);
        mLog.append("\n");
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
        /**
         * 打开Accessory模式
         *
         * @param usbDevice
         */
        void openAccessoryMode(UsbDevice usbDevice);

        /**
         * 打开设备(手机)失败
         */
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
        /**
         * usb断开连接
         */
        void usbDetached();
    }
}
