public enum ServerStatus {

    /**
     * In the last check the server has fallen below the minimum power threshold.
     */
    inactive,

    /**
     * The server performs above the minimum power threshold or has just been restarted.
     */
    running,

    /**
     * The server has failed three restarts in a row.
     */
    failedRestarts,

    /**
     * The server is in maintenance mode and should not be checked.
     */
    maintenance

}
