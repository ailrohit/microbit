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

import com.android.volley.AuthFailureError;
import com.android.volley.Request;
import com.android.volley.RequestQueue;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.Volley;
import com.hoho.android.usbserial.driver.UsbSerialDriver;
import com.hoho.android.usbserial.driver.UsbSerialPort;
import com.hoho.android.usbserial.driver.UsbSerialProber;
import com.hoho.android.usbserial.util.HexDump;
import com.hoho.android.usbserial.util.SerialInputOutputManager;
import com.samsung.microbit.core.RadioJsonObjectRequest;
import com.samsung.microbit.core.VolleyResponse;
import com.samsung.microbit.data.model.HubRestAPIParams;
import com.samsung.microbit.data.model.RadioPacket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    public static String HUB_SERVICE_RESTART = "com.samsung.microbit.service.HubService.RestartService";
    public static boolean IsHubActive = false;
    private RequestQueue requestQueue = null;

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
                        if(packet.getRequestType() == RadioPacket.REQUEST_TYPE_HELLO)
                        {
                            RadioPacket returnPacket = new RadioPacket(packet);
                            returnPacket.append(0);
                            mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));
                        }
                        else {
                            processPacket(packet);
                        }


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
        String url = HubRestAPIParams.SamsungIOT_Url;
        String parts [] = packet.getStringData().split("/");
        JSONObject post_params = new JSONObject();

        if(parts.length == 0)
        {
            // handle exception nothing can be done
            RadioPacket returnPacket = new RadioPacket(packet);
            returnPacket.append("packet error");
            mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));
            return;
        }

        if(parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_IOT) == 0)
        {
            url = HubRestAPIParams.SamsungIOT_Url;

                url = url + parts[3];

            if(parts[2].compareToIgnoreCase("bulbState") == 0
                    || parts[2].compareToIgnoreCase("switchState") == 0)
            {

                if((packet.getRequestType() ==  RadioPacket.REQUEST_TYPE_POST_REQUEST)&& packet.getIntData() == 0)
                {
                    try {
                        post_params.put("value", "off");
                    }catch (JSONException e){

                    }
                }

                if((packet.getRequestType() ==  RadioPacket.REQUEST_TYPE_POST_REQUEST)&& packet.getIntData() == 1)
                {
                    try {
                        post_params.put("value", "on");
                    }catch (JSONException e){

                    }
                }

                url = url + "/switch/";
            }
            else if(parts[2].compareToIgnoreCase("bulbLevel") == 0)
            {
                if((packet.getRequestType() ==  RadioPacket.REQUEST_TYPE_POST_REQUEST)) {
                    try {
                        post_params.put("value", packet.getIntData());
                    } catch (JSONException e) {

                    }
                }

                url = url + "/switch-level/";
            }
            else if(parts[2].compareToIgnoreCase("bulbTemp") == 0)
            {
                if(packet.getRequestType() ==  RadioPacket.REQUEST_TYPE_POST_REQUEST) {
                    try {
                        post_params.put("value", packet.getIntData());
                    } catch (JSONException e) {

                    }
                }
                url = url + "/color-temperature/";
            }
            else if(parts[2].compareToIgnoreCase("sensorState") == 0)
            {
                url = url + "/motion/";
            }
            else if(parts[2].compareToIgnoreCase("sensorTemp") == 0)
            {
                url = url + "/temperature-measurement/";
            }
            else if(parts[2].compareToIgnoreCase("bulbColour") == 0)
            {
                if(packet.getRequestType() ==  RadioPacket.REQUEST_TYPE_POST_REQUEST) {
                    try {
                        post_params.put("value", packet.getIntData());
                    } catch (JSONException e) {

                    }
                }
                url = url + "/color-control/";
            }
        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_SHARE) == 0)
        {
            url = HubRestAPIParams.ShareDataUrl;
            if(parts[2].compareToIgnoreCase("fetchData") == 0)
            {
                url = url + parts[3];
            }
            else if (parts[2].compareToIgnoreCase("shareData") == 0)
            {
                try {
                    post_params.put("value",parts[3]);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            else if (parts[2].compareToIgnoreCase("historicalData") == 0)
            {
                url = HubRestAPIParams.HistoricalUrl;
                Calendar cal = Calendar.getInstance();
                Date currentLocalTime = cal.getTime();
                DateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                String localTime = date.format(currentLocalTime);
                try {
                    post_params.put("value",parts[3]);
                    post_params.put("name",parts[4]);
                    post_params.put("namespace",parts[5]);
                    post_params.put("unit",parts[6]);
                    post_params.put("type",0);
                    post_params.put("time",localTime);
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_ENERGY) == 0)
        {
            url = HubRestAPIParams.EnergyDataUrl;
            if(parts[2].compareToIgnoreCase("energyLevel") == 0) {
                if (parts[3].compareToIgnoreCase("0") == 0) {
                    url = url + "energy_type=ELECTRICITY";
                } else if (parts[3].compareToIgnoreCase("1") == 0) {
                    url = url + "energy_type=GAS";
                } else if (parts[3].compareToIgnoreCase("2") == 0) {
                    url = url + "energy_type=SOLAR";
                }
            }

            if(parts[4].compareToIgnoreCase("local") == 0)
            {
                url = url + "&location_uid="+ HubRestAPIParams.SchoolID;
            }
            else
            {
                url = url + "&location_uid="+ parts[4];
            }

            if(parts.length > 5)
            if(parts[5].compareToIgnoreCase("historical") == 0)
            {
                Calendar cal = Calendar.getInstance();
                Date currentLocalTime = cal.getTime() ,prevDateTime = cal.getTime();
                int intParam = Integer.parseInt(parts[7]);
                DateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss z");
                if(parts[6].compareToIgnoreCase("hour") == 0)
                {
                    cal.add(Calendar.HOUR_OF_DAY, - intParam);
                    prevDateTime = cal.getTime();
                }
                else if(parts[6].compareToIgnoreCase("day") == 0)
                {
                    cal.add(Calendar.DATE, - intParam);
                    prevDateTime = cal.getTime();
                }
                else if(parts[6].compareToIgnoreCase("week") == 0)
                {
                    cal.add(Calendar.DATE, - (intParam * 7));
                    prevDateTime = cal.getTime();
                }
                else if(parts[6].compareToIgnoreCase("month") == 0)
                {
                    cal.add(Calendar.DATE, - (intParam * 30));
                    prevDateTime = cal.getTime();
                }

                url = url + "&from=" + date.format(prevDateTime) + "&to=" + date.format(currentLocalTime);
            }

        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_CARBON) == 0)
        {
            url = HubRestAPIParams.CarbonDataUrl;
            if(parts[2].compareToIgnoreCase("index") == 0) {
                url = url + "intensity";
            }
            else if(parts[2].compareToIgnoreCase("value") == 0) {
                url = url + "intensity";
            }
            else if(parts[2].compareToIgnoreCase("genmix") == 0) {
                url = url + "generation";
            }
        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_ISS) == 0)
        {
            url = HubRestAPIParams.IssDataUrl;
            if(parts[2].compareToIgnoreCase("location") == 0) {
            }
            else if(parts[2].compareToIgnoreCase("solarlocation") == 0) {
            }
            else if(parts[2].compareToIgnoreCase("velocity") == 0) {
            }
            else if(parts[2].compareToIgnoreCase("altitude") == 0) {
            }
            else if(parts[2].compareToIgnoreCase("daynum") == 0) {
            }
        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_ENERGY_METER) == 0)
        {
            url = HubRestAPIParams.HistoricalUrl;
                Calendar cal = Calendar.getInstance();
                Date currentLocalTime = cal.getTime();
                DateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

                String localTime = date.format(currentLocalTime);
                try {
                    post_params.put("value",String.valueOf(packet.getIntData()));
                    post_params.put("name",parts[2]);
                    post_params.put("namespace","energy");
                    post_params.put("unit","watt");
                    post_params.put("type",packet.getIntExtraType());
                    post_params.put("time",localTime);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

        }
        else if (parts[1].compareToIgnoreCase(HubRestAPIParams.PKG_WEATHER) == 0)
        {

        }




            if (packet.getRequestType() == RadioPacket.REQUEST_TYPE_POST_REQUEST) {


                RadioJsonObjectRequest jsonObjReq = new RadioJsonObjectRequest(Request.Method.POST,
                        url, post_params,
                        packet,
                        radioListener,
                        responseLister,
                        new Response.ErrorListener() {
                            @Override
                            public void onErrorResponse(VolleyError error) {
                                //Failure Callback
                            }
                        }) {
                    /**
                     * Passing some request headers*
                     */
                    @Override
                    public Map getHeaders() throws AuthFailureError {
                        HashMap headers = new HashMap();
                        headers.put("Content-Type", "application/json; charset=utf-8");
                        headers.put("school-id", HubRestAPIParams.SchoolID);
                        headers.put("pi-id", HubRestAPIParams.HubID);
                        return headers;
                    }
                };

                // Adding the request to the queue along with a unique string tag
                addToRequestQueue(jsonObjReq, "postRequest");
            }



        if (packet.getRequestType() == RadioPacket.REQUEST_TYPE_GET_REQUEST) {

            RadioJsonObjectRequest jsonObjReq = new RadioJsonObjectRequest(Request.Method.GET,
                    url, null,packet,
                    radioListener,
                    responseLister,
                    new Response.ErrorListener() {
                        @Override
                        public void onErrorResponse(VolleyError error) {
                            //Failure Callback
                        }
                    }){
                /**
                 * Passing some request headers*
                 */
                @Override
                public Map getHeaders() throws AuthFailureError {
                    HashMap headers = new HashMap();
                    headers.put("Content-Type", "application/json; charset=utf-8");
                    headers.put("school-id", HubRestAPIParams.SchoolID);
                    headers.put("pi-id", HubRestAPIParams.HubID);
                    return headers;
                }
            };

            // Adding the request to the queue along with a unique string tag
            addToRequestQueue(jsonObjReq, "getRequest");
        }

    }

    VolleyResponse radioListener = new VolleyResponse() {
        @Override
        public void onResponse(JSONObject object, RadioPacket tag) {
            RadioPacket returnPacket = new RadioPacket(tag);
            if(object != null) {
                try {
                    Object data;
                    if(returnPacket.getRequestType() == RadioPacket.REQUEST_TYPE_POST_REQUEST)
                    {
                        data = object.get("response");
                        returnPacket.append(data);
                    }
                    else
                    {
                        data = object.get("value");
                        returnPacket.append(String.valueOf(data));
                    }
                } catch (JSONException e) {
                    e.printStackTrace();
                    returnPacket.append("OK");
                    mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));
                }
            }
            else
            {
                returnPacket.append("OK");
            }
            mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));
        }

        @Override
        public void onError(VolleyError error, RadioPacket tag) {
            RadioPacket returnPacket = new RadioPacket(tag);
            returnPacket.append(error.toString());
            mSerialIoManager.writeAsync(returnPacket.marshall((byte)0));

        }
    };

    Response.Listener responseLister = new Response.Listener() {
        @Override
        public void onResponse(Object response) {
        }
    };

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

    /*
    Create a getRequestQueue() method to return the instance of
    RequestQueue.This kind of implementation ensures that
    the variable is instatiated only once and the same
    instance is used throughout the application
    */
    public RequestQueue getRequestQueue() {
        if (requestQueue == null)
            requestQueue = Volley.newRequestQueue(getApplicationContext());
        return requestQueue;
    }
    /*
    public method to add the Request to the the single
    instance of RequestQueue created above.Setting a tag to every
    request helps in grouping them. Tags act as identifier
    for requests and can be used while cancelling them
    */
    public void addToRequestQueue(Request request, String tag) {
        request.setTag(tag);
        getRequestQueue().add(request);
    }
    /*
    Cancel all the requests matching with the given tag
    */
    public void cancelAllRequests(String tag) {
        getRequestQueue().cancelAll(tag);
    }



}
