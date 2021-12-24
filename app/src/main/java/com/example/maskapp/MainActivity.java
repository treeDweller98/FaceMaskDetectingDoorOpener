package com.example.maskapp;

import static android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
import static android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.res.AssetFileDescriptor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbManager;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.felhr.usbserial.UsbSerialDevice;
import com.felhr.usbserial.UsbSerialInterface;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.util.HashMap;
import java.util.Map;


public class MainActivity extends AppCompatActivity {

    private final byte PERSON_DETECTED = 84;             // 'T' ARDUINO MUST SEND THIS TO TRIGGER CAMERA
    private final byte YES_MASKED= 89;                   // 'Y' SEND TO ARDUINO TO OPEN DOOR
    private final byte NOT_MASKED = 78;                  // 'N' SEND TO ARDUINO TO DENY ENTRY

    public final int BAUD_RATE = 9600;
    public final String ACTION_USB_PERMISSION = "com.example.maskapp.USB_PERMISSION";

    private final int CAMERA_PERM_CODE = 1;
    private final float REAR_CAM_ROTATE_ANGLE = 90;             // on my redmi note 6 pro
    private final float FRONT_CAM_ROTATE_ANGLE = -90;           // on my redmi note 6 pro
    private final int ARDUINO_VID = 0x1A86;                           // my CH340 nano v3
    private final int ARDUINO_VID2 = 0x2341;                          // ordinary arduinos hopefully

    SurfaceTexture surfaceTexture;
    Camera camera;
    Interpreter tflite;
    ImageProcessor imageProcessor;
    Bitmap bitmap;
    float rotateAngle;

    UsbManager usbManager;
    UsbDevice device;
    UsbSerialDevice serialPort;
    UsbDeviceConnection connection;

    Button detectButton, startButton, stopButton;
    ToggleButton cameraSelector;
    ImageView imageView;
    TextView statusText;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Prepare the app
        {
            // Initialise app UI
            detectButton = (Button) findViewById(R.id.detectButton );
            startButton = (Button) findViewById(R.id.startButton );
            stopButton = (Button) findViewById(R.id.stopButton);
            cameraSelector = (ToggleButton) findViewById(R.id.cameraToggle);
            imageView = (ImageView) findViewById(R.id.image);
            statusText = (TextView) findViewById(R.id.textView); statusText.setText( "Connect Arduino via USB and press CONNECT" );

            // Initialise camera
            if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED){
                ActivityCompat.requestPermissions(this, new String[] {Manifest.permission.CAMERA}, CAMERA_PERM_CODE);
            }
            try {
                surfaceTexture = new SurfaceTexture(0);
                camera = Camera.open();     // rear camera is default
                camera.setPreviewTexture( surfaceTexture );
                camera.setParameters( camera.getParameters() );
                rotateAngle = REAR_CAM_ROTATE_ANGLE;
            } catch ( Exception e ) {
                Log.d("CAMERA", e.getMessage());
                Toast.makeText( this, "camera could not be opened - restart app" , Toast.LENGTH_LONG ).show();
            }

            // Load tflite object from model file
            try {
                tflite = new Interpreter(loadModelFile());
            } catch ( Exception e ) {
                Log.d("MODEL", e.getMessage());
                Toast.makeText( this, "tensorflow-lite model is not found/loaded" , Toast.LENGTH_LONG ).show();
            }

            // For input processing later on
            imageProcessor = new ImageProcessor.Builder()
                    .add(new ResizeOp(224, 224, ResizeOp.ResizeMethod.BILINEAR))
                    .build();

