RTSP-Client-Server
======


Introduction
----
Streaming videos are ubiquitous in the web today. The internet, originally designed for simple text/document based transfers using protocols such as the HTTP, is the platform on which streaming media is passed from one end of the world to another. In order to stream data across networks efficiently, the layers of the network stack must be enhanced with capabilities to transfer it efficiently while not congesting the network as a whole. This project explores one of such capabilities in the application layer by using the [real time streaming protocol](https://www.ietf.org/rfc/rfc2326.txt) (RTP) and the control protocol (RTCP) which provides the capability for network congestion control.


Architecture
----
This project uses a client-server architecture. Although RTP is a scalable protocol that could be extended to large scale with many senders and receivers, for simplicity we will be working with a single receiver/sender unicast model. The server is responsible for fetching a video file (whose name is provided by the client’s request) locally and passing it a frame at a time to the client. The client can make requests to the server via the RTSP protocol, which specifies the kinds of actions that the client can request the server to take on the stream, i.e. play, pause, stop, etc. Quality of service information can be fed back to the server from the client that sends packets following the RTCP protocol. Since RTP is an application layer protocol, it can be built on top of TCP/IP or UDP. Because TCP often comes with large overhead (due to its own congestion control mechanisms), we pick the minimal weight UDP protocol and handle congestion control using RTCP packets.

