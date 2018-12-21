package com.samsung.microbit.core;

import com.android.volley.AuthFailureError;
import com.android.volley.Cache;
import com.android.volley.NetworkResponse;
import com.android.volley.ParseError;
import com.android.volley.Response;
import com.android.volley.VolleyError;
import com.android.volley.toolbox.HttpHeaderParser;
import com.android.volley.toolbox.JsonObjectRequest;
import com.samsung.microbit.data.model.RadioPacket;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;


public class RadioJsonObjectRequest extends JsonObjectRequest {

    private VolleyResponse volleyResponse;
    private RadioPacket tagPacket;


    public RadioJsonObjectRequest(int method, String url, JSONObject jsonObject, RadioPacket tag, VolleyResponse volleyResponse, Response.Listener<JSONObject> listener, Response.ErrorListener errorListener) {
        super(method, url, jsonObject, listener, errorListener);
        this.volleyResponse = volleyResponse;
        this.tagPacket= tag;
    }

    @Override
    protected void deliverResponse(JSONObject response) {
        super.deliverResponse(response);
        volleyResponse.onResponse(response, tagPacket);
    }

    @Override
    protected Response<JSONObject> parseNetworkResponse(NetworkResponse response) {
        if(response.statusCode == 200)
        {
            if(tagPacket.getRequestType() == RadioPacket.REQUEST_TYPE_POST_REQUEST) {
                JSONObject resObject = new JSONObject();
                try {
                    String json = new String(
                            response.data,
                            HttpHeaderParser.parseCharset(response.headers));
                    resObject.put("response", json);
                } catch (UnsupportedEncodingException e) {
                    return Response.error(new ParseError(e));
                } catch (JSONException e1) {
                    e1.printStackTrace();
                }
                return Response.success(resObject, HttpHeaderParser.parseCacheHeaders(response));
            }
            else
            {
                return super.parseNetworkResponse(response);
            }
        }
        else {
            return super.parseNetworkResponse(response);
        }
    }

    @Override
    public void deliverError(VolleyError error) {
        super.deliverError(error);
        volleyResponse.onError(error, tagPacket);
    }
}
