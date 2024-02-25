package cpd.g15.wordScramble;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.HashMap;
import java.util.Iterator;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Auth extends Thread {

    private final ReentrantReadWriteLock lockPlayerDB;
    private final ServerSocketChannel serverChannel;
    private final Queue queue;



    public Auth(ServerSocketChannel serverChannel, ReentrantReadWriteLock lockPlayerDB, Queue queue){
        this.lockPlayerDB = lockPlayerDB;
        this.serverChannel = serverChannel;
        this.queue = queue;

    }

    public void run(){

        try{
            Selector selector = Selector.open();
            serverChannel.register(selector, SelectionKey.OP_ACCEPT);

            while (true){
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();

                while (iterator.hasNext()){
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if(!key.isValid()){
                        continue;
                    }
                    if(key.isAcceptable()){
                        accept(selector,serverChannel);
                    }else if(key.isReadable()){
                        read(key);
                    }
                }

            }
        }catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void read(SelectionKey key) throws IOException{
        SocketChannel clientChannel = (SocketChannel) key.channel();
        Message message;
        try {
            message = Message.readObject(clientChannel);

        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (SocketException e) {
            // Handle connection reset
            System.out.println("Client disconnected: " + clientChannel);
            clientChannel.close();
            return;
        }

        if(message == null || message.getObject().toString().equals("quit")){
            System.out.println("Client disconnected: ");
            clientChannel.close();
            return;
        }
        dealWithMessage(message,clientChannel, key);

    }

    private void dealWithMessage(Message message, SocketChannel clientChannel, SelectionKey key) {
        KeyAttachment keyAttachment = (KeyAttachment) key.attachment();

        try {
            switch (message.getType()){
                case AUTH_START_TOKEN:
                    Message requestToken = new Message(Message.Type.AUTH_REQUEST_TOKEN,"");
                    requestToken.writeObject(clientChannel);
                    break;
                case AUTH_START_CREDENTIALS:
                    Message requestChoice = new Message(Message.Type.AUTH_REQUEST_CHOICE,"Register 1 / Login 2");
                    requestChoice.writeObject(clientChannel);
                    break;
                case AUTH_RESPONSE_TOKEN:
                    //Get token
                    String tokenClient = (String) message.getObject();
                    //Check if token is valid
                    if(checkToken(tokenClient) && authenticateByToken(tokenClient)){
                        Message authSuccess = new Message(Message.Type.AUTH_SUCCESS,"Logged in by token");
                        authSuccess.writeObject(clientChannel);

                        //Cancel key to this channel
                        key.cancel();
                        //Add to queue
                        queue.resumeToQueue(tokenClient, clientChannel);

                    }else{
                        Message tokenExpired = new Message(Message.Type.AUTH_REQUEST_CHOICE, "Token expired\nRegister 1 / Login 2");
                        tokenExpired.writeObject(clientChannel);
                    }
                    break;
                case AUTH_RESPONSE_CHOICE:
                    int choice = (int) message.getObject();
                    if(choice == 1){
                        keyAttachment.getMap().put("choice","register");
                    }else if(choice == 2) {
                        keyAttachment.getMap().put("choice", "login");
                    }

                    Message credentialsMessage = new Message(Message.Type.AUTH_REQUEST_CREDENTIALS,"Credentials");
                    credentialsMessage.writeObject(clientChannel);
                    break;
                case AUTH_RESPONSE_CREDENTIALS:
                    HashMap messageMap = (HashMap) message.getObject();
                    String username = (String) messageMap.get("username");
                    String password = (String) messageMap.get("password");
                    String token = "";
                    String authType = keyAttachment.getMap().get("choice");
                    if(authType.equals("register")){
                        token = registerPlayer(username,password);
                    }else if(authType.equals("login")){
                        token = authenticatePlayer(username,password);
                    }
                    //error
                    if(token.isEmpty()) {
                        Message messageError = null;
                        if(authType.equals("register"))
                            messageError = new Message(Message.Type.AUTH_REQUEST_CREDENTIALS,"Username already exists");
                        else if(authType.equals("login")){
                            messageError = new Message(Message.Type.AUTH_REQUEST_CREDENTIALS,"Wrong username or password");
                        }
                        messageError.writeObject(clientChannel);
                    }else{
                        Message messageSuccess;
                        if(authType.equals("register")){
                            messageSuccess = new Message(Message.Type.AUTH_SUCCESS,"Registered successfully\nToken:" + token);
                        }else{
                            messageSuccess = new Message(Message.Type.AUTH_SUCCESS,"Logged in successfully\nToken:" + token);
                        }
                        messageSuccess.writeObject(clientChannel);
                        
                        // Update player's waiting token
                        Server.playerDatabase.updatePlayerWaitingToken(username, token);

                        //CANCEL KEY TO THIS CHANNEL
                        key.cancel();

                        //Add player to queue
                        queue.addPlayerToQueueAndRegisterChannel(username, clientChannel);
                    }
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void accept(Selector selector, ServerSocketChannel serverSocketChannel) throws IOException {
        //Accept client
        SocketChannel clientChannel = serverSocketChannel.accept();
        clientChannel.configureBlocking(false);

        //Register client for reading
        SelectionKey key = clientChannel.register(selector, SelectionKey.OP_READ);

        //Attach empty hashmap to client
        KeyAttachment attachment = new KeyAttachment();
        key.attach(attachment);

        //Send message to request auth to client
        Message startAuth = new Message(Message.Type.AUTH_START,"Auth Start");
        startAuth.writeObject(clientChannel);
    }



    private boolean checkToken(String token) throws IOException {
        //Check if token expired
        lockPlayerDB.readLock().lock();
        //Check if token exists
        if(Server.playerDatabase.tokenExpiration.containsKey(token)){
            //Get token expiration time
            long expirationTime = Server.playerDatabase.tokenExpiration.get(token);
            //Check if expiration time has passed
            if(System.currentTimeMillis() < expirationTime ){
                lockPlayerDB.readLock().unlock();
                return true;
            }else{
                lockPlayerDB.readLock().unlock();
                return false;
            }
        }
        lockPlayerDB.readLock().unlock();
        return false;
    }

    private String registerPlayer(String username, String password){
        String sessionToken = "";

        lockPlayerDB.writeLock().lock();
        if(Server.playerDatabase.registerPlayer(username,password)) {
            sessionToken = Server.playerDatabase.generateSessionToken();
            Server.playerDatabase.addSessionToken(username, sessionToken);

        }
        lockPlayerDB.writeLock().unlock();
        return sessionToken;
    }

    private String authenticatePlayer(String username, String password)  {
        String sessionToken = "";

        lockPlayerDB.writeLock().lock();
        if(Server.playerDatabase.authenticatePlayer(username,password)){
            sessionToken = Server.playerDatabase.generateSessionToken();
            Server.playerDatabase.addSessionToken(username,sessionToken);
        }
        lockPlayerDB.writeLock().unlock();
        return sessionToken;
    }

    private  boolean authenticateByToken(String token){
        lockPlayerDB.writeLock().lock();
        if(Server.playerDatabase.authenticatePlayerByToken(token)){
            lockPlayerDB.writeLock().unlock();
            return true;
        }
        lockPlayerDB.writeLock().unlock();
        return false;
    }


}
