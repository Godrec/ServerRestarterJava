import com.jcraft.jsch.Channel;
import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.Snmp;
import org.snmp4j.TransportMapping;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.UdpAddress;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Contains the data for managing a server.
 */
public class Server {

    // CONSTANTS
    private static final String PUBLIC_COMMUNITY = "public";
    private static final String PRIVATE_COMMUNITY = "private";
    private static final int POWER_OFF = 1;
    private static final int POWER_ON = 0;
    private static final int WAITING_DURATION = 10; // seconds
    private static final int EXTENDED_WAITING_DURATION = 30; // seconds
    private static final int POWER_THRESHOLD_SERVER_OFF = 30;
    private static final int SNMP_RETRIES = 1;
    private static final int SNMP_TIMEOUT = 2000;
    private static final int sshPort = 22;
    private static final int SSH_TIMEOUT = 10000;
    private static final String RESTART_COMMAND = "sudo shutdown -r now";

    // Class parameters
    private final CommunityTarget<Address> publicCommunity;

    private final CommunityTarget<Address> privateCommunity;

    public final String id;
    public final String ip;

    private final String pduAddress;
    public final int pduIndex;
    public final int pduOutletNumber;

    private final OID getPowerOid;
    private final OID setSwitchOid;

    private final int triggerMinPower;

    private final Snmp snmp;
    private final JSch jSch;

    private int restartTries = 0;
    private ServerStatus status;

    private final Logger logger;

    private final boolean sshIsViaKey;

    /**
     * Creates a new server instance.
     *
     * @param id The ID of the server for naming purposes.
     * @param ip The IPv4 of the server for ssh access.
     * @param pduAddress The IPv4 of the server's PDU.
     * @param pduIndex The index of the server's PDU within their bundle.
     * @param pduOutletNumber The number of the outlet in the PDU the server is connected to.
     * @param triggerMinPower The amount of W the server is recognized as idle.
     * @param keyFilePath The path to the ssh key file. Leave empty if not used.
     * @param controlActive Whether the server should be checked.
     * @throws IOException Thrown when creating streams for the SNMP protocol.
     * @throws JSchException Thrown when setting up and reading the ssh connection's keyfile.
     */
    public Server(String id, String ip, String pduAddress, int pduIndex, int pduOutletNumber, int triggerMinPower,
                  String keyFilePath, boolean controlActive) throws IOException, JSchException {
        this.id = id;
        this.ip = ip;
        this.pduAddress = pduAddress;
        this.pduIndex = pduIndex;
        this.pduOutletNumber = pduOutletNumber;
        this.triggerMinPower = triggerMinPower;

        if (!controlActive) {
            status = ServerStatus.maintenance;
        } else {
            status = ServerStatus.running;
        }
        publicCommunity = createCommunity(PUBLIC_COMMUNITY);
        privateCommunity = createCommunity(PRIVATE_COMMUNITY);
        TransportMapping<UdpAddress> transport = new DefaultUdpTransportMapping();
        snmp = new Snmp(transport);
        transport.listen();
        final String powerOid = String.format("1.3.6.1.4.1.2.%d.3.%d.2.0", pduIndex, pduOutletNumber);
        final String switchOid = String.format("1.3.6.1.4.1.2.%d.3.%d.4.0", pduIndex, pduOutletNumber);
        getPowerOid = new OID(powerOid);
        setSwitchOid = new OID(switchOid);
        jSch = new JSch();
        sshIsViaKey = !keyFilePath.isEmpty();

        if (sshIsViaKey) {
            jSch.addIdentity(keyFilePath, Parameters.sshPassphrase);
        }
        logger = Logger.getLogger("main");
    }

    private CommunityTarget<Address> createCommunity(String communityName) {
        CommunityTarget<Address> community = new CommunityTarget<>();
        community.setCommunity(new OctetString(communityName));
        community.setAddress(GenericAddress.parse("udp:" + pduAddress + "/161"));
        community.setRetries(SNMP_RETRIES);
        community.setTimeout(SNMP_TIMEOUT);
        community.setVersion(SnmpConstants.version1);
        return community;
    }

