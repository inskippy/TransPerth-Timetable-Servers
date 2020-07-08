// Written by Adam Inskip, 22929581
// Written for CITS3002 Computer Networks, Project 2020 Semester 1

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.channels.Channel;
import java.nio.channels.DatagramChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Scanner;
import java.util.Set;

public class station {
	
	public static int sentRF = -1;
	public static CharsetEncoder encoder;
	public static CharsetDecoder decoder;
	public static Map<String, Object> completeTF;
	public static ArrayList<String> TIMETABLE;
	public static LocalTime TIME;
	
	// Station info
	public static String stationName;
	public static int tcp_port;
	public static int udp_host;
	public static int[] udpNeighbours;
	public static String host;
	public static ServerSocketChannel tcp_server;
	public static DatagramChannel udp_server;
	
	@SuppressWarnings("unchecked")
	public static void main(String args[]) throws IOException {
		// create record of completed TF packets
		completeTF = new HashMap<>();
		completeTF.put("RFcount", 0);
		completeTF.put("TFresponse", new ArrayList<String>());
		
		// Create encoder for converting strings to bytes
		encoder = Charset.forName("US-ASCII").newEncoder();
		decoder = Charset.forName("US-ASCII").newDecoder();
		
		// get port info
		stationName = args[0];
		tcp_port = Integer.valueOf(args[1]);
		udp_host = Integer.valueOf(args[2]);
		udpNeighbours = Arrays.asList(Arrays.copyOfRange(args, 3, args.length)).stream().mapToInt(Integer::parseInt).toArray();
		host = "localhost";
		
		// create socket selector
		Selector selector = Selector.open();
		
		// create TCP server socket
		tcp_server = ServerSocketChannel.open();
		tcp_server.bind(new InetSocketAddress(host, tcp_port));
		tcp_server.configureBlocking(false);
		
		// create UDP server socket
		udp_server = DatagramChannel.open();
		udp_server.bind(new InetSocketAddress(host, udp_host));
		udp_server.configureBlocking(false);
		
		// Register the TCP/UDP servers to listen with selector
		tcp_server.register(selector, SelectionKey.OP_ACCEPT);
		udp_server.register(selector, SelectionKey.OP_READ);
		
		// Create buffer for reading incoming data
		ByteBuffer buffer = ByteBuffer.allocate(1024);
		
		// read station's timetable
		TIMETABLE = readTimetable(stationName);
		
		// set request time
		// hardcoded here for testing
//		TIME = LocalTime.of(8,15);
		// or get time on startup, update on new TCP request
		TIME = LocalTime.now();
		TIME = LocalTime.of(TIME.getHour(), TIME.getMinute());
		
		// record TCP client
		SocketChannel TCP_CLIENT = null;
		
		while (true) {
			// Exception in thread "main" java.lang.ClassCastException:
			// class java.lang.Boolean cannot be cast to class java.util.ArrayList
			// (java.lang.Boolean and java.util.ArrayList are in module java.base of loader 'bootstrap')
			int rfc = (int) completeTF.get("RFcount");
			ArrayList<String> tfr = (ArrayList<String>) completeTF.get("TFresponse");
			int tfrs = tfr.size();
			if (sentRF == 0 && rfc == tfrs) {
				// all RF and TF packets have completed journey, send message back to browser
				String response = generateJourneyHTTP();
				TCP_CLIENT.write(encoder.encode(CharBuffer.wrap(response)));
				TCP_CLIENT.close();
				
				// reset everything to handle next TCP request
				sentRF = -1;
				completeTF.put("RFcount", 0);
				completeTF.put("TFresponse", new ArrayList<String>());
				
			}
			
			String response400 = String.join(System.getProperty("line.separator"),
					"HTTP/1.0 400 Bad Request",
					"Content-Length: 59",
					"Content-Type: text/html",
					"Connection: Closed\n",
					"<!doctype html>",
					"<html>",
					"<body>400 Bad Request</body>",
					"</html>");
			
			ByteBuffer responseBR = encoder.encode(CharBuffer.wrap(response400));
			
			selector.select();
			Set<SelectionKey> selectedKeys = selector.selectedKeys();
			Iterator<SelectionKey> iter = selectedKeys.iterator();
			while (iter.hasNext()) {
				SelectionKey key = iter.next();
				iter.remove();
				Channel c = key.channel();
				
				if (key.isAcceptable() && c == tcp_server) {
					SocketChannel client = tcp_server.accept();
					client.configureBlocking(false);
					client.register(selector, SelectionKey.OP_READ);
					TCP_CLIENT = client;
					TIME = LocalTime.now();
					TIME = LocalTime.of(TIME.getHour(), TIME.getMinute());

				} else if (key.isReadable() && c == udp_server) {
					@SuppressWarnings("unused")
					SocketAddress clientAddress = udp_server.receive(buffer);
					byte[] data = getBufferData(buffer);
					handleUDP(data);
				} else if (key.isReadable()) {
					// here we can assume readable and not UDP, therefore an accepted TCP
					SocketChannel client = (SocketChannel) key.channel();
					client.read(buffer);
					byte[] data = getBufferData(buffer);
					String response = handleTCP(data);
					if (response != null) {
						client.write(responseBR);
						client.close();
					}
				}
			}
		}				
	}
	
