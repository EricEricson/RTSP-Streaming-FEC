import java.io.*;
import java.net.*;
import java.awt.*;
import java.util.*;
import java.awt.event.*;
import java.util.Random;

import javax.swing.*;
import javax.swing.Timer;


public class Server extends JFrame implements ActionListener {


	private static final long serialVersionUID = 1L;
	
  //RTP variables:
  //----------------
  DatagramSocket RTPsocket; 											//socket to be used to send and receive UDP packets
  DatagramPacket senddp; 												//UDP packet containing the video frames

  InetAddress ClientIPAddr; 											//Client IP address
  int RTP_dest_port = 0; 												//destination port for RTP packets  (given by the RTSP Client)
 
  long sessionStartTime;
  
  //GUI:
  //----------------
  JLabel lbSendFrame;
  Scrollbar sbLossRate;
  JLabel lbLossRate;

  //Video variables:
  //----------------
  int imagenb = 0; 														//image nb of the image currently transmitted
  VideoStream video = null; 											//VideoStream object used to access video frames
  static int MJPEG_TYPE = 26; 											//RTP payload type for MJPEG video
  static int FRAME_PERIOD = 40; 										//Frame period of the video to stream, in ms
  static int VIDEO_LENGTH = 500; 										//length of the video in frames

  Timer timer; 															//timer used to send the images at the video frame rate
  RTPpacket data_packet1;
  RTPpacket data_packet2;
  byte[] dataBuf1; 														//buffer used to store the images send by packet1
  byte[] dataBuf2; 														//buffer used to store the images send by packet2
  byte[] fecBuf;   														//buffer used to store fec-Data for 2 Packets
  
  //RTSP variables
  //----------------
  //rtsp states
  final static int INIT = 0;
  final static int READY = 1;
  final static int PLAYING = 2;
  //rtsp message types
  final static int SETUP = 3;
  final static int PLAY = 4;
  final static int PAUSE = 5;
  final static int TEARDOWN = 6;
  final static int OPTIONS = 7;
  final static int DESCRIBE = 8;

  static int state; 													//RTSP Server state == INIT or READY or PLAY
  Socket RTSPsocket; 													//socket used to send/receive RTSP messages
  
  //input and output stream filters
  static BufferedReader RTSPBufferedReader;
  static BufferedWriter RTSPBufferedWriter;
  static String VideoFileName; 											//video file requested from the client
  static int RTSP_ID = 123456; 											//ID of the RTSP session
  int RTSPSeqNb = 0; 													//Sequence number of RTSP messages within the session
  
  final static String CRLF = "\r\n";
 
  //LossRate
  double lossRate = 0.0;
  Random randomGen = new Random();
  
  
  //--------------------------------
  //Constructor
  //--------------------------------
  public Server(){

    //init Frame
    super("Server");

    //init Timer
    timer = new Timer(FRAME_PERIOD, this);
    timer.setInitialDelay(0);
    timer.setCoalesce(true);

    //init Buffer
	dataBuf1  = new byte[15000]; 										//buffer used to store the images to send by packet1
	dataBuf2  = new byte[15000]; 										//buffer used to store the images to send by packet1
	fecBuf  = new byte[15000]; 	 										//buffer used to store fec-Data for 2 Packets

    //Handler to close the main window
    addWindowListener(new WindowAdapter() {
      public void windowClosing(WindowEvent e) {	
		timer.stop();													//stop the timer and exit
		System.exit(0);
      }});

    
    
    //GUI:
    lbSendFrame = new JLabel("Send frame #        ", JLabel.CENTER);	//SendFrame-Label
    getContentPane().add(lbSendFrame, BorderLayout.NORTH);
    
    sbLossRate = new Scrollbar(Scrollbar.HORIZONTAL, 0, 2, 0, 100);		//LossRate-Scrollbar
    sbLossRate.addAdjustmentListener(new tfLossRateListener());			//add Handler
       getContentPane().add(sbLossRate, BorderLayout.CENTER);
      
    lbLossRate = new JLabel("LossRate: 0%", JLabel.CENTER);				//LossRate-Label
    getContentPane().add(lbLossRate, BorderLayout.SOUTH);
    
  }
          
