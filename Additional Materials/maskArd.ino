#include <LiquidCrystal.h>

LiquidCrystal lcd(12, 11, 5, 4, 3, 2); 
int const ledReject = 10;
int const ledAccept = 9;
int const proxS = 8;

char const TAKE_PIC = 84;	// 'T'
char const MASK_YES = 89;	// 'Y'
char const MASK_NO = 78; 	// 'N'

bool waitingForInference = false;
char recByte = NULL;


void setup() {
  pinMode( proxS, INPUT );
  pinMode( ledReject, OUTPUT );
  pinMode( ledAccept, OUTPUT );
  Serial.begin(9600);
  
  while (!Serial) {
    ; // wait for serial port to connect.
   }
  
  lcd.begin(16,2);	
  lcd.setCursor(0,0);
  lcd.print("hello world!");
  delay(1000);
  
}

void loop() {
  if ( waitingForInference == true && Serial.available() ) {
    lcd.clear();
    
  	recByte = Serial.read();
    
    if ( recByte == MASK_YES ) {	// mask detected
      digitalWrite( ledAccept, HIGH );
      
      lcd.setCursor(1,0); lcd.print( "Mask detected!" );
      lcd.setCursor(3,1); lcd.print( "Welcome :)" );
      
    } else if ( recByte == MASK_NO ) {
      digitalWrite( ledReject, HIGH );

      lcd.setCursor(0,0); lcd.print( "No Mask No Entry" );
      lcd.setCursor(0,1); lcd.print( "Take one ->" );
 
    } else { 
      lcd.setCursor(0,0); lcd.print( "unknown signal:" ); lcd.print( recByte ); 
    }
    
    delay( 2000 );	// allow person to enter / clear the doorway
    
    digitalWrite( ledAccept, LOW );
    digitalWrite( ledReject, LOW );
    waitingForInference = false;
    
    lcd.clear();
    lcd.setCursor(0,0); lcd.print( "Stand in front" );
    lcd.setCursor(0,1); lcd.print( "of Camera" );
  }
  
  if ( waitingForInference == false && digitalRead( proxS ) == LOW ) {
	Serial.write( TAKE_PIC );
    waitingForInference = true;

    lcd.setCursor(0,0); lcd.print( "No Mask No Entry" );
    lcd.setCursor(0,1); lcd.print( "Reading..." );
  }
}
