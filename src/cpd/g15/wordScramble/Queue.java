package cpd.g15.wordScramble;

import java.io.IOException;
import java.net.SocketException;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public class Queue extends Thread{

    private final ReentrantReadWriteLock lockPlayerQueue;
    private final ReentrantReadWriteLock lockPlayerDB;
    private final int gameMode;
    private Selector selector;
    protected static TreeSet<Player> playerQueue;

    private static final long DISCONNECTION_TIMEOUT = 20000;
    private static final double RELAXATION_FACTOR = 1.4;
    private static final int INITIAL_ACCEPTABLE_DIFFERENCE = 100;
    private long lastTimeTeamCreated;
    private final Map<SocketChannel,Long> clientsTimeouts;
    private int acceptableDifference;
    public Queue(ReentrantReadWriteLock lockPlayerDB, ReentrantReadWriteLock lockPlayerQueue, int gameMode) {
        this.lockPlayerQueue = lockPlayerQueue;
        this.lockPlayerDB = lockPlayerDB;
        this.gameMode = gameMode;
        this.acceptableDifference = INITIAL_ACCEPTABLE_DIFFERENCE;
        this.clientsTimeouts = new HashMap<>();
        this.lastTimeTeamCreated = System.currentTimeMillis();
        playerQueue = createQueue();
    }

    public void run() {
        try {
            this.selector = Selector.open();
            while(true){
                this.selector.select(1);
                Iterator<SelectionKey> iterator = this.selector.selectedKeys().iterator();
                while (iterator.hasNext()){
                    SelectionKey key = iterator.next();
                    iterator.remove();

                    if(!key.isValid()){
                        continue;
                    }
                    if(key.isReadable()){
                        read(key);
                    }
                }
                checkTimeouts();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void checkTimeouts() throws IOException {
        //If DISCONNECT_TIMEOUT has passed since last message, close connection
        for (Map.Entry<SocketChannel, Long> clientTimeout : clientsTimeouts.entrySet()){
            //Timeout as passed
            if(clientTimeout.getValue() < System.currentTimeMillis()){
                closeClientTimeout(clientTimeout.getKey());
                clientsTimeouts.remove(clientTimeout.getKey());
            }
        }
    }

    private TreeSet<Player> createQueue() {
        //Game mode Simple queue by join time > username
        if(this.gameMode == 1){
            return new TreeSet<>(
                    Comparator.<Player>comparingLong(Player::getJoinTime)
                            .thenComparing(Player::getUsername) // to ensure unique connection
            );
        }

        //Game mode Simple queue by rank > join time > username
        return new TreeSet<>(
                Comparator.<Player>comparingInt(Player::getElo).reversed()
                        .thenComparing(Player::getJoinTime)
                        .thenComparing(Player::getUsername) // to ensure unique connection
        );
    }

    public void closeClient(SocketChannel clientChannel){
        //Reset player state
        Player player = Player.PlayerDatabase.getPlayerByChannel(clientChannel);
        if(player != null){
            player.setLoggedIn(false);
            player.setJoinTime(0);
        }

        try {
            clientChannel.close();

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void closeClientTimeout(SocketChannel clientChannel) {
        lockPlayerDB.writeLock().lock();
        Player player = Player.PlayerDatabase.getPlayerByChannel(clientChannel);
        if(player != null){
            player.setLoggedIn(false);
            player.setJoinTime(0);
        }
        lockPlayerDB.writeLock().unlock();

        lockPlayerQueue.writeLock().lock();
        if(player != null){
            playerQueue.remove(player);
        }

        lockPlayerQueue.writeLock().unlock();

        Message messageTimeout = new Message(Message.Type.QUEUE_CLIENT_TIMEOUT, "Timeout. You are being disconnected.");

        try {
            clientChannel.close();
            messageTimeout.writeObject(clientChannel);

        }catch (ClosedChannelException e){
            System.out.println("Closed channel");
        }catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
    private void dealWithMessage(Message message, SocketChannel clientChannel) throws IOException {
        Message messageToSend;
        //Update client timeout
        clientsTimeouts.put(clientChannel,System.currentTimeMillis() + DISCONNECTION_TIMEOUT);
        Player p = Player.PlayerDatabase.getPlayerByChannel(clientChannel);
        if(p != null){
            String token = p.getWaitingToken();
            if(token != null){
                if(Server.playerDatabase.tokenExpiration.containsKey(token)){
                    //Check if token expired
                    if(Server.playerDatabase.tokenExpiration.get(token) < System.currentTimeMillis()){
                        if(p.isLoggedIn){
                            //Create new token
                            String newToken = Server.playerDatabase.generateSessionToken();
                            //Update token
                            Server.playerDatabase.updatePlayerWaitingToken(p.getUsername(),newToken);
                            //Send token to client
                            messageToSend = new Message(Message.Type.QUEUE_TOKEN_REFRESH, newToken);
                            messageToSend.writeObject(clientChannel);
                        }
                    }
                }
            }
        }
        switch (message.getType()){
            case QUEUE_TOKEN_REFRESH_OK,QUEUE_RESPONSE:
                messageToSend = new Message(Message.Type.QUEUE_WAITING, "queue...");
                messageToSend.writeObject(clientChannel);
                break;
        }
    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        Message message;

        try {
            message = Message.readObject(clientChannel);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        } catch (SocketException e) {

            System.out.println("Client disconnected");
            closeClient(clientChannel);
            return;
        }

        if(message == null){
            System.out.println("Client disconnected from queue: ");
            closeClient(clientChannel);
            return;
        }
        dealWithMessage(message,clientChannel);
    }

    private boolean addPlayerToQueue(String username){
        //Get player by username
        Player p = Player.PlayerDatabase.getPlayer(username);
        lockPlayerQueue.writeLock().lock();
        //Check if player is logged in
        if(p != null && p.isLoggedIn){
            //Set join time
            p.setJoinTime(System.currentTimeMillis());
            //Add player to queue
            playerQueue.add(p);
            lockPlayerQueue.writeLock().unlock();
            return true;
        }
        lockPlayerQueue.writeLock().unlock();
        return false;
    }

    public void addPlayerToQueueAndRegisterChannel(String username, SocketChannel clientChannel) {
        Player p = Player.PlayerDatabase.getPlayer(username);
        p.setChannel(clientChannel);

        //Add player to queue
        if(addPlayerToQueue(username)) {
            try {
                //Register channel
                clientChannel.register(this.selector,SelectionKey.OP_READ);
                //Add client to timeouts
                clientsTimeouts.put(clientChannel, System.currentTimeMillis() + DISCONNECTION_TIMEOUT);
                this.selector.wakeup();

                //Send queue start message
                Message startQueue = new Message(Message.Type.QUEUE_START, "You are in queue");
                startQueue.writeObject(clientChannel);

            } catch (ClosedChannelException e) {
                System.out.println("Failed to register clientChannel for " + username);
                throw new RuntimeException(e);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }

    //Resume connection to queue when player connects by token
    public void resumeToQueue(String token, SocketChannel clientChannel) {
        //Get player by token
        Player p = Player.PlayerDatabase.getPlayerByToken(token);
        //Set player channel
        p.setChannel(clientChannel);

        try {
            //Register channel to queue selector
            clientChannel.register(this.selector,SelectionKey.OP_READ);
            clientsTimeouts.put(clientChannel, System.currentTimeMillis() + DISCONNECTION_TIMEOUT);
            this.selector.wakeup();

            Message startQueue = new Message(Message.Type.QUEUE_START, "You are in queue");
            startQueue.writeObject(clientChannel);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    // Match players in queue
    public ArrayList<Player> matchPlayersSimple(int numberOfPlayers) {
        //Create List of players to play
        ArrayList<Player> matchPlayers = new ArrayList<>();

        lockPlayerQueue.writeLock().lock();
        // create descending iterator to get players
        Iterator<Player> iterator = playerQueue.descendingIterator();

        while (iterator.hasNext() && matchPlayers.size() < numberOfPlayers) {
            Player player = iterator.next();

            // check if player is logged in
            if (player.getLoggedIn()) {

                // Unregister player channel from queue
                SelectionKey key = player.getChannel().keyFor(selector);
                if(key != null){
                    key.cancel();
                }
                clientsTimeouts.remove(player.getChannel());
                // set waiting token to empty string
                player.setWaitingToken("");
                // add player to match list
                matchPlayers.add(player);
                // remove player from queue
                iterator.remove();
            }
        }
        lockPlayerQueue.writeLock().unlock();

        return matchPlayers;
    }

    //TODO:Review this algorithm
    public ArrayList<Player> matchPlayersRanked(int numPlayersPerGame) {
        ArrayList<Player> activePlayers = getActivePlayersInQueue();
        while (!activePlayers.isEmpty()){
            ArrayList<Player> teamTest = new ArrayList<>();
            if(activePlayers.size() < numPlayersPerGame){
                break;
            }
            for(int i = 0; i < numPlayersPerGame; i++){
                teamTest.add(activePlayers.get(i));
            }

            if(isAcceptableTeam(teamTest)){
                this.lastTimeTeamCreated = System.currentTimeMillis();
                this.acceptableDifference = INITIAL_ACCEPTABLE_DIFFERENCE;
                lockPlayerQueue.writeLock().lock();
                for(Player player:teamTest){
                    this.lastTimeTeamCreated = System.currentTimeMillis();
                    //Cancel key for queue channel
                    player.getChannel().keyFor(selector).cancel();
                    clientsTimeouts.remove(player.getChannel());
                    //Remove player from queue
                    playerQueue.remove(player);
                }
                lockPlayerQueue.writeLock().unlock();
                return teamTest;
            }else{
                activePlayers.remove(0);
            }
        }
        if(System.currentTimeMillis() >= this.lastTimeTeamCreated + 60000){
            adjustAcceptableRange();
        }

        return null;
    }

    private void adjustAcceptableRange() {
        this.acceptableDifference *= RELAXATION_FACTOR;
    }

    private boolean isAcceptableTeam(ArrayList<Player> matchPlayers) {
        return matchPlayers.get(0).getElo() - matchPlayers.get(matchPlayers.size() - 1).getElo() <= this.acceptableDifference;
    }



    public static TreeSet<Player> getPlayerQueue(){
        return playerQueue;
    }


    public ArrayList<Player> getActivePlayersInQueue(){
        ArrayList<Player> matchPlayers = new ArrayList<>();
        lockPlayerQueue.readLock().lock();
        for(Player player:playerQueue){
            if(player.isLoggedIn){
                matchPlayers.add(player);
            }
        }
        lockPlayerQueue.readLock().unlock();
        return matchPlayers;
    }

    //Remove players in queue that are logged out and token has expired
    public void cleanDisconnectedPlayersWithExpiredToken(){
        lockPlayerQueue.writeLock().lock();
        for(Player p: playerQueue){
            Long tokenExpiration = Server.playerDatabase.tokenExpiration.get(p.getWaitingToken());
            if(tokenExpiration != null && tokenExpiration < System.currentTimeMillis() && !p.getLoggedIn()){
                playerQueue.remove(p);
            }
        }
        lockPlayerQueue.writeLock().unlock();
    }

}
