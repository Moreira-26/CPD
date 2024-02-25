package cpd.g15.wordScramble;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.TimerTask;
import java.util.concurrent.ThreadPoolExecutor;

import static cpd.g15.wordScramble.Server.executorService;

class ServerStatus extends TimerTask {
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("HH:mm:ss");
    @Override
    public void run() {
        Long currentTimeMillis = System.currentTimeMillis();
        System.out.println("\n\n\n\n\nCurrent time: " + formatTime(currentTimeMillis));
        System.out.println("Number of games: " + ((ThreadPoolExecutor) executorService).getActiveCount());
        printPlayerList();
    }

    private void printPlayerList() {
        System.out.println("\nPLAYER DATABASE");
        System.out.println("Username      | Password  | Rank          | LoggedIn | Token                            | ExpireTime");
        System.out.println("--------------------------------------------------------------------------------------------------------");
        for (Player p : Player.PlayerDatabase.getPlayers()) {

            System.out.printf("%-14s | %-8s | %-13s | %-8s | %-32s | %s%n",
                    p.getUsername(), p.getPassword(),p.getRank()+ " ("+p.getElo()+") ", p.isLoggedIn, p.getWaitingToken(),
                    formatTime(Server.playerDatabase.tokenExpiration.get(p.getWaitingToken())));
        }
        System.out.println("--------------------------------------------------------------------------------------------------------");
        System.out.println("\nPLAYER QUEUE");
        System.out.println("--------------------------------------------------------------------------------------------------------");
        for (Player p : Queue.getPlayerQueue()) {
            System.out.printf("%-9s ->",p.getUsername());
        }
        System.out.println("\n--------------------------------------------------------------------------------------------------------");
        System.out.println("\nCURRENT GAMES");
        System.out.println("--------------------------------------------------------------------------------------------------------");
        for (WordScrambleRunnable game : Server.currentGames) {
                System.out.print("Game: ");
                for (Player p : game.getPlayers()) {
                    System.out.printf("%s ", p.getUsername());
                }
                System.out.println();
        }
        System.out.println("--------------------------------------------------------------------------------------------------------");
    }

    private String formatTime(Long currentTimeMillis) {
        if(currentTimeMillis == null) return "";
        LocalDateTime currentTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(currentTimeMillis), ZoneId.systemDefault());
        return currentTime.format(TIME_FORMATTER);
    }


}