	public static byte[] getBufferData(ByteBuffer buffer) {
		byte[] data = new byte[buffer.position()];
		buffer.rewind();
		buffer.get(data);
		buffer.clear();
		return data;
	}
	
	public static ByteBuffer buildRouteFind(Map<String, Object> RF) {
		String RFmsg = RF.get("type") + "," +
			RF.get("source") + "," +
			RF.get("sourcePort") + "," +
			RF.get("dest") + "," +
			RF.get("path") + "," +
			RF.get("portPath") + "," +
			RF.get("complete") + "," +
			RF.get("invalid");
		try {
			ByteBuffer responseBuffer = encoder.encode(CharBuffer.wrap(RFmsg));
			return responseBuffer;
		} catch (CharacterCodingException e) {
			// this won't happen in scope of the project, but if it does, terminate
			e.printStackTrace();
			System.exit(0);
			return null;
		}
	}
	
	public static Map<String, Object> decodeRouteFind(String[] incRF) {
		Map<String, Object> RF = new HashMap<>();
		RF.put("type", incRF[0]);
		RF.put("source", incRF[1]);
		RF.put("sourcePort", Integer.valueOf(incRF[2]));
		RF.put("dest", incRF[3]);
		RF.put("path", incRF[4]);
		RF.put("portPath", incRF[5]);
		RF.put("complete", Integer.valueOf(incRF[6]));
		RF.put("invalid", Integer.valueOf(incRF[7]));
		
		return RF; 
	}
	
	public static String timefind_toString(Map<String, Object> TF) {
		String TFmsg = TF.get("type") + "," +
				TF.get("path") + "," +
				TF.get("portPath") + "," +
				TF.get("arrivalTime") + "," +
				TF.get("complete") + "," +
				TF.get("invalid");
		return TFmsg;
	}
	
	public static ByteBuffer buildTimeFind(Map<String, Object> TF) {
		String TFmsg = timefind_toString(TF);
		try {
			ByteBuffer responseBuffer = encoder.encode(CharBuffer.wrap(TFmsg));
			return responseBuffer;
		} catch (CharacterCodingException e) {
			// this won't happen in scope of the project, but if it does, terminate
			e.printStackTrace();
			System.exit(0);
			return null;
		}
	}
	
	public static Map<String, Object> decodeTimeFind(String[] incTF) {
		Map<String, Object> TF = new HashMap<>();
		TF.put("type", incTF[0]);
		TF.put("path", incTF[1]);
		TF.put("portPath", incTF[2]);
		TF.put("arrivalTime", LocalTime.parse(incTF[3]));
		TF.put("complete", Integer.parseInt(incTF[4]));
		TF.put("invalid", Integer.parseInt(incTF[5]));
		return TF; 
	}
	
	public static ByteBuffer buildCR(Map<String, Object> CR) {
		String RFmsg = CR.get("type") + "," +
			CR.get("path") + "," +
			CR.get("portPath") + "," +
			CR.get("count") + ",";
		try {
			ByteBuffer CRbuffer = encoder.encode(CharBuffer.wrap(RFmsg));
			return CRbuffer;
		} catch (CharacterCodingException e) {
			// this won't happen in scope of the project, but if it does, terminate
			e.printStackTrace();
			System.exit(0);
			return null;
		}
	}
	
	public static Map<String, Object> decodeCR(String[] incCR) {
		Map<String, Object> CR = new HashMap<>();
		CR.put("type", incCR[0]);
		CR.put("path", incCR[1]);
		CR.put("portPath", incCR[2]);
		CR.put("count", Integer.valueOf(incCR[3]));
		
		return CR; 
	}
	
