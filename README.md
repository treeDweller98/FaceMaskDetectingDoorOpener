# FaceMaskDetectingDoorOpener

IUB Autumn 2021 CSE216L-1
Group 2: jigglypuff

Project Description:  A smart door opener that denies entry if you are not wearing a mask.

Arduino detects an individual approaching using IR sensors, and signals the connected Android device via USB.
The Android device's camera is triggered and the photo taken is fed into a tensorflow-lite (quantised) model.
Based on the inference result, the appropriate instruction is passed to the Arduino to either:
    (a) Display a welcome message on the LCD and open the door.
        or
    (b) Display a message requesting them to wear a mask.
        
The model has been trained using a dataset by github user prajnasb.        

Additional Materials folder contains the dataset (reorganised for our convenience), exported model file,
Arduino sketch, diagram(s) of the whole setup etc.
