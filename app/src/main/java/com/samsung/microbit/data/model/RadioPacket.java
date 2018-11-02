package com.samsung.microbit.data.model;

import android.util.Log;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

public class RadioPacket {
    private final String TAG = RadioPacket.class.getSimpleName();
    private final String STRUCT_FORMAT = "<HHB";
    private final short HEADER_LEN = 5;

    private final short SUBTYPE_STRING = 0x01;
    private final short SUBTYPE_INT = 0x02;
    private final short SUBTYPE_FLOAT = 0x04;
    private final short SUBTYPE_EVENT = 0x08;

    private final short REQUEST_TYPE_GET_REQUEST = 0x01;
    private final short REQUEST_TYPE_POST_REQUEST = 0x02;
    private final short REQUEST_TYPE_CLOUD_VARIABLE = 0x04;
    private final short REQUEST_TYPE_BROADCAST = 0x08;

    private final short REQUEST_STATUS_ACK = 0x20;
    private final short REQUEST_STATUS_ERROR = 0x40;
    private final short REQUEST_STATUS_OK = 0x80;

    private String header,payload;
    private byte [] uid, app_id;
    private int intData = 0;
    private float floatData =0;
    private byte request_type = 1;
    private String strData = "";

    public RadioPacket(RadioPacket radioPacket){
        uid = radioPacket.uid;
        app_id = radioPacket.app_id;
        request_type = radioPacket.request_type;
    }

    public RadioPacket(byte[] array) {
        byte [] header =  Arrays.copyOfRange(array,0,5);
        byte [] payload = Arrays.copyOfRange(array,5,(array.length-1));

        uid =  Arrays.copyOfRange(header,0,2);
        app_id = Arrays.copyOfRange(header,2,4);
        request_type = header[4];

        Log.d(TAG, "uid=" + uid[0] + " " + uid[1] + " app_id=" + app_id[0] + " " + app_id[1] + " request_type=" + request_type);

        unmarshall(payload);
    }

    private void unmarshall(byte[] payload)
    {
        if (payload.length == 0)
            return;

        int subType = payload[0];
        byte [] reminder = Arrays.copyOfRange(payload,1,(payload.length - 1)); //payload.substring(1);

        if((subType & SUBTYPE_STRING) != 0)
        {

            strData = reminder.toString();
        }
        else if ((subType & SUBTYPE_INT) != 0)
        {
            intData = toLittleEndian( Arrays.copyOfRange(reminder,0,3));
            unmarshall(Arrays.copyOfRange(reminder,4,(reminder.length - 1)));
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

    private int toLittleEndian(byte[] bytes) {
        return ( bytes[0] | (bytes[1] << 8) | (bytes[2] << 16) | (bytes[3] << 24) );
    }



    public byte[] marshall(byte status)
    {
        byte return_code;
        byte[] endBytes = new byte[] {0x00,(byte)0xc0};
        if (status == 0)
            return_code = 0;
        else if (status == REQUEST_STATUS_OK)
            return_code = REQUEST_STATUS_ACK;
        else
            return_code = REQUEST_STATUS_ERROR;

        // header preparation
        ByteBuffer pump_on_buf = ByteBuffer.allocate(5);
        pump_on_buf.order(ByteOrder.LITTLE_ENDIAN);
        pump_on_buf.put(uid);
        pump_on_buf.put(app_id);
        request_type = (byte)(request_type | return_code);
        pump_on_buf.put(request_type);
        byte[] pump_on = pump_on_buf.array();

        // payload preparation
        ByteBuffer buf =  ByteBuffer.allocate(4);
        buf.order(ByteOrder.LITTLE_ENDIAN);
        byte[] payload = null;
        byte type = 0;
        if(intData != 0){

            buf.putInt(intData);
            payload = buf.array();

            type =  SUBTYPE_INT;
        }
        else if (floatData != 0){
            buf.putFloat(floatData);
            payload = buf.array();
            type =  SUBTYPE_FLOAT;

        }
        else if (strData.length() > 0){
            payload = strData.getBytes();
            type =  SUBTYPE_STRING;

        }


        ByteBuffer retBuf = ByteBuffer.allocate(pump_on.length + payload.length + 2 + 1);
        retBuf.put(pump_on);
        retBuf.put(type);
        retBuf.put(payload);
        retBuf.put(endBytes);

        return retBuf.array();
    }

    public void append (Object data)
    {
        if(data instanceof String) {
            strData = (String) data;
        }
        else if(data instanceof Integer) {
            intData = (int) data;
        }
        else  if(data instanceof Float) {
            floatData = (Float) data;
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

    public float getFloatData()
    {
        return floatData;
    }
}