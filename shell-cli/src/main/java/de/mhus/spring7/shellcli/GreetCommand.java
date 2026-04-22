package de.mhus.spring7.shellcli;

import org.springframework.shell.core.command.annotation.Command;
import org.springframework.shell.core.command.annotation.Option;
import org.springframework.stereotype.Component;

@Component
public class GreetCommand {

    @Command(name = "greet", group = "Greetings", description = "Greet someone by name.")
    public void greet(
            @Option(longName = "name", shortName = 'n', defaultValue = "World") String name) {
        IO.println("Hello, " + name + "!");
    }
}
