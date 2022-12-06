package com.example.androidFit;

import androidx.appcompat.app.AppCompatActivity;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.auth.api.signin.GoogleSignIn;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.FitnessOptions;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResponse;
import com.mikhaellopez.circularprogressbar.CircularProgressBar;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.text.DecimalFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import org.joda.time.DateTime;

public class StepsCounter extends AppCompatActivity implements SensorEventListener
{
    private static final int REQUEST_OAUTH_REQUEST_CODE = 0x1001;
    private static final String TAG = "MainActivity";
    private TextView counter;
    private TextView weekCounter;
    static DataSource ESTIMATED_STEP_DELTAS = new DataSource.Builder()
            .setDataType(DataType.TYPE_STEP_COUNT_DELTA)
            .setType(DataSource.TYPE_DERIVED)
            .setStreamName("estimated_steps")
            .setAppPackageName("com.google.android.gms")
            .build();

    private TextView permanentCount;
    private TextView tempStepCount;
    private TextView burntCalories;
    private TextView targetToBurn;
    private CircularProgressBar circularProgressBar;
    private Button resetButton;
    boolean activityRunning;
    private SensorManager sensorManager;

    private boolean firstSensorReading = true;

    private Context context;
    private float targetCalories;
    private int stepCountAtLastReset;

    DecimalFormat df = new DecimalFormat("0.00");

    private final String TARGET_CAL_FILE = "targetCalories.txt";
    private final String LAST_STEP_CNT_FILE = "lastStepCount.txt";


