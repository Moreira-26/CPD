package cpd.g15.wordScramble;

import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.locks.ReentrantReadWriteLock;


public class Server {

    protected static Player.PlayerDatabase playerDatabase = new Player.PlayerDatabase();
    protected static ExecutorService executorService = Executors.newFixedThreadPool(5);
    private static int NUM_PLAYERS_PER_GAME;
    private static final int DISPLAY_INTERVAL = 1000;

    protected static List<WordScrambleRunnable> currentGames = new ArrayList<>();


    public static void main(String[] args) {
        if (args.length != 3) {
            System.out.println("Invalid number of arguments");
            System.out.println("Usage: java Server <port> <gameMode: 1 - simple // 2 - ranked> <Nº Players per game>");
            System.exit(1);
        }
        int port = 0, gameMode = 0, numPlayersPerGame = 0;
        try{
             port = Integer.parseInt(args[0]);
             gameMode = Integer.parseInt(args[1]);
             numPlayersPerGame = Integer.parseInt(args[2]);
        }catch (NumberFormatException e) {
            System.out.println("Invalid arguments");
            System.out.println("Usage: java Server <port> <gameMode: 1 - simple // 2 - ranked> <Nº Players per game>");
            System.exit(1);
        }


        if(gameMode != 1 && gameMode != 2){
            System.out.println("Invalid game mode");
            System.exit(1);
        }

        if(numPlayersPerGame < 1){
            System.out.println("Invalid number of players per game, minimum is 2");
            System.exit(1);
        }

        NUM_PLAYERS_PER_GAME = numPlayersPerGame;

        try {
            //Start Server Socket Channel
            ServerSocketChannel serverChannel = ServerSocketChannel.open();
            serverChannel.bind(new InetSocketAddress(port));
            serverChannel.configureBlocking(false);
            System.out.println("Server is listening on port " + port);

            //Create lock for player database
            ReentrantReadWriteLock lockPlayerDB = new ReentrantReadWriteLock();
            //Create lock for player queue
            ReentrantReadWriteLock lockPlayerQueue = new ReentrantReadWriteLock();

            //Create queue thread
            Queue queue = new Queue(lockPlayerDB, lockPlayerQueue, gameMode);
            queue.start();

            //Create auth thread
            Auth auth = new Auth(serverChannel, lockPlayerDB, queue);
            auth.start();

            //Timer for displaying in intervals
            Timer timer = new Timer();
            timer.scheduleAtFixedRate(new ServerStatus(), 0, DISPLAY_INTERVAL);



            while (true) {
                queue.cleanDisconnectedPlayersWithExpiredToken();
                if(queue.getActivePlayersInQueue().size() >= NUM_PLAYERS_PER_GAME){
                    int activeThreads = ((ThreadPoolExecutor) executorService).getActiveCount();
                    int maxThreads = ((ThreadPoolExecutor) executorService).getMaximumPoolSize();
                    //Number of active threads is equal or higher than the max number of threads a game cannot be started
                    if(activeThreads >= maxThreads){
                        continue;
                    }
                    ArrayList<Player> playersGame;
                    if(gameMode == 1){
                        playersGame = queue.matchPlayersSimple(NUM_PLAYERS_PER_GAME);
                    }else{
                        playersGame = queue.matchPlayersRanked(NUM_PLAYERS_PER_GAME);
                    }

                    if(playersGame != null && (playersGame.size() == NUM_PLAYERS_PER_GAME)){
                        //Create a new thread to handle the game
                        WordScrambleRunnable game = new WordScrambleRunnable(playersGame, queue);
                        currentGames.add(game);
                        executorService.submit(game);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            System.out.println("Server closed");
        }
    }





}

