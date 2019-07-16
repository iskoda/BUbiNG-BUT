package cz.vutbr.fit.knot;

import java.io.*;
import java.net.*;
import java.util.*;
import java.math.BigInteger;

import java.nio.ByteBuffer;

import net.openhft.hashing.LongHashFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Deduplication worker.
 * @author xondre09
 */
public class KnotDedup {
	private static final Logger LOGGER = LoggerFactory.getLogger(KnotDedup.class);
	/** The paragraph must be longer than 50 characters. */
	static final int LENGTH_LOW  = 50;
	/** The set of database servers hosts. */
	Set<String> servers;
        /** The port on which the servers are listening. */
	int port;

	Map<Integer,String> distibution;
	BigInteger blockCount;
	KnotDedupClient[] blockToServer;

	List<CharSequence> paragraphs;

	/** Create the worker.
	 * 
	 * @param hashMap the hash map.
	 * @throws FileNotFoundException the hash map not found.
         */
	public KnotDedup(String hashMap) throws FileNotFoundException  {
		this( hashMap, 1234 );
	}

	/** Create the worker.
	 * 
	 * @param hashMap the hash map.
	 * @param port the port of database server.
	 * @throws FileNotFoundException the hash map not found.
	 */
	public KnotDedup(String hashMap, int port) throws FileNotFoundException {
		this( loadHashMap( hashMap ), port );
	}

	/** Create he worker.
	 * 
	 * @param distibution the map of blocks of hash to hosts.
	 * @param port the port of database server.
	 */
	protected KnotDedup(Map<Integer,String> distibution, int port) {
		this.blockCount = BigInteger.ZERO;
		this.distibution = distibution;
		this.servers = new HashSet<>();
		this.port = port;

		for(Map.Entry<Integer, String> entry : this.distibution.entrySet()) {
			String value = entry.getValue();
			this.servers.add( value );
			this.blockCount = this.blockCount.add( BigInteger.ONE );
		}
	}

	/** Copy of worker.
	 */
	public KnotDedup copy() {
		return new KnotDedup( this.distibution, this.port );
	}

	/** Add hash to one of the servers
	 * 
	 * @param hash Hash to add to server,
	 * @return Returns False if hash is duplicite.
	 * @throws IOException 
	 */
	public boolean addHash(byte[] hash) throws IOException {
		Integer blockId = new BigInteger( 1, hash ).mod( blockCount ).intValue();

		return blockToServer[blockId].addHash( hash );
	}

	/** Set a list of strings to detect duplicates.
	 * 
	 * @param paragraphs List of strings.
	 */
	public void setParagraphs( List<CharSequence> paragraphs ) {
		this.paragraphs = paragraphs;
	}
        
	public List<CharSequence> getParagraphs() {
		return paragraphs;
	}
        
	/** Determining the rate of duplication.
	 * 
	 * @return Returns interval <0, 1>. If isn't duplicite return 0.
	 * @throws IOException 
	 */
	public float duplicityRate() throws IOException {
		float newParagraphs = 0.f;
		float duplicitParagraphs = 0.f;
		long value;
		byte[] hash;

		if( paragraphs == null || paragraphs.isEmpty() ) {
			return Float.NaN;
		}
		
                if(paragraphs.size() > 1) {
			// is the entire contents duplicate?
			String document = String.join(" ", paragraphs);

			value = LongHashFunction.xx().hashBytes(document.getBytes());
			hash = ByteBuffer.allocate(8).putLong(value).array();

			if(! addHash(hash)) return 1.f;
		}

		for(CharSequence paragraph : paragraphs) {
			if(paragraph.length() > LENGTH_LOW) {
				value = LongHashFunction.xx().hashBytes(paragraph.toString().getBytes());
				hash = ByteBuffer.allocate(8).putLong(value).array();
 
				float w = paragraph.length();

				if(addHash(hash)) newParagraphs += w;
				else duplicitParagraphs += w;                               
			}
		}

		if(duplicitParagraphs == 0.f && newParagraphs == 0.f) {
			return 0.f;
		}

		return duplicitParagraphs / (duplicitParagraphs + newParagraphs);
	}