    @SuppressLint("MissingInflatedId")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_steps_counter);

        context = this;

        permanentCount =  findViewById(R.id.count);
        tempStepCount =  findViewById(R.id.tempStepCount);
        burntCalories = findViewById(R.id.burntCalories);
        targetToBurn = findViewById(R.id.targetToBurn);

        circularProgressBar = findViewById(R.id.circularProgressBar);
        resetButton = findViewById(R.id.resetButton);

        counter = findViewById(R.id.counter);
        weekCounter = findViewById(R.id.week_counter);

        resetButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                targetSetPopup();
            }
        });

        if (hasFitPermission()) {
            readStepCountDelta();
            readHistoricStepCount();
        } else {
            requestFitnessPermission();
        }

        try {
            initActivity();
        } catch (IOException e) {
            e.printStackTrace();
        }

        sensorManager = (SensorManager) getSystemService(Context.SENSOR_SERVICE);




    }
    private boolean hasFitPermission() {
        FitnessOptions fitnessOptions = getFitnessSignInOptions();
        return GoogleSignIn.hasPermissions(GoogleSignIn.getLastSignedInAccount(this), fitnessOptions);
    }
    private void requestFitnessPermission() {
        GoogleSignIn.requestPermissions(
                this,
                REQUEST_OAUTH_REQUEST_CODE,
                GoogleSignIn.getLastSignedInAccount(this),
                getFitnessSignInOptions());
    }

    private FitnessOptions getFitnessSignInOptions() {
        return FitnessOptions.builder()
                .addDataType(DataType.TYPE_STEP_COUNT_CUMULATIVE)
                .addDataType(DataType.TYPE_STEP_COUNT_DELTA)
                .addDataType(DataType.AGGREGATE_DISTANCE_DELTA)
                .build();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == REQUEST_OAUTH_REQUEST_CODE) {
                Log.i(TAG, "Fitness permission granted");
                subscribeStepCount();
                readStepCountDelta(); // Read today's data
                readHistoricStepCount(); // Read last weeks data
            }
        } else {
            Log.i(TAG, "Fitness permission denied");
        }
    }
    private void subscribeStepCount() {
        Fitness.getRecordingClient(this, Objects.requireNonNull(GoogleSignIn.getLastSignedInAccount(this)))
                .subscribe(DataType.TYPE_STEP_COUNT_CUMULATIVE);
    }
    private void readStepCountDelta() {
        if (!hasFitPermission()) {
            requestFitnessPermission();
            return;
        }

        Fitness.getHistoryClient(this, Objects.requireNonNull(GoogleSignIn.getLastSignedInAccount(this)))
                .readDailyTotal(DataType.AGGREGATE_STEP_COUNT_DELTA)
                .addOnSuccessListener(
                        dataSet -> {
                            long total =
                                    dataSet.isEmpty()
                                            ? 0
                                            : dataSet.getDataPoints().get(0).getValue(Field.FIELD_STEPS).asInt();
                            Log.d(TAG, "Total steps: " + total);
                            //display counts on screen
//                            counter.setText(String.format(Locale.ENGLISH, "%d", total));
                        })
                .addOnFailureListener(
                        e -> Log.w(TAG, "There was a problem getting the step count.", e));


    }
    private void readHistoricStepCount() {
        if (!hasFitPermission()) {
            requestFitnessPermission();
            return;
        }

        Fitness.getHistoryClient(this, Objects.requireNonNull(GoogleSignIn.getLastSignedInAccount(this)))
                .readData(queryFitnessData())
                .addOnSuccessListener(
                        this::printData)
                .addOnFailureListener(
                        e -> Log.e(TAG, "There was a problem reading the historic data.", e));
    }

    public static DataReadRequest queryFitnessData() {
        DateTime dt = new DateTime().withTimeAtStartOfDay();
        long endTime = dt.getMillis();
        long startTime = dt.minusWeeks(1).getMillis();

        return new DataReadRequest.Builder()
                .aggregate(ESTIMATED_STEP_DELTAS, DataType.AGGREGATE_STEP_COUNT_DELTA)
                .bucketByTime(1, TimeUnit.DAYS)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();
    }

    public void printData(DataReadResponse dataReadResult) {
        StringBuilder result = new StringBuilder();
        if (dataReadResult.getBuckets().size() > 0) {
            Log.i(TAG, "Number of returned buckets of DataSets is: " + dataReadResult.getBuckets().size());
            for (Bucket bucket : dataReadResult.getBuckets()) {
                List<DataSet> dataSets = bucket.getDataSets();
                for (DataSet dataSet : dataSets) {
                    result.append(formatDataSet(dataSet));
                }
            }
        } else if (dataReadResult.getDataSets().size() > 0) {
            Log.i(TAG, "Number of returned DataSets is: " + dataReadResult.getDataSets().size());
            for (DataSet dataSet : dataReadResult.getDataSets()) {
                result.append(formatDataSet(dataSet));
            }
        }
        Log.d(TAG, "result: " + result);
        weekCounter.setText(result);
    }
    private static String formatDataSet(DataSet dataSet) {
        StringBuilder result = new StringBuilder();

        for (DataPoint dp : dataSet.getDataPoints()) {
            org.joda.time.DateTime sDT = new org.joda.time.DateTime(dp.getStartTime(TimeUnit.MILLISECONDS));
            org.joda.time.DateTime eDT = new org.joda.time.DateTime(dp.getEndTime(TimeUnit.MILLISECONDS));

            result.append(
                    String.format(
                            Locale.ENGLISH,
                            "%s %s to %s %s\n",
                            sDT.dayOfWeek().getAsShortText(),
                            sDT.toLocalTime().toString("HH:mm"),
                            eDT.dayOfWeek().getAsShortText(),
                            eDT.toLocalTime().toString("HH:mm")
                    )
            );

            result.append(
                    String.format(
                            Locale.ENGLISH,
                            "%s: %s %s\n",
                            sDT.dayOfWeek().getAsShortText(),
                            dp.getValue(dp.getDataType().getFields().get(0)),
                            dp.getDataType().getFields().get(0).getName()));
        }

        return String.valueOf(result);
    }


    @Override
    protected void onPostResume() {
        super.onPostResume();
        activityRunning = true;
        Sensor counter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER);
        if(counter != null)
        {
            sensorManager.registerListener(this ,counter , SensorManager.SENSOR_DELAY_UI);
        }

        else
            Toast.makeText(this ,  "The device doesn't have support to count steps" , Toast.LENGTH_LONG).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        activityRunning = false;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if(activityRunning)
        {
            permanentCount.setText(String.valueOf((int)event.values[0]));
            int steps = (int)event.values[0] - stepCountAtLastReset;
            tempStepCount.setText(String.valueOf(steps));
            burntCalories.setText( df.format( (float)steps/20));

            if(firstSensorReading) {
                firstSensorReading = false;
                animateProgressBar();
            }

            circularProgressBar.setProgress(Math.min((float)steps/20 ,  targetCalories));

        }

    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
    }

    void animateProgressBar()
    {
      /*  System.out.println("-------------------------------------------------------------------------------------");
        System.out.println("permCount = "+permanentCount.getText().toString());
        System.out.println("last step count = "+ stepCountAtLastReset);
*/
        int steps = Integer.parseInt(permanentCount.getText().toString()) - stepCountAtLastReset;
        circularProgressBar.setProgressWithAnimation((float)steps/20 , 1000L);
    }

    void initProgressBar()
    {
        circularProgressBar.setProgressMax(targetCalories);
        circularProgressBar.setProgress(0);
   }

   void initActivity() throws IOException {
       importStepCountAtLastReset();
       importTargetCalories();

       targetToBurn.setText(String.valueOf(targetCalories));
       int steps = Integer.parseInt(permanentCount.getText().toString()) - stepCountAtLastReset;
       tempStepCount.setText(String.valueOf(steps));
       burntCalories.setText( df.format( (float)steps/20));

       initProgressBar();
   }


    private void targetSetPopup()
    {
        final EditText displayText;
        final Button setButton;

        final Dialog dialog = new Dialog(context);

        dialog.setContentView(R.layout.set_target_to_burn);

        displayText = dialog.findViewById(R.id.displayText);

        setButton =  dialog.findViewById(R.id.setButton);




        setButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                try {
                    String calStr = displayText.getText().toString();

                    if(! calStr.equals("")) {
                        exportStepCountAtLastReset(permanentCount.getText().toString());
                        exportTargetCalories(calStr);

                        tempStepCount.setText("0");

                        initActivity();

                        dialog.dismiss();
                    }

                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        });

        dialog.show();

    }


    void importTargetCalories() throws IOException {
        FileInputStream fis = null;

        try {
            fis = openFileInput(TARGET_CAL_FILE);
            BufferedReader reader = new BufferedReader(new BufferedReader(new InputStreamReader(fis)));
            targetCalories = Float.parseFloat(reader.readLine());

        } catch (Exception e) {


            targetCalories = 0;
            e.printStackTrace();
        }
        finally {
            if(fis != null)
            {
                fis.close();
            }
        }
    }

    void importStepCountAtLastReset() throws IOException {
        FileInputStream fis = null;

        try {
            fis = openFileInput(LAST_STEP_CNT_FILE);
            BufferedReader reader = new BufferedReader(new BufferedReader(new InputStreamReader(fis)));
            stepCountAtLastReset = Integer.parseInt(reader.readLine());

        } catch (Exception e) {

            stepCountAtLastReset = 0;
            e.printStackTrace();
        }
        finally {
            if(fis != null)
            {
                fis.close();
            }
        }
    }

    void exportTargetCalories(String str) throws IOException {
        FileOutputStream fos = null;
        str = str+"\n";

        try {
            fos = openFileOutput(TARGET_CAL_FILE , MODE_PRIVATE);
            fos.write(str.getBytes());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            if(fos != null)
            {
                fos.close();
            }
        }
    }

    void exportStepCountAtLastReset(String str) throws IOException {
        FileOutputStream fos = null;

        try {
            fos = openFileOutput(LAST_STEP_CNT_FILE , MODE_PRIVATE);
            fos.write(str.getBytes());
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
        finally {
            if(fos != null)
            {
                fos.close();
            }
        }
    }


}