	public static String handleTCP(byte[] data) {
		// get destination from request
		String[] textData = new String(data, StandardCharsets.UTF_8).split(System.getProperty("line.separator"));
		String requestLine = textData[0];
		String destinationRequest = requestLine.split(" ")[1];
		String destination = null;
		if (destinationRequest.contains("?") && destinationRequest.charAt(destinationRequest.length()-1) != '?') {
			destination = destinationRequest.split("=")[1];
		}
		
		if (destination == null) {
			return String.join(System.getProperty("line.separator"),
					"HTTP/1.0 400 Bad Request",
					"Content-Length: 59",
					"Content-Type: text/html",
					"Connection: Closed\n",
					"<!doctype html>",
					"<html>",
					"<body>400 Bad Request</body>",
					"</html>");
		} else {
			// valid destination supplied, begin RF packet flood
			Map<String, Object> initialRF = new HashMap<>();
			initialRF.put("type", "RF");
			initialRF.put("source", stationName);
			initialRF.put("sourcePort", udp_host);
			initialRF.put("dest", destination);
			initialRF.put("path", stationName);
			initialRF.put("portPath", Integer.toString(udp_host));
			initialRF.put("complete", 0);
			initialRF.put("invalid", 0);
			
			sentRF = 0;
			
			for (int n : udpNeighbours) {
				try {
					udp_server.send(buildRouteFind(initialRF), new InetSocketAddress(host, n));
					sentRF += 1;
				} catch (IOException | NullPointerException e) {
					e.printStackTrace();
					System.exit(0);
				}
			}
			return null;
		}
	}
	
