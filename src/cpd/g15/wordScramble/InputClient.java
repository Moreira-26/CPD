package cpd.g15.wordScramble;

import java.io.IOException;
import java.util.LinkedList;
import java.util.Scanner;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

public class InputClient extends Thread{
    private final Scanner scanner;
    private final Queue<String> queue;
    private  volatile Boolean stop;
    private final ReentrantLock lock;
    public InputClient(Scanner scanner) {
        this.scanner = scanner;
        this.lock = new ReentrantLock();
        this.queue = new LinkedList<>();
        this.stop = false;
    }

    public void run() {
            while (!stop) {
                try {
                    if(System.in.available() > 0){
                        String input  = scanner.nextLine();
                        lock.lock();
                        queue.add(input);
                        lock.unlock();
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
            queue.clear();
    }

    public String getInput() {
        lock.lock();
        String input = queue.poll();
        lock.unlock();
        return input;
    }


    public void stopThread(){
        this.stop = true;
        this.scanner.close();
    }


    public Boolean hasInput(){
        return !queue.isEmpty();
    }

}
