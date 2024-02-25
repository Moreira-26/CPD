package cpd.g15.wordScramble;

import java.io.IOException;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.*;

public class Player {
    private final String username;
    private final String password;
    protected boolean isLoggedIn;
    private Ranking rank;
    private int elo;
    private String waitingToken;
    private long joinTime;
    private SocketChannel channel;

    enum Ranking {
        BRONZE,
        SILVER,
        GOLD
    }

    public Player(String username, String password, int elo) {
        this.username = username;
        this.password = password;
        this.elo = elo;
        this.isLoggedIn = false;
        this.rank = determineRanking();
        this.waitingToken = "";
    }

    public String getUsername() {
        return username;
    }

    public String getPassword() {
        return password;
    }

    public int getElo() {
        return elo;
    }
    public void setElo(int elo) {
        this.elo = elo;
    }

    public void setChannel(SocketChannel channel) {
        this.channel = channel;
    }

    public Ranking getRank() {
        return rank;
    }
    public void updateRank(){
        this.rank = determineRanking();
    }

    public boolean getLoggedIn(){
        return this.isLoggedIn;
    }

    public void setLoggedIn(boolean loggedIn){
        this.isLoggedIn = loggedIn;
    }

    public  SocketChannel getChannel(){
        return this.channel;
    }

    public String getWaitingToken() {
        return waitingToken;
    }

    public void setWaitingToken(String waitingToken) {
        this.waitingToken = waitingToken;
    }


    private Ranking determineRanking() {
        if (this.elo < 801) {
            return Ranking.BRONZE;
        } else if (this.elo < 951) {
            return Ranking.SILVER;
        } else {
            return Ranking.GOLD;
        }
    }

    public long getJoinTime(){
        return this.joinTime;
    }

    public void setJoinTime(long time){
        this.joinTime = time;
    }

    // ================== Player Database ==================

    public static class PlayerDatabase {
        private final Path path = Paths.get("../data/PlayerDatabase.txt");

        private static final int STARTING_ELO = 400;
        private static final long TOKEN_EXPIRATION_TIME = 50000;

        public  Map<String,Long> tokenExpiration = new HashMap<>();

        private static List<Player> playerList;

        PlayerDatabase() {
            playerList = new ArrayList<>();
            try {

                List<String> lines = Files.readAllLines(this.path, StandardCharsets.UTF_8);
                for (int i = 2; i < lines.size(); i++) {
                    String[] playerData = lines.get(i).split(" - ");
                    Player p = new Player(playerData[0], playerData[1], Integer.parseInt(playerData[2]));
                    playerList.add(p);
                }

            } catch (IOException e) {
                System.out.print("Invalid Path");
            }
        }

        public static Player getPlayer(String username){
            return playerList.stream().filter(player -> player.getUsername().equals(username)).findAny().orElse(null);
        }

        public static Player getPlayerByToken(String token){
            return playerList.stream().filter(player -> player.getWaitingToken().equals(token)).findAny().orElse(null);
        }

        public  static Player getPlayerByChannel(SocketChannel channel){
            return playerList.stream()
                    .filter(player -> {
                        SocketChannel playerChannel = player.getChannel();
                        return playerChannel != null && playerChannel.equals(channel);
                    })
                    .findAny()
                    .orElse(null);
        }

        public boolean registerPlayer(String username, String password) {
            if (playerList.stream().anyMatch(player -> player.getUsername().equals(username)) || username.equals("") || password.equals("")) {
                return false;
            } else {
                Player p = new Player(username, password, STARTING_ELO);
                p.setLoggedIn(true);
                playerList.add(p);

                String playerData = "\n" + username + " - " + password + " - " + STARTING_ELO ;
                try {
                    Files.writeString(this.path, playerData, StandardCharsets.UTF_8, StandardOpenOption.APPEND);
                } catch (IOException e) {
                    System.out.print("Invalid Path");
                }

                return true;
            }
        }

        public boolean authenticatePlayer(String username, String password) {
            Player p = playerList.stream().filter(player -> player.getUsername().equals(username)).findAny().orElse(null);
            if(p == null || !p.getPassword().equals(password) || p.isLoggedIn){

                if(p == null){
                    System.out.println("Player not found");
                }else if(!p.getPassword().equals(password)){
                    System.out.println(password + " // " + p.getPassword());
                    System.out.println("Password incorrect");
                }else{
                    System.out.println("Player already logged in");
                }

                return false;
            }else{
                p.setLoggedIn(true);
                return true;
            }
        }

        public boolean authenticatePlayerByToken(String token) {
            //Find player for this token
            Player p = playerList.stream().filter(player -> player.getWaitingToken().equals(token)).findAny().orElse(null);
            //Player not found
            if(p == null || p.isLoggedIn){
                if(p == null){
                    System.out.println("Player not found");
                }else{
                    System.out.println("Player already logged in");
                }
                return false;
            }else{
                p.setLoggedIn(true);
                return true;
            }
        }

        public void updatePlayerWaitingToken(String username, String waitingToken) {
            Player player = getPlayer(username);
            if (player != null) {
                String oldWaitingToken = player.getWaitingToken();
                tokenExpiration.remove(oldWaitingToken);
                tokenExpiration.put(waitingToken,System.currentTimeMillis() + (TOKEN_EXPIRATION_TIME));
                player.setWaitingToken(waitingToken);
            }
        }

        public static List<Player> getPlayers() {
            return playerList;
        }

        public void signOutPlayer(String username) {
            Player p = playerList.stream().filter(player -> player.getUsername().equals(username)).findAny().orElse(null);
            if(p == null || !p.isLoggedIn){
                if(p == null){
                    System.out.println("Player not found");
                }else{
                    System.out.println("Player not logged in");
                }
            }else{
                p.isLoggedIn = false;
                p.setChannel(null);
            }
        }

        public String generateSessionToken(){
            SecureRandom secureRandom = new SecureRandom();
            Base64.Encoder base64Encoder = Base64.getUrlEncoder();
            byte[] randomBytes = new byte[24];
            secureRandom.nextBytes(randomBytes);
            return base64Encoder.encodeToString(randomBytes);

        }

        public void addSessionToken(String username, String sessionToken){
            playerList.stream().filter(player -> player.getUsername().equals(username)).findAny().ifPresent(p -> p.waitingToken = sessionToken);
        }

    }

}
