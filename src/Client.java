import java.io.*;
import java.net.*;
import java.util.*;
import java.awt.*;
import java.awt.event.*;

import javax.swing.*;
import javax.swing.Timer;

public class Client{
  
  //rtsp message types
  final static int SETUP = 3;
  final static int PLAY = 4;
  final static int PAUSE = 5;
  final static int TEARDOWN = 6;
  final static int OPTIONS = 7;
  final static int DESCRIBE = 8;
	  
  //GUI
  //----
  JPanel mainPanel = new JPanel();
  JPanel buttonPanel = new JPanel();
  JPanel buttonPanel2 = new JPanel();
  JFrame frameClient = new JFrame("Client");
  JButton setupButton = new JButton("Setup");
  JButton playButton = new JButton("Play");
  JButton pauseButton = new JButton("Pause");
  JButton tearButton = new JButton("Teardown");
  JButton optionsButton = new JButton("Options");
  JButton descrButton = new JButton("Describe");
  JLabel iconLabel = new JLabel();
  JLabel statisticLabel = new JLabel();
  JLabel restoredLabel = new JLabel();
  ImageIcon icon;


  //RTP variables:
  //----------------
  DatagramSocket RTPsocket; 											//socket to be used to send and receive UDP packets
  DatagramSocket FECsocket;												//used to receive FEC-PAckets on RTP_RCV_PORT+11
  static int RTP_RCV_PORT = 25000; 										//port where the client will receive the RTP packets
   
  //DATA and FEC variables
  //---------------- 
  DataReceiver dataReceiver;											//Thread für Empfang der Datenpakete
  FECReceiver fecReceiver;												//Thread für Empfang der FECpakete
  
  static int DATABUF_SIZE = 8;											//Größe des Puffers für die Datenpakete
  RTPpacket[] dataBuffer;												//Puffer für RTP-Datenpakete
  boolean[] dataBufferFilled;											//Marker ob Datenpaket vorhanden
  FECPacket[] fecBuffer;												//Puffer für FEC-Pakete
  boolean[] fecBufferFilled;											//Marker ob FEC-Paket vorhanden
  
  Timer displayTimer;
  final int DISPLAY_TIMER_DELAY = 40; 									//Abspielgeschwindigkeit als Abstand der Frames in ms
																		//leider fest implementiert, sonst kein genaues Timing der FEC-Operationen möglich
																		//wenn Server mit anderer Geschwindigkeit sendet -> schlecht
  
  
  //RTSP variables
  //----------------
  //rtsp states 
  final static int INIT = 0;
  final static int READY = 1;
  final static int PLAYING = 2;
  static int state; 													//RTSP state == INIT or READY or PLAYING
  Socket RTSPsocket; 													//socket used to send/receive RTSP messages
  //input and output stream filters
  static BufferedReader RTSPBufferedReader;
  static BufferedWriter RTSPBufferedWriter;
  static String VideoFileName; 											//video file to request to the server
  int RTSPSeqNb = 0; 													//Sequence number of RTSP messages within the session
  int RTSPid = 0; 														//ID of the RTSP session (given by the RTSP Server)

  final static String CRLF = "\r\n";

  //Video constants:
  //------------------
  static int MJPEG_TYPE = 26; 											//RTP payload type for MJPEG video
  int VIDEOLENGTH = 499;
  
