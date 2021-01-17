import com.jcraft.jsch.JSchException;
import de.vandermeer.asciitable.AsciiTable;
import org.json.simple.JSONArray;
import org.json.simple.JSONObject;
import org.json.simple.parser.JSONParser;
import org.json.simple.parser.ParseException;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Reads a config file, manages a list of servers by checking their status.
 */
public class ServerManager {

    private final Map<String, Server> servers = new HashMap<>();
    private static final String CONFIG_FILE_NAME = "config.txt";

    private int checkInterval;

    Thread checkCycle;

    public ServerManager() throws ParseException, JSchException, IOException {
        readConfig();
    }

    private void readConfig() throws IOException, ParseException {
        System.out.println("[INFO] Loading config file...");
        JSONParser parser = new JSONParser();
        FileReader configReader = null;

        try {
            configReader = new FileReader(CONFIG_FILE_NAME);
        } catch (FileNotFoundException up) {
            createDefaultConfig();
            throw up;
        }

        if (configReader != null) {
            JSONObject json = (JSONObject) parser.parse(new FileReader(CONFIG_FILE_NAME));
            checkInterval = ((Long) json.get("checkIntervalInSeconds")).intValue();
            JSONArray jsonServers = (JSONArray) json.get("servers");

            for (JSONObject jsonServer : (Iterable<JSONObject>) jsonServers) {
                final String id = (String) jsonServer.get("id");
                final String ip = (String) jsonServer.get("ip");
                final String keyFilePath = (String) jsonServer.get("sshKeyFilePath");
                final String pduAddress = (String) jsonServer.get("pduAddress");
                final int pduIndex = ((Long) jsonServer.get("pduIndex")).intValue();
                final int pduOutletNumber = ((Long) jsonServer.get("pduOutletNumber")).intValue();
                final int triggerMinPower = ((Long) jsonServer.get("triggerMinimumPower")).intValue();
                final boolean controlActive = (boolean) jsonServer.get("controlActive");

                try {
                    final Server server = new Server(id, ip, pduAddress, pduIndex, pduOutletNumber, triggerMinPower, keyFilePath, controlActive);
                    servers.put(id, server);
                } catch (JSchException e) {
                    System.out.println("[ERROR] Invalid keyFile or passphrase for server " + id + ".");
                }
            }
        }
    }

    /**
     * Starts a server status check thread. Can be stopped by calling {@link #exit()}.
     */
    public void startCheckCycle() {
        checkCycle = new Thread(() -> {
            boolean quit = false;

            while (!quit) {
                System.out.println(fetchContent());
                servers.forEach((k, v) -> v.checkStatus());

                try {
                    Thread.sleep(checkInterval * 1000L);
                } catch (InterruptedException e) {
                    quit = true;
                }
            }
        });
    }

    /**
     * Creates a default / empty config file.
     * Called when there doesn't exist one yet.
     */
    private void createDefaultConfig() {
        File configFile = new File(CONFIG_FILE_NAME);
        boolean success = false;

        try {
            success = configFile.createNewFile();
        } catch (IOException e) {
            System.out.println("[ERROR] Cannot create config file.");
        }

        if (success) {
            try {
                FileWriter out = new FileWriter(configFile);
                writeDefaultConfigFile(out);
                out.close();
            } catch (IOException e) {
                System.out.println("[ERROR] Cannot create config file.");
            }
        }
    }

    /**
     * Returns the managed servers and their status as a table to print out.
     *
     * @return A formatted String.
     */
    public String fetchContent() {
        if (servers.isEmpty()) {
            return "No servers added yet.";
        }
        AsciiTable table = new AsciiTable();
        table.addRule();
        table.addRow("ID", "Status", "PDU-Index", "PDU-Outlet");
        table.addRule();
        servers.forEach((k, v) -> table.addRow(v.id, v.getStatus().name(), v.pduIndex, v.pduOutletNumber));
        table.addRule();
        return table.render();
    }

    public void restartServer(String id, boolean hardRestart) throws IOException {
        Server server = servers.get(id);

        if (server == null) {
            throw new IllegalArgumentException();
        } else {
            if (hardRestart) {
                server.hardRestart(false);
            } else {
                server.softRestart();
            }
        }
    }

    /**
     * Fetches the status and some properties of the server with the given id.
     *
     * @param id The ID of the server.
     * @return A formatted String.
     */
    public String fetchStatusOf(String id) {
        Server server = servers.get(id);

        if (server == null) {
            throw new IllegalArgumentException();
        } else {
            String powerUsage = "No connection";

            try {
                powerUsage = String.valueOf(server.fetchPowerUsage());
            } catch (IOException ignored) {

            }
            AsciiTable table = new AsciiTable();
            table.addRule();
            table.addRow("ID", "Status", "PDU-Index", "PDU-Outlet", "Power Usage");
            table.addRule();
            table.addRow(server.id, server.getStatus().name(), server.pduIndex, server.pduOutletNumber, powerUsage);
            table.addRule();
            return table.render();
        }
    }

    private void writeDefaultConfigFile(FileWriter out) throws IOException {
        out.write("{\n");
        out.write("\"checkIntervalInSeconds\": <VALUE>,\n");
        out.write("\"servers\": [\n");
        out.write("               {\n");
        out.write("                 \"id\": \"<VALUE>\",\n");
        out.write("                 \"ip\": \"<IPV4>\",\n");
        out.write("                 \"pduAddress\": \"<IPV4>\",\n");
        out.write("                 \"pduIndex\": <VALUE>,\n");
        out.write("                 \"pduOutletNumber\": <VALUE>,\n");
        out.write("                 \"triggerMinimumPower\": <VALUE>,\n");
        out.write("                 \"maintenance\": <true/false>\n");
        out.write("               }\n");
        out.write("           ]\n");
        out.write("}");
    }

    /**
     * Stops the check cycle loop if it is running.
     */
    public void exit() {
        if (checkCycle != null && checkCycle.isAlive()) {
            checkCycle.interrupt();
        }
    }
}