  //------------------------------------
  //main
  //------------------------------------
  public static void main(String argv[]) throws Exception
  {
		
	int RTSPport;
		
		if (argv.length == 0) {											// Check portnummer
			JOptionPane.showMessageDialog(null,"Es fehlt ein gültiger Port,\n bitte per Hand eingeben.");
			String tmp;		
			tmp = JOptionPane.showInputDialog("Port des Servers (Standard 3333):");
			RTSPport = Integer.parseInt(tmp);

		} 
		else {
			// get RTSP socket port from the command line
			RTSPport = Integer.parseInt(argv[0]);
		}

    //create a Server object
    Server theServer = new Server();

    //show GUI:
    theServer.pack();
    theServer.setVisible(true);

    
    //Initiate TCP connection with the client for the RTSP session
    ServerSocket listenSocket = new ServerSocket(RTSPport);
    theServer.RTSPsocket = listenSocket.accept();
    listenSocket.close();

    theServer.sessionStartTime = System.currentTimeMillis();
    
    //Get Client IP address
    theServer.ClientIPAddr = theServer.RTSPsocket.getInetAddress();

    //Initiate RTSPstate
    state = INIT;

    //Set input and output stream filters:
    RTSPBufferedReader = new BufferedReader(new InputStreamReader(theServer.RTSPsocket.getInputStream()) );
    RTSPBufferedWriter = new BufferedWriter(new OutputStreamWriter(theServer.RTSPsocket.getOutputStream()) );

    //Wait for the SETUP, OPTIONS or DESCRIBE message from the client
    int request_type;
    boolean done = false;
    while(!done)
    {
		request_type = theServer.parse_RTSP_request(); 					//blocking
		
		//SETUP
		if (request_type == SETUP)
		  {
		    done = true;
	
		    //update RTSP state
		    state = READY;
		    System.out.println("New RTSP state: READY");
	   
		    //Send response
		    theServer.send_RTSP_response(request_type);
	       
		   	
		    if(theServer.video == null)									//try to load the video
		    { 	try	{	
						theServer.video = new VideoStream(VideoFileName); }
		    	catch(Exception e){ 	
						System.out.println("Datei " + VideoFileName + " konnte nicht geoeffnet werden");
		    		return;
		    	}	
		    }			    
		    theServer.RTPsocket = new DatagramSocket();					//init RTP socket
		  }
		
		//OPTIONS
		else if(request_type == OPTIONS) theServer.send_RTSP_response(request_type);
		
		//DESCRIBE
		else if(request_type == DESCRIBE)
		{	
		    if(theServer.video == null)									//try to load the video
		    { 	try	{	theServer.video = new VideoStream(VideoFileName); }
		    	catch(Exception e)
		    	{ 	System.out.println("Datei " + VideoFileName + " konnte nicht geoeffnet werden");	    		
		    		return;
		    	}	
		    }
		    
		    theServer.send_RTSP_response(request_type);	
		}

     }

     
    while(true)															//loop to handle RTSP requests
    {	
		request_type = theServer.parse_RTSP_request(); 					//parse the request
	    theServer.send_RTSP_response(request_type);	   					//answer
	    
		if ((request_type == PLAY) && (state == READY))
		{
		    theServer.timer.start();									//start timer
		    
		    
		    state = PLAYING;											//update state
		    System.out.println("New RTSP state: PLAYING");
		}
		else if ((request_type == PAUSE) && (state == PLAYING))
		{
		    
		    theServer.timer.stop();										//stop timer
		    
		    
		    state = READY;												//update state
		    System.out.println("New RTSP state: READY");
		  }
		else if (request_type == TEARDOWN)
		{
		    
		    theServer.timer.stop();										//stop timer
		    
		    
		    theServer.RTSPsocket.close();								//close sockets
		    theServer.RTPsocket.close();
	
		    System.exit(0);
		}	 
      }
  }