    /**
     * Checks the status of the server depending.
     * Restarts if:
     * - multiple restarts have not already failed.
     * - the server is not in maintenance mode.
     * - the power consumption is lower than the set threshold {@link #triggerMinPower}.
     *
     * First a soft restart via SSH is tried, if that fails a hard restart by turning the power off and on again.
     */
    void checkStatus() {
        if (status != ServerStatus.failedRestarts && status != ServerStatus.maintenance) {
            int powerUsage = -1;

            try {
                powerUsage = fetchPowerUsage();
            } catch (RuntimeException e) {
                logger.log(Level.SEVERE, e.getMessage());
            } catch (IOException e) {
                logger.log(Level.SEVERE, "PDU of server " + id + " unreachable.");
            }

            if (powerUsage >= 0) {
                logger.info("Server " + id + " only pulls " + powerUsage + "W.");

                if (powerUsage <= POWER_THRESHOLD_SERVER_OFF) {
                    try {
                        hardRestart(true);
                    } catch (IOException e) {
                        logger.log(Level.SEVERE, "PDU of server " + id + " unreachable.");
                    }
                } else if (powerUsage < triggerMinPower) {
                    flagRestart();
                } else {
                    restartTries = 0;
                }
            }
        }
    }

    private void flagRestart() {
        if (status == ServerStatus.inactive) {
            if (restartTries < 3) {
                restart();
            } else {
                logger.log(Level.SEVERE, "Server " + id + " has failed to restart three times.");
                status = ServerStatus.failedRestarts;
            }
        } else {
            status = ServerStatus.inactive;
        }
    }

    private void restart() {
        logger.info("Soft restarting server " + id + ".");

        if (!softRestart()) {
            logger.info("Server " + id + " unresponsive, hard restarting.");

            try {
                hardRestart(false);
            } catch (IOException e) {
                logger.log(Level.SEVERE,"PDU of server " + id + " unreachable.");
            }
        }
        restartTries++;
        status = ServerStatus.running;
    }

    /**
     * Tries to restart the server via SSH.
     *
     * @return Returns {@code true} if a ssh connection was established.
     */
    public boolean softRestart() {
        logger.log(Level.INFO, "Server " + id + " tries to soft restart.");
        boolean success = false;
        Session ssh = null;
        Channel channel = null;

        try {
            ssh = jSch.getSession(Parameters.sshUser, ip, sshPort);

            if (!sshIsViaKey) {
                ssh.setPassword(Parameters.sshPassphrase);
            }
            java.util.Properties config = new java.util.Properties();
            config.put("StrictHostKeyChecking", "no");
            ssh.setConfig(config);
            ssh.connect(SSH_TIMEOUT);
            channel = ssh.openChannel("exec");
            ((ChannelExec) channel).setCommand(RESTART_COMMAND);
            channel.setInputStream(null);
            ((ChannelExec) channel).setErrStream(System.err);
            channel.connect();
            success = true;
        } catch (JSchException e) {
            logger.info("Server " + id + "doesn't respond.");
        } finally {
            if (ssh != null) {
                ssh.disconnect();
            }

            if (channel != null) {
                channel.disconnect();
            }
        }
        return success;
    }

    /**
     * Restarts the server by turning the power off and on again.
     *
     * @param longWait Whether we should wait longer for all power to run out.
     * @throws IOException If the connection to the PDU fails.
     */
    public void hardRestart(boolean longWait) throws IOException {
        int waitingDuration = longWait ? EXTENDED_WAITING_DURATION : WAITING_DURATION;
        logger.log(Level.INFO, "Server " + id + " hard restarts.");
        switchPower(POWER_OFF);

        try {
            Thread.sleep(waitingDuration * 1000L);
        } catch (InterruptedException ignored) {
        }
        switchPower(POWER_ON);
    }

    private void switchPower(int value) throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(setSwitchOid, new Integer32(value)));
        pdu.setType(PDU.SET);
        snmp.send(pdu, privateCommunity, null);
    }

    /**
     * Fetches the current power usage of the server.
     *
     * @return The power usage in Watt.
     * @throws IOException  If the connection to the PDU fails.
     */
    public int fetchPowerUsage() throws IOException {
        PDU pdu = new PDU();
        pdu.add(new VariableBinding(getPowerOid));
        pdu.setType(PDU.GET);
        ResponseEvent<Address> responseEvent = snmp.send(pdu, publicCommunity);
        PDU response = responseEvent.getResponse();

        if (response != null) {
            return response.get(0).getVariable().toInt();
        }
        throw new RuntimeException("PDU of server " + id + " unreachable.");
    }

    /**
     * Gets the server's current status.
     *
     * @return The server's current {@link ServerStatus}.
     */
    public ServerStatus getStatus() {
        return status;
    }

}