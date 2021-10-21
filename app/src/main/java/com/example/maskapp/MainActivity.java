package com.example.maskapp;

import static android.hardware.Camera.CameraInfo.CAMERA_FACING_BACK;
import static android.hardware.Camera.CameraInfo.CAMERA_FACING_FRONT;

import android.Manifest;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
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

    private final String PERSON_DETECTED = "11";             // ARDUINO MUST SEND THIS TO TRIGGER CAMERA
    private final String YES_MASKED= "YE";                   // SEND TO ARDUINO TO OPEN DOOR
    private final String NOT_MASKED = "NO";                  // SEND TO ARDUINO TO DENY ENTRY

    public final int BAUD_RATE = 9600;
    public final String ACTION_USB_PERMISSION = "USB_PERMISSION";

    private final int CAMERA_PERM_CODE = 1;
    private final float REAR_CAM_ROTATE_ANGLE = 90;           // on my redmi note 6 pro
    private final float FRONT_CAM_ROTATE_ANGLE = -90;           // on my redmi note 6 pro


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

    Button detectButton, connectButton;
    ToggleButton cameraSelector;
    ImageView imageView;
    TextView statusText;

    // ARDUINO CONNECTION
    /** Receives data from Arduino and triggers action */
    UsbSerialInterface.UsbReadCallback mCallback = new UsbSerialInterface.UsbReadCallback() { //Defining a Callback which triggers whenever data is read.
        @Override
        public void onReceivedData(byte[] arg0) {
            String data = null;
            try {
                data = new String( arg0, "UTF-8" );

                if ( data.equals( PERSON_DETECTED ) ) {
                    detectButton.performClick();        // i.e. when IR detects person
                }
            } catch (UnsupportedEncodingException e) {
                e.printStackTrace();
                Toast.makeText( getBaseContext(), "Corrupt data returned by Arduino" , Toast.LENGTH_LONG ).show();
            }
        }
    };

    /** Asks permission, opens and closes connection (if device is arduino) */
    private final BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals(ACTION_USB_PERMISSION)) {
                boolean granted = intent.getExtras().getBoolean(UsbManager.EXTRA_PERMISSION_GRANTED);
                if (granted) {
                    connection = usbManager.openDevice(device);
                    serialPort = UsbSerialDevice.createUsbSerialDevice(device, connection);
                    if (serialPort != null) {
                        if (serialPort.open()) { //Set Serial Connection Parameters.
                            serialPort.setBaudRate( BAUD_RATE );
                            serialPort.setDataBits(UsbSerialInterface.DATA_BITS_8);
                            serialPort.setStopBits(UsbSerialInterface.STOP_BITS_1);
                            serialPort.setParity(UsbSerialInterface.PARITY_NONE);
                            serialPort.setFlowControl(UsbSerialInterface.FLOW_CONTROL_OFF);
                            serialPort.read(mCallback);

                            statusText.setText( "Serial Connection Open." );
                            Toast.makeText( getBaseContext(), "Arduino Connected: system is active." , Toast.LENGTH_LONG ).show();
                        } else {
                            Log.d("SERIAL", "PORT NOT OPEN");
                            Toast.makeText( getBaseContext(), "SERIAL: Port not open" , Toast.LENGTH_LONG ).show();
                        }
                    } else {
                        Log.d("SERIAL", "PORT IS NULL");
                        Toast.makeText( getBaseContext(), "SERIAL: Port is NULL" , Toast.LENGTH_LONG ).show();
                    }
                } else {
                    Log.d("SERIAL", "PERM NOT GRANTED");
                    Toast.makeText( getBaseContext(), "SERIAL: USB permission not granted" , Toast.LENGTH_LONG ).show();
                }
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_ATTACHED)) {
                connectButton.performClick();
            }
            else if (intent.getAction().equals(UsbManager.ACTION_USB_DEVICE_DETACHED)) {
                serialPort.close();
                statusText.setText( "Serial Connection Closed." );
                Toast.makeText( getBaseContext(), "Arduino connection closed: system is inactive." , Toast.LENGTH_LONG ).show();
            }
        }
    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Prepare the app
        {
            // Prepare USB manager
            usbManager= (UsbManager) getSystemService(this.USB_SERVICE);

            // Initialise app UI
            detectButton = (Button) findViewById(R.id.detect);
            connectButton = (Button) findViewById(R.id.arduinoConnectBtn);
            cameraSelector = (ToggleButton) findViewById(R.id.cameraToggle);
            imageView = (ImageView) findViewById(R.id.image);
            statusText = (TextView) findViewById(R.id.textView); statusText.setText( "Connect Arduino via USB and press ACTIVATE" );

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

        // Take photo and run inference on click
        detectButton.setOnClickListener( new View.OnClickListener() {
            @Override
            public void onClick(View arg0) {
                camera.startPreview();
                camera.takePicture( null, null, jpegCallback );
            }
        } );

        // Connect/Disconnect to Arduino on click
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                HashMap<String, UsbDevice> usbDevices = usbManager.getDeviceList();
                if (!usbDevices.isEmpty()) {
                    boolean keep = true;
                    for (Map.Entry<String, UsbDevice> entry : usbDevices.entrySet()) {
                        device = entry.getValue();
                        int deviceVID = device.getVendorId();
                        if (deviceVID == 0x2341) { //Arduino Vendor ID
                            PendingIntent pi = PendingIntent.getBroadcast( getBaseContext(), 0, new Intent(ACTION_USB_PERMISSION), 0);
                            usbManager.requestPermission(device, pi);
                            keep = false;
                        } else {
                            connection = null;
                            device = null;
                        }
                        if (!keep) break;
                    }
                } else {
                    Toast.makeText( getBaseContext(), "Please connect a device first" , Toast.LENGTH_SHORT ).show();
                }
            }
        });
    }


    // INFERENCE RELATED METHODS
    /** Sets the image to the bitmap and ImageView, calls inference */
    Camera.PictureCallback jpegCallback = new Camera.PictureCallback() {
        public void onPictureTaken(final byte[] data, Camera camera) {
            bitmap = BitmapFactory.decodeByteArray( data, 0, data.length  );
            bitmap = rotateImage( bitmap, rotateAngle );
            imageView.setImageBitmap( bitmap );

            if ( doInference() ) {
                Toast.makeText( getBaseContext(), "MASK YAY UwU" , Toast.LENGTH_LONG ).show();
                if ( serialPort != null && serialPort.isOpen() ) serialPort.write( YES_MASKED.getBytes() );
            } else {
                Toast.makeText( getBaseContext(), "MASK NAY TwT" , Toast.LENGTH_LONG ).show();
                if ( serialPort != null && serialPort.isOpen() ) serialPort.write( NOT_MASKED.getBytes() );
            }
        }
    };

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
}