  private void sendRTPPacket(RTPpacket rtp_packet, int port)
 { 	
    	
	try {
		
		int packet_length = rtp_packet.getlength();						// get to total length of the full rtp packet to send

		
		byte[] packet_bits = new byte[packet_length];					// retrieve the packet bitstream and store it in an array of bytes
		rtp_packet.getpacket(packet_bits);

		// send the packet as a DatagramPacket over the UDP socket
		senddp = new DatagramPacket(packet_bits, packet_length, ClientIPAddr, port);

		
		int z = Math.abs(randomGen.nextInt()) % 100;					//delete packets depending on the lossrate
		if (z >= (lossRate * 100))	RTPsocket.send(senddp);				//send
		else															
		{	if(port == RTP_dest_port) System.out.println("Datenpacket " + rtp_packet.getsequencenumber() + " verworfen!");
			else System.out.println("FECPacket " + rtp_packet.getsequencenumber() + " verworfen!");
		}
		
		lbSendFrame.setText("Send frame #" + imagenb);					// update GUI
	}
		
	catch (Exception ex) {
		System.out.println("Exception caught: " + ex);
		System.exit(0);
	}
  }
  
  //------------------------
  //Handler for timer
  //------------------------
  public void actionPerformed(ActionEvent e) {

    if (imagenb < VIDEO_LENGTH) {										//if the current imagenb is less than the length of the video
    	imagenb++;
    	
    	//ungerades frame -> get the next two picutres and build the filepaket, send paket1 and FEC-paket
    	if(imagenb%2 == 1)	{ 	
        	int image_length1; 											//Paket1
    	    try {
    		image_length1 = video.getnextframe(dataBuf1);
    	 	data_packet1 = new RTPpacket(MJPEG_TYPE, imagenb, imagenb*FRAME_PERIOD, dataBuf1, image_length1);
        	
    	 	//Paket2
    	 	if (imagenb < VIDEO_LENGTH) {
			    int image_length2;  
				image_length2 = video.getnextframe(dataBuf2);
		 		data_packet2 = new RTPpacket(MJPEG_TYPE, imagenb+1, (imagenb+1)*FRAME_PERIOD, dataBuf2, image_length2);   	 	
			}
    	    
    	    }catch (Exception ex) {
    			System.out.println("Exception caught: " + ex);
    			System.exit(0);
    		}
    	    
    	    //send datapaket1 
    	    sendRTPPacket(data_packet1, RTP_dest_port);   	 	
    	    
 		  	//build FEC-paket
    	    FECPacket fecPacket = new FECPacket(data_packet1, data_packet2, (imagenb/16)*16+1);	//SNBase: 1, 17, 33...
    	    int fecPacket_length = fecPacket.getpacket(fecBuf);
    		RTPpacket rtp_packet = new RTPpacket(100, imagenb, imagenb*FRAME_PERIOD, fecBuf, fecPacket_length);
    	   
    		//send FEC-paket
    		sendRTPPacket(rtp_packet, RTP_dest_port+11);
    	   
	    }
    	//gerades frame -> send datapaket2
    	else sendRTPPacket(data_packet2, RTP_dest_port);	
 	
      }
    else timer.stop();	//if we have reached the end of the video file, stop the timer
  }


  //------------------------
  //Handler for LossRate Scrollbar
  //-----------------------
  class tfLossRateListener implements AdjustmentListener
 {
	@Override
	public void adjustmentValueChanged(AdjustmentEvent arg0) 
	{	lossRate = (double)arg0.getValue()/100.0;
		lbLossRate.setText(String.format("LossRate: %.2f%%", lossRate));
		System.out.println("New loss rate: " +  lossRate);
	}
  }
	  
