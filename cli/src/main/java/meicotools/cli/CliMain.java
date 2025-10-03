package meicotools.cli;

import picocli.CommandLine;
import picocli.CommandLine.Command;

@Command(name = "meico-tools", subcommands = {
    ConvertCommand.class, PerformCommand.class, ModifyCommand.class
})
public class CliMain implements Runnable {
    public void run() { CommandLine.usage(this, System.out); }
    public static void main(String[] args) {
        System.exit(new CommandLine(new CliMain()).execute(args));
    }
}
