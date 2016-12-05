package nasa.nccs.cdas.workers;
import org.zeromq.ZMQ;
import org.apache.log4j.Logger;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;


public abstract class Worker {
    int BASE_PORT = 2336;
    ZMQ.Socket request_socket = null;
    ConcurrentLinkedQueue<TransVar> results = null;
    Process process = null;
    ResultThread resultThread = null;
    protected Logger logger = null;
    protected int result_port = -1;
    protected int request_port = -1;
    private boolean isValid = true;

    static int bindSocket( ZMQ.Socket socket, int init_port ) {
        int test_port = init_port;
        while( true ) {
            try {
                socket.bind("tcp://*:" + String.valueOf(test_port));
                break;
            } catch (Exception err ) {
                test_port = test_port + 1;
            }
        }
        return test_port;
    }

    private void addResult( String result_header, byte[] data ) {
        logger.info( "Caching result from worker: " + result_header );
        results.add( new TransVar( result_header, data ) );
    }

    private void invalidateRequest( ) {
        isValid = false;
    }

    public TransVar getResult() {
        logger.info( "Waiting for result to appear from worker");
        while( isValid ) {
            TransVar result = results.poll();
            if( result == null ) try { Thread.sleep(100); } catch( Exception err ) { return null; }
            else { return result; }
        }
        return null;
    }

    public class ResultThread extends Thread {
        ZMQ.Socket result_socket = null;
        int port = -1;
        boolean active = true;
        public ResultThread( int base_port, ZMQ.Context context ) {
            result_socket = context.socket(ZMQ.PULL);
            port = bindSocket(result_socket,base_port);
        }
        public void run() {
            while( active ) try {
                String result_header = new String(result_socket.recv(0)).trim();
                String[] parts = result_header.split("[|]");
                logger.info( "Received result header from worker: " + result_header );
                if( parts[0].equals("array") ) {
                    logger.info("Waiting for result data ");
                    byte[] data = result_socket.recv(0);
                    addResult(result_header, data);
                } else if( parts[0].equals("error") ) {
                    logger.error("Python worker signaled error:\n" + parts[1] );
                    invalidateRequest();
                } else {
                    logger.info( "Unknown result header type: " + parts[0] );
                }
            } catch ( java.nio.channels.ClosedSelectorException ex ) {
                logger.info( "Result Socket closed." );
                active = false;
            } catch ( Exception ex ) {
                logger.error( "Error in ResultThread: " + ex.toString() );
                ex.printStackTrace();
                term();
            }
        }
        public void term() {
            active = false;
            try { result_socket.close(); }  catch ( Exception ex ) { ; }
        }
    }

    public Worker( ZMQ.Context context, Logger _logger ) {
        logger = _logger;
        results = new ConcurrentLinkedQueue();
        request_socket = context.socket(ZMQ.PUSH);
        request_port = bindSocket( request_socket, BASE_PORT );
        resultThread = new ResultThread( request_port + 1, context );
        resultThread.start();
        result_port = resultThread.port;
        logger.info( String.format("Starting Worker, ports: %d %d",  request_port, result_port ) );
    }

    public void sendDataPacket( String header, byte[] data ) {
        request_socket.send(header);
        request_socket.send(data);
    }

    public void quit() {
        request_socket.send("quit");
        shutdown();
    }

    public void sendArrayData( String id, int[] origin, int[] shape, byte[] data, Map<String, String> metadata ) {
        List<String> slist = Arrays.asList( "array", id, ia2s(origin), ia2s(shape), m2s(metadata) );
        String header = String.join("|", slist);
        System.out.println("Sending header: " + header);
        sendDataPacket( header, data );
    }

    public void sendRequest( String operation, String[] inputs, Map<String, String> metadata ) {
        List<String> slist = Arrays.asList(  "task", operation, sa2s(inputs), m2s(metadata)  );
        String header = String.join("|", slist);
        System.out.println( "Sending Task Request: " + header );
        request_socket.send(header);
        isValid = true;
    }

    public void sendShutdown( ) {
        List<String> slist = Arrays.asList(  "quit", "0"  );
        String header = String.join("|", slist);
        System.out.println( "Sending Quit Request: " + header );
        request_socket.send(header);
    }

    public void shutdown( ) {
        sendShutdown();
        try { resultThread.term();  }  catch ( Exception ex ) { ; }
        try { request_socket.close(); }  catch ( Exception ex ) { ; }
    }

    public String ia2s( int[] array ) { return Arrays.toString(array).replaceAll("\\[|\\]|\\s", ""); }
    public String sa2s( String[] array ) { return String.join(",",array); }
    public String m2s( Map<String, String> metadata ) {
        ArrayList<String> items = new ArrayList<String>();
        for (Map.Entry<String,String> entry : metadata.entrySet() ) {
            items.add( entry.getKey() + ":" + entry.getValue() );
        }
        return String.join( ";", items );
    }
}