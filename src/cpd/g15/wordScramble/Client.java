package cpd.g15.wordScramble;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.channels.SocketChannel;
import java.util.*;


public class Client {
    private static final String SERVER_ADDRESS = "localhost";

    private InputClient inputClient;
    private String token = "";
    private final int clientNumber;
    private boolean closeConnection = false;
    private boolean playing = false;

    private final PriorityQueue<Message> messageGameQueue = new PriorityQueue<>(new MessageComparator());

    public static void main(String[] args) {
        if (args.length != 2) {
            System.out.println("Usage: java Client <port> <clientNumber>");
            System.exit(1);
        }

        int port = Integer.parseInt(args[0]);
        int clientNumber = Integer.parseInt(args[1]);
        Client client = new Client(clientNumber);
        client.start(port);
    }

    public Client(int clientNumber) {
        this.clientNumber = clientNumber;
    }

    public void start(int port) {
        try {
            //Get token from file
            this.token = getToken();
            this.inputClient = new InputClient(new Scanner(System.in));
            this.inputClient.start();
            //Start connection
            SocketChannel clientChannel = SocketChannel.open();
            clientChannel.connect(new InetSocketAddress(SERVER_ADDRESS, port));
            clientChannel.configureBlocking(false);
            System.out.println("Connected to server!");



            try {
                while (!closeConnection) {
                    if(this.playing && !this.messageGameQueue.isEmpty()){
                        Message messageToProcess = this.messageGameQueue.remove();
                        dealWithMessageGAME(messageToProcess,clientChannel);
                    }
                    Message message = Message.readObject(clientChannel);
                    if (message != null) {
                        System.out.println(message.getObject());

                        if (message.getType().toString().contains("AUTH")) {
                            dealWithMessageAUTH(message, clientChannel);
                        } else if (message.getType().toString().contains("QUEUE")) {
                            dealWithMessageQUEUE(message, clientChannel);
                        } else {
                            if(message.getType().toString().contains("START")){
                                dealWithMessageGAME(message,clientChannel);
                            }else{
                                messageGameQueue.add(message);
                            }
                        }
                    }

                }
                this.inputClient.stopThread();
            }catch (SocketException e){
                System.out.println("Server went down");
            }catch (IOException e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } catch (ClassNotFoundException e) {
                throw new RuntimeException(e);
            } finally {
                clientChannel.close();
            }

            //Close socket
        }catch (ConnectException e) {
            System.out.println("Server down");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private String getToken(){
        File tokenFile = new File("client"+ clientNumber +"token");
        try {
            Scanner reader = new Scanner(tokenFile);
            return reader.nextLine();
        } catch (Exception e) {
            try {
                tokenFile.createNewFile();
            } catch (IOException ex) {
                System.out.println("Error creating file");
            }
            return "";
        }
    }


    private void dealWithMessageAUTH(Message message, SocketChannel clientChannel) {
        try {
            Message messageToSend;
            String input;
            switch (message.getType()){
                case AUTH_START:
                    //Check if token exists
                    if(!token.isEmpty()){
                        //Start auth by token
                        messageToSend = new Message(Message.Type.AUTH_START_TOKEN,"");
                    }else{
                        //Start auth by credentials
                        messageToSend = new Message(Message.Type.AUTH_START_CREDENTIALS, "");
                    }
                    messageToSend.writeObject(clientChannel);
                    break;
                case AUTH_REQUEST_TOKEN:
                    //Send token to server
                    messageToSend = new Message(Message.Type.AUTH_RESPONSE_TOKEN,token);
                    messageToSend.writeObject(clientChannel);
                    break;
                case AUTH_REQUEST_CHOICE:
                    try {
                        do{
                            input = this.inputClient.getInput();
                        }while (input == null);
                        // Read from console
                        //input = scanner.nextLine();

                        // Verify input using regex
                        while (!input.matches("[12]")) {
                            System.out.println("Invalid choice, try again");
                            do{
                                input = this.inputClient.getInput();
                            }while (input == null);
                        }

                        int number = Integer.parseInt(input);
                        // Create message object Response
                        messageToSend = new Message(Message.Type.AUTH_RESPONSE_CHOICE, number);
                        messageToSend.writeObject(clientChannel);
                    } catch (NoSuchElementException e) {
                        System.out.println("Input stream closed. Quitting...");
                        closeConnection();
                        return;
                    }
                    break;
                case AUTH_REQUEST_CREDENTIALS:
                    try {
                        System.out.println("Enter username:");
                        String username,password;
                        do{
                            username = this.inputClient.getInput();
                        }while (username == null);
                        System.out.println("Enter password:");
                        do{
                            password = this.inputClient.getInput();
                        }while (password == null);

                        if (username.equals("quit") || password.equals("quit")) {
                            System.out.println("Quitting...");
                            closeConnection();
                            return;
                        }

                        HashMap<String, String> credentials = new HashMap<>();
                        credentials.put("username", username);
                        credentials.put("password", password);
                        messageToSend = new Message(Message.Type.AUTH_RESPONSE_CREDENTIALS, credentials);
                        messageToSend.writeObject(clientChannel);
                    } catch (NoSuchElementException e) {
                        System.out.println("Input stream closed. Quitting...");
                        closeConnection();
                        return;
                    }
                    break;
                case AUTH_SUCCESS:
                    String messagePayload = (String) message.getObject();
                    if(messagePayload.contains("Token")){
                        this.token = messagePayload.substring(messagePayload.indexOf(":") + 1);
                        FileWriter writer = new FileWriter("client"+ clientNumber +"token");
                        writer.write(token);
                        writer.close();
                    }
                    break;

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void dealWithMessageQUEUE(Message message, SocketChannel clientChannel) {
        Message messageToSend;
        try {
            switch (message.getType()) {
                case QUEUE_START, QUEUE_WAITING:
                    messageToSend = new Message(Message.Type.QUEUE_RESPONSE, "waiting");
                    messageToSend.writeObject(clientChannel);
                    break;
                case QUEUE_TOKEN_REFRESH:
                    this.token = (String) message.getObject();
                    FileWriter writer = new FileWriter("client"+ clientNumber +"token");
                    writer.write(token);
                    writer.close();
                    messageToSend = new Message(Message.Type.QUEUE_TOKEN_REFRESH_OK, "");
                    messageToSend.writeObject(clientChannel);
                    break;
                case QUEUE_CLIENT_TIMEOUT:
                    closeConnection();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void dealWithMessageGAME(Message message, SocketChannel clientChannel) {
        try {
            //
            Message messageToSend;
            String input;
            switch (message.getType()) {
                case GAME_START:
                    this.playing = true;
                    messageToSend = new Message(Message.Type.GAME_START_READY, "");
                    messageToSend.writeObject(clientChannel);
                    break;
                case GAME_SERVER_GET_NEW_WORD :
                    //TODO: Try to make this non-blocking
                    //Read word console
                    if(this.inputClient.hasInput()){
                        input = this.inputClient.getInput();
                    }else{
                        this.messageGameQueue.add(message);
                        return;
                    }
                    //Read word console
                    //input = scanner.nextLine();
                    //Only take first word
                    input = input.split(" ")[0];
                    //Create message object Response
                    if(input.equals("quit")){
                        messageToSend = new Message(Message.Type.GAME_CLIENT_QUIT_IN_GAME, input);
                        messageToSend.writeObject(clientChannel);
                        closeConnection();
                        return;
                    }else{
                        messageToSend = new Message(Message.Type.GAME_CLIENT_WORD, input);
                    }

                    messageToSend.writeObject(clientChannel);

                    break;
                case GAME_SERVER_CORRECT_WORD, GAME_SERVER_PLAYER_WON, GAME_SERVER_PLAYER_DISCONNECTED:
                    this.messageGameQueue.clear();
                    //Read word console to play again
                    do{
                        input = this.inputClient.getInput();
                    }while (input == null);

                    while (!input.matches("[yn]")) {
                        System.out.println("Invalid choice, try again");
                        do{
                            input = this.inputClient.getInput();
                        }while (input == null);
                    }

                    if(input.equals("y")){
                        messageToSend = new Message(Message.Type.GAME_CLIENT_PLAY_AGAIN, "");
                        messageToSend.writeObject(clientChannel);
                        this.messageGameQueue.clear();
                    }else{
                        messageToSend = new Message(Message.Type.GAME_CLIENT_QUIT, "");
                        messageToSend.writeObject(clientChannel);
                        System.out.println("Thank you for playing");
                        closeConnection = true;
                    }
                    this.messageGameQueue.clear();
                    break;

            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void closeConnection() {
        this.closeConnection = true;
    }

    private static class MessageComparator implements Comparator<Message> {
        @Override
        public int compare(Message m1, Message m2) {
            if (m1.getType() == m2.getType()) {
                return 0;
            } else if (m1.getType() == Message.Type.GAME_SERVER_PLAYER_WON) {
                return -1;  // m1 has higher priority
            } else if (m2.getType() == Message.Type.GAME_SERVER_PLAYER_WON) {
                return 1;   // m2 has higher priority
            }else if (m1.getType() == Message.Type.GAME_SERVER_PLAYER_DISCONNECTED) {
                return -1;  // m1 has higher priority
            } else if (m2.getType() == Message.Type.GAME_SERVER_PLAYER_DISCONNECTED) {
                return 1;   // m2 has higher priority
            }else if(m1.getType() == Message.Type.GAME_START) {
                return  1;
            }else if(m2.getType() == Message.Type.GAME_START) {
                return  -1;
            }else {
                return 0;   // Other message types have equal priority
            }
        }
    }
}