            // Prepare USB manager
            usbManager= (UsbManager) getSystemService(this.USB_SERVICE);
            IntentFilter filter = new IntentFilter();
            filter.addAction(ACTION_USB_PERMISSION);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
            filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
            registerReceiver(broadcastReceiver, filter);
            setUiEnabled(false);
        }

        // Camera selector (default is rear, rotate angle 90)
        cameraSelector.setOnCheckedChangeListener( new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if(isChecked) {
                    try {
                        camera.release(); camera = null;
                        camera = Camera.open( CAMERA_FACING_FRONT );     // open front cam
                        camera.setPreviewTexture( surfaceTexture );
                        camera.setParameters( camera.getParameters() );
                        rotateAngle = FRONT_CAM_ROTATE_ANGLE;
                    } catch ( Exception e ) {
                        Log.d("CAMERA", e.getMessage());
                        Toast.makeText( getBaseContext(), "front camera could not be opened - restart app" , Toast.LENGTH_LONG ).show();
                    }
                } else {
                    try {
                        camera.release(); camera = null;
                        camera = Camera.open( CAMERA_FACING_BACK );     // open rear cam
                        camera.setPreviewTexture( surfaceTexture );
                        camera.setParameters( camera.getParameters() );
                        rotateAngle = REAR_CAM_ROTATE_ANGLE;
                    } catch ( Exception e ) {
                        Log.d("CAMERA", e.getMessage());
                        Toast.makeText( getBaseContext(), "rear camera could not be opened - restart app" , Toast.LENGTH_LONG ).show();
                    }
                }
            }
        });
    }


    /** Receives data from Arduino and triggers action */
    UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() {
        @Override
        public void onReceivedData( byte[] arg0 ) {
            if ( arg0[0] == PERSON_DETECTED )
                onClickDetect( detectButton );
        }
    };

    /** Sets the image to the bitmap and ImageView, calls inference      NEEDS UPDATES */
    Camera.PictureCallback jpegCallback = new Camera.PictureCallback() {
        public void onPictureTaken(final byte[] data, Camera camera) {
            bitmap = BitmapFactory.decodeByteArray( data, 0, data.length  );
            bitmap = rotateImage( bitmap, rotateAngle );
            imageView.setImageBitmap( bitmap );
            sendDoorSignal( doInference() );
        }
    };


    // ARDUINO CONNECTION
    /** Asks permission, opens and closes connection (if device is arduino) */
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        //Broadcast Receiver to automatically start and stop the Serial connection.
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted =
                        intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) {
                    connection = usbManager.openDevice(device);
                    serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                    if (serialPort != null) {
                        if (serialPort.open()) { //Set Serial Connection Parameters.
                            setUiEnabled(true); //Enable Buttons in UI
                            serialPort.setBaudRate( BAUD_RATE );
                            serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                            serialPort.read(mCallback); //
                            tvAppend( statusText,"Serial Connection Opened!\n");

                        } else {
                            Log.d("SERIAL", "PORT NOT OPEN");
                        }
                    } else {
                        Log.d("SERIAL", "PORT IS NULL");
                    }
                } else {
                    Log.d("SERIAL", "PERM NOT GRANTED");
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                onClickStart(startButton);
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                onClickStop(stopButton);
            }
        }
    };

    /** Connect/Disconnect to Arduino on click */
    public void onClickStart( View view ) {
        HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
        if (!usbDevices.isEmpty()) {
            boolean keep = true;
            for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                device = entry.getValue();
                int deviceVID = device.getVendorId();
                if ( deviceVID == ARDUINO_VID || deviceVID == ARDUINO_VID2 )
                {
                    PendingIntent pi = PendingIntent.getBroadcast(this, 0, new Intent(ACTION_USB_PERMISSION), 0);
                    usbManager.requestPermission(device, pi);
                    keep = false;
                } else {
                    connection = null;
                    device = null;
                }
                if (!keep)
                    break;
            }
        }
    }
    public void onClickStop( View view ) {
        setUiEnabled(false);
        serialPort.close();
        tvAppend( statusText,"\nSerial Connection Closed! \n");
    }
    public void sendDoorSignal( boolean inferenceResult ) {
        if ( inferenceResult ) {
            Toast.makeText( getBaseContext(), "Mask Detected..." , Toast.LENGTH_LONG ).show();
            if ( serialPort != null && serialPort.isOpen() ) {
                serialPort.write( new byte[]{YES_MASKED} );
                Toast.makeText( getBaseContext(), "Opening door" , Toast.LENGTH_LONG ).show();
            }
        } else {
            Toast.makeText( getBaseContext(), "No Mask..." , Toast.LENGTH_LONG ).show();
            if ( serialPort != null && serialPort.isOpen() ) {
                serialPort.write( new byte[]{NOT_MASKED} );
                Toast.makeText( getBaseContext(), "Access Denied" , Toast.LENGTH_LONG ).show();
            }
        }
    }

    // INFERENCE RELATED METHODS
    /** Take photo and run inference on click */
    public void onClickDetect( View view ) {
        camera.startPreview();
        camera.takePicture( null, null, jpegCallback );
    }

    /** Uses the pre-prepared tflite model, bitmap and image processor to give output */
    private boolean doInference() {
        // Process image before feeding into model
        TensorImage tensorImage = new TensorImage(DataType.UINT8);
        tensorImage.load( bitmap );
        tensorImage = imageProcessor.process(tensorImage);

        // To hold the output
        TensorBuffer probabilityBuffer = TensorBuffer.createFixedSize(new int[]{1, 2}, DataType.UINT8);

        // Run model
        tflite.run( tensorImage.getBuffer(), probabilityBuffer.getBuffer() );

        // Output TRUE if wearing mask
        int[] resArr = probabilityBuffer.getIntArray();
        return ( resArr[0] <= resArr[1] );                   // 0 is no-mask
    }

    /** UTILITY: Memory-maps the model file stored in Assets;             used for loading model */
    private final MappedByteBuffer loadModelFile() throws IOException {
        // Open model using input stream and memory map it to load
        AssetFileDescriptor fileDescriptor = this.getAssets().openFd( "mask_tflite_quantized/model.tflite");
        FileInputStream inputStream = new FileInputStream( fileDescriptor.getFileDescriptor() );
        FileChannel fileChannel = inputStream.getChannel();
        long startOffset = fileDescriptor.getStartOffset();
        long declaredLength = fileDescriptor.getDeclaredLength();
        return fileChannel.map( FileChannel.MapMode.READ_ONLY, startOffset, declaredLength );
    }

    /** UTILITY: Rotates image from camera to proper orientation;         angle = 90 for my Redmi Note 6 Pro */
    private final Bitmap rotateImage(Bitmap source, float angle) {
        Matrix matrix = new Matrix();
        matrix.postRotate(angle);
        return Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(),
                matrix, true);
    }

    // UI Utilities
    /** enables and disables connect/disconnect button */
    public void setUiEnabled(boolean bool) {
        startButton.setEnabled(!bool);
        stopButton.setEnabled(bool);
        statusText.setEnabled(bool);
    }
    /** displays status in statusText */
    private void tvAppend(TextView tv, CharSequence text) {
        final TextView ftv = tv;
        final CharSequence ftext = text;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                ftv.append(ftext);
            }
        });
    }
}
