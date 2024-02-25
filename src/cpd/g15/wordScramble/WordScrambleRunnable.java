package cpd.g15.wordScramble;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketException;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.util.*;

public class WordScrambleRunnable implements Runnable {

    private final String scrambledWord;
    private final String selectedWord;

    private final Queue queue;

    private Selector selector;

    private boolean gameOver;

    private final ArrayList<Player> players;
    private static final int ELO_INCREASE = 100;
    private static final int ELO_DECREASE = 50;

    private final Set<SocketChannel> readyPlayers = new HashSet<>();
    private final Set<SocketChannel> disconnectedPlayers = new HashSet<>();

    private final String[] words = {"apple", "banana", "orange", "grape", "kiwi", "mango", "strawberry", "blueberry", "watermelon", "pineapple", "cherry", "lemon", "lime", "peach", "pear"};


    public WordScrambleRunnable(ArrayList<Player> players, Queue queue) {
        this.players = players;
        this.selectedWord = words[new Random().nextInt(words.length)];
        this.scrambledWord = scrambleWord(this.selectedWord);
        this.gameOver = false;
        this.queue =  queue;
    }

    @Override
    public void run() {
        System.out.println("Game started");
        try {
            Selector selector = Selector.open();
            this.selector = selector;
            registerSocketChannels(selector);

            while(disconnectedPlayers.size() != players.size()){
                selector.select();
                Iterator<SelectionKey> iterator = selector.selectedKeys().iterator();
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
            }
            Server.currentGames.remove(this);

        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

    private void read(SelectionKey key) throws IOException {
        SocketChannel clientChannel = (SocketChannel) key.channel();
        Message message = null;
        try {
            message = Message.readObject(clientChannel);
        }catch (ConnectException e){
            broadcastSomeoneDisconnected(clientChannel);
            clientChannel.close();
            Player.PlayerDatabase.getPlayerByChannel(clientChannel).isLoggedIn = false;
        }catch (SocketException e){
            broadcastSomeoneDisconnected(clientChannel);
            clientChannel.close();
            Player.PlayerDatabase.getPlayerByChannel(clientChannel).isLoggedIn = false;
            return;
        }catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        //Deal with client disconnected
        if(message == null){
            System.out.println("Client disconnected");
            disconnectedPlayers.add(clientChannel);
            clientChannel.close();
            Player.PlayerDatabase.getPlayerByChannel(clientChannel).isLoggedIn = false;

            return;
        }
        dealWithMessage(message,clientChannel);
    }

    private void dealWithMessage(Message message, SocketChannel clientChannel) throws IOException {
        Message messageToSend;
        switch (message.getType()){
            case GAME_START_READY:
                readyPlayers.add(clientChannel);
                if(readyPlayers.size() == players.size()){
                    for(Player p : players){
                        messageToSend = new Message(Message.Type.GAME_SERVER_GET_NEW_WORD, "Guess Word:");
                        messageToSend.writeObject(p.getChannel());
                    }
                }
                break;
            //Receive guess from client
            case GAME_CLIENT_WORD:
                if (!gameOver) {
                    //Check if word is correct
                    if (message.getObject().toString().equals(selectedWord)) {
                        this.gameOver = true;
                        //Broadcast that someone won to everyone minus the winner
                        broadcastWinner(clientChannel);
                        //Send message to winner
                        messageToSend = new Message(Message.Type.GAME_SERVER_CORRECT_WORD, "You guessed the word! The word was: " + selectedWord + "\nPlay again?(y/n)");
                    } else {
                        //Send message to loser
                        messageToSend = new Message(Message.Type.GAME_SERVER_GET_NEW_WORD, "You guessed the word incorrectly!");
                    }
                    messageToSend.writeObject(clientChannel);
                }
                break;
            case GAME_CLIENT_QUIT_IN_GAME:
                this.gameOver = true;
                broadcastSomeoneDisconnected(clientChannel);
                break;
            //Receive play again from client
            case GAME_CLIENT_PLAY_AGAIN:
                disconnectedPlayers.add(clientChannel);
                // Unregister player channel from queue
                SelectionKey key = clientChannel.keyFor(selector);
                if(key != null){
                    key.cancel();
                }
                //Add player to queue
                queue.addPlayerToQueueAndRegisterChannel(Player.PlayerDatabase.getPlayerByChannel(clientChannel).getUsername(),clientChannel);

                break;
            case GAME_CLIENT_QUIT:
                disconnectedPlayers.add(clientChannel);
                System.out.println("Player disconnected");
                //Close connection (logout)
                Server.playerDatabase.signOutPlayer(Player.PlayerDatabase.getPlayerByChannel(clientChannel).getUsername());
                clientChannel.close();
                break;
        }
    }

    private void broadcastSomeoneDisconnected(SocketChannel disconnectChannel) throws IOException {
        Message disconnectMessage = new Message(Message.Type.GAME_SERVER_PLAYER_DISCONNECTED,"Someone disconnected. Game ended.\nPlay again?(y/n)");
        for(Player player:players){
            if(!player.getChannel().equals(disconnectChannel)){
                disconnectMessage.writeObject(player.getChannel());
            }
        }
    }


    public void registerSocketChannels(Selector selector) throws IOException {
        for(Player player : players){
            SocketChannel clientChannel = player.getChannel();
            clientChannel.register(selector, SelectionKey.OP_READ);
            Message startGame = new Message(Message.Type.GAME_START,"Game starting!\n" + "Scramble Word: " + scrambledWord);
            startGame.writeObject(clientChannel);
            System.out.println("startGame sent to player: " + player.getUsername() );
        }
    }

    public void broadcastWinner(SocketChannel winnerChannel) throws IOException {
        Player p = Player.PlayerDatabase.getPlayerByChannel(winnerChannel);
        Message winMessage = new Message(Message.Type.GAME_SERVER_PLAYER_WON, "You lost!\nPlayer " + p.getUsername()+ " won\n"+ "The word was: "+ this.selectedWord + "\nPlay again?(y/n)");
        for(Player player:players){
            if(!player.getChannel().equals(winnerChannel)){
                winMessage.writeObject(player.getChannel());
                player.setElo(player.getElo() - ELO_DECREASE );
                player.updateRank();
            }else{
                player.setElo(player.getElo() + ELO_INCREASE);
            }
        }
    }

    public List<Player> getPlayers(){
        return players;
    }

    public static String scrambleWord(String word) {
        List<Character> chars = new ArrayList<>();
        for (char c : word.toCharArray()) {
            chars.add(c);
        }
        Collections.shuffle(chars);
        StringBuilder sb = new StringBuilder();
        for (char c : chars) {
            sb.append(c);
        }
        return sb.toString();
    }
}