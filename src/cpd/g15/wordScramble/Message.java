package cpd.g15.wordScramble;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;

public class Message implements Serializable {
    enum Type {

        //Authentication
        AUTH_START,
        AUTH_START_TOKEN,
        AUTH_START_CREDENTIALS,
        AUTH_REQUEST_TOKEN,
        AUTH_RESPONSE_TOKEN,
        AUTH_REQUEST_CHOICE,
        AUTH_RESPONSE_CHOICE,
        AUTH_REQUEST_CREDENTIALS,
        AUTH_RESPONSE_CREDENTIALS,
        AUTH_SUCCESS,

        //Game
        GAME_START,
        GAME_CLIENT_WORD,
        GAME_SERVER_CORRECT_WORD,
        GAME_SERVER_GET_NEW_WORD,
        GAME_SERVER_PLAYER_WON,
        GAME_SERVER_PLAYER_DISCONNECTED,
        GAME_CLIENT_PLAY_AGAIN,
        GAME_CLIENT_QUIT_IN_GAME,
        GAME_CLIENT_QUIT,
        GAME_START_READY,

        // Queue
        QUEUE_START,
        QUEUE_RESPONSE,
        QUEUE_WAITING,
        QUEUE_TOKEN_REFRESH,
        QUEUE_TOKEN_REFRESH_OK,
        QUEUE_CLIENT_TIMEOUT,
    }

    private final Type type;
    private final Object object;

    public Message( Type type, Object object) {
        this.type = type ;
        this.object = object;
    }

    public boolean writeObject(SocketChannel channel) throws IOException {
        //Create byte stream
        ByteArrayOutputStream byteOUTStream = new ByteArrayOutputStream();
        //Create object stream
        ObjectOutputStream objectOUTStream = new ObjectOutputStream(byteOUTStream);
        //Write message to stream
        objectOUTStream.writeObject(this);
        //Convert message to byte array
        byte[] messageBytes = byteOUTStream.toByteArray();
        //Get message size
        int messageSize = messageBytes.length;
        //Create buffer to hold message size and message content
        ByteBuffer buffer = ByteBuffer.allocate(4 + messageSize);
        buffer.putInt(messageSize);
        //Write byte stream to buffer
        buffer.put(messageBytes);
        //Flip buffer
        buffer.flip();
        //Write buffer to channel
        return channel.write(buffer) > 0;

    }

    public static Message readObject(SocketChannel channel) throws IOException, ClassNotFoundException {
        //Create  byte buffer to hold object
        ByteBuffer sizeBuffer = ByteBuffer.allocate(4);
        //Read bytes from channel into buffer
        int bytesRead = channel.read(sizeBuffer);
        if(bytesRead == -1){
            return null;
        }

        if (sizeBuffer.remaining() > 0) {
            // The object size has not been fully read yet, so return null for now
            return null;
        }


        sizeBuffer.flip();
        int messageSize = sizeBuffer.getInt();

        // Create buffer to hold message content
        ByteBuffer contentBuffer = ByteBuffer.allocate(messageSize);
        // Read message content from channel into content buffer
        bytesRead = channel.read(contentBuffer);

        if (bytesRead == -1) {
            // Channel has been closed
            return null;
        }

        if (contentBuffer.remaining() > 0) {
            // The message content has not been fully read yet, so return null for now
            return null;
        }

        contentBuffer.flip();
        byte[] messageBytes = new byte[messageSize];
        contentBuffer.get(messageBytes);

        // Create object input stream with byte array input stream
        ObjectInputStream objectInStream = new ObjectInputStream(new ByteArrayInputStream(messageBytes));
        // Read object from stream
        return (Message) objectInStream.readObject();
    }


    public Type getType() {
        return type;
    }

    public Object getObject() {
        return object;
    }
}
