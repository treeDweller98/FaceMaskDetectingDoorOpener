# FaceMaskDetectingDoorOpener

IUB Autumn 2021 CSE216L-1
Group 2: jigglypuff

Project Description:  A smart door opener that denies entry if you are not wearing a mask.

Link to published paper:  
[Face Mask Detection Using Artificial Intelligence to Operate Automatic Door](https://link.springer.com/chapter/10.1007/978-981-19-6004-8_29)

![app-ui](https://github.com/treeDweller98/FaceMaskDetectingDoorOpener/blob/master/Additional%20Materials/facemask-app-ui.png?raw=true)

Arduino detects an individual approaching using IR sensors, and signals the connected Android device via USB.
The Android device's camera is triggered and the photo taken is fed into a tensorflow-lite (quantised) model.
Based on the inference result, the appropriate instruction is passed to the Arduino to either:

    (a) Display a welcome message on the LCD and open the door.
        or
    (b) Display a message requesting them to wear a mask.
        
The model has been trained using a dataset by github user prajnasb.        

Additional Materials folder contains the paper and the presentations, along with the dataset (reorganised for our convenience), exported model file, Arduino sketch, diagram(s) of the whole setup etc, and the tinkercad public link. Application APK is in the releases section.