	@SuppressWarnings("unchecked")
	public static void handleUDP(byte[] data) {
		String[] strData = new String(data).split(",");
		if (strData[0].equals("RF")) {
			Map<String, Object> msg = decodeRouteFind(strData);
			if ((int) msg.get("complete") == 0) {
				if (!((String) msg.get("path")).contains(stationName)) {
					// path doesn't include current station name, still searching for route
					if (msg.get("dest").equals(stationName)) {
						// reached destination, complete the path and send back to source via neighbours
						msg.put("path", msg.get("path") + "/" + stationName);
						msg.put("portPath", msg.get("portPath") + "/" + Integer.toString(udp_host));
						msg.put("complete", 1);
						backtraceRF(msg);
					} else {
						// still searching for destination, append path and send to neighbours
						msg.put("path", msg.get("path") + "/" + stationName);
						msg.put("portPath", msg.get("portPath") + "/" + Integer.toString(udp_host));
						int outboundRF = 0;
						for (int n : udpNeighbours) {
							if (n != Integer.parseInt(((String) msg.get("portPath")).split("/")[((String) msg.get("portPath")).split("/").length - 2])) {
								// don't send to neighbour this packet was received from
								try {
									udp_server.send(buildRouteFind(msg), new InetSocketAddress(host, n));
									outboundRF += 1;
								} catch (IOException | NullPointerException e) {
									e.printStackTrace();
									System.exit(0);
								}
							}
						}
						Map<String, Object> newCR = new HashMap<>();
						newCR.put("type", "CR");
						newCR.put("path", msg.get("path"));
						newCR.put("portPath", msg.get("portPath"));
						newCR.put("count", outboundRF-1); // accounting for new packets, minus one that was already in existence
						backtraceCR(newCR);
					}
				} else {
					// packet still searching, but has already visited this station
					// mark as complete but invalid and return to source
					msg.put("complete", 1);
					msg.put("invalid", 1);
					backtraceRF(msg);
				}
			} else {
				// received completed RouteFind packet
				if (Integer.parseInt(((String) msg.get("portPath")).split("/")[0]) != udp_host) {
					// complete but not at source yet
					backtraceRF(msg);
				} else {
					// complete and arrived at source
					if (((int) msg.get("invalid")) != 1){
						// valid route returned
						sentRF -= 1;
						completeTF.put("RFcount", (int) completeTF.get("RFcount") + 1);
						String completePath = (String) msg.get("path");
						String nextDep = findNextDeparture(TIMETABLE, stationName, completePath.split("/")[1], TIME);
						// create and send TF
						if (nextDep == null) {
							// no valid route from source
							ArrayList<String> al = (ArrayList) completeTF.get("TFresponse");
							al.add(null);
							completeTF.put("TFresponse", al);
						} else {
							// valid route is possible at source, begin search
							Map<String, Object> initialTF = new HashMap<>();
							initialTF.put("type", "TF");
							initialTF.put("path", completePath);
							initialTF.put("portPath", (String) msg.get("portPath"));
							initialTF.put("arrivalTime", nextDep.split(",")[3]);
							initialTF.put("complete", 0);
							initialTF.put("invalid", 0);
							try {
								udp_server.send(buildTimeFind(initialTF), new InetSocketAddress(host, Integer.parseInt(((String) initialTF.get("portPath")).split("/")[1])));
							} catch (IOException e) {
								e.printStackTrace();
								System.exit(0);
							}
							
						}
					} else {
						// invalid route returned to source
						// discard packet, decrease active packet count by 1
						sentRF -= 1;
					}
				}
			}
		} else if (strData[0].equals("TF")) {
			Map<String, Object> msg = decodeTimeFind(strData);
			if ((int) msg.get("complete") == 0) {
				if (((String) msg.get("path")).split("/")[((String) msg.get("path")).split("/").length - 1].equals(stationName)) {
					// arrived at destination
					msg.put("complete", 1);
					// backtrace to source
					if (Integer.parseInt(((String) msg.get("portPath")).split("/")[0]) != udp_host) {
						backtraceTF(msg);
					}
				} else {
					// find next station
					int currentStationIndex = Arrays.asList(((String) msg.get("path")).split("/")).indexOf(stationName);
					LocalTime arrivalTime = (LocalTime) msg.get("arrivalTime");
					// find next time to next station on journey
					String nextDep = findNextDeparture(TIMETABLE, stationName, ((String) msg.get("path")).split("/")[currentStationIndex], arrivalTime);
					if (nextDep == null) {
						// mark packet as complete but invalid, begin backtrace to source
						msg.put("complete", 1);
						msg.put("invalid", 1);
						backtraceTF(msg);
					} else {
						// send to next station on path
						msg.put("arrivalTime", nextDep.split(",")[3]);
						try {
							udp_server.send(buildTimeFind(msg), new InetSocketAddress(host, Integer.parseInt(((String) msg.get("portPath")).split("/")[currentStationIndex+1])));
						} catch (IOException e) {
							e.printStackTrace();
							System.exit(0);
						}
					}
				}
			} else {
				// received completed timefind packet
				if (Integer.parseInt(((String) msg.get("portPath")).split("/")[0]) != udp_host) {
					backtraceTF(msg);
				} else {
					// complete timefind packet arrived back at source
					if ((int) msg.get("invalid") == 0) {
						// complete, valid timefind packet
						
						ArrayList<String> al = (ArrayList) completeTF.get("TFresponse");
						al.add(timefind_toString(msg));
						completeTF.put("TFresponse", al);
					} else {
						// invalid timefind i.e. no possible route
						ArrayList<String> al = (ArrayList) completeTF.get("TFresponse");
						al.add(null);
						completeTF.put("TFresponse", al);
					}
				}
			}
		} else if (strData[0].equals("CR")) {
			Map<String, Object> msg = decodeCR(strData);
			if (Integer.parseInt(((String) msg.get("portPath")).split("/")[0]) != udp_host) {
				// still travelling to source
				backtraceCR(msg);
			} else {
				// CR arrived back at source, increase active packet count accordingly
				sentRF += (int) msg.get("count");
			}
		}
	}
	
