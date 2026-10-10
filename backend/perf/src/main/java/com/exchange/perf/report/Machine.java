package com.exchange.perf.report;

import java.io.IOException;
import java.util.Locale;

/** The machine a run happened on, in one sentence, so two reports can be told apart. */
final class Machine {

    private Machine() {
    }

    static String describe() {
        String os = System.getProperty("os.name") + " " + System.getProperty("os.arch");
        int cpus = Runtime.getRuntime().availableProcessors();
        String commit = git("rev-parse", "--short", "HEAD");
        return String.format(Locale.ROOT, "%s, %d CPUs, Java %s, commit %s", os, cpus,
                System.getProperty("java.version"), commit == null ? "unknown" : commit);
    }

    private static String git(String... args) {
        try {
            String[] cmd = new String[args.length + 1];
            cmd[0] = "git";
            System.arraycopy(args, 0, cmd, 1, args.length);
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 ? out : null;
        } catch (IOException | InterruptedException e) {
            return null;
        }
    }
}
