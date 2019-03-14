package com.samsung.microbit.data.model;

import android.util.Log;

import com.hoho.android.usbserial.util.HexDump;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class RadioPacket {

    private final String TAG = RadioPacket.class.getSimpleName();
    private final String STRUCT_FORMAT = "<HHB";
    private final short HEADER_LEN = 5;

    private final byte SUBTYPE_STRING = 0x01;
    private final byte SUBTYPE_INT = 0x02;
    private final byte SUBTYPE_FLOAT = 0x04;
    private final byte SUBTYPE_EVENT = 0x08;

    public static final short REQUEST_TYPE_GET_REQUEST = 0x01;
    public static final short REQUEST_TYPE_POST_REQUEST = 0x02;
    public static final short REQUEST_TYPE_CLOUD_VARIABLE = 0x04;
    public static final short REQUEST_TYPE_BROADCAST = 0x08;
    public static final short REQUEST_TYPE_HELLO = 0x10;

    private final short REQUEST_STATUS_ACK = 0x20;
    private final short REQUEST_STATUS_ERROR = 0x40;
    private final short REQUEST_STATUS_OK = 0x80;

    private byte ReturnType = SUBTYPE_INT;
    private String header,payload;
    private byte [] uid;
    private byte app_id, name_space;
    private int intData = -1;
    private int intExtraType = -1;
    private float floatData = (float)-1.0;
    private byte request_type = 1;
    private String strData = "";
    private String strDataExtra = "";

    public RadioPacket(RadioPacket radioPacket){
        uid = radioPacket.uid;
        app_id = radioPacket.app_id;
        name_space = radioPacket.name_space;
        request_type = radioPacket.request_type;
    }

    public RadioPacket(byte[] array) {
        byte [] header =  Arrays.copyOfRange(array,0,5);
        byte [] payload = Arrays.copyOfRange(array,5,(array.length));

        app_id =  header[0] ; //Arrays.copyOfRange(header,0,2);
        name_space = header[1] ;
        uid = Arrays.copyOfRange(header,2,4);
        request_type = header[4];

        Log.d(TAG, "uid[0]=" + Integer.toString(uid[0],16) + " uid[1]= " + Integer.toString(uid[1],16) + " app_id=" + app_id + " name_space " + name_space + " request_type=" + request_type);

        unmarshall(payload);
    }

    public int getRequestType()
    {
        return  request_type;
    }
    private void unmarshall(byte[] payload)
    {
        if (payload.length == 0)
            return;

        int subType = payload[0];

        byte [] reminder = Arrays.copyOfRange(payload,1,(payload.length)); //payload.substring(1);

        Log.d(TAG, "reminder length = " + payload.length);

        if((subType & SUBTYPE_STRING) != 0)
        {
            decodeInputParameters(reminder);
            //strData +=   new String(reminder, StandardCharsets.US_ASCII);
        }
        else if ((subType & SUBTYPE_INT) != 0)
        {
            int end = 3;
            if(reminder.length < 4)
            {
                end = reminder.length - 1;
            }
            intData = toLittleEndian( Arrays.copyOfRange(reminder,0,end));

            if(reminder.length > 4) {
                unmarshall(Arrays.copyOfRange(reminder, 4, (reminder.length)));
            }
        }
        else if((subType & SUBTYPE_FLOAT) != 0)
        {
            // unhandled case
            ByteBuffer buff = ByteBuffer.allocate(4);
            buff.order(ByteOrder.LITTLE_ENDIAN);
            buff.put(Arrays.copyOfRange(reminder,0,3));
            floatData = buff.getFloat();
            unmarshall(Arrays.copyOfRange(reminder,4,(reminder.length - 1)));
        }



        Log.d(TAG, "subType=" + subType + " strData=" + strData + " intData=" + intData + " floatData=" + floatData);
    }

    private void decodeInputParameters(byte[] bytes) {

        Log.d(TAG, "Input bytes length = " + bytes.length );
        //traverse to null
        for (int counter = 0; counter < bytes.length ; counter ++)
        {
            if(bytes[counter] == 0)
            {
                // end of string
                byte [] reminder = Arrays.copyOfRange(bytes,0,counter);
                if(strData.length() == 0)
                {
                    strData += new String(reminder, StandardCharsets.US_ASCII);
                }
                else {
                    strData += new String(reminder, StandardCharsets.US_ASCII) + "/";
                }

                Log.d(TAG, "new string = " + strData );

                if(counter == (bytes.length -1))
                    return;

                if(bytes[counter + 1] == 1)
                {
                    decodeInputParameters(Arrays.copyOfRange(bytes,counter + 2,(bytes.length)));
                    break;
                }
                else if(bytes[counter + 1] == 2)
                {
                    // hoping only one int data per string and that is end
                    intData = toLittleEndian( Arrays.copyOfRange(bytes,counter + 2,counter + 6));
                    Log.d(TAG, "length of remaining byte data " + (bytes.length - counter - 6));
                    if((bytes.length -counter -6) > 4)
                    {
                        intExtraType = toLittleEndian( Arrays.copyOfRange(bytes,counter + 6,counter + 10));
                    }
                    break;
                }
                else if(bytes[counter + 1] == -64)
                {
                    break; // end mark for input data
                }
            }
            if(bytes[counter] == -64)
            {
                if(request_type == RadioPacket.REQUEST_TYPE_HELLO)
                {
                    byte [] reminder = Arrays.copyOfRange(bytes,0,counter);
                    if(strDataExtra.length() == 0)
                    {
                        strDataExtra += new String(reminder, StandardCharsets.US_ASCII);
                    }
                    else {
                        strDataExtra += new String(reminder, StandardCharsets.US_ASCII) + "/";
                    }

                    Log.d(TAG, "new strDataExtra = " + strDataExtra );
                }
            }
        }
    }

    private int toLittleEndian(byte[] bytes) {
        if(bytes.length == 4) {
            return (bytes[0] | (bytes[1] << 8) | (bytes[2] << 16) | (bytes[3] << 24));
        }

        if(bytes.length == 3) {
            return (bytes[0] | (bytes[1] << 8) | (bytes[2] << 16));
        }

        if(bytes.length == 2) {
            return (bytes[0] | (bytes[1] << 8));
        }

        if(bytes.length == 1) {
            return (bytes[0]);
        }

        return 0;
    }



    public byte[] marshall(byte status)
    {
        byte return_code;
        byte[] endBytesString = new byte[] {0,(byte)0xc0};
        byte[] endBytesInt = new byte[] {(byte)0xc0};
        byte[] endBytes = endBytesInt;
        if (status == 0)
            return_code = (byte)REQUEST_STATUS_OK;
        else
            return_code = REQUEST_STATUS_ERROR;

        // header preparation
        ByteBuffer pump_on_buf = ByteBuffer.allocate(5);
        pump_on_buf.order(ByteOrder.LITTLE_ENDIAN);
        pump_on_buf.put(app_id);
        pump_on_buf.put(name_space);
        pump_on_buf.put(uid);
        request_type = (byte)(request_type | return_code);
        pump_on_buf.put(request_type);
        byte[] pump_on = pump_on_buf.array();

        // payload preparation
        ByteBuffer buf =  ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        byte[] payload = null;

        if(ReturnType == SUBTYPE_INT){
            buf.putInt(intData);
            payload = buf.array();
        }
        else if (ReturnType == SUBTYPE_FLOAT){
            buf.putFloat(floatData);
            payload = buf.array();
        }
        else if (ReturnType == SUBTYPE_STRING){
            payload = strData.getBytes();
            endBytes = endBytesString;
        }

        if(request_type == REQUEST_TYPE_HELLO)
        {
            buf.putInt(intData);
            payload = buf.array();
            HubRestAPIParams.HubID = strDataExtra;
            HubRestAPIParams.SchoolID = strData;
        }


        ByteBuffer retBuf = ByteBuffer.allocate(pump_on.length + payload.length + 1 + endBytes.length);
        retBuf.order(ByteOrder.LITTLE_ENDIAN);
        retBuf.put(pump_on);
        retBuf.put(ReturnType);
        retBuf.put(payload);
        retBuf.put(endBytes);

        Log.d(TAG, "uid[0]=" + Integer.toString(uid[0],16) + " uid[1]= " + Integer.toString(uid[1],16) + " app_id=" + app_id + " name_space " + name_space + " ReturnType=" + ReturnType
        + " request_type "  + Integer.toString(request_type,16));

        Log.d(TAG,"going hex " + HexDump.dumpHexString(retBuf.array()));
        return retBuf.array();
    }

    public void append (Object data)
    {
        if(data instanceof String) {
            strData = (String) data;
            ReturnType  =  SUBTYPE_STRING;
        }
        else if(data instanceof Integer) {
            intData = (int) data;
            ReturnType =  SUBTYPE_INT;
        }
        else  if(data instanceof Float) {
            floatData = (Float) data;
            ReturnType =  SUBTYPE_FLOAT;
        }
    }

    public String getStringData()
    {
        return strData;
    }

    public int getIntData()
    {
        return intData;
    }

    public int getIntExtraType()
    {
        return intExtraType;
    }

    public float getFloatData()
    {
        return floatData;
    }
}