package com.example.android.sunshine.app.sync;

import android.util.Log;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

//From documentation at https://developer.android.com/training/wearables/data-layer/events.html#Listen
//This class will listen for requests from the wearable to fetch new data
public class SunshineWearService extends WearableListenerService {
    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        Log.d("JW", "in onDataChanged SunshineWearService");
        for (DataEvent event : dataEventBuffer){
            if (event.getType() == DataEvent.TYPE_CHANGED){
                if (event.getDataItem().getUri().getPath().equals("/wear-weather")){
                    //We got a request for our watchface, we want to sync the data
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
