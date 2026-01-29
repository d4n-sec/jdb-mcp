import java.util.Scanner;
import java.io.IOException;

class SecretData {
    private String data;
    public SecretData(String data) { this.data = data; }
    @Override
    public String toString() {
        return "SecretData[content=" + data + "]";
    }
}

public class NotepadLauncher {
    public static void main(String[] args) {
        SecretData secret = new SecretData("sensitive_info");
        Scanner scanner = new Scanner(System.in);
        String trigger = "just_Do_it"; // You can replace this with any string you want

        System.out.println("Please enter the trigger command (enter password to open Notepad):");

        while (true) {
            if (scanner.hasNextLine()) {
                String input = scanner.nextLine();
                if (trigger.equals(input.trim())) {
                    System.out.println("Trigger successful, opening Notepad...");
                    try {
                        // Open Notepad on Windows
                        new ProcessBuilder("notepad.exe").start();
                    } catch (IOException e) {
                        System.err.println("Could not open Notepad: " + e.getMessage());
                    }
                } else {
                    System.out.println("Input content: '" + input + "' is not the trigger command. Please try again.");
                }
            } else {
                try { Thread.sleep(100); } catch (InterruptedException e) {}
            }
        }
    }
}
