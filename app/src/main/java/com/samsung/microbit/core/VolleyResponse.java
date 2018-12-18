package com.samsung.microbit.core;

import com.android.volley.VolleyError;
import com.samsung.microbit.data.model.RadioPacket;

import org.json.JSONObject;

public interface VolleyResponse {
    void onResponse(JSONObject object, RadioPacket tag);

    void onError(VolleyError error, RadioPacket tag);
}
