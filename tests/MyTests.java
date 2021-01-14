import com.jcraft.jsch.JSchException;
import org.junit.Test;

import java.io.IOException;

public class MyTests {

    @Test
    public void checkSNMPGet() throws IOException, JSchException {
        Server server = new Server("id", "",  "192.168.178.148", 1, 1, 1000, "",false);
        server.fetchPowerUsage();
    }

    @Test
    public void sshTest() throws IOException, JSchException {
        Server server = new Server("id", "192.168.178.118",  "192.168.178.148", 1, 1, 1000, "E:\\DokumenteE\\RPiServer\\.sshRSA\\id_rsa",
                false);
        server.softRestart();
    }

}