	public static void backtraceRF(Map<String, Object> RF) {
		int[] intPortPath = Arrays.asList(((String) RF.get("portPath")).split("/")).stream().mapToInt(Integer::parseInt).toArray();		
		Integer[] portPath = Arrays.stream(intPortPath).boxed().toArray(Integer[]::new);
		int ppIndex = Arrays.asList(portPath).indexOf(udp_host);
		try {
			if (ppIndex != 0) {
				// normal backtrace
				udp_server.send(buildRouteFind(RF), new InetSocketAddress(host, portPath[ppIndex-1]));
			} else {
				// may have circled and revisited source, don't backtrace down path, send directly to source station as neighbouring
				// should add minor efficiency improvement
				udp_server.send(buildRouteFind(RF), new InetSocketAddress(host, portPath[ppIndex]));
			}
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	public static void backtraceTF(Map<String, Object> TF) {
		int[] intPortPath = Arrays.asList(((String) TF.get("portPath")).split("/")).stream().mapToInt(Integer::parseInt).toArray();		
		Integer[] portPath = Arrays.stream(intPortPath).boxed().toArray(Integer[]::new);
		int ppIndex = Arrays.asList(portPath).indexOf(udp_host);
		try {	
			udp_server.send(buildTimeFind(TF), new InetSocketAddress(host, portPath[ppIndex-1]));
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	public static void backtraceCR(Map<String, Object> RF) {
		int[] intPortPath = Arrays.asList(((String) RF.get("portPath")).split("/")).stream().mapToInt(Integer::parseInt).toArray();		
		Integer[] portPath = Arrays.stream(intPortPath).boxed().toArray(Integer[]::new);
		int ppIndex = Arrays.asList(portPath).indexOf(udp_host);
		try {
			udp_server.send(buildCR(RF), new InetSocketAddress(host, portPath[ppIndex-1]));
		} catch (IOException e) {
			e.printStackTrace();
			System.exit(0);
		}
	}
	
	public static ArrayList<String> readTimetable (String stationName) {
		File f = new File("tt-" + stationName);
		ArrayList<String> TIMETABLE = new ArrayList<>();
		long modtime = f.lastModified();
		TIMETABLE.add(Long.toString(modtime));
		try {
			Scanner scanner = new Scanner(f);
			while (scanner.hasNextLine()) {
				TIMETABLE.add(scanner.nextLine());
			} 
			scanner.close();
			return TIMETABLE;
		} catch (FileNotFoundException e) {
			e.printStackTrace();
			System.exit(0);
		}
		return null;
	}
	
	public static String findNextDeparture(ArrayList<String> timetable, String stationName, String destination, LocalTime time) {
		// extract modtime and check timetable hasn't changed
		long modtime = Long.valueOf(timetable.get(0));
		File f = new File("tt-" + stationName);
		if (f.lastModified() > modtime) {
			// reread timetable
			timetable = readTimetable(stationName);
		}
		for (int i = 2; i < timetable.size(); i++) {
			// skip modtime and header lines, start i=2
			String t = timetable.get(i);
			if (t.contains(destination)) {
				String[] data = t.split(",");
				LocalTime departTime = LocalTime.parse(data[0]);
				if (time.compareTo(departTime) < 0) {
					// found valid departure time
					return t;
				}
			}
		}
		return null;
	}
	
	@SuppressWarnings("unchecked")
	public static String generateJourneyHTTP() {
		String optimalTF = null;
		for (String TF : ((ArrayList<String>) completeTF.get("TFresponse"))) {
			if (TF != null) {
				// initialize optimalTF to first valid TF response
				optimalTF = TF;
				break;
			}
		}
		
		if (optimalTF == null) {
			// no valid journeys available
			String responseBody = String.join(System.getProperty("line.separator"),
					"<!doctype html>",
					"<html>",
					"<body>No valid journeys are available to that location today. Check tomorrow's timetable instead.</body>",
					"</html>");
			String responseHeader = String.join(System.getProperty("line.separator"),
					"HTTP/1.0 200 OK",
					"Content-Length: " + responseBody.length(),
					"Content-Type: text/html",
					"Connection: Closed",
					"");
			return String.join(System.getProperty("line.separator"),
					responseHeader,
					responseBody);
		}
		
		// if we get this far, a valid journey must be possible
		// seek earliest arrival at destination
		for (String TF : ((ArrayList<String>) completeTF.get("TFresponse"))) {
			if (TF != null) {
				if (LocalTime.parse(TF.split(",")[3]).compareTo(LocalTime.parse(optimalTF.split(",")[3])) < 0) {
					optimalTF = TF;
				}
			}
		}
		String[] firstLeg = findNextDeparture(TIMETABLE, stationName, optimalTF.split(",")[1].split("/")[1], TIME).split(",");
		String departTime = firstLeg[0];
		String busNo = firstLeg[1];
		String stopNo = firstLeg[2];
		String arrivalTime = optimalTF.split(",")[3];
		String destination = optimalTF.split(",")[1].split("/")[optimalTF.split(",")[1].split("/").length - 1];
		String responseBody = String.join(System.getProperty("line.separator"),
				"<!doctype html>",
				"<html>",
				"<body>Departure: " + busNo + " from " + stopNo + " at " + departTime + "<br>Arrival: " + arrivalTime + " at " + destination,
				"</body>",
				"</html>");
		String responseHeader = String.join(System.getProperty("line.separator"),
				"HTTP/1.0 200 OK",
				"Content-Length: " + responseBody.length(),
				"Content-Type: text/html",
				"Connection: Closed",
				""
				);
		return String.join(System.getProperty("line.separator"),
				responseHeader,
				responseBody);
	}
}




