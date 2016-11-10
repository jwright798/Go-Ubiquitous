package com.example.android.sunshine.app.sync;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.WearableListenerService;

public class SunshineWearService extends WearableListenerService {
    public SunshineWearService() {
    }

    @Override
    public void onDataChanged(DataEventBuffer dataEventBuffer) {
        for (DataEvent event : dataEventBuffer){
            if (event.getType() == DataEvent.TYPE_CHANGED){
                //Make sure the path is equal to our specific path
                String path = event.getDataItem().getUri().getPath();
                if (path.equals("/weatherWear")){
                    //we can use this service as our context
                    SunshineSyncAdapter.syncImmediately(this);
                }
            }
        }
    }
}