  //--------------------------
  //Constructor
  //--------------------------
  public Client() 
  {

    //build GUI
    //--------------------------
 
    //Frame
    frameClient.addWindowListener(new WindowAdapter() {
       public void windowClosing(WindowEvent e) {	 System.exit(0);       }
    });

    //Buttons
    buttonPanel.setLayout(new GridLayout(2,0));
    buttonPanel.add(setupButton);
    buttonPanel.add(playButton);
    buttonPanel.add(pauseButton);
    buttonPanel.add(tearButton);
    buttonPanel.add(optionsButton);
    buttonPanel.add(descrButton);
    setupButton.addActionListener(new setupButtonListener());
    playButton.addActionListener(new playButtonListener());
    pauseButton.addActionListener(new pauseButtonListener());
    tearButton.addActionListener(new tearButtonListener());
    optionsButton.addActionListener(new optionsButtonListener());
    descrButton.addActionListener(new descrButtonListener());

    //Image display label
    iconLabel.setIcon(null);
    
    //MainPanel
    mainPanel.setLayout(null);
    mainPanel.add(iconLabel);
    mainPanel.add(buttonPanel);
    mainPanel.add(buttonPanel2);
    mainPanel.add(statisticLabel);
    mainPanel.add(restoredLabel);
   
    //Positionierungen
    iconLabel.setBounds(0,0,380,280);
    buttonPanel.setBounds(0,280,380,50);
    buttonPanel2.setBounds(0,320,380,30);
    statisticLabel.setBounds(0,350,380,50);
    restoredLabel.setBounds(0,400,380,12);
   
    //MainWindow
    frameClient.getContentPane().add(mainPanel, BorderLayout.CENTER);
    frameClient.setSize(new Dimension(390,460));
    frameClient.setVisible(true);

    

    //Threadobjekte anlegen
    dataReceiver = new DataReceiver();
    fecReceiver = new FECReceiver();
	
    //Datenpuffer leer initialisieren
    dataBuffer = new RTPpacket[DATABUF_SIZE];
    dataBufferFilled = new boolean[DATABUF_SIZE];
    for(int i=0; i< DATABUF_SIZE; i++) dataBufferFilled[i] = false;
    
    //FECpuffer leer initialisieren
    fecBuffer = new FECPacket[DATABUF_SIZE/2];
    fecBufferFilled = new boolean[DATABUF_SIZE/2];
    for(int i=0; i< DATABUF_SIZE/2; i++) fecBufferFilled[i] = false;
    
    //Timer für Abspielgeschwindigkeit initialisieren
    displayTimer = new Timer(DISPLAY_TIMER_DELAY , new DisplayImageListener());
    displayTimer.setInitialDelay(DATABUF_SIZE*DISPLAY_TIMER_DELAY/2);
    displayTimer.setCoalesce(true);
  }