  //------------------------
  //Hilfsfunktion um restliche Lines aus einem RTSP Request auszulesen
  //-----------------------  
  private void readOptionalLines() throws SocketException
  {
	  RTSPsocket.setSoTimeout(5);										//short timeout
	  try
	  {
		  while(true)
		  {
			  String line = RTSPBufferedReader.readLine();
			  System.out.println(line);
		  }
	  }
	  catch(IOException ie) { }											//exceeded Timeout -> no more lines
	  
	  RTSPsocket.setSoTimeout(0);										//set Timeout invinity
  }
  //------------------------------------
  //Parse RTSP Request
  //------------------------------------
  private int parse_RTSP_request()
  {
    int request_type = -1;
    try{
      //parse request line and extract the request_type:
      String RequestLine = RTSPBufferedReader.readLine();
      //System.out.println("RTSP Server - Received from Client:");
      System.out.println(RequestLine);

      StringTokenizer tokens = new StringTokenizer(RequestLine);
      String request_type_string = tokens.nextToken();

      //convert to request_type structure:
      if ((new String(request_type_string)).compareTo("SETUP") == 0)			request_type = SETUP;
      else if ((new String(request_type_string)).compareTo("PLAY") == 0)		request_type = PLAY;
      else if ((new String(request_type_string)).compareTo("PAUSE") == 0)		request_type = PAUSE;
      else if ((new String(request_type_string)).compareTo("TEARDOWN") == 0)	request_type = TEARDOWN;
      else if ((new String(request_type_string)).compareTo("OPTIONS") == 0)		request_type = OPTIONS;
      else if ((new String(request_type_string)).compareTo("DESCRIBE") == 0)	request_type = DESCRIBE;
	
      
      //extract VideoFileName from RequestLine
      if ((request_type == SETUP) || (request_type == DESCRIBE)) VideoFileName = tokens.nextToken();
      
      String SeqNumLine = RTSPBufferedReader.readLine();				//parse the SeqNumLine and extract CSeq field
      System.out.println(SeqNumLine);									//print SeqNumLine
      tokens = new StringTokenizer(SeqNumLine);
      tokens.nextToken();
      RTSPSeqNb = Integer.parseInt(tokens.nextToken());
	
      if (request_type == SETUP) {
          String LastLine = RTSPBufferedReader.readLine();				//get LastLine
          System.out.println(LastLine);

		  tokens = new StringTokenizer(LastLine);						//extract RTP_dest_port from LastLine
		  for (int i=0; i<3; i++)
		    tokens.nextToken(); 										//skip unused stuff
		  RTP_dest_port = Integer.parseInt(tokens.nextToken());
      }
      
      readOptionalLines();												// read more even if it is unimportant
      
    }
    catch(Exception ex)
      {
	System.out.println("Exception caught: "+ex);
	System.exit(0);
      }
    return(request_type);
  }

  //------------------------------------
  //Send RTSP Response
  //------------------------------------
  private void send_RTSP_response(int request_type)
  {
    try{
      
      RTSPBufferedWriter.write("RTSP/1.0 200 OK"+CRLF);
      RTSPBufferedWriter.write("CSeq: "+RTSPSeqNb+CRLF);
      
      //OPTIONS
      if(request_type == OPTIONS) RTSPBufferedWriter.write("Public: DESCRIBE, SETUP, PLAY, PAUSE, TEARDOWN"+CRLF);   
      
      //DESCRIBE
      else if(request_type == DESCRIBE) {	
    	RTSPBufferedWriter.write("Date: "+new Date(video.lastModified)+CRLF);   
      	RTSPBufferedWriter.write("Content-Type: multipart/x-mixed-replace;boundary=--boundary"+CRLF);   
      	RTSPBufferedWriter.write("Content-Length: "+video.length+CRLF+CRLF);   
      	
    	RTSPBufferedWriter.write("v=0"+CRLF);   
    	RTSPBufferedWriter.write("o=originator and session identifier"+CRLF);   
     	RTSPBufferedWriter.write("t="+sessionStartTime+" "+System.currentTimeMillis()+CRLF);   
        RTSPBufferedWriter.write("m=video 49170/2 RTP/AVP 98"+CRLF);   
     }
      
      else RTSPBufferedWriter.write("Session: "+RTSP_ID+CRLF);
     
      RTSPBufferedWriter.flush();
    }
    catch(Exception ex)
      {
	System.out.println("Exception caught: "+ex);
	System.exit(0);
      }
  }
}
