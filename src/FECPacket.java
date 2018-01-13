public class FECPacket 
{
	//FEC-Header
	static int FECHEADER_SIZE = 10;
	byte[] fecHeader;
	public int Extension = 0;
	public int LongMask = 0;

	//RTP-Informationen aus den Paketen 
	public int RTPPadding;
	public int RTPExtension;
	public int RTPCC;
	public int RTPMarker;
	public int RTPPayloadType;
	public int RTPTimeStamp;
	public int RTPLength;

	//Referenz-SequenceNumber
	public int SNBase; 

	
	//FEC-Level0-Header
	static int FECL0HEADER_SIZE = 4;
	byte[] fecL0Header;
	public int L0protLength;											//payloadlaenge
	public int L0mask;													//bitwise marking which data packets are protected
	
	//FEC-Level0-Payload
	public byte[] L0payload;

	
	
	// --------------------------
	// constructor for 2 FEC-files
	// --------------------------
	public FECPacket(RTPpacket p1, RTPpacket p2, int SNBaseIn) {
		
		//get RTP-information and XOR
		RTPPadding = p1.getPadding() ^ p2.getPadding();
		RTPExtension = p1.getExtension() ^ p2.getExtension();
		RTPCC = p1.getCC() ^ p2.getCC();
		RTPMarker = p1.getMarker() ^ p2.getMarker();  
		RTPPayloadType = p1.getpayloadtype() ^ p2.getpayloadtype();	  
		RTPTimeStamp = p1.gettimestamp() ^p2.gettimestamp();
		RTPLength = p1.getlength() ^ p2.getlength();
		
		//set SNBase
		SNBase = SNBaseIn;		

		//build FEC-Header 
		fecHeader = new byte[FECHEADER_SIZE];	
		fecHeader[0] = (byte)(Extension << 7);
		fecHeader[0] |= (byte)(LongMask << 6);
		fecHeader[0] |= (byte)(RTPPadding << 5);
		fecHeader[0] |= (byte)(RTPExtension << 4);
		fecHeader[0] |= (byte)(RTPCC);
		fecHeader[1] = (byte)(RTPMarker << 7);
		fecHeader[1] |= (byte) RTPPayloadType;
		fecHeader[2] = (byte) (SNBase >> 8);
		fecHeader[3] = (byte) (SNBase & 0xFF);   
		fecHeader[4] = (byte) (RTPTimeStamp >> 24);
		fecHeader[5] = (byte) (RTPTimeStamp >> 16);
		fecHeader[6] = (byte) (RTPTimeStamp >> 8);
		fecHeader[7] = (byte) (RTPTimeStamp & 0xFF);	
		fecHeader[8] = (byte) (RTPLength >> 8);
		fecHeader[9] = (byte) (RTPLength & 0xFF);
					   
		//build FEC-Level0-Header
		L0protLength = Math.max(p1.getpayload_length(), p2.getpayload_length());
		L0mask = (1 << (p1.getsequencenumber() - SNBaseIn)) | (1 << (p2.getsequencenumber() - SNBaseIn));
		fecL0Header = new byte [FECL0HEADER_SIZE];
		fecL0Header[0] = (byte) (L0protLength >> 8);
		fecL0Header[1] = (byte) (L0protLength & 0xFF);
		fecL0Header[2] = (byte) (L0mask >> 8);
		fecL0Header[3] = (byte) (L0mask & 0xFF);
	 		
		//helpfulbuffer insert with files
		byte[] buf1 = new byte[15000];	
		byte[] buf2 = new byte[15000];
		p1.getpayload(buf1);
		p2.getpayload(buf2);
		
		//create Payload
		L0payload =  new byte[L0protLength];
		for(int i=0; i<L0protLength; i++) L0payload[i] = (byte) (buf1[i] ^ buf2[i]);
	}
	
	
	
	// --------------------------
	// constructor for recived FEC-file
	// --------------------------	
	public FECPacket(byte[] packet, int packet_size)
	{	
		if (packet_size <= FECHEADER_SIZE + FECL0HEADER_SIZE) return; 	//Paket isn't a FEC-paket
		
	 	fecHeader = new byte[FECHEADER_SIZE];							//read FEC-Header
	    for (int i=0; i < FECHEADER_SIZE; i++) fecHeader[i] = packet[i];
	  
	    //read the header and set the var
	    Extension = unsigned_int(fecHeader[0] & 128);
	    LongMask = fecHeader[0] & 64;
	    RTPPadding = fecHeader[0] & 32;
	    RTPExtension = fecHeader[0] & 16;
	    RTPCC = fecHeader[0] & 15;
	    RTPMarker = unsigned_int(fecHeader[1] & 128);
	    RTPPayloadType = unsigned_int(fecHeader[1] & 127);
	    RTPTimeStamp = unsigned_int(fecHeader[7]) + 256*unsigned_int(fecHeader[6]) + 65536*unsigned_int(fecHeader[5]) + 16777216*unsigned_int(fecHeader[4]);
	    RTPLength = 256*unsigned_int(fecHeader[8]) + unsigned_int(fecHeader[9]);
	    SNBase = 256*unsigned_int(fecHeader[2]) + unsigned_int(fecHeader[3]);
		
		
	    //read L0-Header
	    fecL0Header = new byte[FECL0HEADER_SIZE];
		for (int i=0; i < FECL0HEADER_SIZE; i++)	fecL0Header[i] = packet[i+FECHEADER_SIZE];
	    
	    //read the header and set the var
		L0protLength = 256 * unsigned_int(fecL0Header[0]) + unsigned_int(fecL0Header[1]);
		L0mask = 256 * unsigned_int(fecL0Header[2]) + unsigned_int(fecL0Header[3]);
			
	    //read L0Payload
		L0payload = new byte[L0protLength];
		for (int i=0; i < L0protLength; i++)	L0payload[i] = packet[i+FECHEADER_SIZE+FECL0HEADER_SIZE];	  
	 }
	 
	 
	// --------------------------
	// restore filepaket
	// Parameter inPacket: 	Partnerdatenpaket
	// return 	outPacket:	das restaurierte Datenpaket oder null bei Fehler
	// --------------------------	
	 public RTPpacket restoreL0(RTPpacket inPacket) {
	 	 
	 	 //get the sequencenumber
		 int seqnr;
		 int[] numbers = getNumbersOfL0Packets();						//get the seqnumb from the portected files
		 if(inPacket.getsequencenumber() == numbers[0]) seqnr = numbers[1];
		 else if(inPacket.getsequencenumber() == numbers[1]) seqnr = numbers[0];
		 else return null;												//inPacket isn't a FEC-packet
		 
		 
		 //restore var
		 int timeStamp = RTPTimeStamp ^ inPacket.gettimestamp();
		 int payloadType = RTPPayloadType ^ inPacket.getpayloadtype();	 	
		 int data_length = (RTPLength ^ inPacket.getlength()) - RTPpacket.HEADER_SIZE;
		 
		 //restore payload 
		 byte[] bufin = new byte[15000];
		 inPacket.getpayload(bufin);
		 byte[] bufout = new byte[15000];
		 for(int i=0; i<data_length; i++) bufout[i] = (byte) (L0payload[i] ^ bufin[i]);
		 
		 //create out-Paket
		 RTPpacket outPacket = new RTPpacket(payloadType, seqnr, timeStamp, bufout, data_length);
		 
		 //return the restored paket
		 return outPacket;
	 }
	 
	// --------------------------
	// Sequenznummern der durch das FEC-Paket geschuetzten Datenpakete ermitteln
	// return sequNumbers:	Sequenznummern als int-Array
	// --------------------------		 
	 public int[] getNumbersOfL0Packets()
	 {	 
		 int count = 0;
		 for(int i=0; i<16; i++)
		 {	if(((L0mask >> i) & 1) == 1) count++;
		 }
		
		 //get seqnumbers
		 int[] sequNumbers = new int [count];
		 for(int i=0, j=0; i<16; i++) 
		 {	if(((L0mask >> i) & 1) == 1) 
		 	{	sequNumbers[j] = SNBase + i;
		 		j++;
		 	}
		 }
			
	 	return sequNumbers;												//return sequNumbers
	 }

	 
	// --------------------------
	// print FEC-Header
	// --------------------------
	public void printFECheader()
	{	System.out.print("FEC-Header: ");

	    for (int i=0; i < (FECHEADER_SIZE); i++)						//Byte-Schleife
	    {	for (int j = 7; j>=0 ; j--)									//Bit-Schleife
	    	{	if (((1<<j) & fecHeader[i] ) != 0) System.out.print("1");
	    		else System.out.print("0");
	    	}
			System.out.print(" ");
	    }    
	    System.out.println();
	}

	// --------------------------
	// Print the FECL0-Header 
	// --------------------------
	public void printFECL0header() 
	{	System.out.print("L0 -Header: ");

		for (int i = 0; i < (FECL0HEADER_SIZE); i++) //Byte-Schleife
		{	for (int j = 7; j >= 0; j--)					//Bit-Schleife
			{
				if (((1 << j) & fecL0Header[i]) != 0) System.out.print("1");
				else System.out.print("0");
			}
			System.out.print(" ");
		}
		System.out.println();
	}
	  
	  
	  //--------------------------
	  //getpacket: returns the packet bitstream and its length
	  //--------------------------
	  public int getpacket(byte[] packet)
	  {
	    //construct the packet = FECHEADER + FECL0HEADER + payload
	    for (int i=0; i < FECHEADER_SIZE; i++)		packet[i] = fecHeader[i];    
	    for (int i=0; i < FECL0HEADER_SIZE; i++)	packet[i+FECHEADER_SIZE] = fecL0Header[i];	    
	    for (int i=0; i < L0protLength; i++)		packet[i+FECHEADER_SIZE+FECL0HEADER_SIZE] = L0payload[i];

	    //return total size of the packet
	    return(FECHEADER_SIZE + FECL0HEADER_SIZE + L0protLength);
	  }
	  

	  
	  //return the unsigned value of 8-bit integer nb
	  static int unsigned_int(int nb) 
	  {
	    if (nb >= 0) return(nb);
	    else return(256+nb);
	  }
}