  //------------------------------------
  //main
  //------------------------------------
  public static void main(String argv[]) throws Exception
  {
    //get server RTSP port, IP address and filename from the command line
    //------------------
    int RTSP_server_port;
		String ServerHost;
		InetAddress ServerIPAddr;
		String tmp;

		
		// Check auf alle 3 Argumente
		if (argv.length < 3 || argv.length > 3) {
			JOptionPane.showMessageDialog(null,"Es fehlt eines der Argumente,\nbitte per Hand weitere eingeben.");
			tmp = JOptionPane.showInputDialog("IP Adresse des Servers (localhost):");
			ServerHost = tmp;

			tmp = JOptionPane.showInputDialog("Server Port (Default Server 3333):");
			RTSP_server_port = Integer.parseInt(tmp);

			tmp = JOptionPane.showInputDialog("VideoFileName (movie.Mjpeg):");
			VideoFileName = tmp;

			ServerIPAddr = InetAddress.getByName(ServerHost);
		} 
		else {
			// get server RTSP port and IP address from the command line
			ServerHost = argv[0];
			RTSP_server_port = Integer.parseInt(argv[1]);
			ServerIPAddr = InetAddress.getByName(ServerHost);

			// Get video filename to request
			VideoFileName = argv[2];
		}
  
    //Create a Client object
    Client theClient = new Client();
    
 
    //Establish a TCP connection with the server to exchange RTSP messages
    //------------------
    try
    {	theClient.RTSPsocket = new Socket(ServerIPAddr, RTSP_server_port);
    }
    catch(ConnectException ce)
    {	//Verbindung nicht zustande gekommen
    	System.out.println("Server kann nicht kontaktiert werden!");
    	System.exit(0);
    }

    //Set input and output stream filters:
    RTSPBufferedReader = new BufferedReader(new InputStreamReader(theClient.RTSPsocket.getInputStream()) );
    RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theClient.RTSPsocket.getOutputStream()) );

    //init RTSP state:
    state = INIT;
  }


  //------------------------------------
  //Handler for buttons
  //------------------------------------

  //Handler for Setup button
  //-----------------------
  class setupButtonListener implements ActionListener{
    public void actionPerformed(ActionEvent e){

      System.out.println("Setup Button pressed !");      

      if (state == INIT) 
      {
		  //Init non-blocking RTPsocket that will be used to receive data
		  try{
			  		  
		    //Socket für Datenpakete auf Port: RTP_RCV_PORT
		    RTPsocket = new DatagramSocket(RTP_RCV_PORT);
		    RTPsocket.setSoTimeout(5);
	
		    //Socket für FECpakete auf Port: RTP_RCV_PORT + 11
			FECsocket = new DatagramSocket(RTP_RCV_PORT+11);
		    FECsocket.setSoTimeout(5);
		  }
		  catch (SocketException se)
		  {
		      System.out.println("Socket exception: "+se);
		      System.exit(0);
		  }
	
		  //init RTSP sequence number
		  RTSPSeqNb = 1;
		 
		  //Send SETUP message to the server
		  send_RTSP_request("SETUP");
	
		  //Wait for the response 
		  if (parse_server_response(SETUP) != 200)	    System.out.println("Invalid Server Response");
		  else 
		  {
		      //change RTSP state and print new state 
		      state = READY;  
		      System.out.println("New RTSP state: READY");
		  }
      }//else if state != INIT then do nothing
    }
  }
  
  //Handler for Play button
  //-----------------------
  class playButtonListener implements ActionListener
  {
	  
    boolean startThreads = true;										//Hilfsvariable um Empfangsthreads nur beim 1.Playaufruf zu starten
    
	public void actionPerformed(ActionEvent e)
	{

		System.out.println("Play Button pressed !"); 

		if (state == READY) 
		{
    	  
    	  RTSPSeqNb++; 													//increase RTSP sequence number	  
    	  send_RTSP_request("PLAY"); 									//Send PLAY message to the server

    	  //Wait for the response 
    	  if (parse_server_response(PLAY) != 200)  System.out.println("Invalid Server Response");
    	  else 
    	  {
		      //start the displaytimer
		      displayTimer.start();
		     
		      
		      if(startThreads) {										//start Threads
			      dataReceiver.start();
			      fecReceiver.start();
			      startThreads = false;
		      }
		      
		      //change RTSP state and print out new state
		      state = PLAYING;		      
		      System.out.println("New RTSP state: PLAYING");
    	  }
		}//else if state != READY then do nothing
    }
  }


  //Handler for Pause button
  //-----------------------
  class pauseButtonListener implements ActionListener 
  {
    public void actionPerformed(ActionEvent e)
    {

      System.out.println("Pause Button pressed !");   

      if (state == PLAYING) 
      {
    	  //increase RTSP sequence number
    	  RTSPSeqNb++;

    	  //Send PAUSE message to the server
    	  send_RTSP_request("PAUSE");
	
    	  //Wait for the response 
    	  if (parse_server_response(PAUSE) != 200) System.out.println("Invalid Server Response");
    	  else 
    	  {		      
		      //stop the displaytimer
		      displayTimer.stop();
		      
		      //change RTSP state and print out new state
		      state = READY;	      
		      System.out.println("New RTSP state: READY");
    	  }
      }	 //else if state != PLAYING then do nothing     
    }
  }

  //Handler for Teardown button
  //-----------------------
  class tearButtonListener implements ActionListener 
  {
    public void actionPerformed(ActionEvent e)
    {

      System.out.println("Teardown Button pressed !");  

      
  	  RTSPSeqNb++;    													//increase RTSP sequence number

      
      send_RTSP_request("TEARDOWN");									//Send TEARDOWN message to the server

      //Wait for the response 
      if (parse_server_response(TEARDOWN) != 200) System.out.println("Invalid Server Response");
      else 
      {     	
		  
		  displayTimer.stop();											//stop the displaytimer
		  System.exit(0);
		  
	      
	      state = INIT;	      											//change RTSP state and print out new state
	      System.out.println("New RTSP state: INIT");
      }
    }
  }

  //Handler for Options button
  //-----------------------
  class optionsButtonListener implements ActionListener 
  {
    public void actionPerformed(ActionEvent e)
    {
      System.out.println("Options Button pressed !");  
      RTSPSeqNb++;
      send_RTSP_request("OPTIONS");

      //Wait for the response 
      if (parse_server_response(OPTIONS) != 200)  System.out.println("Invalid Server Response");
    }
  }

  
  //Handler for Describe button
  //-----------------------
  class descrButtonListener implements ActionListener {
    public void actionPerformed(ActionEvent e){

      System.out.println("Describe Button pressed !");
      RTSPSeqNb++;
      send_RTSP_request("DESCRIBE");

      //Wait for the response 
      if (parse_server_response(DESCRIBE) != 200) System.out.println("Invalid Server Response");
    }
  }
  
  
  
  //------------------------------------
  //Parse Server Response
  //------------------------------------
  private int parse_server_response(int request_type) 
  {
    int reply_code = 0;

    try{
      //parse status line and extract the reply_code:
      String StatusLine = RTSPBufferedReader.readLine();
      //System.out.println("RTSP Client - Received from Server:");
      System.out.println(StatusLine);
    
      StringTokenizer tokens = new StringTokenizer(StatusLine);
      tokens.nextToken(); //skip over the RTSP version
      reply_code = Integer.parseInt(tokens.nextToken());
      
      //if reply code is OK get and print the 2 other lines
      if (reply_code == 200)
      {
    	  //die ersten beiden Zeilen einlesen und ausgeben
		  String FirstLine = RTSPBufferedReader.readLine();
		  System.out.println(FirstLine);	  
		  String SecondLine = RTSPBufferedReader.readLine();
		  System.out.println(SecondLine);
		
		  //SETUP -> RTSPid einlesen
		  if(request_type == SETUP)
		  {	tokens = new StringTokenizer(SecondLine);  
		  	tokens.nextToken();
		  	RTSPid = Integer.parseInt(tokens.nextToken());
		  }
	  
		  //DESCRIBE -> ggf zusätliche Zeilen auslesen
		  else if(request_type == DESCRIBE) readOptionalLines();	
	  }
    }
    catch(Exception ex)
      {
	System.out.println("Exception caught: "+ex);
	System.exit(0);
      }
    
    return(reply_code);
  }
  
  //------------------------
  //Hilfsfunktion um restliche Lines aus einer RTSP Response auszulesen
  //-----------------------  
  private void readOptionalLines() throws SocketException
  {
	  RTSPsocket.setSoTimeout(5);										//kurzen Timeout setzen
	  try
	  {
		  while(true)
		  {
			  String line = RTSPBufferedReader.readLine();
			  System.out.println(line);
		  }
	  }
	  catch(IOException ie) { }											//Timeout over -> no more lines
	  
	  RTSPsocket.setSoTimeout(0);										//Timeout wieder auf unendlich setzen	
  }

  //------------------------------------
  //Send RTSP Request
  //------------------------------------
  private void send_RTSP_request(String request_type)
  {
    try{
   
      //write the request line:
      RTSPBufferedWriter.write(request_type + " " + VideoFileName + " RTSP/1.0" + CRLF);

      //write the CSeq line: 
      RTSPBufferedWriter.write("CSeq: " + RTSPSeqNb + CRLF);


      //check if request_type is equal to "SETUP" and in this case write the Transport: line advertising to the server the port used to receive the RTP packets RTP_RCV_PORT
      if(request_type == "SETUP")  RTSPBufferedWriter.write("Transport: RTP/UDP; client_port= " + RTP_RCV_PORT + CRLF);    
      //OPTIONS
      else if(request_type == "OPTIONS") { }
      //DESCRIBE
      else if(request_type == "DESCRIBE") RTSPBufferedWriter.write("Accept: application/sdp" + CRLF);
      
      //otherwise, write the Session line from the RTSPid field
      else RTSPBufferedWriter.write("Session: " + RTSPid + CRLF);     

      RTSPBufferedWriter.flush();
    }
    catch(Exception ex)
    {
		System.out.println("Exception caught: "+ex);
		System.exit(0);
    }
  }
  
  
  
  //------------------------------------
  //Handler des Displaytimers
  //------------------------------------ 
  class DisplayImageListener implements ActionListener 
  {	int currIndex = 0;		//Index des aktuellen Bildes im dataBuffer
  	byte[] imageBuf;		//Puffer für Bilddaten
  	int seqNr = 0;			//Sequenznummer des letzten gezeigten Bildes
  	int numRestored = 0;	//für Statistikausgabe
 	int numNotRestored = 0; //für Statistikausgabe

  	public DisplayImageListener()
  	{
  		imageBuf = new byte[15000];
  	}
  	
	private void showImage(RTPpacket packet)
	{
		int length = packet.getpayload(imageBuf);
		
		//get an Image object from the payload bitstream
		Toolkit toolkit = Toolkit.getDefaultToolkit();
		Image image = toolkit.createImage(imageBuf, 0, length);
		
		//display the image as an ImageIcon object
		icon = new ImageIcon(image);
		iconLabel.setIcon(icon);
	}
	
	
	public void actionPerformed(ActionEvent e) 
	{	
		//Paket vorhanden
		if(dataBufferFilled[currIndex] && (dataBuffer[currIndex].getsequencenumber() > seqNr))
		{
			showImage(dataBuffer[currIndex]);
			seqNr = dataBuffer[currIndex].getsequencenumber();
		}
		//Paket fehlt -> FEC
		else 
		{	seqNr++;
			System.out.print("\nPacket " + seqNr + " missing");
				
			//index des Partnerpakets ermitteln
			int index2 = currIndex+1;
			if(currIndex%2 == 1) index2 = currIndex-1;
			
			//FEC-Paket und Partner-Paket vorhanden -> Restaurierung möglich
			if((fecBufferFilled[currIndex/2]) && (dataBufferFilled[index2]))
			{	
				RTPpacket restoredPacket = fecBuffer[currIndex/2].restoreL0(dataBuffer[index2]);
				
				if(restoredPacket != null)
					{showImage(restoredPacket);
				
				System.out.println("  -> restored\n");
				numRestored++;	
					}
			}
			//FEC-Paket und/oder Partner-Paket fehlt
			else
			{	numNotRestored++;
				if(!fecBufferFilled[currIndex/2]) System.out.print("\nFEC-Packet missing");
				if(!dataBufferFilled[index2]) System.out.print("\n2nd Data-Packet missing");
				System.out.println("\n");
			}
		}
		
		//Markierungen in den Buffern löschen
		if(currIndex%2 == 1)
		{	dataBufferFilled[currIndex-1] = false;
			dataBufferFilled[currIndex] = false;
			fecBufferFilled[currIndex/2] = false;
		}
		
		//Bufferindex weiterschalten
		currIndex++;
		currIndex = currIndex % DATABUF_SIZE;
		
		//Statistikausgabe aktualisieren
		float rate = 0;
		if((numRestored + numNotRestored) > 0) rate = 100.0f*((float)numRestored / (float)(numRestored + numNotRestored));
	   	restoredLabel.setText(String.format("restored: %d, not restored: %d, restored-rate: %.2f%%", numRestored, numNotRestored, rate));		
	}	
  }
  
  
  
  //------------------------------------
  //Empfänger der Datenpakete
  //-----------------------
  class DataReceiver extends Thread
  {
	  int lastSequenceNumber = 0;										//Sequenznummer des letzten empfangenen Paketes
	  long oldtime = System.currentTimeMillis(); 						//Hilfsvariable um Verstreichen einer Sekunde zu ermitteln
	 
	  //Variablen für Statistikausgabe
	  int numOK = 0;
	  int numLost = 0;
	  int numOld = 0;
	  long bytesPerSecond = 0;
	  long curBytesPerSecond = 0;
	  	
	  byte[] payload;													//Payload eines empfangenen Paketes

	  public void run()
	  {    
		    
		    //allocate enough memory for the buffer used to receive data from the server
		    payload = new byte[15000]; 
		    
		    while(true)
		    {			  
		   	
		   	 if(System.currentTimeMillis() - oldtime >= 1000)			//Sekunde um -> bytesPerSecond aktualisieren
		   	 {
		   		oldtime = System.currentTimeMillis();
		   		bytesPerSecond = curBytesPerSecond;
		   		curBytesPerSecond = 0;
		   	 }
	   	 
	         //Construct a DatagramPacket to receive data from the UDP socket
	         DatagramPacket rcvdp = new DatagramPacket(payload, payload.length);

	         try{
	        	 //receive the DP from the socket:
		     	RTPsocket.receive(rcvdp);
		   	  
		     	//create an RTPpacket object from the DP
		     	RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
		   	
		     	//Statistik ausgeben
		     	curBytesPerSecond += rcvdp.getLength(); 
		     	float packetloss = 0;
		     	if(numOK > 0) packetloss = (float)numLost/ ((float)numLost + numOK) * 100;
		     	//statisticLabel.setText("<html>" + String.format("OK: %d, Lost: %d, Old: %d", numOK, numLost, numOld) + "<br>" + 
		     	statisticLabel.setText("<html>" + String.format("OK: %d, Lost: %d", numOK, numLost) + "<br>" + 
		   			String.format("Lossrate: %.2f%%, Datenrate: %.2fkb/s", packetloss, bytesPerSecond/1000.0) + "<html>");
		   	   	//Video vorbei?
		     	if((numOK + numLost+10) >= VIDEOLENGTH) displayTimer.stop();
		   	  
			   	//print important header fields of the RTP packet received: 
			   	System.out.println("Got RTP packet with SeqNum # "+rtp_packet.getsequencenumber()+" TimeStamp "+rtp_packet.gettimestamp()+" ms, of type "+rtp_packet.getpayloadtype());
			   	
			   	
			   	//Check Sequence Number
			   	int seqnr = rtp_packet.getsequencenumber();
			   	if(lastSequenceNumber >= seqnr) 						//zu altes Paket
			   	{	numOld++;
			   		continue;
			   	}
			   	numOK++;
			   	numLost += seqnr - (lastSequenceNumber +1);				//fehlende Pakete ermitteln
			   	lastSequenceNumber = seqnr;
			   	
	
			   	//Paket in dataPuffer einsortieren
			   	int bufindex = (seqnr-1)%DATABUF_SIZE;					//Zielindex ermitteln
			   	dataBuffer[bufindex] = rtp_packet;
			   	dataBufferFilled[bufindex] = true;
			   	
		     }
	         catch (InterruptedIOException iioe){ }
	         catch (IOException ioe) { 		System.out.println("Exception caught: "+ioe);      }          
	 	  }	    
	  }
  }
  

  //------------------------------------
  //Empfänger der FECpakete
  //-----------------------
  class FECReceiver extends Thread
  {  	
	  byte[] payload;													//Payload eines empfangenen Paketes
	  		
	  public void run()
	  {  
		  payload = new byte[15000]; 
		  
		  while(true)
		  {
			  //receive FEC-Packet
		      DatagramPacket  rcvdp = new DatagramPacket(payload, payload.length);  

		      try
		      {	 
		    	  	//RTP-Packet empfangen
		    	  	FECsocket.receive(rcvdp);
		 
		    		RTPpacket rtp_packet = new RTPpacket(rcvdp.getData(), rcvdp.getLength());
		    		
		    		//FEC-Packet extrahieren
				   	int payload_length = rtp_packet.getpayload(payload);
					FECPacket fecPacket = new FECPacket(payload, payload_length);
				   	
					//Nummern der geschützten Pakete auslesen und ausgeben
				   	int[] numbers = fecPacket.getNumbersOfL0Packets();
				   	System.out.print("Got FEC-Paket for ");
				   	for(int i=0; i<numbers.length; i++) System.out.print(numbers[i] +" ");
				   	System.out.print("\n");

				   	//FEC-Paket in fecBuffer speichern
				   	int bufindex = ((numbers[0]-1)/2)%(DATABUF_SIZE/2);		//Zielindex ermitteln
				   	fecBuffer[bufindex] = fecPacket;
				   	fecBufferFilled[bufindex] = true;	
		      }
		      
		      catch (InterruptedIOException iioe){	      }
		      catch (IOException ioe) {		System.out.println("Exception caught: "+ioe);      }		      	      
		  }
	  }  
  }
} //Ende der Clientklasse  

