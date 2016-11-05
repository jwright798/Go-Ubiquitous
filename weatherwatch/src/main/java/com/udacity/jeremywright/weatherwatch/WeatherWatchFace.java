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

package com.udacity.jeremywright.weatherwatch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.wearable.watchface.CanvasWatchFaceService;
import android.support.wearable.watchface.WatchFaceStyle;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.WindowInsets;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
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

    private final String weatherAPIKey = BuildConfig.OPEN_WEATHER_MAP_API_KEY;
    public static final int LOCATION_STATUS_SERVER_DOWN = 1;
    public static final int LOCATION_STATUS_SERVER_INVALID = 2;

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
     * Handler message id for updating the weather periodically in interactive mode.
     */
    private static final int MSG_UPDATE_WEATHER = 1;
    /**
     * Update rate in milliseconds for normal (not ambient and not mute) mode. We update twice
     * a second to blink the colons.
     */
    private static final long NORMAL_UPDATE_RATE_MS = 500;

    @Override
    public Engine onCreateEngine() {
        return new Engine();
    }

    private class Engine extends CanvasWatchFaceService.Engine {

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

        final BroadcastReceiver mTimeZoneReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                mCalendar.setTimeZone(TimeZone.getDefault());
                invalidate();
            }
        };

        //using an async task to fetch the weather that returns the id for the weather from api
        private AsyncTask<Void, Void, Integer> weatherTask;

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
            mUpdateTimeHandler.removeMessages(MSG_UPDATE_WEATHER);
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
                //Go ahead and update weather
                mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
            } else {
                //We don't want to update the weather when we aren't visible
                mUpdateTimeHandler.removeMessages(MSG_UPDATE_WEATHER);
                if (weatherTask != null){
                    weatherTask.cancel(true);
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

            //Go ahead and update the weather
            mUpdateTimeHandler.sendEmptyMessage(MSG_UPDATE_WEATHER);
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
                    case MSG_UPDATE_WEATHER:
                        //cancel current weather task and start a new one
                        if (weatherTask != null){
                            weatherTask.cancel(true);
                        }
                        weatherTask = new WeatherTask();
                        weatherTask.execute();
                        break;
                }
            }
        };


        //Async task to get current weather, returns an int to map to correct image
        //Logic used from SunshineSyncAdapter
        private class WeatherTask extends AsyncTask<Void, Void, Integer>{
            @Override
            protected Integer doInBackground(Void... voids) {

                //need context
                Context context = getBaseContext();

                //Get Location from Shared Prefs (set from the app)
                String location = getLocation();

                // These two need to be declared outside the try/catch
                // so that they can be closed in the finally block.
                HttpURLConnection urlConnection = null;
                BufferedReader reader = null;

                // Will contain the raw JSON response as a string.
                String forecastJsonStr = null;

                String format = "json";
                String units = "imperial";
                String numDays = "1";
                int emptyCode = -1;

                try {
                    // Construct the URL for the OpenWeatherMap query
                    // Possible parameters are avaiable at OWM's forecast API page, at
                    // http://openweathermap.org/API#forecast
                    final String FORECAST_BASE_URL =
                            "http://api.openweathermap.org/data/2.5/forecast/daily?";
                    final String QUERY_PARAM = "q";
                    final String FORMAT_PARAM = "mode";
                    final String UNITS_PARAM = "units";
                    final String APPID_PARAM = "APPID";
                    final String COUNT_PARAM = "cnt";

                    Uri builtUri = Uri.parse(FORECAST_BASE_URL).buildUpon()
                            .appendQueryParameter(QUERY_PARAM, location)
                            .appendQueryParameter(FORMAT_PARAM, format)
                            .appendQueryParameter(UNITS_PARAM, units)
                            .appendQueryParameter(COUNT_PARAM, numDays)
                            .appendQueryParameter(APPID_PARAM, weatherAPIKey)
                            .build();
                    Log.d("JW", builtUri.toString());
                    URL url = new URL(builtUri.toString());

                    // Create the request to OpenWeatherMap, and open the connection
                    urlConnection = (HttpURLConnection) url.openConnection();
                    urlConnection.setRequestMethod("GET");
                    urlConnection.connect();

                    // Read the input stream into a String
                    InputStream inputStream = urlConnection.getInputStream();
                    StringBuffer buffer = new StringBuffer();
                    if (inputStream == null) {
                        // Nothing to do.
                        return emptyCode;
                    }
                    reader = new BufferedReader(new InputStreamReader(inputStream));

                    String line;
                    while ((line = reader.readLine()) != null) {
                        // Since it's JSON, adding a newline isn't necessary (it won't affect parsing)
                        // But it does make debugging a *lot* easier if you print out the completed
                        // buffer for debugging.
                        buffer.append(line + "\n");
                    }

                    if (buffer.length() == 0) {
                        // Stream was empty.  No point in parsing.
                        setLocationStatus(context, LOCATION_STATUS_SERVER_DOWN);
                        return emptyCode;
                    }
                    forecastJsonStr = buffer.toString();
                    getWeatherDataFromJson(forecastJsonStr);
                    //Everything was ok, so return 0
                    return 0;
                } catch (IOException e) {
                    Log.e("JW", "Error ", e);
                    // If the code didn't successfully get the weather data, there's no point in attempting
                    // to parse it.
                    setLocationStatus(context, LOCATION_STATUS_SERVER_DOWN);
                } catch (JSONException e) {
                    Log.e("JW", e.getMessage(), e);
                    e.printStackTrace();
                    setLocationStatus(context, LOCATION_STATUS_SERVER_INVALID);
                } finally {
                    if (urlConnection != null) {
                        urlConnection.disconnect();
                    }
                    if (reader != null) {
                        try {
                            reader.close();
                        } catch (final IOException e) {
                            Log.e("JW", "Error closing stream", e);
                        }
                    }
                }
                return emptyCode;
            }

            @Override
            protected void onPostExecute(Integer integer) {

                if (integer == -1){
                    //we have an error, so we need to clear values and tell the user
                    Log.d("JW", "There was an error");
                    if (weatherAPIKey.isEmpty()){
                        low = "NO API KEY";
                    } else {
                        low = "ERROR";
                    }
                    high = "";
                    weatherCode = -1;
                } else {
                    //refresh the watchface
                    invalidate();
                }
            }
        }

         private void setLocationStatus(Context c, int locationStatus){
            SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(c);
            SharedPreferences.Editor spe = sp.edit();
            spe.putInt(c.getString(R.string.pref_location_status_key), locationStatus);
            spe.commit();
        }

        //Utility method to format the JSON
        private void getWeatherDataFromJson(String forecastJsonStr)
                throws JSONException {
            String degreeSymbol = "\uu00b0";
            //Create JSON object and extract high, low and weathercode
            JSONObject forcastObject = new JSONObject(forecastJsonStr);

            //get the weather symbol
            //sometimes we get multiple weather symbols in the array, so we will jsut grab the first one for the day
            JSONArray listArray = forcastObject.getJSONArray("list");
            JSONObject day = listArray.getJSONObject(0);
            JSONArray weatherArray = day.getJSONArray("weather");
            JSONObject weather = weatherArray.getJSONObject(0);
            weatherCode = weather.getInt("id");
            Log.d("JW", "WEATHERCODE VALUE " + weatherCode);

            //Get the high/low
            JSONObject mainValues = day.getJSONObject("temp");
            high = "H:"+Integer.toString((int)mainValues.getDouble("max"))+degreeSymbol;
            Log.d("JW", "HIGH VALUE " + high);
            low = "L:" + Integer.toString((int)mainValues.getDouble("min"))+degreeSymbol;
            Log.d("JW", "LOW VALUE " + low);
        }

        //Get location from the main app (San Antonio is the default)
        private String getLocation(){
            SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(getBaseContext());
            return prefs.getString(getBaseContext().getString(R.string.pref_location_key),
                    getBaseContext().getString(R.string.pref_location_default));
        }

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
    }
}
