import com.jcraft.jsch.JSchException;
import org.json.simple.parser.ParseException;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

public class Main {

    // Constants
    private static final String SHELL_PROMPT = "manager> ";
    private static final String INFO_RELOADING = "Reloading config. Please start the check cycle manually.";
    private static final String INFO_STARTING = "Starting check cycle.";
    private static final String INFO_STOPPING = "Stopping check cycle.";
    private static final String ERR_SUFFIX = " Type help for a list of commands.";
    private static final String ERR_CMD_UNKNOWN = "The command you've entered does not exist.";
    private static final String ERR_CMD_INVALID = "Invalid command usage.";
    private static final String ERR_TOO_FEW_ARGS = "Not enough arguments.";
    private static final String ERR_TOO_MANY_ARGS = "Too many arguments.";
    private static final String ERR_SERVER_NOT_FOUND = "Server with given ID not found.";
    private static final String ERR_PDU_CONNECTION = "PDU of server %s unreachable";
    private static final String ERR_CONFIG_FILE_BAD = "Config file is not well formatted.";
    private static final String ERR_CONFIG_FILE_NOT_FOUND = "Config file not found. Creating an empty config.txt in current path.";
    private static final int ONE_ARG = 2;
    private static final int TWO_ARGS = 3;

    // Class values
    private static ServerManager manager;
    private static Logger logger;

    private Main() {
    }

    public static void main(String[] args) throws IOException {
        initLogger();
        loadManager();
        startExecutor(new BufferedReader(new InputStreamReader(System.in)));
    }

    private static void initLogger() {
        logger = Logger.getLogger("main");
        FileHandler fh;

        try {
            fh = new FileHandler("warnings.log");
            logger.addHandler(fh);
            System.setProperty("java.util.logging.SimpleFormatter.format", "%1$tF %1$tT [%4$s] %5$s%6$s%n");
            SimpleFormatter formatter = new SimpleFormatter();
            fh.setFormatter(formatter);
        } catch (SecurityException | IOException e) {
            printError("Couldn't initialize logger.", false);
        }
    }

    /**
     * Loop for reading and processing user input.
     *
     * @param stdin The source of the user input.
     * @throws IOException If {@code stdin} throws it.
     */
    private static void startExecutor(BufferedReader stdin) throws IOException {
        boolean quit = false;

        while (!quit) {
            System.out.print(SHELL_PROMPT);
            String input = stdin.readLine();

            if (input == null) {
                break;
            } else if (!input.isEmpty()) {
                quit = runCmd(input);
            }
        }
    }

    /**
     * Takes a command and matches it to the correct method or prints out an
     * error.
     *
     * @param cmd The raw user input that represents the command.
     * @return {@code true} if and only if the quit command was called.
     */
    private static boolean runCmd(String cmd) {
        String[] tokens = cmd.trim().split("\\s+");

        switch (tokens[0].toUpperCase()) {
            case "A":
            case "ACTIVATE":
                activateCmd();
            case "D":
            case "DEACTIVATE":
                deactivateCmd();
            case "L":
            case "LIST":
                listCmd();
                break;
            case "S":
            case "STATUS":
                statusCmd(tokens);
                break;
            case "RESTART":
                restartCmd(tokens);
                break;
            case "RELOAD":
                reloadCmd();
                break;
            case "H":
            case "HELP":
                helpCmd();
                break;
            case "Q":
            case "QUIT":
                manager.exit();
                return true;
            default:
                printError(ERR_CMD_UNKNOWN, true);
                break;
        }
        return false;
    }

    private static void listCmd() {
        System.out.println(manager.fetchContent());
    }

    private static void activateCmd() {
        printInfo(INFO_STARTING);
        manager.startCheckCycle();
    }

    private static void deactivateCmd() {
        printInfo(INFO_STOPPING);
        manager.exit();
    }

    private static void statusCmd(String[] tokens) {
        if (matchesArgsNum(tokens.length, ONE_ARG)) {
            String id = tokens[1];
            id = id.replaceAll("\"", "");

            try {
                String status = manager.fetchStatusOf(id);
                System.out.println(status);
            } catch (IllegalArgumentException e) {
                printError(ERR_SERVER_NOT_FOUND, false);
            }
        }
    }

    private static void restartCmd(String[] tokens) {
        int args = tokens.length;

        if (args == ONE_ARG) {
            String id = tokens[1];
            id = id.replaceAll("\"", "");
            tryRestart(id, false);
        } else if (matchesArgsNum(args, TWO_ARGS)) {
            String id = null;
            if (tokens[1].equalsIgnoreCase("-H")) {
                id = tokens[2];
            } else if (tokens[2].equalsIgnoreCase("-H")) {
                id = tokens[1];
            }

            if (id == null) {
                printError(ERR_CMD_INVALID, true);
            } else {
                id = id.replaceAll("\"", "");
                tryRestart(id, true);
            }
        }
    }

    private static void tryRestart(String id, boolean hardRestart) {
        try {
            manager.restartServer(id, hardRestart);
        } catch (IOException e) {
            String error = String.format(ERR_PDU_CONNECTION, id);
            printError(error, false);
        }
    }

    private static void reloadCmd() {
        printInfo(INFO_RELOADING);
        manager.exit();
        loadManager();
    }

    private static void loadManager() {
        try {
            manager = new ServerManager();
            logger.info("Loaded server manager.");
        } catch (ClassCastException | ParseException e) {
            printError(ERR_CONFIG_FILE_BAD, false);
        } catch (JSchException e) {
            printError(e.getMessage(), false);
        } catch (FileNotFoundException e) {
            printError(ERR_CONFIG_FILE_NOT_FOUND, false);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void helpCmd() {
        System.out.println("This program loads a list of servers configured in a config.txt file in the same directory. These servers can" +
                " then be automatically managed depending on the set minimum power threshold.");
        System.out.println("Available commands:");
        String cmdFormat = "%-10s %-15s %-10s%n";
        System.out.printf(cmdFormat, "LIST", "", "Prints the loaded servers.");
        System.out.printf(cmdFormat, "STATUS", "<String:ID>", "Prints the status and power usage of the given server.");
        System.out.printf(cmdFormat, "ACTIVATE", "", "Activates the server status checker.");
        System.out.printf(cmdFormat, "DEACTIVATE", "", "Deactivates the server status checker.");
        System.out.printf(cmdFormat, "RESTART", "<ID>", "Starts the server status checker. Optional Parameter -h hard restarts the server.");
        System.out.printf(cmdFormat, "RELOAD", "", "Deactivates the server status checker and reloads the config.");
        System.out.printf(cmdFormat, "HELP", "", "Take three guesses.");
        System.out.printf(cmdFormat, "QUIT", "", "Quits the program.");
    }

    /**
     * Checks whether a given number of arguments matches an intended number
     * of arguments. Prints out an error otherwise.
     *
     * @param givenArgs    The number of args given.
     * @param intendedArgs The number of args that were intended.
     * @return {@code true} if and only if numbers match.
     */
    private static boolean matchesArgsNum(int givenArgs, int intendedArgs) {
        if (givenArgs < intendedArgs) {
            printError(ERR_TOO_FEW_ARGS, true);
        } else if (givenArgs > intendedArgs) {
            printError(ERR_TOO_MANY_ARGS, true);
        } else {
            return true;
        }
        return false;
    }

    private static void printInfo(String info) {
        logger.log(Level.INFO, info);
    }


    private static void printError(String error, boolean isCommandError) {
        if (isCommandError) {
            System.out.println(error + ERR_SUFFIX);
        } else {
            logger.log(Level.SEVERE, error);
        }
    }

}
