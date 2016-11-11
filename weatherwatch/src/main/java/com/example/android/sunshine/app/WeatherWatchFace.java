/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.sunshine.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.api.GoogleApiClient;
import com.google.android.gms.common.api.PendingResult;
import com.google.android.gms.common.api.ResultCallback;
import com.google.android.gms.wearable.DataApi;
import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMap;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

/**
 * Digital watch face with seconds. In ambient mode, the seconds aren't displayed. On devices with
 * low-bit ambient mode, the text is drawn without anti-aliasing in ambient mode.
 */
public class WeatherWatchFace extends CanvasWatchFaceService {

    private static final Typeface NORMAL_TYPEFACE =
            Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL);

    /**
     * Update rate in milliseconds for interactive mode. We update once a second since seconds are
     * displayed in interactive mode.
     */
    private static final long INTERACTIVE_UPDATE_RATE_MS = TimeUnit.SECONDS.toMillis(1);

    /**
     * Handler message id for updating the time periodically in interactive mode.
     */
    private static final int MSG_UPDATE_TIME = 0;
    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine implements DataApi.DataListener,
            GoogleApiClient.ConnectionCallbacks,
            GoogleApiClient.OnConnectionFailedListener {

        //final Handler mUpdateTimeHandler = new EngineHandler(this);
        boolean mRegisteredTimeZoneReceiver = false;
        Paint mBackgroundPaint;
        Paint mTimePaint;
        Paint mDatePaint;
        boolean mAmbient;
        Calendar mCalendar;

        //Date variables
        Date mDate;
        SimpleDateFormat mDateFormat;

        //Strings for high and low values
        String high;
        String low;
        int weatherCode = 0;
        int weatherResourceId = -1;
        boolean isRound = false;

        //dimens for drawing on the screen
        float mXTimeOffset;
        float mYTimeOffset;
        float mXDateOffset;
        float mYDateOffset;
        float mXTempOffset;
        float mYTempOffset;
        float mXImageOffset;
        float mYImageOffset;

        //adding GoogleApiClient
        private GoogleApiClient client;

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        static final int MSG_UPDATE_TIME = 0;

        /** How often {@link #mUpdateTimeHandler} ticks in milliseconds. */
        long mInteractiveUpdateRateMs = NORMAL_UPDATE_RATE_MS;

        /**
         * Whether the display supports fewer bits for each color in ambient mode. When true, we
         * disable anti-aliasing in ambient mode.
         */
        boolean mLowBitAmbient;

        @Override
        public void onCreate(SurfaceHolder holder) {
            super.onCreate(holder);

            setWatchFaceStyle(new WatchFaceStyle.Builder(WeatherWatchFace.this)
                    .setCardPeekMode(WatchFaceStyle.PEEK_MODE_VARIABLE)
                    .setBackgroundVisibility(WatchFaceStyle.BACKGROUND_VISIBILITY_INTERRUPTIVE)
                    .setShowSystemUiTime(false)
                    .setAcceptsTapEvents(true)
                    .build());

            //Initializing the client
            client = new GoogleApiClient.Builder(WeatherWatchFace.this)
                    .addApi(Wearable.API)
                    .addConnectionCallbacks(this)
                    .addOnConnectionFailedListener(this)
                    .build();
            //Try and connect on start up
            client.connect();

            Resources resources = WeatherWatchFace.this.getResources();

            //set up initial Y offsets and colors
            mYTimeOffset = resources.getDimension(R.dimen.digital_y_time_offset);
            mYDateOffset = resources.getDimension(R.dimen.digital_y_date_offset);
            mYTempOffset = resources.getDimension(R.dimen.digital_y_temp_offset);
            mYImageOffset = resources.getDimension(R.dimen.digital_y_image_offset);

            mBackgroundPaint = new Paint();
            mBackgroundPaint.setColor(resources.getColor(R.color.background));

            mTimePaint = new Paint();
            mTimePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mDatePaint = new Paint();
            mDatePaint = createTextPaint(resources.getColor(R.color.digital_text));

            mCalendar = Calendar.getInstance();
        }

        @Override
        public void onDestroy() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            super.onDestroy();
        }

        private Paint createTextPaint(int textColor) {
            Paint paint = new Paint();
            paint.setColor(textColor);
            paint.setTypeface(NORMAL_TYPEFACE);
            paint.setAntiAlias(true);
            return paint;
        }

        @Override
        public void onVisibilityChanged(boolean visible) {
            super.onVisibilityChanged(visible);

            if (visible) {
                registerReceiver();

                // Update time zone in case it changed while we weren't visible.
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            } else {
                //Since the visibility changed, we should disconnect the client
                if (client != null && client.isConnected()) {
                    client.disconnect();
                }
                unregisterReceiver();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();
        }

        private void registerReceiver() {
            if (mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = true;
            IntentFilter filter = new IntentFilter(Intent.ACTION_TIMEZONE_CHANGED);
            WeatherWatchFace.this.registerReceiver(mTimeZoneReceiver, filter);
        }

        private void unregisterReceiver() {
            if (!mRegisteredTimeZoneReceiver) {
                return;
            }
            mRegisteredTimeZoneReceiver = false;
            WeatherWatchFace.this.unregisterReceiver(mTimeZoneReceiver);
        }

        @Override
        public void onApplyWindowInsets(WindowInsets insets) {
            super.onApplyWindowInsets(insets);

            // Load resources that have alternate values for round watches.
            Resources resources = WeatherWatchFace.this.getResources();
            isRound = insets.isRound();

            //Set up time
            mXTimeOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_time_round : R.dimen.digital_x_time_offset);
            float textSize = resources.getDimension(isRound
                    ? R.dimen.digital_time_size_round : R.dimen.digital_time_size);
            mTimePaint.setTextSize(textSize);


            //Set up date
            mXDateOffset = resources.getDimension(isRound
                    ? R.dimen.digital_x_offset_date_round : R.dimen.digital_x_date_offset);
            float dateTextSize = resources.getDimension(R.dimen.digital_date_size);
            mDatePaint.setTextSize(dateTextSize);


            //Set up weather
            mXTempOffset = resources.getDimension(isRound
                    ? R.dimen.digital_temp_x_offset_round : R.dimen.digital_temp_x_offset);
            mXImageOffset = resources.getDimension(isRound
                    ? R.dimen.digital_image_x_offset_round : R.dimen.digital_image_x_offset);
        }

        @Override
        public void onPropertiesChanged(Bundle properties) {
            super.onPropertiesChanged(properties);
            mLowBitAmbient = properties.getBoolean(PROPERTY_LOW_BIT_AMBIENT, false);
        }

        @Override
        public void onTimeTick() {
            super.onTimeTick();
            invalidate();
        }

        @Override
        public void onAmbientModeChanged(boolean inAmbientMode) {
            super.onAmbientModeChanged(inAmbientMode);
            if (mAmbient != inAmbientMode) {
                mAmbient = inAmbientMode;
                if (mLowBitAmbient) {
                    mTimePaint.setAntiAlias(!inAmbientMode);
                }
                invalidate();
            }

            // Whether the timer should be running depends on whether we're visible (as well as
            // whether we're in ambient mode), so we may need to start or stop the timer.
            updateTimer();

        }

        /**
         * Captures tap event (and tap type) and toggles the background color if the user finishes
         * a tap.
         */
        @Override
        public void onTapCommand(int tapType, int x, int y, long eventTime) {
            switch (tapType) {
                case TAP_TYPE_TOUCH:
                    // The user has started touching the screen.
                    break;
                case TAP_TYPE_TOUCH_CANCEL:
                    // The user has started a different gesture or otherwise cancelled the tap.
                    break;
                case TAP_TYPE_TAP:
                    // The user has completed the tap gesture.
                    // TODO: Add code to handle the tap gesture.
                   // Toast.makeText(getApplicationContext(), R.string.message, Toast.LENGTH_SHORT)
                   //         .show();
                    break;
            }
            invalidate();
        }

        @Override
        public void onDraw(Canvas canvas, Rect bounds) {
            // Draw the background.
            if (isInAmbientMode()) {
                canvas.drawColor(Color.BLACK);
            } else {
                canvas.drawRect(0, 0, bounds.width(), bounds.height(), mBackgroundPaint);
            }

            // Draw H:MM (I prefer 12 hr mode)
            long now = System.currentTimeMillis();
            mCalendar.setTimeInMillis(now);

            String text = String.format("%d:%02d", mCalendar.get(Calendar.HOUR),
                    mCalendar.get(Calendar.MINUTE));
            canvas.drawText(text, mXTimeOffset+20, mYTimeOffset, mTimePaint);

            //Draw the current date format from sample screenshots
            mDate= new Date();
            mDate.setTime(now);
            mDateFormat = new SimpleDateFormat("E, MMM d y", Locale.getDefault());
            mDateFormat.setCalendar(mCalendar);
            String currentDate = mDateFormat.format(mDate).toUpperCase();
            canvas.drawText(currentDate, mXDateOffset+10, mYDateOffset+20, mDatePaint);

            //Draw the weather
            //set the icon to use
            weatherResourceId = getWeatherResourceFromCode(weatherCode);

            //Don't show image in Ambient mode
            if (!isInAmbientMode() && weatherResourceId != -1) {
                //Get the resource, has to be in bitmap for for the canvas to draw
                Bitmap iconDrawable = BitmapFactory.decodeResource(getResources(), weatherResourceId);
                canvas.drawBitmap(iconDrawable, mXImageOffset, mYImageOffset, null);
            }

            //Set the high and low values
            if(high != null && low != null) {
                canvas.drawText(low, mXTempOffset, mYTempOffset, mDatePaint);

                //for some reason, I needed extra padding for square faces, so I added this condition
                if (isRound) {
                    canvas.drawText(high, mXTempOffset + 80, mYTempOffset, mDatePaint);
                } else{
                    canvas.drawText(high, mXTempOffset + 90, mYTempOffset, mDatePaint);
                }
            }
        }

        /**
         * Starts the {@link #mUpdateTimeHandler} timer if it should be running and isn't currently
         * or stops it if it shouldn't be running but currently is.
         */
        private void updateTimer() {
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_TIME);
            if (shouldTimerBeRunning()) {
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_TIME);
            }
        }

        /**
         * Returns whether the {@link #mUpdateTimeHandler} timer should be running. The timer should
         * only run when we're visible and in interactive mode.
         */
        private boolean shouldTimerBeRunning() {
            return isVisible() && !isInAmbientMode();
        }

        /**
         * Handle updating the time periodically in interactive mode.
         */
        private void handleUpdateTimeMessage() {
            invalidate();
            if (shouldTimerBeRunning()) {
                long timeMs = System.currentTimeMillis();
                long delayMs = INTERACTIVE_UPDATE_RATE_MS
                        - (timeMs % INTERACTIVE_UPDATE_RATE_MS);
                mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
            }
        }

        //Creating a new custom Handler to take care of watchface events
        //from sample code (DigitalWatchFaceService.java)
        final Handler mUpdateTimeHandler = new Handler() {
            @Override
            public void handleMessage(Message message) {
                switch (message.what) {
                    case MSG_UPDATE_TIME:
                        invalidate();
                        if (shouldTimerBeRunning()) {
                            long timeMs = System.currentTimeMillis();
                            long delayMs =
                                    mInteractiveUpdateRateMs - (timeMs % mInteractiveUpdateRateMs);
                            mUpdateTimeHandler.sendEmptyMessageDelayed(MSG_UPDATE_TIME, delayMs);
                        }
                        break;
                }
            }
        };

        //Utility method from the Utility class in the main app
        private int getWeatherResourceFromCode(int weatherId){
            // Based on weather code data found at:
            // http://bugs.openweathermap.org/projects/api/wiki/Weather_Condition_Codes
            if (weatherId >= 200 && weatherId <= 232) {
                return R.drawable.ic_storm;
            } else if (weatherId >= 300 && weatherId <= 321) {
                return R.drawable.ic_light_rain;
            } else if (weatherId >= 500 && weatherId <= 504) {
                return R.drawable.ic_rain;
            } else if (weatherId == 511) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 520 && weatherId <= 531) {
                return R.drawable.ic_rain;
            } else if (weatherId >= 600 && weatherId <= 622) {
                return R.drawable.ic_snow;
            } else if (weatherId >= 701 && weatherId <= 761) {
                return R.drawable.ic_fog;
            } else if (weatherId == 761 || weatherId == 781) {
                return R.drawable.ic_storm;
            } else if (weatherId == 800) {
                return R.drawable.ic_clear;
            } else if (weatherId == 801) {
                return R.drawable.ic_light_clouds;
            } else if (weatherId >= 802 && weatherId <= 804) {
                return R.drawable.ic_cloudy;
            }
            return -1;
        }

        //Implementing the Listener methods
        @Override
        public void onConnected(@Nullable Bundle bundle) {
            Log.d("JW", "connection successful");
            Wearable.DataApi.addListener(client, this);
            //We have a new connection, so lets get new data
            requestWeatherData();
        }

        @Override
        public void onConnectionSuspended(int i) {
            Log.d("JW", "connection suspended");
        }

        @Override
        public void onConnectionFailed(@NonNull ConnectionResult connectionResult) {
            Log.d("JW", "connection failed");
        }

        @Override
        public void onDataChanged(DataEventBuffer dataEventBuffer) {
            Log.e("JW", "in onDataChanged");
            for (DataEvent event : dataEventBuffer){
                Log.d("JW", Integer.toString(event.getType()));
                if (event.getType() == DataEvent.TYPE_CHANGED){
                    //get the uri and path
                    Log.e("JW", event.getDataItem().getUri().getPath());
                    if (event.getDataItem().getUri().getPath().equals("/weather-info")){
                        //WE GOT WEATHER INFO
                        //Get the map
                        DataMap map = DataMapItem.fromDataItem(event.getDataItem()).getDataMap();

                        //Double checking ALL THE VARIABLES
                        if (map.containsKey("weatherId")){
                            weatherCode = map.getInt("weatherId");
                        } else{
                            Log.e("JW", "no weatherId received");
                        }

                        //Added some basic formatting
                        if (map.containsKey("high")){
                            high = "H: "+map.getString("high");
                        } else{
                            Log.e("JW", "no high received");
                        }

                        //Added some basic formatting
                        if (map.containsKey("low")){
                            low = "L: "+ map.getString("low");
                        } else{
                            Log.e("JW", "no low received");
                        }
                    }
                }
            }
            invalidate();
        }

        //Method to poll data from the app (great for 2-way communication)
        private void requestWeatherData() {
            Log.d("JW", "requesting data from watch");
            //Kudos to my reviewer for helping me with this code.
            PutDataMapRequest putDataMapReq = PutDataMapRequest.create("/wear-weather");
            putDataMapReq.getDataMap().putLong("Time",System.currentTimeMillis());
            //Setting as urgent so there is no delay
            PutDataRequest putDataReq = putDataMapReq.asPutDataRequest().setUrgent();

            PendingResult<DataApi.DataItemResult> pendingResult = Wearable.DataApi.putDataItem(client, putDataReq);
            pendingResult.setResultCallback(new ResultCallback<DataApi.DataItemResult>() {
                @Override
                public void onResult(DataApi.DataItemResult dataItemResult) {
                    Log.d("JW", "Sending : " + dataItemResult.getStatus().isSuccess());
                }
            });
        }
    }
}