	/** Connection to servers.
	 * 
	 * @throws UnknownHostException
	 * @throws IOException 
	 */
	public void connect() throws UnknownHostException, IOException {
		Map<String, KnotDedupClient> tmpClient = new HashMap<>();

		for (String hostname : servers) {
			try {
				tmpClient.put(hostname, new KnotDedupClient(hostname, port));
			} catch (UnknownHostException e) {
				LOGGER.error("Unknown host ", hostname);
				throw e;
			} catch (Exception e) {
				LOGGER.error("Unknown error ", e);
				throw e;
			}
		}

		blockToServer = new KnotDedupClient[blockCount.intValue()];

		for(Map.Entry<Integer, String> entry : distibution.entrySet()) {
			blockToServer[entry.getKey()] = tmpClient.get( entry.getValue() );
		}
	}

	/** Close connection.
	 * 
	 * @throws IOException 
	 */
	public void close() throws IOException {
		for (KnotDedupClient server : blockToServer) {
			server.close();
		}
	}

	/** Load hash map from file.
         * 
         * @param hashMap File name of hash map
         * @return Mapping hash block to server
         * @throws FileNotFoundException 
         */
	private static Map<Integer,String> loadHashMap(String hashMap) throws FileNotFoundException {
		Map<Integer,String> output = new HashMap<>();
		File file = new File(hashMap);
		try (Scanner input = new Scanner(file)) {
			for( int i = 0; i < 9; i++ ) {
				input.next();
			}

			while(input.hasNext()) {
				int key = input.nextInt();
				input.nextInt();
				String value = input.next();
				output.put( key, value);
			}
		}
		return output;
	}

	/** Client to connect to one server.
	 */
	static class KnotDedupClient {

		Socket clientSocket;
		OutputStream outToServer;
		InputStream inFromServer;
		String hostname;
		int port;

		public KnotDedupClient(String server, int port) throws UnknownHostException, IOException {
			hostname = server;
			this.port = port;
			clientSocket = new Socket(server, port);
			outToServer = clientSocket.getOutputStream();
			inFromServer = clientSocket.getInputStream();
		}

		/** Add hash to database on server.
		 * 
		 * @param hash
		 * @return If hash is duplicate return false
		 * @throws IOException 
		 */
		public boolean addHash(byte[] hash) throws IOException{
			int count;
			try {
				outToServer.write(hash);
			} catch (IOException e) {
				reconnect();
				return addHash( hash );
			}

			clientSocket.setSoTimeout( 10 * 1000 );
			byte[] bytes = new byte[17];
			try {
				count = inFromServer.read(bytes);
			} catch ( SocketTimeoutException ignored ) {
				reconnect();
				return addHash( hash );
			}

			if( count == -1 ) {
				reconnect();
				return addHash(hash);
			}

			if( bytes[0] == '1') {
				return false;
			} else if ( bytes[0] == '0' ) {
				return true;
			}
			return true;
		}
                
		/** Re-connect to server.
		 * 
		 * @throws IOException 
		 */
		protected void reconnect() throws UnknownHostException, IOException {
			close();
			connect();
		}
                
		/** Create conection to server
                 * .
		 * @throws IOException 
		 */
		public void connect() throws UnknownHostException, IOException {
			clientSocket = new Socket(hostname, port);
			outToServer = clientSocket.getOutputStream();
			inFromServer = clientSocket.getInputStream();

			clientSocket.setSoTimeout( 10 * 1000 );
		}
                
		/** Close conection.
                 * 
		 * @throws IOException 
		 */
		public void close() throws IOException {
			outToServer.close();
			inFromServer.close();
			clientSocket.close();
		}
	}
}