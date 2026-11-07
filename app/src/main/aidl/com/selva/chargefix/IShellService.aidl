package com.selva.chargefix;

interface IShellService {
    // Shizuku calls this code to tear the service down.
    void destroy() = 16777114;

    /** Runs a shell command as the "shell" user and returns stdout+stderr. */
    String exec(String cmd) = 1;

    /** Runs a USB-port operation (see ShellUserService) and returns a log. */
    String usbOp(String op, int pauseSec) = 2;
}
