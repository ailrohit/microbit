package com.samsung.microbit.service;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.SystemClock;
import android.support.annotation.Nullable;
import android.util.Log;
import android.widget.Toast;

import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.samsung.microbit.data.model.RadioPacket;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class HubService extends Service {
    private static final String TAG = HubService.class.getSimpleName();
    private static int counter=0;
    private static UsbManager mUsbManager = null;
    private static final int MESSAGE_REFRESH = 101;
    private static final int MESSAGE_OPEN_PORT = 102;
    private static final long REFRESH_TIMEOUT_MILLIS = 10000;
    private static final int MICROBIT_VENDOR_ID = 3368;
    private static final int MICROBIT_PRODUCT_ID = 516;
    public static boolean isMicrobitConnected = false;
    private static UsbSerialPort microBitPort = null;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();



    public static String HUB_SERVICE_DEVICE_EVENT = "com.samsung.microbit.service.device.event";
    public static String HUB_SERVICE_DEVICE_EVENT_CONNECTED = "connected";
    //public static String HUB_SERVICE_DEVICE_EVENT_DISCONNECTED = "disconnected";
    public static String HUB_SERVICE_RESTART = "com.samsung.microbit.service.HubService.RestartService";
    public static boolean IsHubActive = false;

    public HubService(Context applicationContext)
    {
        super();
        Log.i(TAG, "here I am HubService!");
    }

    private final Handler mHandler = new HubHandler();

    private class HubHandler extends Handler{
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_REFRESH:
                    if(!isMicrobitConnected) {
                        refreshDeviceList();
                    }
                    mHandler.sendEmptyMessageDelayed(MESSAGE_REFRESH, REFRESH_TIMEOUT_MILLIS);
                    Log.i(TAG, "Hub service  is active from " + (counter++)/6 + " minute Micro-bit Connected status " + isMicrobitConnected);
                    break;
                case MESSAGE_OPEN_PORT:
                    ConnectMicroBit();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    }
    public HubService(){}
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        mUsbManager = (UsbManager) getSystemService(Context.USB_SERVICE);
        mHandler.sendEmptyMessage(MESSAGE_REFRESH);
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.i(TAG, "ondestroy!");
        Intent broadcastIntent = new Intent(HUB_SERVICE_RESTART);
        sendBroadcast(broadcastIntent);
    }


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void ConnectMicroBit(){

        if (microBitPort == null) {
            Toast.makeText(this, "No serial device." , Toast.LENGTH_SHORT).show();
            Log.i(TAG, "No serial device.");
        } else {

            UsbDeviceConnection connection = mUsbManager.openDevice(microBitPort.getDriver().getDevice());
            if (connection == null) {
                Toast.makeText(this, "Opening device failed" , Toast.LENGTH_SHORT).show();
                Log.i(TAG, "Opening device failed");
                return;
            }

            try {
                microBitPort.open(connection);
                microBitPort.setParameters(115200, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE);
                microBitPort.setDTR(true);
                microBitPort.setRTS(true);
            } catch (IOException e) {
                Log.e(TAG, "Error setting up device: " + e.getMessage(), e);
                Toast.makeText(this, "Error opening device: " + e.getMessage() , Toast.LENGTH_SHORT).show();
                try {
                    microBitPort.close();
                } catch (IOException e2) {
                    // Ignore.
                }
                microBitPort = null;
                return;
            }
            Toast.makeText(this, "Serial device: " + microBitPort.getClass().getSimpleName() , Toast.LENGTH_SHORT).show();
            Log.i(TAG, "Serial device: " + microBitPort.getClass().getSimpleName());
            Intent intent = new Intent(HUB_SERVICE_DEVICE_EVENT);
            intent.putExtra(HUB_SERVICE_DEVICE_EVENT_CONNECTED,1);
            sendBroadcast(intent);
            isMicrobitConnected = true;
        }
        onDeviceStateChange();
    }

    private void stopIoManager() {
        if (mSerialIoManager != null) {
            Log.i(TAG, "Stopping io manager ..");
            mSerialIoManager.stop();
            mSerialIoManager = null;
        }
    }

    private void startIoManager() {
        if (microBitPort != null) {
            Log.i(TAG, "Starting io manager ..");
            mSerialIoManager = new SerialInputOutputManager(microBitPort, mListener);
            mExecutor.submit(mSerialIoManager);
        }
    }

    private void onDeviceStateChange() {
        stopIoManager();
        startIoManager();
    }

    private SerialInputOutputManager mSerialIoManager;

    private final SerialInputOutputManager.Listener mListener =
            new SerialInputOutputManager.Listener() {

                @Override
                public void onRunError(Exception e) {
                    isMicrobitConnected =false;
                    Intent intent = new Intent(HUB_SERVICE_DEVICE_EVENT);
                    intent.putExtra(HUB_SERVICE_DEVICE_EVENT_CONNECTED,0);
                    sendBroadcast(intent);
                    Log.d(TAG, "Runner stopped.");
                }

                @Override
                public void onNewData(final byte[] data) {
                    updateReceivedData(data);
                }
            };

    private String commandStr = "";
    ByteBuffer pump_on_buf = ByteBuffer.allocate(256);
    private int pump_lenght = 0;

    private void updateReceivedData(byte[] data){

        String dataSample = "";
        byte[] endBytes = new byte[] {(byte)0xc0};
        if(data.length > 0)
        {
            pump_lenght += data.length;
            pump_on_buf.put(data);
            try {
                dataSample = new String(data,"UTF-8");
                Log.d(TAG,"coming text " + dataSample  + " length is " + data.length);
                Log.d(TAG,"coming hex " + HexDump.dumpHexString(data));
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
            }

            if(-1 != indexOf(data,endBytes))
            {
                String FinalStr = commandStr + dataSample;

                Log.d(TAG, "Command: " + FinalStr);
                byte [] wholeBytes = pump_on_buf.array();
                final byte [] packetBytes =  Arrays.copyOfRange(wholeBytes,0,(pump_lenght));
                mHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        RadioPacket packet = new RadioPacket(packetBytes);
                        processPacket(packet);

                        RadioPacket returnPacket = new RadioPacket(packet);

                        if(returnPacket.getRequestType() == RadioPacket.REQUEST_TYPE_HELLO)
                        {
                            returnPacket.append(0);
                        }
                        else {
                            returnPacket.append("OK");
                        }
                        mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));
                    }
                },500);

                commandStr = "done";
                pump_on_buf.clear();
                pump_lenght = 0;

            }
        }

        if(commandStr.compareToIgnoreCase("done") != 0) {
            commandStr += dataSample;
        }
        else
        {
            commandStr = "";
        }
    }

    private void refreshDeviceList() {

        new AsyncTask<Void, Void, List<UsbSerialPort>>() {
            @Override
            protected List<UsbSerialPort> doInBackground(Void... params) {
                Log.d(TAG, "Refreshing device list ...");
                SystemClock.sleep(1000);

                final List<UsbSerialDriver> drivers =
                        UsbSerialProber.getDefaultProber().findAllDrivers(mUsbManager);

                final List<UsbSerialPort> result = new ArrayList<UsbSerialPort>();
                for (final UsbSerialDriver driver : drivers) {
                    final List<UsbSerialPort> ports = driver.getPorts();
                    Log.d(TAG, String.format("+ %s: %s port%s",
                            driver, Integer.valueOf(ports.size()), ports.size() == 1 ? "" : "s"));
                    result.addAll(ports);
                }

                for(final UsbSerialPort port : result)
                {
                    final UsbSerialDriver driver = port.getDriver();
                    final UsbDevice device = driver.getDevice();

                    if(device.getVendorId() == MICROBIT_VENDOR_ID && device.getProductId() == MICROBIT_PRODUCT_ID)
                    {

                        microBitPort = port;
                        mHandler.sendEmptyMessage(MESSAGE_OPEN_PORT);
                    }
                }
                return result;
            }

            @Override
            protected void onPostExecute(List<UsbSerialPort> result) {

                Log.d(TAG, "Done refreshing, " + result.size() + " entries found.");
            }

        }.execute((Void) null);
    }

    private void processPacket(RadioPacket packet) {
        // needed to implement
    }


    /**
     * Search the data byte array for the first occurrence
     * of the byte array pattern.
     */
    public int indexOf(byte[] data, byte[] pattern) {
        int[] failure = computeFailure(pattern);

        int j = 0;

        for (int i = 0; i < data.length; i++) {
            while (j > 0 && pattern[j] != data[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == data[i]) {
                j++;
            }
            if (j == pattern.length) {
                return i - pattern.length + 1;
            }
        }
        return -1;
    }

    /**
     * Computes the failure function using a boot-strapping process,
     * where the pattern is matched against itself.
     */
    private int[] computeFailure(byte[] pattern) {
        int[] failure = new int[pattern.length];

        int j = 0;
        for (int i = 1; i < pattern.length; i++) {
            while (j>0 && pattern[j] != pattern[i]) {
                j = failure[j - 1];
            }
            if (pattern[j] == pattern[i]) {
                j++;
            }
            failure[i] = j;
        }

        return failure;
    }

}
